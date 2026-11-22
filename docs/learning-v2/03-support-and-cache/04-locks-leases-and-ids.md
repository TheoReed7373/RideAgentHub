# 第 15 章 锁、版本租约与唯一 ID：三种“保证”各管什么

> 前面几章反复遇到三个词：锁、版本、ID。订单状态变更用锁，聊天回填用版本，用户和订单号用雪花 ID。它们看起来都在“防止并发出错”，但保护对象完全不同：**锁**在持有条件有效时限制对同一资源的竞争，**版本租约**判断一份旧快照还能不能写回，**唯一 ID**只是给实体一个编号。
>
> 本章沿真实调用链把三者拆开，特别说明两个容易误判的事实：订单锁用的是 Redisson `RLock`，不是前几章的 `RedisLock` + Lua；工单业务编号的 Redis key 定义在 `TicketServiceImpl`，不在 `RedisKeyConstants`。沿用虚构人物：司机陈师傅（`7002002`）、客服苏晴（`SUPPORT`，`6003003`）。**本章没有启动服务、运行测试、连接 Redis/MySQL/Redisson 或发送 HTTP/模型请求**；除明确标注“测试源码阅读”的地方，结论都是仓库静态推导，未实测。

## 学习目标

读完本章，你应该能：

1. 用具体例子对比手写 UUID 令牌锁、Redisson `RLock` 和缓存回填版本：对象、粒度、获取/校验/释放、失败行为与适用边界，并区分 token 与版本号。
2. 追踪订单锁的真实调用链，说明事务同步活跃时 `afterCompletion` 注册与不活跃时 `finally` 释放的区别，以及释放线程和持有者检查。
3. 说明消息回填版本的最小机制：读版本、查持久化、并发写入、版本条件回填，并区分正常版本不符与 Redis 异常/响应丢失。
4. 分开追踪工单业务编号与雪花 ID：组成、调用顺序、计数器生命周期、节点租约与发号前检查、时间回拨与序列溢出的处理。
5. 用 T1/T2 时间线解释锁到期与事务完成之间的竞态，不把 Redis 锁、数据库事务或唯一 ID 当成同一种保证。
6. 阅读相关测试的门控、真实/替身组件、断言与清理范围，区分项目事实、教学假设、理论推导和实际运行。

## 前置知识与阅读时间

- 必读：[第 3 章 数据与事务](../00-foundations/03-data-and-transactions.md)——事务代理、`afterCommit` / `afterCompletion`、跨库没有共同事务。
- 必读：[第 4 章 Redis 与流式响应基础](../00-foundations/04-redis-flux-and-sse-basics.md)——Lua 的 `KEYS`/`ARGV` 与原子边界。
- 必读：[第 12 章 工单生命周期与权限](01-ticket-lifecycle.md)——工单号生成在提交流程中的位置、`afterTicketMutation`。
- 必读：[第 13 章 工单池索引与统计缓存](02-ticket-index-and-cache.md)——统计锁与池重建锁的真实调用。
- 必读：[第 14 章 缓存问题与并发窗口](03-cache-races.md)——统计锁到期后的旧写/新写、墓碑与 TTL 前提。
- 必读：[第 8 章 抢单与并发](../02-orders/02-grab-and-concurrency.md)——订单锁、司机占位与条件更新的真实调用，本章锁对比需要它。
- 相关前置：[第 9 章 位置与发现](../02-orders/03-location-and-discovery.md)（订单 GEO 池与司机在线）；[第 5 章 登录与会话](../01-auth/01-login-and-session.md)（用户 ID 用途）。
- 后续主讲：[第 19 章 三层记忆、消息窗口与工具结果反查](../04-chat-and-agent/04-memory-and-tool-results.md)（C4）会完整讲聊天记忆链；本章只做版本机制的最小横向对比。
- 阅读时间：约 100～120 分钟。
- **路径约定**：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；SQL、配置与测试写完整仓库相对路径。
- **证据约定**：区分“仓库源码事实”“测试源码阅读”“实际运行”。本章未实际运行。

---

## 零、先记住十个事实

1. **`RedisLock` 是手写锁**：`SET NX PX` + UUID 令牌，失败立即返回 null（不等待），没有自动续期；释放用 Lua `RELEASE_LOCK_IF_MATCH` 比对令牌，异常只记日志。
2. **订单状态机锁不是 `RedisLock`**：`RideOrderServiceImpl#withOrderLock` 用 Redisson `RLock`，`tryLock(3, SECONDS)` 有等待，未传 `leaseTime` 时依赖 Redisson 看门狗语义；释放用 `isHeldByCurrentThread()` 检查后 `unlock()`。
3. 事务同步活跃时，订单锁在 `finally` 里注册 `afterCompletion`，该回调不区分 status 地调用释放，但**只有回调被执行、`isHeldByCurrentThread()` 为真且 `unlock()` 成功时才算释放**；回调可能由完成事务的线程执行。同步不活跃时 `finally` 中直接尝试释放，同样受持有者检查和 Redis 结果约束。
4. **token 与版本号不是一回事**：token 标识“谁拿到锁”，释放时比对；版本号标识“数据有没有被改过”，回填时比对。一个防误删，一个防旧快照覆盖。
5. 缓存回填版本的载体是 `RedisMemory`：版本 key `chat:history:version:{chatId}`（单调递增、`PERSIST` 去 TTL），数据 key `chat:history:{chatId}`；回填先写暂存 key，再由 Lua 校验版本并原子替换。
6. 工单业务编号是字符串：`T + yyyyMMdd + 类型码 + %05d 当日序号`，由 `TicketServiceImpl` 私有 Lua `INCR ticket:no:{yyyyMMdd}` 生成；序号按天**全局共享**，不按类型分开；仅在 `TTL < 0` 时设 48 小时过期。
7. Redis 序号递增发生在 `@Transactional` 的 `submitTicket` 内，但 Redis 不参与 MySQL 事务；DB 回滚不会撤销已递增的序号，工单号可能跳号。
8. 雪花 ID 由本地 `SnowflakeIdWorker` 生成：epoch 2025-01-01、41 位时间 + 5 位数据中心 + 5 位节点 + 12 位序列；同毫秒序列溢出时循环等到时钟超过上一毫秒（时钟不前进可能持续等待），时间回拨直接抛异常；`lastTimestamp`/`sequence` 是实例内存状态，重启不保留。
9. 节点号由 Redisson 节点锁 `id:node:{datacenter}:{worker}` 保护：`tryLockAsync(0, -1, SECONDS, owner)` 不等待、依赖看门狗；`nextId` 前检查 `isHeldByThread(owner)`，`close()` 释放。这个检查与本地发号**不是跨网络原子操作**。
10. 后端对 Long 有 Jackson 智能序列化：超过 JS 安全整数范围（±9007199254740991）转字符串，否则输出数字；仓库没有前端源码，端到端消费精度未知。

---

## 一、三个业务问题，三种保护对象

### 1.1 同一订单同时被两个人操作

司机陈师傅点击接单，该订单的乘客同时取消这一单。两者都写 `sys_ride_order` 的同一行。当前实现的顺序是：Controller → `RideOrderServiceImpl#driverAcceptOrder`（带 `@Transactional(isolation=READ_COMMITTED)`）→ `withOrderLock` 抢 Redisson 锁 → 锁内调用 `OrderGrabService#tryGrab`：**先在事务内用 `FOR UPDATE` 锁定司机账号行、再查活动订单并 `FOR UPDATE`**，然后写 Redis 司机占位/状态缓存，最后执行 `GRAB_ORDER_ATOMIC` Lua 原子预检与占位 → 回到 Service 做 DB 条件更新 → 提交后的缓存/队列副作用。`FOR UPDATE`、Redis 预检与条件更新都在**事务提交前**；只有 GEO 失效、状态同步、延迟队列等属于提交后动作。锁让同一订单的状态变更在应用层尽量串行（受租期、网络与故障限制）；DB 的 `WHERE status=...` 条件更新是最终裁决者。

### 1.2 一份旧聊天快照想写回缓存

客服苏晴打开一个会话，读 L2 版本 `v1`，去 MySQL 查历史；同时用户又发了一条消息，L2 版本递增到 `v2`。苏晴的读请求若把旧快照整体写回，会抹掉新消息。这里没有“锁住整个会话”，而是回填时检查版本：版本变了就放弃回填，让下一次读重建。

### 1.3 两个实体需要“不重复的编号”

注册新用户、创建新订单、新增工单都要 ID。工单有一个人看得懂的业务编号，用户/订单/RAG 分组用数值雪花 ID。两者都能“区分实体”，但生成位置、失败模式和唯一性依据完全不同。

---

## 二、三种机制对照

### 2.1 手写 UUID 令牌锁：`RedisLock`

```java
// service/base/RedisLock.java（真实节选）
public String tryLock(String key, Duration ttl) {
    if (key == null || key.isBlank() || ttl == null || ttl.toMillis() <= 0) {
        throw new IllegalArgumentException("锁 key 不能为空，有效期至少为 1 毫秒");
    }
    String token = UUID.randomUUID().toString();
    return Boolean.TRUE.equals(redisTemplate.opsForValue().setIfAbsent(key, token, ttl)) ? token : null;
}

public void unlock(String key, String token) {
    if (key == null || key.isBlank() || token == null || token.isBlank()) return;
    try {
        redisTemplate.execute(RedisScripts.RELEASE_LOCK_IF_MATCH, List.of(key), token);
    } catch (RuntimeException e) {
        log.warn("Redis 锁释放失败: key={}", key, e);
    }
}
```

```lua
-- util/RedisScripts.java#RELEASE_LOCK_IF_MATCH（真实节选）
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
```

逐项解释：

- **获取**：`setIfAbsent` 一次调用完成“不存在才写 + TTL”，拿不到立即返回 null，**没有等待**。
- **令牌**：随机 UUID 写进 key 的值，释放时由 Lua 比对；令牌不匹配就不删，避免旧持有者删掉后来者刚拿到的锁。
- **释放失败**：Lua 执行抛 `RuntimeException` 只记日志，不覆盖业务结果；Redis 响应丢失时服务端是否已删未知，key 还可能靠 TTL 到期。
- **不自动续期**：TTL 一次性设置；持锁时间超过 TTL 后，旧持有者仍可能在跑临界区代码，新持有者可能已经拿到锁。

真实调用点（只读检索）：`TicketServiceImpl#getTicketStatistics` 的统计重建锁（5 秒）、`TicketPoolIndex#slice` 的池重建锁（10 秒）、`OrderGeoPool#search` 的 GEO 池重建锁（10 秒），都走这个通用入口；`OrderGrabService#rollbackGrab` 直接拿 `RELEASE_LOCK_IF_MATCH` 脚本做“司机占位”的条件释放，那不是互斥锁的获取/释放配对。

### 2.2 Redisson `RLock`：订单锁与节点锁

同一仓库还有两条**不用** `RedisLock` 的 Redisson 锁路径：

| 路径 | 获取 | 持有者校验 | 释放 |
|---|---|---|---|
| `RideOrderServiceImpl#withOrderLock` | `lock.tryLock(3, TimeUnit.SECONDS)`，有 3 秒等待，未传 `leaseTime` | `lock.isHeldByCurrentThread()` | 事务同步活跃时 `afterCompletion` 中释放；否则 `finally` 立即释放 |
| `IdGeneratorConfig#idNodeLease` | `lock.tryLockAsync(0, -1, SECONDS, owner)`，不等待，`leaseTime=-1` | `lock.isHeldByThread(owner)` | `NodeLease.close()`（Bean 销毁）时按 owner 释放 |
| `OrderSearchTool#modifyOrderDestination` | 直接用同一 `order:lock:{id}` key，`tryLock(3s)` | 无 `isHeldByCurrentThread`，靠 `acquired` 标志 | `finally` 中 `unlock()` |

`tryLock(wait)` 不传 `leaseTime` 时，Redisson 使用其看门狗语义（默认租期由库决定并在持锁期间续期）。源码和测试里都**没有**生产环境 `lockWatchdogTimeout` 的配置证据：只有 `OrderGrabIntegrationTest` 在测试里显式设过 1500ms，不能当成生产事实。反过来说，也不能宣称生产一定使用某个固定秒数。

### 2.3 缓存回填版本：不是锁

`RedisMemory` 的版本机制不做“互斥进入”，它只判断“我读数据时看到的版本，写回时还有效吗”。先看**真实保存链** `MessageMemory#save`：取 L1/L2 版本 → `mysqlMemory.append` 先落库 → `redisMemory.appendIfVersionMatch` → `heapMemory.appendIfVersionMatch` → 更新会话时间；L1/L2 条件写抛运行时异常时清空 L1 并向上抛。`appendIfVersionMatch` 走 `APPEND_HISTORY_IF_VERSION_MATCH`：版本 key 不存在按 `'0'` 比较，脚本先 `INCR + PERSIST` 推进版本，然后分支——版本相同**且数据 key 存在**才追加消息并续 TTL；版本不符则 `DEL` 数据 key；数据 key 不存在时什么都不做（不会创建只含新消息的残缺历史）。

读链 `MessageMemory#get` 才是“读版本 → 查持久化 → 条件回填”：在任何耗时读取前调 `currentVersion` 取租约；L1/L2 未命中才查 MySQL；查到数据后 `overwriteIfLeaseValid` 先写暂存 key，再用 `OVERWRITE_IF_VERSION_MATCH` 校验版本，匹配才 `RENAME` 回填，不匹配则删暂存并返回 false。

`CURRENT_HISTORY_VERSION` 的分支要看**版本 key 自身**：版本 key 不存在时 `DEL` 数据 key、设版本为 `'0'`；仅当版本 key 自身带 TTL（`PTTL >= 0`）时才 `INCR` 版本并删数据 key；最后 `PERSIST` 版本 key。数据 key 带 24 小时 TTL 是正常设计，**不是**这条分支的条件。版本 key 仍可能被外部删除、`FLUSHDB` 或淘汰清除——源码没有“版本被重置后仍安全”的保护。

### 2.4 对照表

| 维度 | `RedisLock`（UUID 令牌） | Redisson `RLock`（订单/节点） | 回填版本（lease） |
|---|---|---|---|
| 保护对象 | 锁键有效期内、令牌未被覆盖时限制同一 key 的获取；TTL 到期后旧工作可与新持有者重叠 | 订单/节点 key 的获取受 Redisson 锁与续期状态限制，故障下不保证跨实例全程互斥 | 一份旧快照不许覆盖新缓存 |
| 粒度 | 单个 Redis key | 单个订单 / 单个节点 key | 单个会话的版本号 |
| 获取 | `SET NX PX`，不等待，固定 TTL | `tryLock(3s)` / `tryLockAsync(0,-1,...)` | `currentVersion` 读号，不“获取” |
| 校验 | 释放时 Lua 比对 token | 释放时 `isHeldByCurrentThread` / `isHeldByThread(owner)` | 回填时 Lua 比对版本号 |
| 释放 | 比对成功才 DEL；异常记日志 | `unlock()` / `unlockAsync(owner)` | 版本匹配后 `RENAME` 消费暂存；不匹配删暂存 |
| 失败 | `RedisLock` 拿不到立即返回 null，后续由调用方决定（统计锁会等待 3×60ms 再直查 DB 且不写缓存）；TTL 后旧持有者仍可运行 | 抢锁超时 409 / 节点号冲突启动失败；看门狗只在续期正常时有效 | 版本不符返回 0 放弃回填；暂存不存在返回 -1；异常时尽力清理并返回 false |
| 不保证 | 临界区全程互斥、自动续期 | 生产看门狗具体参数、DB 事务与锁的原子性 | 外部重置/淘汰版本后仍安全 |

**token ≠ 版本号**：token 是一次加锁的随机身份，回答“是不是我拿的锁”；版本号是数据被改动的代际计数，回答“我手里的快照还可不可信”。两者都靠 Redis 的读-比较-写，但保护目标不同。

---

## 三、统计锁时间线：旧持有者、新持有者与比对删除

前提（与 S3 一致）：统计锁 key `ticket:statistics:lock:{yyyyMMdd}`，TTL 5 秒；T1 是持有者但 4 条 `selectCount` 很慢；T2 是另一个**新发起**的调用；`RedisLock#tryLock` 没有续期。

| 时刻 | T1（旧持有者） | T2（新调用） | Redis |
|---|---|---|---|
| a0 | `tryLock` 成功，写入 token=T0，TTL 5 秒 | — | lock=T0 |
| a1 | 双检 miss，开始查 DB，耗时超过 5 秒 | — | T0 过期，lock 被删除 |
| a2 | 仍在查 DB | `tryLock` 成功，写入 token=T1 | lock=T1 |
| a3 | 写回统计 JSON（本调用唯一一次写） | 仍在查 DB 或即将写回 | 两个重建过程重叠，写回先后未定 |
| a4 | `finally` 调 `unlock(key, T0)`，`RELEASE_LOCK_IF_MATCH` 比较当前值 T1 ≠ T0，不删 | 仍在自己的 DB 查询/写回流程；锁由 T2 持有 | lock 仍为 T1：**T2 的新锁尚未释放也未过期**，所以旧令牌不会误删它；之后由 T2 释放或 TTL 到期 |

三条结论必须分开：① 比对删除只保护“新锁不被旧令牌误删”，**不阻止 T1 的旧工作继续跑完**；② T1 与 T2 的写回顺序没有版本仲裁，最终 JSON 取决于真实写回先后（S2 5.4）；③ 若 `unlock` 的 Lua 报错或响应丢失，客户端不能断言锁是否还在，锁可能靠 TTL 到期。业务返回不受释放失败影响（异常只记日志）。

---

## 四、订单锁：真实调用链与事务边界

### 4.1 入口清单

`withOrderLock` 在 `RideOrderServiceImpl` 中有 7 个直接调用点：接单、到达、出发、结束、支付、取消、超时处理。前六个由 `RideOrderController` 对应 HTTP 端点进入；超时处理由 `OrderTimeoutJob` 的 `@Scheduled` 轮询进入。Agent 侧 `OrderSearchTool#cancelOrder` 复用同一个 `cancelOrder` Service 方法，因此也经过这把锁；但 `OrderSearchTool#modifyOrderDestination` 是另一段独立代码，用同一个 key 但没有持有者检查。查询、创建、位置上报等端点**没有**这把锁。

### 4.2 源码节选

```java
// service/impl/RideOrderServiceImpl.java#withOrderLock / #releaseOrderLock（真实节选）
private <T> T withOrderLock(String orderId, Supplier<T> action) {
    RLock lock = redissonClient.getLock(RedisKeyConstants.orderLockKey(orderId));
    boolean acquired;
    try {
        acquired = lock.tryLock(ORDER_LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BusinessException(409, "系统繁忙，请稍后重试");
    }
    if (!acquired) {
        throw new BusinessException(409, "订单正在处理中，请稍后重试");
    }
    try {
        return action.get();
    } finally {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    releaseOrderLock(lock, orderId);
                }
            });
        } else {
            releaseOrderLock(lock, orderId);
        }
    }
}

private void releaseOrderLock(RLock lock, String orderId) {
    try {
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    } catch (RuntimeException e) {
        log.warn("订单锁释放失败: orderId={}", orderId, e);
    }
}
```

逐段解释：

1. **key**：`RedisKeyConstants.orderLockKey(orderId)`，调用方先把原始参数 `convertToLong(...).toString()` 规范化，所以 `"0100"` 与 `"100"` 命中同一把锁。
2. **等待**：`ORDER_LOCK_WAIT_SECONDS = 3L`，`tryLock(3, SECONDS)` 最多等 3 秒；拿不到抛 409“订单正在处理中”，不进入业务，也不调用 `unlock`。
3. **租期**：只传等待时间、不传 `leaseTime`，依 Redisson 看门狗语义持有；源码没有显式续期方法，也没有生产环境 `lockWatchdogTimeout` 配置证据。
4. **回调**：`TransactionSynchronizationManager.isSynchronizationActive()` 为真时，在 `finally` 中注册 `afterCompletion`；`status` 参数未使用，所以提交、回滚、状态未知都会**调用释放方法**。但这只是注册与触发，不等于释放成功：`releaseOrderLock` 先检查 `isHeldByCurrentThread()`，为真才 `unlock()`；Redis 异常或响应丢失时结果未知，异常只记日志。回调由完成事务的线程在事务结束阶段执行，不是目标方法返回时就立刻释放。
5. **无同步**：同步未激活时（例如直接调用、测试手工构造），`finally` 中立即释放。
6. **持有者检查**：`isHeldByCurrentThread()` 为真才 `unlock()`；释放异常只记日志，不把已完成的业务变成失败。

### 4.3 锁与数据库约束各管什么

`withOrderLock` 让同一订单的状态变更在应用层串行，减少并发冲突；DB 条件更新（`WHERE order_status = CREATED AND driver_id IS NULL`）才是最终裁决者：即使锁因为到期、跨实例配置不同或独立代码路径被绕过，条件更新 0 行仍会拒绝抢单。`OrderGrabService` 还用 Lua 做原子预检与司机占位，失败时通过 `rollbackGrab` 归还占位（S1/O2 已讲）。

反过来，数据库约束不替你管理 Redis 锁的生命周期，Redis 锁也不替数据库提交。这是两个系统：没有 XA，也没有“锁没释放事务就不能提交”的跨库保证。

### 4.4 看门狗、固定租期与等待：只说有证据的部分

- 订单锁有 3 秒等待（`tryLock(3, SECONDS)`），`RedisLock` 没有等待。
- 订单锁不传 `leaseTime`；Redisson 看门狗是库语义，具体默认秒数与生产配置未在仓库证实。
- `RedisLock`、池重建锁、统计锁使用**固定 TTL**，无续期；租期过后旧持有者仍可能运行。
- 节点锁 `tryLockAsync(0, -1, SECONDS, owner)` 不等待，`leaseTime=-1` 同样依赖看门狗；失败时直接抛异常。

---

## 五、消息回填版本：最小必要机制

这里只做横向对比，不重复 C4 的完整记忆链、暂存区故障推导。

### 5.1 读版本与并发写入

```java
// agentbase/memory/MessageMemory.java#save（真实节选，省略参数校验、归属检查与末尾的 updateChatTime）
long heapVersion = heapMemory.currentVersion(chatId);
long redisVersion = redisMemory.currentVersion(chatId);
mysqlMemory.append(chatId, newMessages);
try {
    redisMemory.appendIfVersionMatch(chatId, newMessages, FOCUS_CHAT_TTL, redisVersion);
    heapMemory.appendIfVersionMatch(chatId, newMessages, heapVersion);
} catch (RuntimeException e) {
    heapMemory.clear(chatId);
    throw e;
}
```

真实保存顺序是“取 L1/L2 版本 → MySQL `append` 先落库 → 条件写 L2/L1”。`APPEND_HISTORY_IF_VERSION_MATCH` 在一次 Lua 中先 `GET current`、`INCR + PERSIST` 版本，再按两个条件分支：版本相同**且数据 key 存在**才 `RPUSH` 消息并 `PEXPIRE` TTL；版本不符则 `DEL` 数据 key；数据 key 不存在时不动数据。它不会创建只含新消息的残缺历史，但版本会因每次 save 而推进，使在途旧租约失效。

`CURRENT_HISTORY_VERSION` 的两个迁移分支分别是**版本 key 不存在**（删数据、设版本 `'0'`）与**版本 key 自身带 TTL**（`INCR` 版本、删数据），最后统一 `PERSIST` 版本 key；数据 key 带 TTL 是正常设计，不是版本迁移条件。`RedisMemory#append` 与 `APPEND_WITH_VERSION_BUMP`（**不校验版本**的 `INCR + PERSIST + RPUSH`；只有 `ARGV[1] > 0` 才 `PEXPIRE` 数据 key，否则 `PERSIST` 数据 key）**不是 save 链的一部分**，只在直接调用或测试场景出现，本章不拿它描述用户保存消息。

### 5.2 条件回填

`overwriteIfLeaseValid(chatId, messages, leaseVersion)` 的流程：序列化消息 → 生成随机 token 的暂存 key `chat:history:staging:{chatId}:{token}` → MULTI 写 List 并设 60 秒 TTL → 执行 `OVERWRITE_IF_VERSION_MATCH`。脚本返回分三种：**暂存 key 不存在 → -1**（脚本开头 `EXISTS KEYS[1] == 0`）、**版本不符 → 0**（`DEL` 暂存）、**成功 → 1**（`INCR + PERSIST` 版本、`RENAME` 暂存到数据 key、`PEXPIRE` 24 小时）。Java 只在 `result != null && result == 1L` 时返回 true，其他结果返回 false。暂存写入和脚本执行所在 try 内的异常被 `catch (Exception)` 捕获，尽力 `delete(stagingKey)`（失败只忽略）后返回 **false**；此前的消息序列化在 try 外，不能保证其异常也被捕获；响应丢失时脚本可能已成功执行，所以 false 不能证明服务端没有写。暂存 60 秒 TTL 只在 MULTI 中 `EXPIRE` 真的设置成功后才兜底。

### 5.3 版本不符与异常不是一回事

- **正常版本不符 / 暂存不存在**：Lua 返回 0 或 -1，`overwriteIfLeaseValid` 返回 false；版本不符时删暂存且缓存保持原样，暂存不存在时本就没有待回填数据。调用方可重试整条读链，这是设计中的正常分支。
- **Redis 异常 / 响应丢失**：Java 在 `overwriteIfLeaseValid` 内捕获异常、尽力清理暂存并返回 **false**，不向调用方抛出；客户端不会看到该异常。但服务端 Lua 可能已执行（`RENAME` 完成、缓存已换）也可能未执行，**false 不是“没写”的证明**；若只是响应丢失而脚本已全部执行，Java 同样只看到超时，结果未知。这与锁释放响应丢失同属“结果未知”。
- **版本被外部重置/淘汰**：版本 key 被删除后，`currentVersion` 会重建一个起始号；若此时有人持有旧号并恰好撞上新号，条件校验可能误判有效。源码没有 fencing token 或防重置机制，**不能承诺外部干预后仍安全**。

---

## 六、两套 ID：业务编号与雪花 ID

### 6.1 工单业务编号

```java
// service/impl/TicketServiceImpl.java（真实节选）
private static final DefaultRedisScript<Long> TICKET_SEQUENCE = new DefaultRedisScript<>("""
    local number = redis.call('INCR', KEYS[1])
    if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], 172800) end
    return number
    """, Long.class);
private static final String TICKET_NO_PREFIX = "ticket:no:";

private String generateTicketId(Integer ticketType) {
    String dateStr = LocalDate.now().format(DATE_FORMATTER);
    String typeCode = String.valueOf(ticketType);
    String redisKey = TICKET_NO_PREFIX + dateStr;
    Long seq = stringRedisTemplate.execute(TICKET_SEQUENCE, List.of(redisKey));
    if (seq == null) throw new BusinessException(503, "工单编号服务不可用");
    return String.format("T%s%s%05d", dateStr, typeCode, seq);
}
```

- **组成与 key**：`T + yyyyMMdd + ticketType + %05d 序号`；Redis key 是 `ticket:no:{yyyyMMdd}`，**类型码只进入返回字符串**，不进入 key，当日所有类型共用一个自增序列。
- **TTL 条件**：只有 `TTL < 0`（key 无过期）时才 `EXPIRE 172800` 秒（48 小时）；正数 TTL 不续期，也不是按午夜重置。`LocalDate.now()` 用 JVM 默认时区，与 `BusinessTime` 的 Asia/Shanghai 可能不同（S1 已说明）。
- **序号宽度**：`%05d` 是最小宽度，超过 99999 会变长；`ticket_id` 列 `varchar(16)`，极端序号写入边界未实测。
- **失败边界**：只处理 `seq == null` → 503；Redis 命令抛异常、DB 唯一键冲突都没有本地重试或补偿。生成发生在 `@Transactional` 的 `submitTicket` 内、DB 插入前，但 Redis 不随 MySQL 回滚；回滚后序号可能跳号。
- **常量位置**：`TICKET_NO_PREFIX` 与 Lua 都在 `TicketServiceImpl`，**不在** `RedisKeyConstants`；不要按类名猜位置。

### 6.2 雪花 ID

```java
// config/IdGeneratorConfig.java（真实节选）
@Bean(destroyMethod = "close")
public NodeLease idNodeLease(RedissonClient redis, @Value("${id.worker-id:1}") long workerId,
        @Value("${id.datacenter-id:1}") long datacenterId) {
    SnowflakeIdWorker worker = new SnowflakeIdWorker(workerId, datacenterId);
    RLock lock = redis.getLock("id:node:" + datacenterId + ":" + workerId);
    long owner = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
    if (!lock.tryLockAsync(0, -1, TimeUnit.SECONDS, owner).toCompletableFuture().join())
        throw new IllegalStateException("雪花ID节点号已被其他实例使用，请配置不同的id.worker-id");
    SnowflakeIdWorker guarded = new SnowflakeIdWorker(workerId, datacenterId) {
        @Override public synchronized long nextId() {
            if (!lock.isHeldByThread(owner)) throw new IllegalStateException("ID节点租约已失效，拒绝继续发号");
            return super.nextId();
        }
    };
    return new NodeLease(guarded, lock, owner);
}
```

```java
// util/SnowflakeIdWorker.java#nextId（源码节选；异常消息字符串已简化，其余逻辑与源码一致）
public synchronized long nextId() {
    long timestamp = timeGen();
    if (timestamp < lastTimestamp) {
        throw new RuntimeException("Clock moved backwards...");
    }
    if (lastTimestamp == timestamp) {
        sequence = (sequence + 1) & sequenceMask;
        if (sequence == 0) timestamp = tilNextMillis(lastTimestamp);
    } else {
        sequence = 0L;
    }
    lastTimestamp = timestamp;
    return ((timestamp - twepoch) << timestampLeftShift)
            | (datacenterId << datacenterIdShift) | (workerId << workerIdShift) | sequence;
}
```

要点与边界：

- **参数**：`@Value("${id.worker-id:1}")` / `@Value("${id.datacenter-id:1}")` 默认都是 1；`src/main/resources` 中没有这两个键，实际部署需通过配置源覆盖。`SnowflakeIdWorker` 构造函数校验 0～31。第 15 行那个 `worker` 实例未被返回/使用，真正暴露的是 `guarded` 匿名子类。
- **位分配**：epoch `1735689600000`（2025-01-01）、41 位相对毫秒、5 位数据中心、5 位节点、12 位序列；同一毫秒最多 4096 个序列，溢出时 `tilNextMillis` 忙等下一毫秒；时间回拨直接抛异常，无容忍窗口。
- **节点租约**：key `id:node:{dc}:{worker}`，`tryLockAsync(0, -1, SECONDS, owner)` 不等待、依赖看门狗；owner 是 `ThreadLocalRandom` 随机 long。发号前 `isHeldByThread(owner)` 检查租约，销毁时 `NodeLease.close()` 按 owner 释放。
- **不是原子保证**：检查是 Redis 调用，发号是本地 `synchronized` 方法；两者之间没有 Lua/CAS/fencing token。租约在检查后失效，当前调用仍可能成功发号。`synchronized` 只管单个 JVM 内的线程竞争。
- **失败路径**：节点锁被占启动失败；Redis 连接异常由 `join()` 上抛；**时间回拨**（`timestamp < lastTimestamp`）抛 `RuntimeException`；**发号前租约检查失败**抛 `IllegalStateException`；**序列溢出本身不抛异常**，`sequence` 回到 0 后进入 `tilNextMillis` 循环等待时钟严格超过 `lastTimestamp`，时钟不前进时可能一直等待。源码没有降级发号。
- **唯一性边界**：`lastTimestamp` 与 `sequence` 只是生成器**实例内存状态**，重启不持久化；相同节点号被另一实例复用、进程重启后该状态重建、检查后租约失效、时钟异常，都可能让“运行时未重复”的推断失效。唯一性还依赖节点号配置唯一、Redis 租约可靠与时钟单调；DB 唯一键（`uk_order_id`、`uk_user_id` 等）是额外兜底，但生成器本身不重试重复。这些是源码与理论条件，未做跨实例/重启实测。

### 6.3 真实调用方与前端精度

`SnowflakeIdWorker#nextId` 的生产调用点：`AuthServiceImpl#registerByEmailCode` / `#createAccount` 生成 `userId`，`RideOrderServiceImpl#createOrder` 生成 `orderId`，`RagService#addQA` 生成 `groupId` 与每条问题的 `elasticId`。**没有**证据表明 `chatId` 使用雪花 ID。

`config/Config.java#jacksonCustomizer` 注册了 Long 序列化器：值超过 `9007199254740991`（或小于其相反数）时写成 JSON 字符串，否则写成 JSON 数字；`BigDecimal` 全局转字符串。关键 VO 字段也多用 String（S1 已说明 `TicketVO.id` 是字符串）。业务编号本身就是 `"T..."` 字符串，不经过 Long 序列化。仓库没有前端源码，也没有“大 Long 输出为字符串”的端到端断言，所以只能确认服务端做了处理，不能声称前端已完全验证。

---

## 七、两条可达竞争时间线

### 7.1 订单锁与事务 `afterCompletion`

前提：`driverAcceptOrder` 经 Spring 代理进入事务（同步活跃），T1 的 `tryGrab` 中 `FOR UPDATE` 成功、锁未提前失效；同一订单来了 T1（接单）与 T2（取消），假设 T1 回调在持锁线程上成功释放且 T2 在 3 秒内获得锁。

| 时刻 | T1（接单） | T2（取消） | 可见状态 |
|---|---|---|---|
| t0 | 代理开启事务（同步活跃），`tryLock(3s)` 成功 | 当前 HTTP 取消链无本地事务，`tryLock(3s)` 等待 | lock=T1 |
| t1 | 锁内 `tryGrab`：`FOR UPDATE` 锁司机账号行与活动订单行，写 Redis 占位，`GRAB_ORDER_ATOMIC` 预检通过 | 等待 | DB 未提交 |
| t2 | DB 条件更新成功，注册 `afterCompletion` 与提交后副作用；**目标方法体 return true**，但事务尚未提交、锁仍持有 | 继续等待 | DB 仍是 CREATED（未提交） |
| t3 | 事务代理完成物理提交，DB 已是 DRIVER_ACCEPTED；`afterCompletion` 尚未执行 | 继续等待 | DB 新状态可见，lock=T1 |
| t4 | `afterCompletion` 回调触发 → `releaseOrderLock`：仅当回调执行、`isHeldByCurrentThread()` 为真且 `unlock()` 成功时才释放（前提：锁未被提前过期/续期失败） | 拿到锁（前提：等待未超时） | lock 可能释放；异常/响应丢失时状态未知，可能保留到看门狗到期 |
| t5 | — | 读 DB 已是 DRIVER_ACCEPTED；取消的守卫/条件更新决定是否继续，当前 HTTP 取消链无事务同步时在 finally 尝试释放；有外层同步时注册回调 | — |

要点：**目标方法体返回 ≠ 事务代理返回 ≠ 提交完成 ≠ 回调执行**；“释放”也不是无条件结果，受回调执行、同一持有线程、`unlock` 成功与锁未提前失效限制。若 T2 等满 3 秒抛 409；若 T1 的锁提前失效，两笔工作可能重叠，但 DB 条件更新仍是最终裁决。

### 7.2 回填版本与并发追加

前提（真实 `MessageMemory#get` / `#save` 链）：T1 的 L1/L2 都 miss，在查 MySQL 前取 L2 租约 `v1`；T2 在 T1 查询期间保存一条新消息；T1 的查询结果是不含该消息的旧快照。R 表示稍后的另一个读请求，不是同时发生。

| 时刻 | T1（读链） | T2（保存链） | 版本与缓存 |
|---|---|---|---|
| u0 | L1 miss → `currentVersion` 取租约 `v1` → L2 miss，开始查 MySQL | — | 版本 v1，L2 无完整数据 |
| u1 | — | `mysqlMemory.append` 先把新消息落库（DB 此刻已可见） | DB 含新消息 |
| u2 | — | `appendIfVersionMatch(expected=v1)`：Lua `INCR`→v2、`PERSIST`；**数据 key 不存在时不动数据**，不创建残缺历史 | 版本 v2，L2 仍无完整数据 |
| u3 | 拿到旧快照，调 `overwriteIfLeaseValid(lease=v1)`，暂存 key 写入 | — | 暂存已写 |
| u4 | Lua 比较 `current=v2 ≠ v1` → `DEL` 暂存、返回 0；Java 返回 false | — | 旧快照未回填 |
| u5 | R：L1/L2 miss，取租约 v2，查 DB（新消息已提交可见）→ `overwriteIfLeaseValid(lease=v2)` 成功返回 1、`RENAME` 回填 | — | 缓存含新消息（前提：DB 已提交可见、R 读到对应数据、脚本成功、暂存 TTL 已设置且无其他干预） |

若 T2 的版本推进发生在 T1 的 Lua 执行**期间**，脚本的原子隔离使两条 Lua 不互相插入；若发生在 T1 取租约之前，T1 直接拿到新版本。版本不符是正常放弃分支；暂存写入或脚本执行期间发生 Redis 异常或响应丢失时 Java 返回 false，**不能按“没写”处理**，也不能据此断言脚本一定执行过。u5 的“新值回填”是带前提的可能结果，不是无条件保证。

---

## 八、四种场景怎么选机制

| 场景 | 当前选择 | 为什么不是别的机制 |
|---|---|---|
| 同一订单的状态变更 | Redisson `RLock` + 等待 3 秒 + DB 条件更新 | 锁有效且续期正常时限制同一订单并发，DB 条件更新是相应状态迁移的最终校验；版本号不阻止同时进入业务代码 |
| 统计/池缓存重建 | 手写 `RedisLock` + 固定 TTL + token 比对删除 | 重建是短临界区；固定 TTL 防止锁残留；比对删除防旧持有者误删，但不阻止旧工作 |
| 聊天历史回填 | 版本租约 + 暂存 key + Lua 条件替换 | 目标是让旧快照写不回去，不是阻止读请求并发；版本号比“锁住会话”代价小 |
| 生成实体编号 | Redis `INCR`（业务编号）/ 本地雪花 + 节点锁（数值 ID） | 编号不需要互斥进入临界区；需要的是单调序号或时钟+节点唯一性，外加 DB 唯一键 |

这些是当前源码里的对应关系，不是“理论上只有这一种做法”。

---

## 九、测试阅读：覆盖什么、没覆盖什么

本章只读了测试源码，**没有运行任何测试**；没有连接业务 Redis/MySQL/Redisson。

| 测试 | 门控、真实/替身 | 断言与写入/清理 | 没覆盖 |
|---|---|---|---|
| `OrderLockRegressionTest` | 无门控；`OrderLockTestSupport.availableClient()` 提供 mock `RedissonClient`/`RLock`，其余依赖也 mock | 断言同步活跃时 `afterCommit` 不释放、`afterCompletion(COMMITTED)` 才释放；回滚补偿先于解锁；7 类入口用规范化 key；抢锁失败/中断不进业务；解锁异常不覆盖业务结果。无真实 key/表写入；`@AfterEach` 清理 Token、同步状态和中断标志 | 真实 Redisson 看门狗、真实 DB 提交 |
| `OrderGrabIntegrationTest` | 类级门控只检查 `test.mysql.port`，但 `@BeforeAll` 强制读取 `test.redis.port`（缺失会在解析端口时失败）；真实 MySQL/Redis/Redisson，测试内 `setLockWatchdogTimeout(1500L)` | 随机 schema `order_grab_regression_<uuid>`，只建 `sys_user` / `sys_ride_order` 两表；Redis key **全部固定、无后缀**：`order:status:100/101`、`driver:active:1/2`、`order:lock:100/101`。每例先清两表并重灌 2 名司机 / 2 笔订单，`@AfterEach` 只删状态与司机 4 个 key，**不显式删 `order:lock:*`**，依赖业务 unlock 与 1500ms 看门狗；`@AfterAll` 关连接并 `DROP DATABASE`。固定 key 无并行隔离保证 | 生产 watchdog 配置、解锁失败/进程崩溃后的真实残留、网络故障 |
| `RedisLockRegressionTest` | 无门控；mock `StringRedisTemplate` | 断言 UUID 令牌各异、`setIfAbsent` 带 TTL、竞争返回 null、比对脚本释放、非法参数、释放异常不外抛；无真实写入 | 真实 Redis 时钟与响应丢失 |
| `RedisCacheRegressionTest` | `test.redis.port` 门控，真实 Redis + mock `CityCodeMapper`，整类 **13 个 `@Test`** | 除锁用例的 `lock-{chatId}` / `helper-lock-{chatId}` 外，整类还写聊天历史/版本、城市码 data+version、Hash `hash-{chatId}` 等，这些键加入 `keys` 由 `@AfterEach` 删除；聊天回填暂存键 `chat:history:staging:{chatId}:{token}` **不在 `keys`**，靠 Lua `RENAME`/`DEL`、异常 catch 尽力删除，或在 TTL 已成功设置时由 1 分钟过期兜底；城市码用例临时把默认 Locale 设为 `tr-TR` 并在 `finally` 恢复。只跑单个锁方法仍会执行 `@BeforeEach` / `@AfterEach` 的共同 setup/cleanup | 暂存键在异常后的真实残留、锁与 DB 事务的组合 |
| `SecurityRedisIntegrationTest#duplicateNodeConfigurationIsRejectedAndLostLeaseStopsGeneration` | `test.redis.port` 门控；该方法自建 Redisson client（未覆盖 watchdog），类级 `@BeforeEach` 为每例生成 suffix 并新建 service | 通过 `IdGeneratorConfig#idNodeLease` 创建**固定** `id:node:1:1`，在测试中段（`nextId()>0` 断言之后）**显式 `redis.delete("id:node:1:1")`**，随后断言发号抛异常——不是“测试结束时才删”。方法内经 Redisson adapter 写带 suffix 的 `chat:info:{suffix}` 确认状态；`@AfterEach` 用 `*suffix*` 模式删含 suffix 的 key，**覆盖不到固定 `id:node:1:1`**，若断言中断则依赖 `NodeLease.close()` 的 finally 释放。整类两个 token 测试还会写不带 suffix 的 session-version / user_tokens key，只在各自 `finally` 显式删除 | 跨运行残留的 `id:node:1:1` 会让首锁失败；租约失效与本地发号的真实交错、时钟回拨 |
| `CacheConsistencyRegressionTest`、`RepairDatabaseIntegrationTest` | 前者无门控（全 mock，stub Redis 脚本返回 1L）；后者 `test.mysql.port` 门控，真实 DB + 替身 `SnowflakeIdWorker` | 前者断言事务回调注册/回滚不失效；后者断言 QA 数据与同步任务一起回滚。都不测试编号格式或雪花算法 | 编号 TTL/边界、雪花序列溢出/时间回拨、节点检查与发号原子性 |

全 `src/test` 搜索 `ticket:no:`、`%05d`、`Clock moved backwards`、`sequenceMask` 未发现针对编号格式、序列溢出或时间回拨的直接断言；仓库也没有前端源码或端到端序列化测试。

本章不提供可直接运行的可选命令。若确需在隔离环境验证，必须按目标测试类/方法的门控准备**一次性专用 Redis/MySQL**，并逐项核对上表的固定/随机 key、schema 建删与每例清理：特别是 `order:lock:*` **不由** `OrderGrabIntegrationTest` 的 `@AfterEach` 清理，固定 `id:node:1:1` 不在 `SecurityRedisIntegrationTest` 的模式清理范围，聊天暂存键不在 `RedisCacheRegressionTest` 的 `keys` 清单（异常后可能残留；仅在 TTL 成功设置时可由 1 分钟过期兜底）。不能指向共享实例或使用 `FLUSHALL`。**本章未运行任何测试**，不声称验证通过。

---

## 十、动手验证

**练习 A：只读追踪一次接单**

从 `RideOrderController#driverAccept` 追到 `driverAcceptOrder` → `withOrderLock` → `tryGrab` → DB 条件更新 → 锁释放。回答：锁在什么回调里释放？若方法返回但事务还没提交，锁释放了吗？

<details>
<summary>练习 A 参考答案</summary>

同步活跃时 `withOrderLock` 在 `finally` 中注册 `afterCompletion`，回调由完成事务的线程执行、不区分 status 地调用释放；所以目标方法体返回不等于锁已释放，还要事务完成（提交或回滚）并触发回调，且 `isHeldByCurrentThread()` 为真、`unlock()` 成功、锁未提前过期才算释放。同步不活跃时 `finally` 直接尝试释放，同样受这些条件限制。`tryLock(3s)` 失败抛 409 不进业务；DB 条件更新是最终裁决，锁只提供受租期/网络限制的应用层串行。
</details>

**练习 B：token 还是版本号**

分别指出 `RedisLock#unlock`、`RELEASE_LOCK_IF_MATCH`、`OVERWRITE_IF_VERSION_MATCH` 比较的是什么，以及比较失败的结果。

<details>
<summary>练习 B 参考答案</summary>

前两者比较随机 UUID token：不匹配就不删 key，防旧持有者误删新锁；第三者比较聊天版本号：版本不符返回 0 并删暂存，暂存不存在返回 -1，成功返回 1；Java 只在 1 时返回 true，暂存写入或脚本执行所在 try 内的异常被捕获后尽力清理并返回 false（false 不能证明服务端没写）。token 回答“是不是我拿的锁”，版本回答“快照还可不可信”。
</details>

**练习 C：两套 ID**

工单号和雪花 ID 的生成位置、组成、失败边界分别是什么？`RedisKeyConstants` 里能找到工单号 key 吗？

<details>
<summary>练习 C 参考答案</summary>

工单号在 `TicketServiceImpl`：私有 Lua `INCR ticket:no:{date}`，`T+日期+类型码+%05d序号`，null 抛 503，Redis 不随 DB 回滚；key 不在 `RedisKeyConstants`。雪花 ID 在 `SnowflakeIdWorker`（本地位运算）与 `IdGeneratorConfig`（节点锁+发号前检查）：时间回拨抛异常、序列溢出忙等、租约失效拒发；检查与发号不是跨网络原子。
</details>

**练习 D：真实保存链与回填竞争**

T1 的 L1/L2 miss，取 L2 租约 v1 后查 MySQL；T2 走 `MessageMemory#save` 落库新消息并 `appendIfVersionMatch` 把版本推进到 v2；T1 随后用旧快照回填。结果是什么？Java 返回 false 能说明什么？

<details>
<summary>练习 D 参考答案</summary>

`MessageMemory#save` 先 `mysqlMemory.append` 落库，再 `appendIfVersionMatch`：脚本 `INCR + PERSIST` 推进版本；若数据 key 原本存在且版本匹配才追加消息，版本不符则删数据 key，数据 key 不存在时不动数据——不会创建只含新消息的残缺历史。T1 的 `overwriteIfLeaseValid(v1)` 见当前版本 v2 ≠ v1，`OVERWRITE_IF_VERSION_MATCH` 删暂存、返回 0，Java 返回 false，旧快照不会覆盖缓存。Java 返回 false **不能证明服务端没写**：暂存写入或脚本执行期间的异常被方法内 catch 后尽力清理并返回 false，响应丢失时脚本可能已执行完（返回 1）而客户端只看到超时。版本被外部重置/淘汰后，条件校验前提破坏，源码没有额外保护；下一次读到新值还要求 DB 已提交可见、读到对应数据且脚本成功。
</details>

**练习 E：四种机制选型**

同订单并发、缓存重建、旧快照回填、生成实体编号分别用本章哪种机制？每条说一句理由。

<details>
<summary>练习 E 参考答案</summary>

订单：Redisson `RLock` + DB 条件更新（应用层串行 + DB 裁决）；缓存重建：手写 `RedisLock` 固定 TTL + token 比对（短临界区，防误删）；回填：版本租约 + Lua 条件替换（不阻止并发读，只阻止旧快照覆盖）；编号：Redis INCR / 雪花 ID + 节点锁（单调序号或时钟节点唯一性，DB 唯一键兜底）。
</details>

---

## 十一、一页复习

**一句话**：锁限制“谁能进入”，版本判断“旧快照能否写回”，ID 只是“编号”；三者都可能在 Redis 里读写，但保障对象与失败边界不同。

- `RedisLock`：UUID token、`SET NX PX`、无等待无续期、Lua 比对删除；用于统计锁 5 秒、池锁 10 秒。
- 订单锁：Redisson `RLock`，`tryLock(3s)`，无显式 leaseTime；同步活跃时注册 `afterCompletion`、否则 `finally` 直接调用，只有回调执行 + `isHeldByCurrentThread()` + `unlock()` 成功才算释放；`tryGrab` 的 `FOR UPDATE` 在事务提交前。
- token ≠ 版本号：前者防误删新锁，后者防旧快照覆盖；比对删除不阻止旧工作继续。
- 回填版本（真实 save 链）：`MessageMemory#save` 取版本 → MySQL append → `appendIfVersionMatch`（版本相同且 data 存在才追加，不符则删 data，data 不存在则不创建）；读链 `currentVersion` → 查 DB → `overwriteIfLeaseValid` 条件替换；版本 key `PERSIST`，外部重置/淘汰不安全；`append` / `APPEND_WITH_VERSION_BUMP` 不是 save 链。
- 工单号：`T+yyyyMMdd+类型码+%05d`，key `ticket:no:{date}` 在 `TicketServiceImpl`，TTL<0 时 48 小时，序号按天共享。
- 雪花 ID：epoch 2025-01-01、41+5+5+12 位；节点锁 `id:node:{dc}:{worker}`，发号前检查租约但非跨网络原子；回拨抛错、溢出循环等待时钟前进；`lastTimestamp` / `sequence` 是实例内存状态。
- JSON：Long 超 ±9007199254740991 转字符串；前端消费端未知。
- 测试只读未运行；集成测试只能对一次性专用实例运行，注意 `order:lock:*`、固定 `id:node:1:1` 与暂存键不在通用清理范围。

---

## 十二、自测与答案

**1. `RedisLock` 拿不到锁时会等待吗？TTL 到期后旧持有者还能运行吗？**

<details>
<summary>参考答案</summary>

不等待：`setIfAbsent` 失败立即返回 null。TTL 到期只意味着锁 key 消失、别人可以拿新锁；旧持有者若仍在执行临界区代码，源码里没有任何机制让它停下。比对删除只保护新锁不被旧 token 误删。
</details>

**2. 订单锁在事务同步活跃与不活跃时分别怎么释放？提交失败会释放吗？**

<details>
<summary>参考答案</summary>

同步活跃时 `finally` 注册 `afterCompletion`，回调由完成事务的线程执行、不区分 status 地调用释放；同步不活跃时 `finally` 直接调用。但“调用”不等于“释放成功”：还要回调实际执行、`isHeldByCurrentThread()` 为真、`unlock()` 成功且锁未提前过期；Redis 异常或响应丢失时结果未知，异常只记日志。
</details>

**3. 订单锁的看门狗默认租期是多少？**

<details>
<summary>参考答案</summary>

源码不能确定。`withOrderLock` 只传等待时间不传 `leaseTime`，依 Redisson 看门狗语义；仓库没有生产环境 `lockWatchdogTimeout` 配置证据。测试里显式设过 1500ms，那只说明该测试的行为，不能当生产事实。与之相对，`RedisLock` 是显式固定 TTL、不续期。
</details>

**4. 版本号和 token 能不能互换使用？**

<details>
<summary>参考答案</summary>

不能。token 是一次加锁的随机身份，释放锁时比对；版本号是数据代际计数，回填时比对。前者防“旧持有者删掉新锁”，后者防“旧快照覆盖新数据”。比较失败的结果也不同：锁不删、回填放弃。
</details>

**5. 工单号的序号按天共享还是按类型分开？为什么？**

<details>
<summary>参考答案</summary>

按天共享。`generateTicketId` 的 Redis key 是 `ticket:no:{yyyyMMdd}`，类型码只出现在返回字符串中；源码没有按类型拆 key。所以同一天不同类型工单共用一个自增序列。
</details>

**6. 雪花 ID 的节点锁保证了什么？不保证什么？**

<details>
<summary>参考答案</summary>

启动时尝试占用 `id:node:{dc}:{worker}`；在同一 Redis 锁状态正常且旧锁有效时，重复配置会被拒绝；`nextId` 前检查 `isHeldByThread(owner)`，租约失效则拒发。不保证检查与本地发号跨网络原子；租约在检查后失效仍可能发出号；`synchronized` 只管单 JVM 内线程。唯一性还依赖节点号配置、时钟单调与 DB 唯一键。
</details>

**7. 时间回拨和同毫秒序列溢出分别怎么处理？**

<details>
<summary>参考答案</summary>

时间回拨：`timestamp < lastTimestamp` 直接抛 `RuntimeException`，无容忍窗口。同毫秒序列溢出：`sequence = (sequence+1)&sequenceMask` 回到 0 时调用 `tilNextMillis`，**循环等待时钟严格超过上一毫秒**，不是抛异常；时钟不前进时会一直等待。`lastTimestamp` / `sequence` 是实例内存状态、重启不保留，跨实例/重启的唯一性还要看节点号配置、租约与 DB 唯一键；以上为源码静态推导与理论条件，未实测。
</details>

**8. 后端如何避免雪花 ID 在前端 JS 丢精度？能说前端已经安全了吗？**

<details>
<summary>参考答案</summary>

`Config#jacksonCustomizer` 注册 Long 序列化器，超过 ±9007199254740991 写成字符串，否则写数字；业务编号本身是字符串。仓库没有前端源码，也没有端到端序列化断言，只能确认服务端处理，不能声称前端完全安全。
</details>

**9. 回填脚本的 -1/0/1 与 Java 返回 false 分别是什么意思？**

<details>
<summary>参考答案</summary>

Lua：`-1` = 暂存 key 不存在；`0` = 版本不符并删暂存；`1` = 版本一致、`RENAME` 成功回填并设数据 TTL。Java 只在 1 时返回 true，其余返回 false。暂存写入和脚本执行所在 try 内的异常被 `overwriteIfLeaseValid` 捕获，尽力删除暂存后返回 **false**；此前序列化发生在 try 外；响应丢失时 Lua 可能已执行完（返回 1）也可能未执行，所以 **false 不能证明服务端没写、也不能证明缓存一定没变**。正常版本不符（0）时缓存保持原样，这条分支可预测；异常 / 响应丢失则结果未知。
</details>

**10. 下面哪项是源码事实？（A）所有订单端点都加锁；（B）订单锁用 `RELEASE_LOCK_IF_MATCH`；（C）工单号 key 定义在 `RedisKeyConstants`；（D）`RedisLock` 不续期。**

<details>
<summary>参考答案</summary>

D。A 错：查询/创建/位置上报等端点未加锁；B 错：订单锁用 Redisson `RLock#unlock`，`RELEASE_LOCK_IF_MATCH` 属于 `RedisLock` 与司机占位等路径；C 错：key 在 `TicketServiceImpl` 私有常量；D 对：`RedisLock` 只做一次性 `setIfAbsent(key, token, ttl)`，没有续期。
</details>

---

## 十三、常见误区与导航

### 13.1 常见误区

- 看到“锁”就以为是同一种实现：订单锁是 Redisson `RLock`，统计/池锁是手写 `RedisLock`，回填是版本租约。
- 把 token 比对删除理解成“旧工作立刻停止”：它只防止旧持有者删新锁。
- 把“Service 方法返回”理解成“订单锁已释放”：目标方法体返回 ≠ 事务代理提交完成 ≠ 回调执行；还要回调实际执行、`isHeldByCurrentThread()` 为真、`unlock()` 成功且锁未提前过期。
- 从测试里设的 watchdog 1500ms 推断生产配置：没有证据。
- 把工单业务编号当成雪花 ID：前者是字符串、Redis INCR；后者是 Long、本地位运算 + 节点锁。
- 以为工单号 key 在 `RedisKeyConstants`：实际在 `TicketServiceImpl` 私有常量。
- 把“发号前检查租约”当成跨网络原子：检查后失效仍可能发号；`lastTimestamp` / `sequence` 是实例内存状态，重启不持久化，节点号复用时不能无条件保证唯一。
- 把 `RedisMemory#append` / `APPEND_WITH_VERSION_BUMP` 当成用户保存消息链：真实 `MessageMemory#save` 走 `appendIfVersionMatch`，数据 key 不存在时不会创建残缺历史。
- 把版本不符的正常放弃与 Redis 异常/响应丢失混为一谈：Lua 返回 0 / -1 是正常分支、缓存可预测；暂存写入和脚本执行所在 try 内异常被 catch 后返回 false，响应丢失时 Lua 可能已执行，**false 不等于“没写”**。
- 把后端 Long 序列化处理当成前端已验证：仓库没有前端源码。
- 把测试清理说成“结束时都清掉”：`order:lock:*`、固定 `id:node:1:1`、聊天暂存键都不在对应测试的通用清理清单里。
- 把只读测试断言说成“测试通过”：本章没有运行任何测试。

### 13.2 导航与源码入口

- **上一章**：[第 14 章 缓存问题与并发窗口](03-cache-races.md)——统计锁到期后的旧写/新写与 TTL 前提。
- **下一章**：[第 16 章 一次对话与事件流](../04-chat-and-agent/01-chat-round-and-sse.md)——从请求到 SSE 结束的完整串联。
- **相关专题**：
  - 聊天记忆三层与回填租约 → [第 19 章 三层记忆、消息窗口与工具结果反查](../04-chat-and-agent/04-memory-and-tool-results.md)（C4，后续主讲）
  - 抢单三阶段与订单 GEO 池 → [第 9 章 位置与发现](../02-orders/03-location-and-discovery.md)
  - 工单号在提交流程中的位置 → [第 12 章 工单生命周期与权限](01-ticket-lifecycle.md)
  - 统计锁与双删 → [第 13 章](02-ticket-index-and-cache.md)、[第 14 章](03-cache-races.md)

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；SQL、配置和测试写完整仓库相对路径。

| 结论 | 源码位置 |
|---|---|
| 手写锁与比对脚本 | `service/base/RedisLock.java#tryLock/#unlock`；`util/RedisScripts.java#RELEASE_LOCK_IF_MATCH` |
| 统计/池重建锁调用 | `service/impl/TicketServiceImpl.java#getTicketStatistics/#tryLockTicketStatistics/#releaseTicketStatisticsLock`；`service/base/TicketPoolIndex.java#slice`；`service/base/OrderGeoPool.java#search` |
| 订单锁 | `service/impl/RideOrderServiceImpl.java#withOrderLock/#releaseOrderLock/#driverAcceptOrder` 等 7 处；`constant/RedisKeyConstants.java#orderLockKey`；`agents/OrderSearchTool.java#cancelOrder/#modifyOrderDestination` |
| 事务回调 | `service/impl/RideOrderServiceImpl.java#withOrderLock`（`afterCompletion`）；`service/base/OrderGrabService.java#tryGrab/#rollbackGrab` |
| 版本与回填（真实 save 链） | `agentbase/memory/MessageMemory.java#get/#save/#fillRedisWithLease`；`agentbase/memory/RedisMemory.java#currentVersion/#appendIfVersionMatch/#overwriteIfLeaseValid`；`util/RedisScripts.java#CURRENT_HISTORY_VERSION/#APPEND_HISTORY_IF_VERSION_MATCH/#OVERWRITE_IF_VERSION_MATCH`。另：`RedisMemory#append` / `APPEND_WITH_VERSION_BUMP` 是**直接方法/测试场景**，非 save 链 |
| 工单业务编号 | `service/impl/TicketServiceImpl.java#TICKET_SEQUENCE/#TICKET_NO_PREFIX/#generateTicketId/#submitTicket` |
| 雪花 ID 与节点锁 | `config/IdGeneratorConfig.java#idNodeLease/#NodeLease#close`；`util/SnowflakeIdWorker.java#nextId/#tilNextMillis` |
| JSON 精度 | `config/Config.java#jacksonCustomizer`；`domain/vo/TicketVO.java` |
| 测试阅读（未运行） | `src/test/java/com/fancy/rideagenthub/OrderLockRegressionTest.java`、`OrderGrabIntegrationTest.java`、`RedisLockRegressionTest.java`、`RedisCacheRegressionTest.java`、`SecurityRedisIntegrationTest.java`、`CacheConsistencyRegressionTest.java`、`RepairDatabaseIntegrationTest.java` |
