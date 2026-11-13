# Redis 应用场景盘点与插入点清单

> 目标：定位 RideAgentHub（旧名 TaxiAgent）中适合引入 Redis 各类经典应用的业务点。
> 定位原则：**嫁接在业务本来就有的并发点上**，不为用 Redis 而用 Redis。
> 代码引用格式为 `文件:行号`，基于历史提交 `8d5d574`（`import TaxiAgent project`，属已被重写合并的旧分支）。
> **本文性质**：这是「改造前规划稿 + P1-3 事后补记」的混合体。除第二节 **P1-3（工单池 ZSet）** 为后续提交引入、描述的是当时已实现的能力外，其余「现状」与「建议改造」均为改造前快照；P0/P1 的落地状态、与建议方案的差异见各节追加的【落地状态】说明，当前实现以 `docs/learn/redis/` 与源码为准。章节编号（`P0-x`/`P1-x`/`P2`）是 25 篇学习笔记「来源」的锚点，**保持不变**。

---

## 一、现状盘点：已实现的 Redis 应用

这一节列出**当时已经做好**的部分，避免重复造轮子。

> 【当前状态说明】下表的 `文件:行号` 全部是 `8d5d574` 快照：**主要行号已漂移 10~90 行**（`RedisScripts` 234→278 行、`TicketServiceImpl` 整体下移约 150 行），但布隆过滤器一节 `UserUsernameBloomFilterService:27-67` 仍逐行吻合、`ChatManager:24` 未漂移。当前一律按「类名 + 方法名 + 章节名」定位，见 `docs/learn/redis/README.md` 的统一落点表。表中「本项目实现」列描述的是**改造前实现**，当前语义见最右列的补充说明。

| 经典考点 | 本项目实现（改造前快照） | 位置（历史行号） | 当前实现的补充 |
|---|---|---|---|
| 短信登录 / 共享 Session | Token 存 Redis + `user_tokens:{userId}` Set 反向索引，支持按用户踢下线 | `TokenServiceImpl:35-100` | 现由 `SAVE`/`REVOKE`/`REMOVE` 三段 Lua 原子发布；新增 `auth:session-version:{userId}` 与 `sessionVersion`/`credentialStamp` 字段 |
| 验证码 + 发送冷却 | 验证码 TTL + 独立冷却 key，发送失败回滚删除 | `EmailCodeService:39-61`、`96-115` | 现由 `ISSUE`/`VERIFY`/`CLEANUP`/`COMPLETE` 四段 Lua 覆盖，冷却 key 值改为 `issueId`，新增 5 次错误上限 |
| 缓存穿透（布隆过滤器） | Redisson `RBloomFilter`，含全量重建方法 | `UserUsernameBloomFilterService:27-67` | 类仍在（本表唯一逐行吻合者），但 `mightContain` 已退出可用性主链路，见 [03 篇](learn/redis/03-cache-penetration-bloom.md) |
| 缓存击穿（互斥锁 + 双检） | 手写 `setIfAbsent` 锁 + 双检 + 锁等待重试 + 降级直查 DB | `TicketServiceImpl:983-1053` | 抽成 `service/base/RedisLock.java`（`setIfAbsent` + `RELEASE_LOCK_IF_MATCH` 令牌校验释放）；订单状态机另用 Redisson 看门狗锁；仍有 3 处非锁用途的 `setIfAbsent` |
| 缓存雪崩（随机 TTL） | 基础 20s + 0-10s 随机抖动 | `TicketServiceImpl:65-66`、`1039-1040` | 数值未变，当前常量在 `:87-88`、使用点 `:1191-1192` |
| 全局唯一 ID | `INCR ticket:no:{yyyyMMdd}` 生成当日序号 | `TicketServiceImpl:558-571` | 现由 `TICKET_SEQUENCE` Lua 保证 `INCR`+`EXPIRE` 原子；并与雪花 ID + Redisson 节点租约**两套并存** |
| 多级缓存 L1/L2/L3 | Heap → Redis List → MySQL 三级，读时逐级回填 | `MessageMemory`、`RedisMemory`、`HeapMemory` | 层次不变，但改为 `getAll` + `MessageWindow.tail`、先写 MySQL 再回填 L2/L1，版本 key `PERSIST` 永久保留 |
| 工具调用结果缓存 | callId 为 key，三级缓存 | `ToolResponseMemory:28-72` | 退化为 **Redis + MySQL 两级**，key `chat:tool:v2:{chatId}:{callId}`，TTL 24h；历史消息保存工具结果全文 |
| 会话锁 | `chat:info:{chatId}` Hash 存 `locked`，TTL 60 分钟 | `ChatManager:24-43` | `HSET`+`EXPIRE` 已由 `HASH_SET_WITH_EXPIRE` Lua 原子化；另有独立的单轮执行锁 `chat:execution:{chatId}` |
| 两阶段确认令牌 | 取消订单前先 `verifyCancelConditions` 写入令牌，`cancelOrder` 校验后一次性消费 | `OrderSearchTool:179-303` | 取消令牌已由 `CANCEL` Lua 原子校验 + `HDEL`；订单创建权限改由 `OrderConfirmation` 的 `CONFIRM`/`CONSUME` 两段 Lua 管理 |
| 静态数据缓存 | 高德城市编码，含 `__NULL__` 空值哨兵防穿透 | `CityCodeUtil:20`、`35-61` | 新增版本 key + 两段 Lua（`INVALIDATE_CITY_CODE` / `CACHE_CITY_CODE_IF_VERSION_MATCH`）；但 `invalidate()` 写路径未接线（部分实现） |

> 注：`OrderSearchTool:179-303` 的两阶段令牌用 Redis Hash 模拟了一次性令牌，
> 但 `finally` 块做的是"无条件删除"而非"原子校验并删除"，严格说存在重复消费窗口。
> 见 `02-cache-consistency-race.md` 第三节。
>
> 【当前状态】该「无条件删除」造成的**核心重复消费竞态已修复**：`OrderSearchTool.CANCEL` Lua 把「校验令牌 + 绑定 orderId + 5 分钟时效 + `HDEL`」合并为一次原子操作；**仍在**的是 `hasKey` 预检、`finally` 里的冗余 `delete`、以及令牌消费与业务执行分离。这段推导保留作「改造前对照」，见 [11 篇](learn/redis/11-two-phase-confirm-token.md)。

---

## 二、待插入的应用点

> 【各节落地状态总表】本节各 `P0-x`/`P1-x` 的编号与「现状 / 问题 / 建议改造路径」原文**全部保留**，作为「为什么需要这个能力」的推导材料；但它们是改造前口吻，读的时候必须配合下表判断当前状态。术语遵循 `docs/learn/redis/README.md` 的「术语与状态标注规范」：`当前实现` / `改造前实现` / `设计建议（未采纳）` / `机制已就位·写路径未接入` / `部分实现` / `未实现`。

| 节 | 规划时的建议 | 当前状态 | 实际落地方案与差异（含为什么不同） | 主要证据 | 对应笔记 |
|---|---|---|---|---|---|
| P0-1 | 阶段一 Lua 原子预检；阶段二 Redis Stream 异步落库 | **阶段一已落地 / 阶段二未实现** | 阶段一用 `GRAB_ORDER_ATOMIC` Lua 合并「订单可抢 + 司机空闲」的预检与占位（统一 5 分钟 TTL）；DB 侧保留 `FOR UPDATE` 司机账号行锁与活跃订单行锁、订单条件更新兜底，`tryGrab` 标 `@Transactional(MANDATORY)`，回滚经 `afterCompletion` 走 `RELEASE_LOCK_IF_MATCH`。阶段二**仍是事务内同步落库**（全库无 `xadd`/Stream 落库路径），因为异步化要额外处理落库失败补偿与状态可见性；`currentLat/currentLng` 已用于 `driverGeoIndex.refreshIfExist` | `OrderGrabService.tryGrab`、`RideOrderServiceImpl.driverAcceptOrder`、`RedisScripts.GRAB_ORDER_ATOMIC` | [14](learn/redis/14-grab-order-lua.md)、[13](learn/redis/13-distributed-lock.md) |
| P0-2 | `GEOSEARCH` 按距离 + 状态变更时 `ZREM` 单成员 | **已落地但方案不同** | 实际 `SEARCH_ORDER_GEO_POOL` Lua 内 `GEORADIUS … ASC COUNT 200 WITHDIST`（兼容 Redis 5）；订单变更走**整池失效 + 版本/锁令牌原子发布重建**（`INVALIDATE_ORDER_GEO_POOL` / `PUBLISH_ORDER_GEO_POOL`），而非单成员摘除 —— 单成员删除容易漏状态，整池重建以「首次查询全量回表」换完整性；回表后版本二次校验不一致则整页降级 DB，分页有 offset 溢出保护，事务中不发布、回滚不发布。代码里的 `GEOSEARCH` 是**改造建议**，不是现状断言 | `OrderGeoPool`、`RedisScripts:48-85` | [15](learn/redis/15-nearby-order-geo.md) |
| P0-3 | `ZADD`/`ZREM` 延迟队列 + `ZREM` 抢占去重 | **已落地且超出建议** | `CLAIM_DUE_ORDERS` Lua 一次完成 `ZRANGEBYSCORE 0..now LIMIT 0 batch` + 逐条 `ZREM`，并防「改期后误删」；排期/取消走 `afterCommit`（`Math.addExact` 防溢出），失败 `log.warn` 交补偿；`OrderTimeoutJob.recoverOverdueOrders()` 每分钟按三类锚点扫 DB、限制 `batchSize`、失败按 `pollIntervalMs` 重排，条件更新带 `update_time` 快照且保留 `real_price`；预约单改期不误取消；`disabled` 同时关闭轮询与补偿，`@PostConstruct validate()` 让非法配置启动即失败 | `OrderDelayQueue`、`OrderTimeoutJob`、`RedisScripts.CLAIM_DUE_ORDERS` | [17](learn/redis/17-order-timeout-delay-queue.md) |
| P1-1 | 分类结果缓存（空结果写 `""` 哨兵）+ 按 userId 限流 | **部分实现（方案不同）** | `ClassificationCache` 的 `buildKey` 用 `"v2" + encode(...)` 长度前缀指纹（不用 `\0`），结果归一化只接受 5 个标签，模型异常降级返回 null，且**空结果不缓存**（`put` 直接 return）—— 这是与「空值哨兵」建议不同的落地选型：分类结果可以随时重算，写哨兵反而会缓存一次模型失败；`TTL ≤ 0` 时禁用缓存已实现。限流除按 userId 的 30/60 外，另有入口层按 `CredentialStamp.of(remoteAddr)` 摘要的 120/60（`OPTIONS` 放行） | `ClassificationCache`、`ChatServiceImpl.resolveClassification`、`ChatRateLimiter`、`RequestRateLimitInterceptor` | [21](learn/redis/21-llm-classification-cache.md)、[22](learn/redis/22-sliding-window-rate-limit.md) |
| P1-2 | 订单状态机用 Redisson `RLock`，手写锁统一成 `RedisLock` | **已落地** | `withOrderLock` 用 `tryLock(3, TimeUnit.SECONDS)`（不传 leaseTime，**启用看门狗**），7 个状态变更统一同一把锁，锁竞争直接 409 且不触碰 Mapper/队列/缓存；持锁到 `TransactionSynchronization.afterCompletion`；`SYNC_ORDER_STATUS` Lua 保证状态单调推进，迟到旧状态不能覆盖新状态；手写 `RedisLock` 只用于统计缓存 / GEO 池 / 工单池索引重建 | `RideOrderServiceImpl.withOrderLock`、`RedisLock`、`RedisScripts.SYNC_ORDER_STATUS` | [13](learn/redis/13-distributed-lock.md) |
| P1-3 | 无（本节是 `8d5d574` 之后的事后补记，当时即「已实现」） | **保持原文** | 全段与 `TicketPoolIndex` 逐条吻合，是本文唯一描述当时现状的段落；下方第四节优先级表把它列为「待实施」属于自相矛盾，已在该节修正 | `TicketPoolIndex`、`TicketServiceImpl.afterTicketMutation` | [23](learn/redis/23-ticket-pool-zset.md) |
| P2 | 7 项演示型候选 | **大部分未实现；接口幂等局部落地** | BitMap / ZINCRBY / HyperLogLog / Pub-Sub / Token 滑动续期仍 0 命中；通用幂等注解、切面仍无，但已有三处服务端一次性令牌（`OrderConfirmation.CONSUME`、`OrderSearchTool.CANCEL`、验证码一次性消费）；`PermissionAspect` 现在从 `UserTokenContext` 读角色，不查 DB | `docs/learn/redis/README.md`「未实现的考点」 | [L/README](learn/redis/README.md) |

### P0-1：司机抢单 = 秒杀模型

**现状**

`RideOrderServiceImpl.driverAcceptOrder:271-304` 完全依赖 DB 乐观锁：

```text
// 280-289 行：每个司机抢单都要 selectCount 一次"我有没有未结束订单"
Long activeCount = rideOrderMapper.selectCount(new LambdaQueryWrapper<RideOrder>()
        .eq(RideOrder::getDriverId, driverIdLong)
        .eq(RideOrder::getIsDeleted, 0)
        .notIn(RideOrder::getOrderStatus, List.of(...)));
if (activeCount != null && activeCount > 0) {
    throw new BusinessException(409, "司机已有未结束订单，无法接单");
}

// 292-301 行：乐观锁更新
int updated = rideOrderMapper.update(null, new LambdaUpdateWrapper<RideOrder>()
        .eq(RideOrder::getOrderId, orderId)
        .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode())
        .isNull(RideOrder::getDriverId)
        .set(RideOrder::getDriverId, driverIdLong)
        ...);
```

**问题**

1. `activeCount` 查询是**热点行**。抢单高峰时所有司机的请求都压在同一索引上，而它和后面的乐观锁更新是**两次独立 DB 往返**，中间存在窗口。
2. `driverAcceptOrder:271` 的签名收了 `currentLat` / `currentLng`，但方法体内**从头到尾没有使用**——这是明显的预留钩子，说明"按距离匹配司机"本来就在设计意图内。
3. 一人一单校验与订单可抢校验**不在同一临界区**，理论上同一司机并发请求两个订单可能双双通过预检。

**业务语义映射**

| 黑马点评 | TaxiAgent |
|---|---|
| 优惠券库存（=1） | 待接单订单（`order_status=10`） |
| 抢购用户 | 接单司机 |
| 一人一单校验 | 一个司机同时只能有一个进行中订单 |
| Lua 原子预检 | Lua 原子校验"订单可抢 + 司机空闲" |

**建议改造路径**

阶段一（最小改动）：用 Lua 脚本把两个校验合并成一次原子判断，返回抢单令牌。

```
KEYS[1] = order:status:{orderId}      -- 不存在则从 DB 预热
KEYS[2] = driver:active:{driverId}    -- 司机当前进行中订单
-- 原子判断：订单可抢 且 司机空闲 → 写入令牌 + 置司机占用
```

阶段二：抢到令牌后**返回即响应**，通过 Redis Stream 异步落库，削峰。

> 阶段二复杂度不低（需要处理落库失败补偿），建议阶段一验证通过后再评估。

> 【当前落地状态 · 阶段一 / 阶段二】**阶段一已落地**，且与建议有三点差异：
> 1. 预检与占位在 `OrderGrabService.tryGrab` 的 `GRAB_ORDER_ATOMIC` Lua 内一次完成，占位 TTL 统一为 5 分钟（`CACHE_TTL`），不再是规划稿里的 2h / 6h 分档；
> 2. Redis 只承担**快速预检与占位**，正确性由 DB 兜底：`tryGrab` 标注 `@Transactional(propagation = MANDATORY)`，事务内对司机账号行和在途订单行加 `FOR UPDATE`，再由「`order_status=CREATED` 且 `driver_id IS NULL`」的条件更新裁决谁抢到；
> 3. 回滚不是无条件 `DELETE`，而是 `afterCompletion` 回调里用 `RELEASE_LOCK_IF_MATCH` 校验锁令牌后释放，状态同步走 `SYNC_ORDER_STATUS` 单调推进，迟到的旧状态不能覆盖新状态。
>
> **阶段二（Redis Stream 异步落库）未实现**：`RideOrderServiceImpl.createOrder` 仍是事务内同步落库，全库检索不到 `xadd` / Stream 落库路径。取舍原因：异步化会让「接口成功」与「订单可见」之间出现窗口，还要补消息表、补偿任务与幂等消费；当前行锁 + 条件更新已经把同一订单的热点请求串行化，削峰收益不足以抵消正确性成本。
>
> 另外 `currentLat` / `currentLng` 不再「从头到尾没有使用」：`driverGeoIndex.refreshIfExist` 会用它们刷新仍在线司机的位置，但不会把已下线司机重新上线。详见 [14 篇](learn/redis/14-grab-order-lua.md)、[13 篇](learn/redis/13-distributed-lock.md)。

---

### P0-2：司机工单池 = 附近商户 GEO

**现状**

`RideOrderServiceImpl:763-778` 的工单池查询是纯 DB 时间排序：

```text
LambdaQueryWrapper<RideOrder> baseQw = new LambdaQueryWrapper<RideOrder>()
        .eq(RideOrder::getIsDeleted, 0)
        .eq(RideOrder::getOrderStatus, RideOrderStatus.CREATED.getCode());

Long total = rideOrderMapper.selectCount(baseQw);
List<RideOrder> list = rideOrderMapper.selectList(baseQw
        .orderByDesc(RideOrder::getCreateTime)   // ← 按创建时间，不是距离
        .last("limit " + offset + "," + s));
```

**这同时是一个真实的业务缺陷**，不只是技术选型问题：司机看到的是"最新发布的订单"，而不是"离我最近的订单"。对出租车业务来说后者才是正确排序。

**建议改造**

```text
// 下单成功时（RideOrderServiceImpl.createOrder 末尾）
// GEOADD order:geo:pool {startLng} {startLat} {orderId}

// 司机端查询附近订单
// GEOSEARCH order:geo:pool FROMLONLAT {lng} {lat} BYRADIUS 3 km ASC COUNT 20

// 司机上报位置时顺带维护在线状态
// GEOADD driver:geo:online {lng} {lat} {driverId}  +  EXPIRE 心跳 TTL
```

订单状态离开 `CREATED`（被接单/取消）时用 `ZREM` 移出 GEO 集合（GEO 底层即 ZSet）。

> 【当前落地状态 · 已落地但方案不同】上面的 `GEOSEARCH` 是**改造建议中的选型**，落地时为了兼容 Redis 5 改用 `GEORADIUS`（`SEARCH_ORDER_GEO_POOL` Lua 内 `GEORADIUS … ASC COUNT 200 WITHDIST`），因此它不是文档写错了，而是「建议 ≠ 现状」。同理，实际没有采用单成员 `ZREM`：订单变更在事务提交后走 `INVALIDATE_ORDER_GEO_POOL` **整池失效**（换版本 + DEL ready/pool），下次查询抢重建锁、全量回表，再由 `PUBLISH_ORDER_GEO_POOL` 校验版本与锁令牌后原子发布；数据与 ready 标记固定 2 小时到期、写入不续期。
>
> 为什么改成整池重建：单成员摘除依赖每一条写路径都记得 `ZREM`，一旦漏掉就会留下「已接单但仍在附近列表」的脏成员；整池失效把正确性收敛到一个版本号上，代价是变更后的首次查询要全量回表。回表后还会二次校验版本，不一致就整页降级 DB 查询，并有分页 offset 溢出保护；事务中不发布、回滚不发布，过期锁持有者不能发布。详见 [15 篇](learn/redis/15-nearby-order-geo.md)。

**顺带修掉 P0-1 提到的未使用参数**：`driverAcceptOrder` 的 `currentLat`/`currentLng` 正好在这里派上用场。

---

### P0-3：订单超时 = 延迟队列（ZSet）

**现状**

`RideOrderStatus.CREATED` 状态的订单**没有任何超时处理**。没人接单就永远挂在创建状态。同理，司机接单后未到达、行程结束后未支付，都没有超时兜底。

**建议改造**

```text
// 下单时写入延迟队列
// ZADD delay:order:timeout score={deadline时间戳} member={orderId}

// 后台线程轮询到期任务
// ZRANGEBYSCORE delay:order:timeout 0 {now} LIMIT 0 100
```

覆盖三个场景：

| 场景 | 触发条件 | 处理动作 |
|---|---|---|
| 无人接单 | 创建后 N 分钟无司机接单 | 自动取消 + 通知乘客 |
| 司机未到达 | 接单后 N 分钟未到达 | 释放订单回池 + 通知司机 |
| 未支付 | 行程结束后 N 分钟未支付 | 自动关单 |

实现要点：轮询必须用 `ZREM` 原子抢占（`Lua` 或 `ZREM` 返回值判断），避免多实例重复处理同一任务。

---

### P1-1：LLM 分类缓存 + 调用限流

**现状**

`ChatServiceImpl.chat:76-96` —— **每轮对话都要调用两次 LLM**：

```text
// 76-85 行：对话为空，调分类器
classification = chatClient.prompt()
        .system(CLASSIFIER_SYS_PROMPT)
        .user(param.getPrompt())
        .options(DashScopeChatOptions.builder().model(CLASSIFIER_MODEL)...)  // qwen3-max-preview
        .call().content();

// 88-95 行：对话非空，再调一次分类器
classification = chatClient.prompt()
        .system(CLASSIFIER_SYS_PROMPT)
        .user(CLASSIFIER_USER_PROMPT.formatted(classRedis, messages.getFirst().getText(), param.getPrompt()))
        ...
```

分类模型是 `qwen3-max-preview`（`ChatServiceImpl:33`），又贵又慢。而且全程**没有任何限流**。

**这是唯一一个纯靠 Redis 就能直接省钱的点。**

**建议改造**

1. **分类结果缓存**：key 用 `prompt` 的 hash（或规范化后的文本），TTL 适当（分类语义稳定）。
   - 空结果写 `""` 防穿透（沿用 `CityCodeUtil:20` 的哨兵思路）
   - 多轮场景的 key 要包含上下文特征，否则可能误命中
2. **按 userId 滑动窗口限流**：Lua 脚本实现，窗口内计数超阈值直接返回友好提示。
   - 这里的"库存"就是 LLM 配额，黑马点评的限流 Lua 脚本在这里比在任何地方都贴切
3. 可顺带做**同一用户并发会话数限制**（现在 `ChatManager.isLocked` 只管单个 chatId）

> 【当前落地状态 · 部分实现（方案不同）】上面 1、2 两条的**方向**已落地，但细节有三处不采纳原建议：
> - **空结果哨兵未采纳**：`ClassificationCache.put()` 在 `classification == null` 时直接 `return`，不写 `""`。理由：分类结果随时可重算，把一次模型失败缓存 6 小时反而会把「暂时不可用」放大成「长期判定错误」；归一化只接受 5 个合法标签，模型调用 `try/catch` 降级返回 null。`buildKey` 也由 `\0` 拼接改成 `"v2" + encode(...)` 长度前缀，避免分隔符注入并区分 null 与空串；`TTL ≤ 0` 时直接禁用缓存。
> - **限流有两套**：按 userId 的 30 次 / 60 秒（`ChatRateLimiter`，限流前置在 `ChatServiceImpl.canStartChat()`，拒绝时发 `notify` 事件并结束流）与入口层按来源地址**摘要**的 120 次 / 60 秒（`RequestRateLimitInterceptor` 挂 `/auth/**`、`/order/estimate`，`OPTIONS` 放行），共用 `SLIDING_WINDOW_RATE_LIMIT` Lua，时间源统一到 Redis `TIME`。
> - 第 3 条「并发会话数限制」仍未做；但新增了**单轮执行锁** `chat:execution:{chatId}`（同一会话一次只处理一轮）与 3 分钟 SSE 超时，解决的是另一类问题，不要混为一谈。
>
> 详见 [21 篇](learn/redis/21-llm-classification-cache.md)、[22 篇](learn/redis/22-sliding-window-rate-limit.md)、[12 篇](learn/redis/12-chat-lock-atomic.md)。

---

### P1-2：订单状态机加分布式锁

**现状**

`driverAcceptOrder` / `driverArriveStart:307` / `startRide:330` / `finishRide:375` 都是"读-判-写"多步操作，目前靠 DB 乐观锁兜底。

**建议改造**

项目**已引入 Redisson**（`UserUsernameBloomFilterService` 在用 `RBloomFilter`），可直接使用 `RLock`：

```text
RLock lock = redissonClient.getLock("order:lock:" + orderId);
lock.lock();
try {
    // 现有的"查未结束订单 + 乐观锁更新"整段成为临界区
} finally {
    lock.unlock();
}
```

顺带把散落的手写锁（`TicketServiceImpl:1049-1053`）统一抽象成 `RedisLock` 组件，
方便讲"手写锁 → Redisson 演进"这条线。

---

### P1-3：工单池用 ZSet 排序

> 【本节性质】本节是 `8d5d574` 之后的**事后补记**，也是全文唯一描述「当时已实现」的段落（`TicketPoolIndex` 由后续提交引入）。因此不要给本节套用「改造前规划稿」标注；下方第四节优先级表把同一项列为「待实施」属于本文内部矛盾，已在第四节修正。以下全段与当前 `TicketPoolIndex.java` / `TicketServiceImpl.afterTicketMutation` 逐条吻合，**原样保留**。

**已实现**

`TicketPoolIndex` 按状态维护 `ticket:pool:{statusCode}`，仅按状态筛选的管理端工单池查询走索引。
带关键词、工单类型、处理人、发起人类型或未指定状态时，整体使用数据库查询。

- 分值为 `priority * 1e13 + updatedAt` 的 UTC 毫秒值；主键补零为 19 位作为成员。
  倒序读取对应数据库的 `priority DESC, updated_at DESC, id DESC`，相同时间下分页顺序稳定。
- 工单写入提交后统一失效统计缓存和全部状态池，并更新随机版本令牌；回滚不触发。
  写入较少时，按需完整重建比逐条增量同步更容易保证集合完整，代价是变更后的首次读取需全量加载对应状态。
- Lua 在版本和重建锁令牌均匹配时原子替换完整索引，旧快照和已过期锁持有者不能发布结果。
- 数据和就绪标记固定保存 2 小时，不因写入续期。就绪标记包含版本和成员数，可区分空池与数据丢失。
  （补充：写入触发整池失效后，**首次读取重建会重新 `PEXPIRE` 2 小时**，所以「固定 2 小时」指每次发布后的生命周期，不是从系统启动起算一次；版本 key `ticket:pool:version` 无 TTL，与聊天历史版本 key 同属 `PERSIST` 模式，见 [09 篇](learn/redis/09-lease-token.md)。）
- Lua 原子读取总数和当前页，回表后再校验状态及版本。缺行、状态漂移或版本变化时整页退回数据库。
- 数据库回退显式查询总数并使用 `LIMIT` 分页，不依赖分页拦截器；偏移使用 `long`，防止整数溢出。
  活跃事务中的查询也使用数据库，防止将事务快照或未提交数据发布到公共缓存。

---

### P2：锦上添花（按时间取舍）

| 场景 | Redis 结构 | 说明 |
|---|---|---|
| 司机出勤签到 | BitMap | `SETBIT driver:sign:{yyyyMM} {driverId} {day} 1`，`BITFIELD` 统计连续出勤 |
| 司机排行榜 | ZSet | `ZINCRBY driver:rank:accept:{date} 1 {driverId}`，接单数/评分排行 |
| UV / DAU 统计 | HyperLogLog | `PFADD uv:chat:{date} {userId}`，百万级基数仅占 12KB |
| 工单聊天实时推送 | Pub/Sub 或 Stream | `TicketServiceImpl.getChatHistory`（当前 `:685`）目前是 DB 轮询，可做多实例广播 |
| 权限缓存 | String | `PermissionAspect` **当前从 `UserTokenContext`（ThreadLocal）读角色，不查 DB 也不查 Redis**；原「每次请求查 DB 角色」的证据已过时，但「没有独立权限缓存」的结论仍成立 |
| Token 滑动过期 | String | `AuthTokenInterceptor` 目前无续期，可加"每次访问刷新 TTL" |
| 接口幂等 | String + SETNX | **通用幂等注解/切面仍未实现**；但已有三处服务端一次性令牌局部落地：`OrderConfirmation.CONSUME`（创建权限）、`OrderSearchTool.CANCEL`（取消权限）、`EmailCodeService`（验证码一次性消费），另见第三节第 1 条 |

---

## 三、现存缺陷（改造时顺带修）

1. **`ToolResponseMemory.save:35` 用了 `set` 而非 `setIfAbsent`**
   ```text
   redisTemplate.opsForValue().set(key, response, DEFAULT_TOOL_CACHE_TTL);
   ```
   重复的 tool call 会覆盖已有结果。若要讲幂等，这是现成的反例。

   【当前状态】反例推导仍然成立，但代码坐标与后果都已变化：`ToolResponseMemory.cache()` 仍用 `set`（非 `setIfAbsent`），`save()` 顺序改为**先 MySQL 再 Redis**，key 为 `chat:tool:v2:{chatId}:{callId}`、TTL 24h。更值得注意的是 MySQL 侧：**每次 `save` 都 `mapper.insert` 一行新记录**，`get()` 靠 `orderByDesc(id) LIMIT 1` 取最新，因此同一个 callId 重复写入不仅会覆盖 Redis 值，还会在 `sys_chat_tool_repsonse` 表里**累积重复行**（该表没有唯一索引）。这是比「覆盖」更重的后果，也是讨论幂等时更好的反例。见 [20 篇](learn/redis/20-tool-response-cache.md)。

2. **`UserUsernameBloomFilterService.rebuild:41-59` 重建期存在穿透**
   ```text
   bloomFilter.delete();                    // 先删
   bloomFilter.tryInit(...);
   for (String username : normalized) {     // 再逐条加
       bloomFilter.add(username);
   }
   ```
   重建期间过滤器为空/半空，所有查询都会穿透到 DB。建议改为**双 key 切换**（重建到新 key，完成后原子改名），而非原地删除重建。

3. **`UserUsernameBloomFilterService.getBloomFilter:63-67` 每次调用都执行 `tryInit`**
   两次 Redis 往返，可缓存 `RBloomFilter` 实例或加本地标记。

---

## 四、实施优先级建议

> 【当前状态】下表是**历史实施顺序记录**，不再是「待办清单」。P0/P1 六行主体已实施（其中 P0-2 方案不同、P1-1 空结果哨兵未采纳、P0-1 阶段二未落地；墓碑 `CityCodeUtil.invalidate(String)` 生产 0 调用方，属「机制已就位·写路径未接入」的部分实现），仅 P2 的演示型候选大部分仍未实现。这张表与第二节 P1-3「已实现」曾自相矛盾，原因就是它写于规划阶段、未按后续提交回写；此处统一为「已实施 + 落地差异」，保留原表以看清当时的取舍依据。

| 优先级 | 项目 | 当时的理由 | 状态与落地差异 |
|---|---|---|---|
| **P0** | 附近订单 GEO | 同时修复真实业务缺陷，改动局部 | ✅ 已实施（`OrderGeoPool` + `DriverGeoIndex`）；**方案不同**：`GEORADIUS` 兼容 Redis 5、整池失效重建而非单成员 `ZREM` |
| **P0** | 订单超时延迟队列 | 补上完全缺失的超时兜底 | ✅ 已实施且增强（`OrderDelayQueue` + `OrderTimeoutJob` + `CLAIM_DUE_ORDERS` Lua + 每分钟 DB 补偿扫描） |
| **P0** | 司机抢单 Lua 化 | 黑马点评核心考点，业务语义贴合 | ⚠️ 阶段一已实施（`GRAB_ORDER_ATOMIC` + 行锁 + 条件更新）；**阶段二 Redis Stream 异步落库未实现** |
| **P1** | LLM 分类缓存 + 限流 | 直接降本，纯增量 | ✅ 已实施（**两套限流并存**）；空结果哨兵建议未采纳，改为「空结果不缓存」 |
| **P1** | 订单状态机 Redisson 锁 | 复用已有依赖 | ✅ 已实施（`withOrderLock` 看门狗 + 7 个状态变更统一锁 + `SYNC_ORDER_STATUS` 单调推进） |
| **P1** | 工单池 ZSet | 性能优化 | ✅ 已实施（**即第二节 P1-3 事后补记的那一项**，见 `TicketPoolIndex`） |
| **P2** | 排行榜 / 签到 / UV / Pub-Sub | 演示价值为主 | ❌ 大部分未实现；仅「接口幂等」有三处局部令牌落地，其余见 `docs/learn/redis/README.md`「未实现的考点」 |

**历史实施顺序（供阅读，不是建议起手）**：当时确实从 P0-2（附近订单 GEO）开始 —— 改动局部、业务收益最直观、不引入异步复杂度。今天学习时建议按「先读本节推导 → 再看第二节落地状态总表 → 最后按 `docs/learn/redis/README.md` 的阅读路径进入 25 篇笔记」的顺序，理解「为什么否掉某个方案」往往比记住结论更有价值。
