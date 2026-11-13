# 会话锁的原子化：`HSET` + `EXPIRE` 合一

> **Redis 考点**：「写字段」与「设过期」必须是同一个原子动作——`HSET` 成功而 `EXPIRE` 失败会把 key 变成永不过期的僵尸。
> **来源**：`docs/01-redis-application-points.md` §2.4（原子操作与分布式锁）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/agentbase/chatinfo/ChatManager.java`、`src/main/java/com/fancy/rideagenthub/service/base/ChatInfoService.java`、`src/main/java/com/fancy/rideagenthub/service/base/ChatRequestRunner.java`

---

## 一、业务场景

项目里有**两种名字相近但语义完全不同的锁**，先分清楚：

| | 对话锁定（`chat:info` 的 `locked` 字段） | 单轮执行锁（`chat:execution:{chatId}`） |
|---|---|---|
| 目的 | 防止跨主题污染——订单创建后锁定会话，引导用户新开对话 | 同一会话一次只处理一轮请求 |
| 实现 | Hash 字段 `locked=true`，DB 有镜像列 | Redisson `RLock`（看门狗） |
| 释放 | 符合恢复条件时 `unlockChat`；Redis 过期后仍读取 DB 锁定状态 | 每轮 `doFinally` 解锁 |
| 所在 | `ChatManager.lockChat` / `ChatInfoService` | `ChatRequestRunner.run` |

**对话锁定**同时保存在数据库与 Redis 中。`HASH_SET_WITH_EXPIRE` 将 Redis 的 `locked=true` 与 60 分钟 TTL 一起写入，避免两条命令之间的中断窗口。TTL 管理的是元数据缓存寿命，不是业务锁的自动解锁期限：Redis 过期后 `isLocked` 回退 `ChatInfoService.checkLock`，数据库仍为 true 时会话继续锁定。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `chat:info:{chatId}` | Hash | 状态依赖（见 4.3） | 会话元数据：`locked` / `classification` / `OrderId` / 订单槽位 / 令牌字段 |
| `chat:execution:{chatId}` | Redisson RLock value | 看门狗（无显式 TTL） | 单轮执行锁 |

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `ChatManager.lockChat` | `chatInfoService.lockChat`（DB 镜像置 locked）+ `HASH_SET_WITH_EXPIRE` Lua 原子写 `locked=true` + 60 分钟过期 |
| `ChatManager.isLocked` | 先读 Redis Hash；查不到（新会话 / 已过期）回退 `chatInfoService.checkLock`（DB） |
| `ChatManager.getRestorableChat` | 判定 + 命中时把会话 key 续 60 分钟 |
| `ChatManager.restoreChat` / `ChatInfoService.restoreChat` | 恢复：DB `unlockChat` + Hash 写 `locked=false` + **`persist`（去掉 TTL）** |
| `ChatRequestRunner.run` | 单轮执行锁：`tryLockAsync(0, -1, SECONDS, owner)` + 后台订阅 + `doFinally` 解锁 |

`getRestorableChatId` 的判定链：先查 DB（`getLatestChatId`，按 `updatedAt` 取最近会话、过滤已删除），再读 Hash 校验 `classification == ORDER` 且无 `OrderId`——**归属过滤在 DB 层完成**，Redis 只承载业务状态判定。

---

## 四、实现拆解

### 4.1 `HASH_SET_WITH_EXPIRE`：两命令合一

```lua
redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])
return redis.call('EXPIRE', KEYS[1], ARGV[3])
```

返回值是 `EXPIRE` 的执行结果（成功 1 / key 不存在 0），不是恒定的 `1`——调用方若要感知失败可以检查返回值。正常执行时两条命令不会被其他客户端插入；脚本运行时错误不会回滚已执行的 HSET，不能据此承诺任何故障下必然带 TTL。

60 分钟仅限制 Redis Hash 的寿命。即使 key 自然过期，DB 的 `locked=true` 仍会生效；订单创建后带有 `OrderId` 的会话不符合 `/restore` 条件，应新建会话。

### 4.2 单轮执行锁：Redisson 看门狗 + 后台订阅

`ChatRequestRunner.run` 的完整生命周期：

1. `UUID.fromString(chatId)`——只做**格式校验**（不要求由 `/newuuid` 签发）；
2. `tryLockAsync(0, -1, SECONDS, owner)`：`waitTime=0`（不排队，抢不到立刻失败）、`leaseTime=-1`（**启用看门狗**续期，无显式 TTL）、`owner` 是随机持有者令牌；
3. `acquisition.get(3, TimeUnit.SECONDS)`：3 秒只是等待 Future 完成的上限；超时走 `thenAccept` 补偿解锁，防止晚到的锁悬挂；
4. `chats.assertOwned(userId, chatId, !resume)`（锁内归属校验：404 / 403 / 410）；
5. **后台订阅**：`sink.asFlux().doOnSubscribe(提交业务到 chatExecutor).doFinally(unlockAsync(owner)).publish().autoConnect(2)`，并主动 `events.subscribe(...)` 构成第一个订阅者（另一个是前端）——**客户端断开不取消执行、不提前放锁**；
6. 抢锁失败：返回 `error` 事件流（「会话正在处理中」），而不是 409 异常——流式端点把拒绝也表达为事件。

「断开不提前放锁」的理由：业务正在工具循环中（可能正在创建订单），提前放锁会让下一轮请求与未完成的本轮交叉写历史。

### 4.3 `chat:info` 的 TTL 语义（三种状态并存）

| 状态 | TTL | 触发 |
|---|---|---|
| 新会话 / 普通 HSET | 默认不设置 TTL | `initChat` 只写 DB，classification / 槽位 HSET 不主动设 TTL |
| 报价后 | 24 小时 | `OrderConfirmation.quoted` |
| 锁定 / 恢复检查命中 | 60 分钟 | `lockChat` / `getRestorableChat` |
| 恢复后 | **永久（`persist`）** | `ChatInfoService.restoreChat` |
| 再锁定 | 回到 60 分钟 | 下一次 `lockChat` |

这是有意设计（「恢复的会话不应在用户还在用时被 TTL 收掉」），但「普通 HSET 无主动 TTL、报价 24h、锁定/恢复检查 60min、恢复后 persist」并存使 `chat:info` 的生命周期**不能单看 TTL 推断**——本篇把它如实记录为设计事实，排查时要知道 `persist` 只由 POST `/restore` 触发。

### 4.4 DB 与 Redis 双写的锁定

`lockChat` 同时写 DB（`Chat.setLocked=true`）与 Redis Hash；`isLocked` 先读 Redis、miss 时回退 DB。DB 是持久镜像（进程重启后锁定状态仍在），Redis 是快速路径。双写无原子性保证（先 DB 后 Redis），但DB 更新成功后 Redis 更新失败：若 locked 字段缺失，下次读取回退 DB；若 Redis 仍保留旧的 locked=false，`isLocked` 会继续信任旧值，可能与 DB 不一致。当前没有双写原子性或版本校验。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| Lua 合并 HSET+EXPIRE | 两条命令 | 两条之间崩溃留下永久锁死的 key（本篇核心） |
| 单轮锁用 Redisson 看门狗 | 手写 `setIfAbsent` + TTL | 工具循环时长不可预估（模型 + 外部 API），固定 TTL 要么浪费要么不够；看门狗自动续期。手写锁的适用场景见 [13 篇](13-distributed-lock.md) |
| 后台订阅（autoConnect(2)） | 直接返回业务流 | 客户端断开不中断执行、不放锁（4.2） |
| 锁定状态 DB + Redis 双写 | 只 Redis / 只 DB | Redis 快路径 + DB 持久镜像；字段缺失可回退 DB；已有旧字段的双写失败仍存在一致性窗口 |
| 抢锁失败发 error 事件 | 抛 409 | 流式端点的拒绝也应是流的一部分，前端统一按事件渲染 |

---

## 六、边界与已知问题

1. **`chat:info` 的 TTL 语义三分**（4.3）：普通 HSET 无主动 TTL、报价 24 小时、锁定/恢复检查 60 分钟、恢复后永久。`persist` 由 POST `/restore` 触发，是这组语义里最特殊的一支。
2. **锁定双写非原子**：先 DB 后 Redis，Redis 字段缺失时回退 DB，旧 locked 字段仍存在时可能继续返回陈旧状态。
3. **`isLocked` 的 Redis 回退 DB**：key 过期后的第一读会打 DB（`checkLock`），之后无人重建 Redis 锁字段（重建发生在下一次 `lockChat`）——中间态读 DB 的成本可忽略。
4. **`tryLockAsync` 的 Future 等待与实际抢锁解耦**：3 秒超时后补偿解锁晚到的锁，但极端时序下「补偿解锁」可能删掉**别的请求**刚抢到的锁（`unlockAsync(owner)` 校验持有者，此风险已被 owner 令牌排除）。

---

## 七、如何验证

```bash
# 1. 锁定的原子化：订单创建成功后（触发 lockChat）
redis-cli HGET chat:info:<chatId> locked      # 期望 "true"
redis-cli TTL chat:info:<chatId>              # 期望：≤ 3600（60 分钟，HSET 与 EXPIRE 同生共死）

# 2. 恢复后：POST /chat/v2/restore
redis-cli TTL chat:info:<chatId>              # 期望 -1（persist，永久）
redis-cli HGET chat:info:<chatId> locked      # 期望 "false"

# 3. GET /chat/v2/restore（判定并续期）：命中时会话 key 续 60 分钟
redis-cli TTL chat:info:<chatId>              # 期望：回到 ≤ 3600

# 4. 单轮执行锁：对话进行期间
redis-cli HGETALL chat:execution:<chatId>     # Redisson RLock 是 Hash，field 为持有者标识、value 为重入计数
redis-cli TTL chat:execution:<chatId>         # 期望：约 30（看门狗默认租约，持续被续期）
# 本轮结束后 key 消失；对话进行中客户端断开，key 仍在（后台订阅不放锁）
```

---

## 八、延伸阅读

- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— `HASH_SET_WITH_EXPIRE` 速查
- [`13-distributed-lock.md`](13-distributed-lock.md) —— Redisson 看门狗与手写锁的双轨分工
- [`25-agent-hitl-state.md`](25-agent-hitl-state.md) —— `chat:info` 上的确认状态机（`break` 字段）
- [`../agent/01-orchestration-and-sse.md`](../agent/01-orchestration-and-sse.md) —— `ChatRequestRunner` 完整调用链与 SSE 生命周期
