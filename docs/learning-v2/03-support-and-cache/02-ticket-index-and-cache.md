# 第 13 章 工单池索引与统计缓存：客服看到的那一页从哪来

> 第 12 章讲的是工单状态在 MySQL 里怎么变。本章换一个视角：客服苏晴打开“待分配”工单池，仪表盘又显示四个数字——这些读操作为什么不直接查数据库？Redis 里的 ZSet、版本号、ready 标记和统计 JSON 各是什么？什么时候可以相信它们，什么时候必须整页退回数据库？
>
> 继续使用虚构人物：客服苏晴（SUPPORT，`userId=6003003`）、管理员老周（ADMIN，`userId=5004004`）；工单号、ID、时间、计数和响应均为示意。本章**没有启动服务、运行测试、连接 MySQL/Redis 或发送 HTTP 请求**；除特别标注“测试源码阅读”的地方，结论都是仓库静态推导，未实测。

## 学习目标

读完本章，你应该能：

1. 追踪 `POST /ticket/admin/page` 从 Controller 到 `TicketServiceImpl`、`TicketPoolIndex`、Lua 和 MySQL 的真实路径，说出哪些筛选条件能走索引、哪些必须整页退回数据库，以及分页总数从哪里来。
2. 按真实公式手算工单池 score，解释优先级、`updated_at`、主键 ID 的排序关系，说明 19 位补零成员、浮点精度、时间来源和适用边界，并能与 SQL 的三列排序对照。
3. 说清 `ticket:pool:version`、`ticket:pool:ready:{status}`、`ticket:pool:{status}` 和重建锁各自解决什么问题；区分“空池”“未就绪”“成员缺失”“版本变化”。
4. 追踪冷池双检、重建锁、数据库快照、版本/锁校验、发布和回表，能用 T1/T2 时间线解释锁到期、失效竞争、Redis 异常/响应丢失和回表不一致，且不把 Lua 原子性当成自动回滚。
5. 追踪统计缓存的命中、坏值、miss、锁与双检、等待降级、TTL/抖动、首次删除和 500ms 延迟双删；区分有界 TTL 与强一致保证。
6. 阅读四个相关测试的门控、真实/替身组件、断言与清理，知道它们没有证明什么；只在一次性专用 MySQL/Redis 上设计实验，不把测试阅读写成运行通过。

## 前置知识与阅读时间

- 必读：[第 12 章 工单生命周期与权限](01-ticket-lifecycle.md)——`sys_ticket` 的 0～4 状态、`handler_id`、写路径和 `afterCommit` 失效调用点。
- 必读：[第 3 章 数据与事务](../00-foundations/03-data-and-transactions.md)——`@Transactional` 代理、条件更新、提交后回调、跨库没有共同事务。
- 必读：[第 4 章 Redis 与流式响应基础](../00-foundations/04-redis-flux-and-sse-basics.md)——ZSet、TTL、Lua 的 KEYS/ARGV、原子边界。
- 推荐：[第 9 章 位置与发现](../02-orders/03-location-and-discovery.md)——附近订单池也使用“版本 + ready + 重建锁 + 回表校验”的相似结构；本章不重复讲 GEO。
- 阅读时间：约 100～120 分钟。
- **路径约定**：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；SQL、配置和测试写完整仓库相对路径。
- **证据约定**：本章区分“仓库源码事实”“测试源码阅读”“实际运行”。本章没有实际运行。

---

## 零、先记住十个事实

1. **MySQL `sys_ticket` 是工单真值**。`ticket:pool:*` 只是按状态排好序的读侧索引，`ticket:statistics:{yyyyMMdd}` 只是仪表盘读侧缓存；两者都不是状态机，也不能补回未提交的数据库变更。
2. 工单池索引**只在“只按状态筛选”时尝试**：`status != null` 且 `type/userType/handlerId/keyword` 都为空。带其他筛选、没有状态、或在真实事务中调用，直接走数据库。
3. score 不是位运算拼接，而是 `priority * 1e13 + updated_at 的 epoch 毫秒`；ZSet 用 `ZREVRANGE` 按 score 降序，score 相同时成员按字典序降序。成员是 19 位补零的主键 ID，因此等分时数值大的 ID 在前。
4. 索引键固定为 `ticket:pool:version`（共享版本，无 TTL）、`ticket:pool:ready:{status}`（`版本:成员数`，2 小时）、`ticket:pool:{status}`（ZSet，2 小时）、`ticket:pool:rebuild:lock:{status}`（重建锁，10 秒）。
5. **空 ZSet 会被 Redis 删除**，所以“这个状态确实没有工单”和“索引还没建起来”不能靠 `EXISTS` 区分；ready 标记保存 `版本:成员数`，空池也有 ready。
6. 冷池读取的路径是：先读索引 → 拿每个状态的重建锁 → 双检 → 必要时初始化版本 → 查该状态全部工单 → 校验行 → 用 Lua 校验版本和锁令牌后整体替换 ready/ZSet → 再读一页；任一环节失败就回退数据库。
7. 版本检查只在**检查那一刻**有效。`slice` 读完、`isCurrent` 通过之后，数据库和 Redis 仍可能变化；响应不保证是“检查后到返回前”的最新快照。
8. 统计缓存是另一套机制：四个 `selectCount` 没有本地事务，缓存 TTL 为 20～30 秒，重建锁 5 秒；抢不到锁时等待 3 次 × 60ms，然后直接查库且**不写缓存**。
9. 工单写入提交后，`afterTicketMutation` 先登记整池失效，再登记统计缓存的“立即删除 + 500ms 延迟删除”。删除/调度失败只记日志，不回滚 MySQL；已登记但尚未执行的延迟删除任务仍可能稍后执行。旧值是否残留是条件结果：只有当本次删除与延迟删除确实未生效（响应丢失后服务端也没删）、此后没有新的工单写入再触发删除、没有并发重写/淘汰、且该 key 的 TTL 未被外部改动时，旧值才会留到它自己的 TTL。
10. 相关测试只有源码阅读，**没有运行**；`TicketPoolRedisIntegrationTest` 和 `TicketPoolDatabaseIntegrationTest` 需要一次性专用 Redis/MySQL，并且会删除 `ticket:pool:*`、创建/清空/删除测试 schema。没有任何测试调用 `getTicketStatistics`。

---

## 一、业务故事、入口与最少概念

### 1.1 故事：苏晴打开工单池和仪表盘

假设今天是 `2026-11-15`。`sys_ticket` 中有下面 7 条虚构工单（`id` 是 MySQL 自增主键，`ticket_id` 是对外编号；二者不是一回事）：

| id | ticket_id | ticket_status | priority | handler_id | created_at | updated_at |
|---:|---|---:|---:|---|---|---|
| 11 | `T20261115000011` | 0 待分配 | 2 紧急 | NULL | 2026-11-14 17:30:00 | 2026-11-14 18:00:00 |
| 12 | `T20261115000012` | 0 待分配 | 1 普通 | NULL | 2026-11-15 09:20:00 | 2026-11-15 09:30:01 |
| 10 | `T20261115000010` | 0 待分配 | 1 普通 | NULL | 2026-11-15 09:00:00 | 2026-11-15 09:30:00 |
| 9 | `T20261115000009` | 0 待分配 | 1 普通 | NULL | 2026-11-15 08:55:00 | 2026-11-15 09:30:00 |
| 8 | `T20261115000008` | 1 处理中 | 1 普通 | 6003003 | 2026-11-15 09:10:00 | 2026-11-15 09:40:00 |
| 7 | `T20261115000007` | 3 已完成 | 1 普通 | 6003003 | 2026-11-15 07:00:00 | 2026-11-15 10:00:00 |
| 6 | `T20261115000006` | 4 已关闭 | 1 普通 | 6003003 | 2026-11-14 16:00:00 | 2026-11-14 17:00:00 |

苏晴做两件事：

1. 打开“待分配”池，每页 2 条，直接跳到第 2 页。
2. 回到仪表盘，看待分配、处理中、今日创建、今日完成四个数字。

按第 3 节的排序，待分配池的完整顺序是 `[11, 12, 10, 9]`，所以第 2 页是 `[10, 9]`。仪表盘按源码的四个条件计算，应为：待分配 `4`（9/10/11/12），处理中 `1`（8），今日创建 `5`（7/8/9/10/12），今日完成 `1`（7；`status=3` 且 `updated_at` 落在今天）。这些数字和页面都是虚构示例，不是实测响应。

### 1.2 入口总表

HTTP 基路径是 `/ticket`，两个端点都要求 ADMIN/SUPPORT：

| 场景 | 方法与路径 | 权限 | 输入 | Service 入口 | 读侧缓存 |
|---|---|---|---|---|---|
| 工单池分页 | `POST /ticket/admin/page` | `@RequirePermission({"ADMIN","SUPPORT"})` | `@RequestBody TicketQueryReqDTO` | `TicketService#getAdminTicketPage` | `ticket:pool:*`，必要时回 DB |
| 仪表盘统计 | `GET /ticket/admin/statistics` | `@RequirePermission({"ADMIN","SUPPORT"})` | 无参数 | `TicketService#getTicketStatistics` | `ticket:statistics:{yyyyMMdd}`，必要时重建 |

工单池和统计**没有 Agent 工具入口**。`TicketTool` 当前暴露的是 C 端创建、查询进度、未完结列表、升级和追加消息；B 端池查询/统计只在 HTTP Controller 中。不要因为 `TicketService` 方法可被工具调用，就假设模型能直接拿到管理员池。

写路径的缓存调用点在第 12 章已经讲过：`submitTicket`、`cancelTicket`、`confirmAndRate`、`assignTicket`、`reassignTicket`、`processTicket`、`sendMessage`、`escalateTicket`、`escalateTicketByAdmin`、`appendUserMessage` 在成功后调用 `afterTicketMutation`（`sendMessage` 当前没有已接入的 HTTP 入口，见 S1）。本章从这些失效动作之后继续追踪。

### 1.3 最少概念

- **ZSet（有序集合）**：每个成员（member）有一个浮点分数（score）；`ZREVRANGE` 按 score 从大到小取成员。score 相同的时候，Redis 再按 member 的字典序反向排列。工单池的 member 是补零后的主键 ID，score 是排序编码。
- **TTL（过期时间）**：键到时间自动删除。`ticket:pool:ready:{status}` 和 `ticket:pool:{status}` 在成功发布时都设为 2 小时；`ticket:pool:version` 不设 TTL。读取不会续期。
- **空 ZSet 自动删除**：ZSet 的最后一个成员被移除后，Redis 会删除这个键。于是“没有任何待分配工单”和“缓存还没建”在 `EXISTS ticket:pool:0` 上都可能是 false，必须另有 ready 标记。
- **版本令牌（version）**：一个随机字符串。工单写入后换新版本；重建发布时用 Lua 校验“我读到的版本还是当前版本”，防止把旧数据库快照发布到新索引上。
- **ready 标记**：值形如 `版本:成员数`。读取时同时校验版本和 `ZCARD`，既能发现版本变化，也能发现成员被删/池不完整。
- **重建锁**：`RedisLock` 以随机 UUID 作为持有者令牌，`SET NX PX` 获取，固定 TTL，释放时用 Lua 比对令牌。它不是可重入锁，也没有看门狗续期；本章只讲它在缓存重建中的用法，锁类型的完整对比在 S4。
- **缓存命中/未命中/坏值**：统计缓存命中就是 JSON 能反序列化；空白或坏 JSON 视为未命中（坏 JSON 还会尝试删除）；Redis 读异常不是“未命中”，而是直接抛出。第 4 节会把这个区别讲清。
- **提交后回调**：`afterTicketMutation` 判断的是 `TransactionSynchronizationManager.isSynchronizationActive()`；`TicketPoolIndex#slice` 判断的是 `isActualTransactionActive()`。两者不是同一个条件，第 3 章已解释。

---

## 二、表、键与排序编码

### 2.1 参与索引的字段

`src/main/resources/sql/init.sql#sys_ticket` 中与本章直接相关的列：

| 列 | 类型 | 与本章的关系 |
|---|---|---|
| `id` | `bigint AUTO_INCREMENT PRIMARY KEY` | ZSet 成员；回表用 `selectByIds`；也是 score 相同后的第二排序键 |
| `priority` | `tinyint NOT NULL DEFAULT 1` | score 的高位权重；1 普通、2 紧急、3 特急 |
| `ticket_status` | `tinyint NOT NULL DEFAULT 0` | 状态池编号；ZSet 按状态分 key |
| `updated_at` | `datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` | score 的低位时间；排序的第二维度 |
| `handler_id` | `bigint NULL` | 索引不记录；带处理人筛选时整页回 DB |

表上的索引是 `PRIMARY(id)`、唯一 `uk_ticket_id`、`idx_user(user_id,user_type)`、`idx_order_id(order_id)`、`idx_handler_status(handler_id,ticket_status)`；**没有单独的 `ticket_status` 索引**。`selectList(status=?)` 和 `selectCount(status=?)` 能否有效使用索引取决于优化器；本章没有运行 `EXPLAIN`，不把 `idx_handler_status` 的第二列当成“状态查询一定走索引”。

`TicketVO.id` 是字符串（`ticket.getId().toString()`），所以 HTTP 响应里的 ID 形如 `"10"`；`PageResult` 的 `page/size` 是 `Integer`，`total` 是 `Long`。索引内部仍用 `Long`。

### 2.2 精确键名、类型与 TTL

| 键 | 类型 | 值/成员 | TTL | 谁写 | 谁读 |
|---|---|---|---|---|---|
| `ticket:pool:version` | String | 随机 UUID | **无 TTL** | `syncByTicketId` 失效；冷池重建 `setIfAbsent` 初始化 | `PUBLISH`/`READ` Lua、`isCurrent` |
| `ticket:pool:ready:{status}` | String | `{version}:{count}`，如 `v1:4`；空池是 `v1:0` | 成功发布时 2 小时 | `PUBLISH_TICKET_POOL` | `READ_TICKET_POOL` |
| `ticket:pool:{status}` | ZSet | member=`%019d` 的主键 ID；score 见 2.3 | 成功发布时 2 小时 | `PUBLISH_TICKET_POOL` | `READ_TICKET_POOL` |
| `ticket:pool:rebuild:lock:{status}` | String | 随机 UUID 令牌 | 10 秒 | `RedisLock#tryLock` | `PUBLISH` 校验；`RedisLock#unlock` 比对删除 |
| `ticket:statistics:{yyyyMMdd}` | String | `TicketDataVO` 的 JSON | 20～30 秒 | `cacheTicketStatistics` | `getCachedTicketStatistics` |
| `ticket:statistics:lock:{yyyyMMdd}` | String | 随机 UUID 令牌 | 5 秒 | `RedisLock#tryLock` | 统计缓存重建临界区 |

`status` 只使用 `TicketStatus` 的 0～4。非法状态不会创建新池：`slice` 里的 `TicketStatus.fromCode(status)` 会抛 `IllegalArgumentException`，但它在 `try` 内，最终被捕获并降级为数据库查询。空池发布时只写 `ready=版本:0`，不会创建 ZSet；`PEXPIRE` 对不存在的 key 不产生新键。

### 2.3 score 的真实公式与手算

源码 `service/base/TicketPoolIndex.java#score`：

```java
// service/base/TicketPoolIndex.java#score（真实节选）
private double score(Ticket ticket) {
    // 数据库时间不带时区；用固定 UTC 映射，避免实例时区和夏令时改变排序。
    long millis = ticket.getUpdatedAt() == null ? 0L
            : ticket.getUpdatedAt().toInstant(ZoneOffset.UTC).toEpochMilli();
    return (ticket.getPriority() == null ? 0 : ticket.getPriority()) * PRIORITY_WEIGHT + millis;
}
```

`PRIORITY_WEIGHT` 是 `1e13`。成员在发布前这样填充：

```java
// service/base/TicketPoolIndex.java#slice（真实节选）
args.add(Double.toString(score(ticket)));
// 等分时 Redis 按成员字典序排序；补零使其与数据库主键数值顺序一致。
args.add(String.format(Locale.ROOT, "%019d", ticket.getId()));
```

把 1.1 的四条待分配工单按这个公式手算。`updated_at` 是数据库墙钟时间，源码把它当作 UTC 来取毫秒，所以：

- `2026-11-15 09:30:00` → `1,794,735,000,000` 毫秒
- `2026-11-15 09:30:01` → `1,794,735,001,000` 毫秒
- `2026-11-14 18:00:00` → `1,794,679,200,000` 毫秒

| id | priority | updated_at | 时间毫秒 | score | ZSet 成员 |
|---:|---:|---|---:|---:|---|
| 11 | 2 | 2026-11-14 18:00:00 | 1,794,679,200,000 | `2×1e13 + 1,794,679,200,000` = **21,794,679,200,000** | `0000000000000000011` |
| 12 | 1 | 2026-11-15 09:30:01 | 1,794,735,001,000 | `1×1e13 + 1,794,735,001,000` = **11,794,735,001,000** | `0000000000000000012` |
| 10 | 1 | 2026-11-15 09:30:00 | 1,794,735,000,000 | `1×1e13 + 1,794,735,000,000` = **11,794,735,000,000** | `0000000000000000010` |
| 9 | 1 | 2026-11-15 09:30:00 | 1,794,735,000,000 | 同上 = **11,794,735,000,000** | `0000000000000000009` |

`ZREVRANGE` 的结果：

1. id 11：priority 2 的 `1e13` 权重压过所有 priority 1 的时间差，虽然它更新时间更早，仍排第一。
2. id 12：同为 priority 1，`09:30:01` 比 `09:30:00` 晚 1 秒，排第二。
3. id 10 和 9：score 完全相同。成员 `...010` 与 `...009` 都是 19 位，字典序降序时 `...010` 更大，所以 10 在 9 前面。

最终 `[11, 12, 10, 9]`。这与数据库回退路径的 `ORDER BY priority DESC, updated_at DESC, id DESC` 在正常数据下一致。

这里有三层排序，不是两列：

- **priority** 决定高位。只要 `updated_at` 的时间毫秒小于 `1e13`（约到 2286 年），相邻 priority 的差距就大于同一 priority 内任何时间差。
- **updated_at** 决定同一 priority 内的先后。
- **id** 只在 priority 和 `updated_at` 都相同时决定顺序；补零把数值比较变成等长字符串比较。若没有补零，字符串 `"10"` 会小于 `"9"`，与数据库 `id DESC` 相反。

**浮点精度与边界**：Java `double` 有 53 位尾数，能精确表示所有不超过 `2^53 = 9,007,199,254,740,992` 的整数。当前合法数据里最大 score 约为 `3×1e13 + 1.8e12 ≈ 3.18e13`，远小于 `2^53`，所以这些整数 score 在 Java 和 Redis 的双精度里是精确的；`Double.toString` 即使输出 `1.1794735001E13` 这样的科学计数法，Redis 也能解析回同一个 double。但不要把它推广到所有极端值：

- `updated_at` 超过约 2286 年（毫秒达到 `1e13`）时，priority 1 的晚时间可能追上 priority 2 的早时间，编码假设失效。
- `priority` 不在 1～3（例如手工写入 0、负数或很大的数）时，权重关系不再符合业务含义。
- `updated_at` 为 NULL 或 `priority` 为 NULL 时，源码分别按 0 处理；DDL 是 `NOT NULL`，所以这是防御性分支。
- `id <= 0` 会在发布前被拒绝；`id` 超过 19 位在 `BIGINT` 下不可能，但若换成其他类型，补零假设就不成立。
- 当前 DDL 是 `DATETIME`（秒精度）。若改成 `DATETIME(3)`，SQL 能区分同一秒内的先后，但精度与 score 同为毫秒：同一毫秒内的两条记录，索引和 SQL 都会继续用 `id` 决出先后（DB 回退查询本身就是 `priority DESC, updated_at DESC, id DESC`），两条路径仍然一致。只有把列提升到亚毫秒精度（如 `DATETIME(6)`）且确实写入亚毫秒值时，SQL 才可能排出 score 表达不了的次序。
- 源码把 `updated_at` 固定按 UTC 映射，不是按 Asia/Shanghai 换算。对所有行都加同一个常数偏移，**相对顺序不变**，但这个 score 不是“真实 epoch 毫秒”，不要拿它和 `System.currentTimeMillis()` 比较。当前 `TicketServiceImpl` 的工单写入多用 `BusinessTime.now()`（Asia/Shanghai）显式写 `updated_at`；DDL 也保留了 `ON UPDATE CURRENT_TIMESTAMP`。如果将来有外部 SQL 或不同时区的写入，数据库墙钟时间是否一致属于部署问题，源码不能保证。

---

## 三、工单池查询：索引快路径与整页回退

### 3.1 HTTP 示意

```http
POST /ticket/admin/page
Authorization: Bearer <TOKEN>
Content-Type: application/json

{
  "status": 0,
  "current": 2,
  "size": 2
}
```

示意响应（未实测；`Result.ok(page)` 的结构来自 `domain/response/Result.java` 与 `PageResult`）：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "page": 2,
    "size": 2,
    "total": 4,
    "records": [
      {
        "id": "10",
        "ticketId": "T20261115000010",
        "priority": 1,
        "ticketStatus": 0,
        "updatedAt": "2026-11-15T09:30:00"
      },
      {
        "id": "9",
        "ticketId": "T20261115000009",
        "priority": 1,
        "ticketStatus": 0,
        "updatedAt": "2026-11-15T09:30:00"
      }
    ]
  },
  "total": 0
}
```

`TicketQueryReqDTO` 只有 Lombok `@Data`，字段有 `userId/userType/status/type/handlerId/keyword/current/size`，默认 `current=1,size=10`。按 Lombok 规则，没有 final 字段的 `@Data` 会生成无参构造器，Jackson 通常可以绑定；本章没有运行验证，若绑定失败仍由 `GlobalExceptionHandler` 映射 400。

### 3.2 哪些条件走索引

`TicketServiceImpl#getAdminTicketPage` 先校验分页，再判断 `canUseTicketPoolIndex`：

| 请求特征 | 走哪条路 | 原因 |
|---|---|---|
| `status` 非空，`type/userType/handlerId/keyword` 都没有 | 先尝试索引 | ZSet 正好按状态分池，且排序维度够用 |
| `status = null` | 直接 DB | 没有指定状态池；索引没有“全部状态”的 ZSet |
| 带 `type` | 直接 DB | 索引不记录工单类型 |
| 带 `userType` | 直接 DB | 索引不记录发起人类型 |
| `handlerId` 有文本 | 直接 DB | 索引不记录处理人；还要 `Long.parseLong` |
| `keyword` 有文本 | 直接 DB | 索引不记录标题/编号，无法做 LIKE |
| 只传 `userId`（管理端） | 直接 DB 或索引都可能；该字段在管理端被忽略 | `pageAdminTicketsFromDb` 没有按 `userId` 过滤，`canUseTicketPoolIndex` 也不看它 |
| `status` 是非法数字（如 99） | 先尝试索引，`TicketStatus.fromCode` 失败后被捕获，再 DB | 非法状态不会创建 ZSet；DB 查询通常返回空 |
| 调用时 `isActualTransactionActive()` 为真 | 直接 DB | 事务中的旧快照/未提交修改不能发布成公共索引 |

**不能先按索引取第 N 页再过滤**。索引的 offset 是在“该状态的全部工单”上滑动的；如果先取 10 条再按类型过滤，可能只剩 2 条，页码越靠后越像数据丢了。所以一旦有额外筛选，整页交给 DB，而不是“索引 + 内存过滤”。

### 3.3 索引路径

```java
// service/impl/TicketServiceImpl.java#pageByTicketPoolIndex（真实节选）
int size = (int) req.getSize();
long offset = (req.getCurrent() - 1) * req.getSize();
Optional<TicketPoolIndex.PoolSlice> slice = ticketPoolIndex.slice(req.getStatus(), offset, size);
if (slice.isEmpty()) {
    return null;
}

TicketPoolIndex.PoolSlice poolSlice = slice.get();
if (poolSlice.ids().isEmpty()) {
    if (!ticketPoolIndex.isCurrent(poolSlice)) {
        return null;
    }
    return PageResult.<TicketVO>builder()
            .page((int) req.getCurrent())
            .size(size)
            .total(poolSlice.total())
            .records(List.of())
            .build();
}

// 按索引给出的顺序取回实体。selectByIds 不保证返回顺序，
// 必须按 ids 重新排列，否则分页顺序会随数据库返回顺序漂移。
Map<Long, Ticket> byId = ticketMapper.selectByIds(poolSlice.ids()).stream()
        .collect(Collectors.toMap(Ticket::getId, ticket -> ticket, (a, b) -> a));
if (byId.size() != poolSlice.ids().size()
        || byId.values().stream().anyMatch(ticket -> !req.getStatus().equals(ticket.getTicketStatus()))
        || !ticketPoolIndex.isCurrent(poolSlice)) {
    // 删除或跨状态变更不能靠过滤成员处理，否则总数错误且页面会缺行。
    return null;
}
```

逐步解释：

1. `offset = (current - 1) * size`；分页校验保证 `current/size` 在 1～`Integer.MAX_VALUE`，乘积在 `long` 范围内。
2. `slice` 返回一个 `PoolSlice(version, total, ids)`。`total` 来自 Lua 里的 `ZCARD`，不是 `SELECT COUNT(*)`；`ids` 是当前页成员按 ZSet 顺序解析出的主键。
3. 如果 `ids` 为空，有两种情况：池确实是空的（`total=0`），或 offset 超出总数（`total>0` 但当前页没有成员）。两种情况都可以返回空 `records`，但**必须再确认版本没变**；版本变了就返回 `null`，让调用方回 DB。
4. 如果 `ids` 非空，用 `selectByIds` 回表取实体。`selectByIds` 的返回顺序不保证，所以后面按 `ids` 的顺序重新映射。
5. 三种不一致会让整页回退：
   - 回表行数少于索引成员数（行被删了）；
   - 某行的 `ticket_status` 不再等于请求状态（跨状态变更）；
   - `isCurrent` 发现版本已变化。
6. 只有全部通过，才用 `poolSlice.ids()` 的顺序把实体映射成 `TicketVO`，`total` 仍用索引里的总数。

**索引提供的是顺序和总数，不是行内容**。标题、优先级、处理人、时间等字段仍然来自 MySQL 的 `selectByIds`；所以“走索引”不等于“不查数据库”，只是免掉了 `selectCount` 和全表排序。

### 3.4 整页回退的数据库路径

```java
// service/impl/TicketServiceImpl.java#pageAdminTicketsFromDb（真实节选）
LambdaQueryWrapper<Ticket> queryWrapper = new LambdaQueryWrapper<>();
if (req.getStatus() != null) queryWrapper.eq(Ticket::getTicketStatus, req.getStatus());
if (req.getType() != null) queryWrapper.eq(Ticket::getTicketType, req.getType());
if (StringUtils.hasText(req.getHandlerId())) {
    queryWrapper.eq(Ticket::getHandlerId, Long.parseLong(req.getHandlerId()));
}
if (req.getUserType() != null) queryWrapper.eq(Ticket::getUserType, req.getUserType());
if (StringUtils.hasText(req.getKeyword())) {
    queryWrapper.and(w -> w.like(Ticket::getTitle, req.getKeyword())
            .or()
            .like(Ticket::getTicketId, req.getKeyword()));
}

Long total = ticketMapper.selectCount(queryWrapper);
// 显式限制数据库分页；本项目没有配置 MyBatis-Plus 分页拦截器。
long offset = (req.getCurrent() - 1) * req.getSize();
queryWrapper.orderByDesc(Ticket::getPriority)
        .orderByDesc(Ticket::getUpdatedAt)
        .orderByDesc(Ticket::getId)
        .last("LIMIT " + offset + "," + req.getSize());
List<TicketVO> voList = ticketMapper.selectList(queryWrapper).stream()
        .map(this::toVO)
        .collect(Collectors.toList());
```

要点：

- 本项目**没有配置 MyBatis-Plus 分页拦截器**，所以这里显式 `selectCount` + `last("LIMIT ...")`，而不是依赖 `selectPage` 自动分页。`offset` 用 `long` 拼接，测试覆盖了超过 `Integer.MAX_VALUE` 的 offset。
- `handlerId` 是非空字符串时直接 `Long.parseLong`；非数字会抛 `NumberFormatException`（`IllegalArgumentException` 子类），进入 HTTP handler 后是 400。
- `keyword` 是 `title LIKE ? OR ticket_id LIKE ?`；用户输入里的 `%` / `_` 没有被转义，这是 SQL 通配符语义，不是本章重点。
- `selectCount` 和 `selectList` 是两条独立语句，方法没有 `@Transactional`（`getAdminTicketPage` 本身也没有）。如果两条语句之间发生插入/删除，`total` 和当前页可能来自不同时刻；这是 DB 回退路径本身的窗口。
- 没有 `status` 时不会加状态条件；这就是“全部状态”查询，不能走单状态 ZSet。

### 3.5 用四条工单走一遍

**第一次请求（冷池）**：`status=0,current=2,size=2`。

| 步骤 | 发生什么 | 数据结构 |
|---|---|---|
| 1 | Controller 权限通过，Service 校验分页 | 无 |
| 2 | `canUseTicketPoolIndex` 为真，`slice(0,2,2)` | 无 |
| 3 | `READ_TICKET_POOL` 发现没有匹配的 ready | 返回 `{}` |
| 4 | 尝试 `ticket:pool:rebuild:lock:0`，假设抢到 | 锁令牌 T0，10 秒 |
| 5 | 双检仍无匹配 ready；`setIfAbsent` 初始化版本 V1 | `ticket:pool:version=V1` |
| 6 | `selectList(ticket_status=0)` 读到 9/10/11/12（顺序不保证） | DB 快照 |
| 7 | 校验每行 id>0、状态仍是 0；构造 score/member | 4 对 ARGV |
| 8 | `PUBLISH_TICKET_POOL` 校验版本 V1 和锁令牌 T0 | 通过 |
| 9 | Lua 删除旧 ready/ZSet，ZADD 4 个成员，写 ready `V1:4`，设置 2 小时 TTL | ZSet 顺序 `[11,12,10,9]` |
| 10 | 再 `READ_TICKET_POOL` 读 offset 2..3 | 返回 `V1,4,[10,9]` |
| 11 | `selectByIds([10,9])` 回表，按顺序映射 | DB 两次（1 次全状态 + 1 次按 ID） |
| 12 | 再 `isCurrent` 确认版本仍是 V1 | 返回第 2 页 |

**第二次请求（热池）**：同一查询。`READ` 直接返回 `V1,4,[10,9]`；`selectByIds` 回表；`isCurrent` 通过；不会 `selectCount`，也不会全表 `selectList`。

**其他分支**：

| 请求/状态 | 结果 |
|---|---|
| `status=0,current=1,size=2` | `[11,12]`，total 4 |
| `status=0,current=3,size=2` | offset 4，`ids` 空；版本仍 V1，返回空 `records`、total 4，不查 DB |
| 池已预热且该状态确实没有工单 | ready `V1:0`，ZSet 键不存在；返回 total 0、空页，不查 DB |
| 冷池且另一个请求持有重建锁 | `slice` 返回 empty；本次整页走 DB；不等待锁 |
| 索引页中的行被删除 | `selectByIds` 行数不足；整页回 DB，total 用 DB 的 `selectCount` |
| 索引成员的状态已改变 | 回表状态不等于请求状态；整页回 DB |
| 读取/回表期间版本变化 | `isCurrent` 为 false；整页回 DB |
| Redis 读异常 | `slice` 捕获 `RuntimeException`，本次整页回 DB |
| DB 回表异常（`selectByIds`） | 不在 `TicketPoolIndex` 的 catch 内，向调用方抛异常，通常 500；不是“自动回 DB” |
| 索引成员非法（如 `"invalid"`） | `readSlice` 的 `Long.valueOf` 抛异常，被 `slice` 的外层 catch 捕获 → empty → 本次 DB；坏成员不会被自动修复，只能等 ready/ZSet 过期、下一次成功失效或外部删除——这些前提本身不保证发生 |

### 3.6 检查之后仍可能变化

`isCurrent` 只比较版本字符串，而且只在两个时刻调用：空结果分支、`selectByIds` 回表后的校验分支——也就是说，**非空页取回行之后仍会再跑一次**。失效若发生在这次检查之前，版本不符会让整页回退；只有检查通过之后到响应写出之前的失效，才逃过这次检查（见 4.5 四种情形）。它**不保证**：

- `isCurrent` 返回 true 后、HTTP 响应写出前，数据库不会再变；
- 行内容（标题、处理人、`updated_at`）不会变；索引只校验状态和版本，不校验字段；
- 写入一定经过 `afterTicketMutation`。如果外部 SQL 或未来代码直接改 `sys_ticket` 却没有推进 `ticket:pool:version`，索引可以继续“版本有效”，但排序/总数已经旧了，直到 2 小时 TTL 或下一次成功失效；
- `selectCount` 和 `selectList` 之间没有变化（DB 回退路径尤其明显）。

所以正确说法是：“在检查点，索引与版本/状态一致”；不是“响应一定是某个时刻的强一致快照”。

---

## 四、冷池重建：双检、锁、版本与发布

### 4.1 `slice` 的真实步骤

```java
// service/base/TicketPoolIndex.java#slice（真实节选，含省略注释）
public Optional<PoolSlice> slice(int status, long offset, int size) {
    if (size <= 0 || offset < 0 || offset > Long.MAX_VALUE - size
            || TransactionSynchronizationManager.isActualTransactionActive()) {
        return Optional.empty();
    }
    try {
        TicketStatus.fromCode(status);
        Optional<PoolSlice> existing = readSlice(status, offset, size);
        if (existing.isPresent()) {
            return existing;
        }
        String lockKey = RedisKeyConstants.ticketPoolRebuildLockKey(status);
        String token = redisLock.tryLock(lockKey, REBUILD_LOCK_TTL);
        if (token == null) {
            return Optional.empty();
        }
        try {
            existing = readSlice(status, offset, size);
            if (existing.isPresent()) {
                return existing;
            }
            redisTemplate.opsForValue().setIfAbsent(RedisKeyConstants.TICKET_POOL_VERSION_KEY,
                    UUID.randomUUID().toString());
            String version = redisTemplate.opsForValue().get(RedisKeyConstants.TICKET_POOL_VERSION_KEY);
            if (version == null) {
                return Optional.empty();
            }
            List<Ticket> tickets = ticketMapper.selectList(new LambdaQueryWrapper<Ticket>()
                    .eq(Ticket::getTicketStatus, status));
            List<String> args = new ArrayList<>();
            args.add(version);
            args.add(token);
            args.add(String.valueOf(INDEX_TTL.toMillis()));
            for (Ticket ticket : tickets) {
                if (ticket.getId() == null || ticket.getId() <= 0
                        || !Integer.valueOf(status).equals(ticket.getTicketStatus())) {
                    return Optional.empty();
                }
                args.add(Double.toString(score(ticket)));
                args.add(String.format(Locale.ROOT, "%019d", ticket.getId()));
            }
            Long published = redisTemplate.execute(RedisScripts.PUBLISH_TICKET_POOL,
                    List.of(RedisKeyConstants.TICKET_POOL_VERSION_KEY, lockKey,
                            RedisKeyConstants.ticketPoolReadyKey(status), RedisKeyConstants.ticketPoolKey(status)),
                    args.toArray());
            return Long.valueOf(1L).equals(published) ? readSlice(status, offset, size) : Optional.empty();
        } finally {
            redisLock.unlock(lockKey, token);
        }
    } catch (RuntimeException e) {
        log.warn("读取工单池索引失败，降级为数据库查询: status={}", status, e);
        return Optional.empty();
    }
}
```

按顺序理解：

1. **参数与事务检查**：`size<=0`、`offset<0`、`offset+size` 溢出，或实际事务活跃，直接返回 empty；调用方回 DB。
2. **第一次读**：`readSlice` 用 `READ_TICKET_POOL` 同时校验版本、ready 中的 `版本:成员数` 和 `ZCARD`。匹配就返回，不拿锁。
3. **拿锁**：`ticket:pool:rebuild:lock:{status}`，令牌随机 UUID，TTL 10 秒。拿不到立即返回 empty，不等待；这就是“冷池并发时其他请求回 DB”的来源。
4. **双检**：拿到锁后再读一次。另一个重建者可能已经发布完成；这次命中就直接返回，并在 `finally` 释放自己的锁（令牌匹配才会删）。
5. **初始化版本**：`setIfAbsent` 只在版本键不存在时写入新 UUID；已存在则保留。然后 `get` 当前版本；null（例如键刚被删/Redis 异常）返回 empty。
6. **数据库快照**：`selectList(ticket_status=status)` 读该状态全部行，没有排序、没有分页。每行必须 `id>0` 且状态等于请求状态；否则**整次重建放弃**，不发布部分索引。
7. **构造 ARGV**：`[version, token, ttlMillis, score1, member1, score2, member2, ...]`。
8. **发布**：`PUBLISH_TICKET_POOL` 先校验当前版本等于 `ARGV[1]`、锁键值等于 `ARGV[2]`；任一不符返回 0。只有校验通过才 `DEL` ready/ZSet、`ZADD` 全部成员、`ZCARD` 计数、写 `ready=version:count` 并设置 `PX`、对 ZSet 设置 `PEXPIRE`。
9. **发布后读取**：只有 Lua 返回 1 才再 `readSlice`；返回 0 或空则本次回 DB。
10. **释放锁**：`finally` 中 `RedisLock#unlock` 用 `RELEASE_LOCK_IF_MATCH` 比对令牌，不匹配不会删；释放失败只记日志，锁由 10 秒 TTL 兜底。

### 4.2 失效：整池换版本

```java
// service/base/TicketPoolIndex.java#syncByTicketId（真实节选）
List<String> keys = new ArrayList<>();
keys.add(RedisKeyConstants.TICKET_POOL_VERSION_KEY);
for (TicketStatus status : TicketStatus.values()) {
    keys.add(RedisKeyConstants.ticketPoolReadyKey(status.getCode()));
    keys.add(RedisKeyConstants.ticketPoolKey(status.getCode()));
}
redisTemplate.execute(RedisScripts.INVALIDATE_TICKET_POOLS, keys, UUID.randomUUID().toString());
```

```lua
-- util/RedisScripts.java#INVALIDATE_TICKET_POOLS（真实节选）
redis.call('SET', KEYS[1], ARGV[1])
for i = 2, #KEYS do
    redis.call('DEL', KEYS[i])
end
return 1
```

- `KEYS[1]` 是共享版本键；`KEYS[2..]` 是 5 个状态的 ready 和 ZSet。
- 一次失效换新版本并删除全部 5 个状态池，而不是只处理当前工单的旧/新状态。源码注释说明这是为了避免跨状态变更和并发回表同步乱序；代价是下一次每个状态首次读取都要各自重建。
- **失效不删除重建锁**。旧重建者可能还持有锁；它的发布因版本变化失败，随后释放锁。新的重建者在这段时间可能拿不到锁，只能回 DB。
- 版本键 `SET` 没有 TTL；ready/ZSet 也没有在这里显式设置 TTL，它们只在下一次成功发布时获得 2 小时 TTL。若失效后迟迟没有读取，key 可能不存在；这是正常的冷池，不是数据丢失。
- 异常边界：`syncByTicketId` 的 `try/catch (RuntimeException)` 只包住 `redisTemplate.execute`，捕获后记日志、**不向调用方抛出**；因此调用方不会因这次 Redis 失效失败而看到异常。`Error` 及其他非 `RuntimeException` 不在这个 catch 内，会向上传播（与 S1 的口径一致）。没有重试。
- 失效失败（含捕获到的 `RuntimeException`）之后的状态是**条件结果**：`INVALIDATE` 可能已经换版本并删池（响应丢失），也可能完全没执行。若确实未执行且旧池的 2 小时 TTL 尚未到、也没有被淘汰/并发重建覆盖，旧热池可能继续服务；下一次读取是否重建、重建是否成功，取决于锁、DB 与 Redis 当时的状态，不是无条件兜底。

### 4.3 四个 Lua 的 KEYS/ARGV/返回

**发布**：

```lua
-- util/RedisScripts.java#PUBLISH_TICKET_POOL（真实节选）
if redis.call('GET', KEYS[1]) ~= ARGV[1] or redis.call('GET', KEYS[2]) ~= ARGV[2] then
    return 0
end
redis.call('DEL', KEYS[3], KEYS[4])
for i = 4, #ARGV, 2 do
    redis.call('ZADD', KEYS[4], ARGV[i], ARGV[i + 1])
end
local count = redis.call('ZCARD', KEYS[4])
redis.call('SET', KEYS[3], ARGV[1] .. ':' .. count, 'PX', ARGV[3])
redis.call('PEXPIRE', KEYS[4], ARGV[3])
return 1
```

| 位置 | 含义 |
|---|---|
| `KEYS[1]` | `ticket:pool:version` |
| `KEYS[2]` | `ticket:pool:rebuild:lock:{status}` |
| `KEYS[3]` | `ticket:pool:ready:{status}` |
| `KEYS[4]` | `ticket:pool:{status}` |
| `ARGV[1]` | 重建开始时读到的版本 |
| `ARGV[2]` | 本次重建锁令牌 |
| `ARGV[3]` | TTL 毫秒（2 小时 = 7,200,000） |
| `ARGV[4..]` | score/member 交替 |
| 返回 | 版本或锁不符 `0`；成功 `1` |

**读取**：

```lua
-- util/RedisScripts.java#READ_TICKET_POOL（真实节选）
local version = redis.call('GET', KEYS[1])
local count = redis.call('ZCARD', KEYS[3])
if not version or redis.call('GET', KEYS[2]) ~= version .. ':' .. count then
    return {}
end
local result = {version, tostring(count)}
local members = redis.call('ZREVRANGE', KEYS[3], ARGV[1], ARGV[2])
for _, member in ipairs(members) do
    result[#result + 1] = member
end
return result
```

| 位置 | 含义 |
|---|---|
| `KEYS[1]` | `ticket:pool:version` |
| `KEYS[2]` | `ticket:pool:ready:{status}` |
| `KEYS[3]` | `ticket:pool:{status}` |
| `ARGV[1]` | 起始下标（从 0 开始） |
| `ARGV[2]` | 结束下标（含） |
| 返回 | 版本缺失或 ready 不等于 `版本:ZCARD` → `{}`；否则 `{version, count 字符串, member...}` |

**释放锁**：

```lua
-- util/RedisScripts.java#RELEASE_LOCK_IF_MATCH（真实节选）
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
end
return 0
```

ready 和非空 ZSet 在同一个脚本里先后设置，TTL 接近但不保证完全相同；如果 ready 先过期，`GET` 为 nil；如果非空 ZSet 先过期，`ZCARD` 变成 0 而 ready 仍写着旧成员数。只要 ready 与 `version:ZCARD` 不再匹配，`READ` 就返回 `{}`；正常空池则没有 ZSet，匹配的 `version:0` ready 仍可被读取。成员数量一致也不证明成员内容正确。**`READ` 返回 `{}` ≠ “本次回退 DB”**：它只是 `slice` 内某一次 `readSlice` 未命中——第一次读未命中会继续尝试拿重建锁，锁内双检未命中会继续重建，只有 `slice` 最终返回 empty 时，`getAdminTicketPage` 才整页回 DB。**但“下一次读取一定重建成功”没有保证**：锁可能被别人持有、DB 可能报错、坏成员可能让 `readSlice` 抛异常、Redis 可能仍不可用。版本检查共有三处：`READ` 的 ready 匹配、`PUBLISH` 的版本+锁令牌校验、`pageByTicketPoolIndex` 的 `isCurrent`；`isCurrent` 是上层返回索引页前的最后一道，不是唯一一道。脚本中途出错是否留下一个仍被 `READ` 当完整池的状态，取决于失败位置，见 4.6。

**四个概念不要混**：

| 概念 | 存储 | 解决的问题 | 不能解决 |
|---|---|---|---|
| 版本 | `ticket:pool:version` | 失效后让旧数据库快照的发布失败 | DB 变更不经过失效；响应前的后续变更 |
| ready | `ticket:pool:ready:{status}` | 区分“空池”和“未就绪”，并校验成员数 | 行内容变化；版本之后的并发写 |
| ZSet 成员 | `ticket:pool:{status}` | 提供顺序和分页窗口 | 行是否仍存在/状态是否仍匹配（要回表） |
| 空 ZSet | key 不存在，`ZCARD=0` | 表示该状态当前有 0 条成员 | 单独无法区分“空池”与“索引丢失”，必须看 ready |

### 4.4 正常冷池时间线（T1/T2 并发）

前提：池未预热；T1 先拿到锁；T2 同时到达；MySQL/Redis 正常。

| 时刻 | T1 | T2 | MySQL/Redis 可见状态 |
|---|---|---|---|
| a0 | `READ` 无匹配 ready | `READ` 无匹配 ready | 无 ready/ZSet |
| a1 | 抢到锁 T0 | 抢锁失败，返回 empty → 整页 DB | 锁 T0，10 秒 |
| a2 | 双检仍空；`setIfAbsent(V1)`；读版本 V1 | 正在查 DB | version=V1 |
| a3 | `selectList(status=0)` 读 4 行 | DB 查询返回 | DB 4 行 |
| a4 | 构造 score/member，`PUBLISH` 校验 V1/T0 | 返回 DB 结果（本次不用索引） | ready/ZSet 未变 |
| a5 | Lua 替换 ready/ZSet，写 `V1:4`，2 小时 | — | 热池 |
| a6 | 再 `READ` 取第 2 页，回表，返回 | — | 下一页请求可命中 |

注意：T2 即使晚于 T1 发布，它这一次仍然返回 DB 结果；索引只影响后续请求。这不是“T2 读到旧索引”，而是“T2 本次没走索引”。

### 4.5 失效/重建竞争时间线

前提：T1 正在冷池重建；T2 是另一个请求，提交了一笔工单变更（或直接调用 `syncByTicketId`）。工单变更的 `afterCommit` 在提交后执行。

**先定位检查点，再谈失效顺序**。沿 `slice → readSlice → pageByTicketPoolIndex → isCurrent` 追踪：

- `slice` 内部依次是：第一次 `readSlice` → 拿锁 → 双检 `readSlice` → `setIfAbsent` 版本 → DB 快照 → `PUBLISH` → **发布后再 `readSlice`** → 返回 `PoolSlice`。
- `slice` 返回之后，`pageByTicketPoolIndex` 才继续：空结果分支调用一次 `isCurrent`；非空结果先 `selectByIds` 回表，再调用一次 `isCurrent`（见 3.3 源码节选）。

所以“`readSlice` 返回”和“`isCurrent` 检查”不是同一时刻，两者之间还隔着“返回 `PoolSlice` → 回表”。`isCurrent` 通过后到 HTTP 响应写出之间，才是这次检查发现不了的窗口。按失效落在哪个阶段，有四种可达情形。

**情形一：失效在 `PUBLISH` 之前**（失效与 DB 快照并发）

| 时刻 | T1（重建） | T2（提交后失效） | Redis/DB | 结果 |
|---|---|---|---|---|
| b0 | `slice` 读空；拿锁 T0；双检；读版本 V1 | — | version=V1，无 ready | T1 开始 DB 快照 |
| b1 | `selectList(status=0)` 读到旧 4 行 | DB 提交新工单；afterCommit 执行 `INVALIDATE` | `version=V2`，删除所有 ready/ZSet | T1 手里的快照已旧 |
| b2 | 构造 ARGV V1，`PUBLISH` | — | 当前 version=V2，锁仍是 T0 | 版本校验失败，返回 0 |
| b3 | `published != 1`，返回 empty → 本次 DB | — | 无 ready/ZSet | T1 不发布旧快照 |
| b4 | `finally` 释放 T0 | — | 锁删除/过期 | 本次回退 DB；下一次读取若拿到锁且 DB/Redis 正常，才**可能**用 V2 重建，不保证成功 |

**情形二：失效在 `PUBLISH` 成功后、`slice` 内再 `readSlice` 前**

| 时刻 | T1 | T2 | 结果 |
|---|---|---|---|
| c0 | `PUBLISH` 成功，ready/ZSet 为 V1 快照 | — | 热池 V1 |
| c1 | 准备 `readSlice` | `INVALIDATE` 写 V2 并删除所有池 | 池被删 |
| c2 | 再 `readSlice` 发现 version=V2 或 ready 缺失 | — | 返回 empty → 本次回退 DB |
| c3 | — | — | 索引页已失效；下一次读取在锁/DB/Redis 均可用时才可能重建 V2，不保证 |

**情形三：失效在 `slice` 内再 `readSlice` 返回后、`isCurrent` 检查前**

这时 `PoolSlice(V1, total, ids)` 已在 T1 手里，`pageByTicketPoolIndex` 正要回表。

| 分支 | 回表与检查 | 结果 |
|---|---|---|
| `ids` 为空（空页） | 失效把 version 改成 V2；空页分支的 `isCurrent` 比较 V1 与当前 V2 → false | 返回 null → 整页回 DB |
| `ids` 非空 | `selectByIds` 回表（行内容取决于回表时刻的 DB 状态），随后 `isCurrent` 见 V2 → false | 返回 null → 整页回 DB |

两种分支都回退，不会把 V1 的旧页当成功结果返回。**前提**是 `isCurrent` 这次确实执行并读到 V2；若它的 Redis `get` 抛异常，同样返回 false（见 3.3），也回退。

**情形四：失效在 `isCurrent` 检查通过后、HTTP 响应写出前**

`isCurrent` 比较的是检查那一刻的 version；若它返回 true 时版本仍是 V1，之后 T2 才写 V2 并删池，这次检查不会再跑。`pageByTicketPoolIndex` 用已经取到的 V1 `ids` 和回表行构造响应，客户端**可能**拿到 V1 时点的旧页——这正是“不保证检查后响应前无并发变化”的具体含义。它是可能而非必然：是否发生取决于失效与响应写出的真实先后，仓库没有运行验证。

四种情形里，情形一、二、三都会回退数据库，情形四可能返回旧结果；没有一种能保证“下一轮一定重建成功”——锁可能被别人持有、DB 可能继续报错、Redis 可能仍不可用。

### 4.6 Lua 原子隔离不等于出错回滚

Redis 执行 Lua 时不会被其他客户端命令插入，脚本内命令顺序执行；这提供的是**隔离/串行化**，不是数据库式事务回滚。若某条 `redis.call` 抛错（例如键类型被外部改成错误类型、内存不足、参数异常），**已经执行的命令不会自动撤销**。

按 `PUBLISH_TICKET_POOL` 的实际命令顺序看，每一步失败留下的状态、以及 `READ` 能否把它当完整池，结果不同：

| 失败位置（脚本执行到） | Redis 已发生的变化 | `READ` 校验 `ready == version:ZCARD` | 本次与后续 |
|---|---|---|---|
| 版本/锁校验（`return 0`） | 未改任何键 | 取决于旧池：若旧 ready 仍匹配则可读到旧池，否则 `{}` | Java 侧 `published != 1` → 本次回 DB |
| `DEL` ready/ZSet 之后、首个 `ZADD` 之前 | ready 已删，ZSet 已删 | ready 缺失 → `{}` | 本次回 DB；下次读取重建 |
| 部分 `ZADD` 之后（未到 `SET ready`） | ZSet 部分成员，ready 仍缺失 | ready 缺失 → `{}` | 本次回 DB；下次重建不会把残池当完整池 |
| `ZCARD` 之后、`SET ready` 之前 | ZSet 已完整，ready 仍缺失 | ready 缺失 → `{}` | 同上 |
| `SET ready ... PX` 之后、`PEXPIRE pool` 之前或之中出错 | ready=`version:count` 已写且带 PX TTL，ZSet 已完整，但 pool 可能没有 TTL | **版本与数量匹配 → `READ` 可能返回该池**（此时数据完整，`PEXPIRE` 只是没跑或报错） | Java 侧可能收到异常/非 1 → 本次仍回 DB；但池已可用，下次读取会命中它 |
| `PEXPIRE pool` 成功后 | ready 与 ZSet 都有 2 小时 TTL，返回 1 | 匹配 → 返回完整池 | 正常热池 |

关键纠正：**不能说“脚本中途错误后的所有状态都不会被 `READ` 看成完整池”**。脚本先写 `SET ready=version:count ... PX`，再 `PEXPIRE pool`；如果在 ready 写成功之后才出错，ready 的版本和数量正好匹配 `ZCARD`，`READ` 会把它当作完整池返回。区别在于 pool 的 TTL：若 `PEXPIRE` 没执行或报错，ZSet 可能没有 2 小时 TTL，而 ready 有；ready 先到期后 `READ` 才返回 `{}`。这与“ready 缺失所以被检出”的前几种失败是不同分支，不要混为一谈。

**空池单独看**：若该状态没有工单，`for` 循环的 `ZADD` 一条也不执行，`ZCARD=0`，`SET ready=version:0` 会写 ready，`PEXPIRE pool` 对一个不存在的 ZSet 键不产生新键。这是正常发布路径，不是错误：`READ` 见 `ZCARD=0`、ready=`version:0` 匹配，返回空页（见 1.3、4.3），不查 DB。空池“没有 ZSet”与脚本失败“ZSet 被删/部分写”都可能让 `ZCARD=0`，但前者有匹配的 `ready=V:0`，后者没有 ready——这正是 ready 要区分的状态。

“下一次重建一定成功”没有保证：DB 可能继续报错、锁可能被别人持有、坏成员可能仍让 `readSlice` 抛异常、Redis 可能仍不可用。缓存也不会“永远正确”：绕过失效的写入、失效失败、响应前变化都不受版本保护。

响应丢失时结果未知：`PUBLISH`/`INVALIDATE` 的脚本可能已执行完（池已换/版本已变），也可能完全没执行，客户端只看到异常或超时。不能凭客户端异常断言 Redis 没变，也不能断言它一定变了；下一次读取的实际结果取决于 Redis 当时的真实状态，仓库没有实测。

### 4.7 提交前注册与提交后执行

`afterTicketMutation` 在业务方法内调用，但真正的动作取决于事务同步：

```java
// service/impl/TicketServiceImpl.java#afterCommit（真实节选）
if (TransactionSynchronizationManager.isSynchronizationActive()) {
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            action.run();
        }
    });
} else {
    action.run();
}
```

- 有同步时：只是**注册**，不执行；事务提交后由 Spring 调用 `afterCommit`。事务回滚时不会执行，索引/统计保持写入前状态。
- 没有同步时（例如测试直接 `new` 服务、或调用方没有事务）：动作**立即执行**；如果后续代码失败，Redis 动作不会跟着回滚。
- `afterTicketMutation` 先注册索引失效，再调用 `evictTicketStatistics` 注册统计删除；两个回调按注册顺序执行。若第一个回调抛出 `syncByTicketId` 捕获范围之外的异常（例如 `Error`），后面的统计回调可能不执行；这是 Spring 同步列表的静态推导，未实测。
- 回调只保证“在提交后执行”，不保证“早于另一个事务的读/写”，也不保证 Redis 一定成功。

---

## 五、统计缓存：命中、重建、降级与双删

### 5.1 入口、键与四个统计项

`GET /ticket/admin/statistics` 没有参数，Controller 直接调用 `getTicketStatistics()`。返回 `Result.data` 是 `TicketDataVO`：

| 字段 | 条件 |
|---|---|
| `pendingAssignCount` | `ticket_status = 0` 的全部工单 |
| `processingCount` | `ticket_status = 1` 且 `handler_id IS NOT NULL` |
| `todayCreatedCount` | `created_at >= 今天 00:00` 且 `< 明天 00:00` |
| `todayCompletedCount` | `ticket_status = 3` 且 `updated_at >= 今天 00:00` 且 `< 明天 00:00` |

```java
// service/impl/TicketServiceImpl.java#queryTicketStatisticsFromDb（真实节选）
LocalDateTime todayStart = bizDate.atStartOfDay();
LocalDateTime todayEnd = todayStart.plusDays(1);

LambdaQueryWrapper<Ticket> pendingWrapper = new LambdaQueryWrapper<>();
pendingWrapper.eq(Ticket::getTicketStatus, TicketStatus.PENDING_ASSIGN.getCode());
Long pendingAssignCount = ticketMapper.selectCount(pendingWrapper);

LambdaQueryWrapper<Ticket> processingWrapper = new LambdaQueryWrapper<>();
processingWrapper.eq(Ticket::getTicketStatus, TicketStatus.PROCESSING.getCode())
        .isNotNull(Ticket::getHandlerId);
Long processingCount = ticketMapper.selectCount(processingWrapper);

LambdaQueryWrapper<Ticket> createdWrapper = new LambdaQueryWrapper<>();
createdWrapper.ge(Ticket::getCreatedAt, todayStart)
        .lt(Ticket::getCreatedAt, todayEnd);
Long todayCreatedCount = ticketMapper.selectCount(createdWrapper);

LambdaQueryWrapper<Ticket> completedWrapper = new LambdaQueryWrapper<>();
completedWrapper.eq(Ticket::getTicketStatus, TicketStatus.COMPLETED.getCode())
        .ge(Ticket::getUpdatedAt, todayStart)
        .lt(Ticket::getUpdatedAt, todayEnd);
Long todayCompletedCount = ticketMapper.selectCount(completedWrapper);
```

四个 `selectCount` 是**四条独立语句**，`getTicketStatistics` 没有 `@Transactional`。它们不是同一个数据库快照：在第一条和最后一条之间发生工单变更，四个数字可能互相矛盾（例如待分配减 1、处理中还没加 1）。缓存只是把这组“某一小段时间内分别读到的数字”保存 20～30 秒，不会修正这个窗口。

“今日完成”用的是 `status=3` 加 `updated_at` 在今天的范围，**没有独立的完成时间列**。因此：

- 今天完成、今天又被别的操作更新，仍算今天完成（只要状态还是 3）；
- 昨天完成、今天被更新，会被算进今天的“完成”，因为 `updated_at` 今天；
- 今天完成、明天再被更新（状态仍是 3），明天会被算进明天的“今日完成”，因为它看的是明天的 `updated_at`；同一个工单可能在不同日期的“今日完成”里各出现一次。历史统计没有持久化，今天的缓存过期后无法回看。

`LocalDate.now()` 用的是 JVM 默认时区；`localDateTime` 直接来自数据库列。`BusinessTime` 固定 Asia/Shanghai，但统计方法没有用它。若应用 JVM 时区、MySQL 会话时区与业务时区不一致，“今天”的边界可能偏移；仓库没有在配置里强制 JVM 时区，这是部署未知项。

统计请求的 `bizDate` 和 `cacheKey` 在方法入口确定；失效回调里的 key 在回调执行时用 `LocalDate.now()` 重新计算。跨午夜时两者可能指向不同日期：新请求用新一天的 key，旧日期的 key 只能等自身 TTL；历史统计没有持久化，不会补算。

### 5.2 命中、坏值、未命中

```java
// service/impl/TicketServiceImpl.java#getCachedTicketStatistics（真实节选）
String json = stringRedisTemplate.opsForValue().get(cacheKey);
if (!StringUtils.hasText(json)) {
    return null;
}
try {
    return objectMapper.readValue(json, TicketDataVO.class);
} catch (Exception e) {
    log.warn("工单统计缓存反序列化失败，删除脏缓存: key={}", cacheKey, e);
    stringRedisTemplate.delete(cacheKey);
    return null;
}
```

| 情况 | 行为 |
|---|---|
| key 不存在 / 值为空白 | 返回 null，视为 miss |
| JSON 能反序列化为 `TicketDataVO` | 命中，直接返回；不校验数字是否合理、是否过期 |
| JSON 损坏/类型不匹配 | 记日志，尝试 `delete`，返回 null → 走重建 |
| 值是 `"null"` | Jackson 返回 null，等同于 miss |
| JSON 对象字段为 null | 命中；`TicketDataVO` 的字段可以是 null（正常写入时 `safeCount` 会转 0） |
| Redis `get` 抛异常 | **不在 try 内，不捕获**；向调用方抛出，HTTP 通常 500 |
| 坏值删除时 Redis 再抛异常 | `catch` 块里的 `delete` 没有第二层 catch，异常继续向上抛 |

统计缓存没有空值哨兵：四个 0 是合法的 `TicketDataVO`，会作为正常 JSON 命中。它也没有版本号，命中时无法判断值是否对应最新 DB。

### 5.3 重建：锁、双检、等待与直接查库

```java
// service/impl/TicketServiceImpl.java#getTicketStatistics（真实节选）
TicketDataVO cached = getCachedTicketStatistics(cacheKey);
if (cached != null) {
    return cached;
}

String lockToken = tryLockTicketStatistics(lockKey);
if (lockToken != null) {
    try {
        TicketDataVO doubleCheck = getCachedTicketStatistics(cacheKey);
        if (doubleCheck != null) {
            return doubleCheck;
        }
        TicketDataVO fresh = queryTicketStatisticsFromDb(bizDate);
        cacheTicketStatistics(cacheKey, fresh);
        return fresh;
    } finally {
        releaseTicketStatisticsLock(lockKey, lockToken);
    }
}

for (int i = 0; i < TICKET_STATS_LOCK_RETRY_TIMES; i++) {
    sleepQuietly(TICKET_STATS_LOCK_WAIT_MILLIS);
    TicketDataVO waited = getCachedTicketStatistics(cacheKey);
    if (waited != null) {
        return waited;
    }
}

log.warn("工单统计缓存重建等待超时，降级直查DB: key={}", cacheKey);
return queryTicketStatisticsFromDb(bizDate);
```

关键常数（都在 `TicketServiceImpl` 内，未做成配置项）：

| 常数 | 值 | 用途 |
|---|---:|---|
| `TICKET_STATS_CACHE_BASE_SECONDS` | 20 | 缓存基础 TTL |
| `TICKET_STATS_CACHE_JITTER_SECONDS` | 10 | 随机增加 0～10 秒 |
| `TICKET_STATS_LOCK_SECONDS` | 5 | 重建锁 TTL |
| `TICKET_STATS_LOCK_WAIT_MILLIS` | 60 | 每次等待毫秒 |
| `TICKET_STATS_LOCK_RETRY_TIMES` | 3 | 最多等待 3 次 |
| `TICKET_STATS_DELAYED_EVICT` | 500ms | 第二次删除延迟 |

流程：

1. 先读缓存；命中直接返回，不拿锁。
2. miss 后 `tryLock(lockKey, 5s)`。拿到锁再双检；如果别人已经写回，直接返回。
3. 仍 miss 则查 4 个 `selectCount`，写缓存，TTL `20 + nextInt(11)` 秒（20～30 秒，含 30），`finally` 释放锁。
4. 拿不到锁：最多 3 次 `sleep(60ms)` + 读缓存；总等待约 180ms 加读操作耗时。期间任何一次命中就返回。
5. 3 次都没命中：记 warn，**直接查 DB 并返回，不写缓存**。所以降级请求不会帮助热池恢复；下一个请求还要重新竞争锁。
6. `tryLock` 本身若抛 Redis 异常，不会进入等待/直查分支；异常直接上抛，`getTicketStatistics` 没有兜底。

写缓存：

```java
// service/impl/TicketServiceImpl.java#cacheTicketStatistics（真实节选）
long ttl = TICKET_STATS_CACHE_BASE_SECONDS
        + ThreadLocalRandom.current().nextInt(TICKET_STATS_CACHE_JITTER_SECONDS + 1);
try {
    String json = objectMapper.writeValueAsString(data);
    stringRedisTemplate.opsForValue().set(cacheKey, json, ttl, TimeUnit.SECONDS);
} catch (Exception e) {
    log.warn("工单统计写缓存失败: key={}", cacheKey, e);
}
```

写失败只记日志，方法仍把 `fresh` 返回给本次请求。这里没有重试，也没有版本保护。下一次请求是否 miss 是**条件结果**：`opsForValue().set` 抛异常时，Redis 可能根本没写入（下次 miss），也可能响应在服务端已写入后才丢失（下次命中刚写的值）；此外还取决于该 key 是否被并发写回、被双删、被淘汰或 TTL 到期。不能无条件断言“下一次请求继续 miss”。

**抖动的作用**：这个缓存一天只有一个 key，不存在“大量 key 同时过期”的批量雪崩；抖动主要让不同实例/不同重建的写回时间错开，减少同时到期的尖峰。别把它说成“把成千上万个 key 的过期时间打散”。

### 5.4 锁到期与两个重建者

先把源码动作数清楚：`getTicketStatistics` **一次调用只执行一次 `tryLockTicketStatistics`**。要么拿到锁进入临界区（双检 → 查 DB → 写缓存一次 → `finally` 解锁），要么拿不到锁就进入 `for` 循环最多 3 次 `sleep(60ms)` + 读缓存，3 次都 miss 就直查 DB 且**不写缓存**后返回。这次调用中不存在“等待后再抢一次锁”。

真正的“两个重建者”来自**两次独立调用**，前提是：统计锁 TTL 5 秒、无续期；调用 A 的 4 个 `selectCount` 超过 5 秒导致锁过期；调用 B 在 A 之后才发起并拿到新锁。也就是说，5 秒后重建的那个**必须是新调用**，不是 A 自己重抢。

| 时刻 | A（第一次调用，先拿锁，旧快照） | B（新调用，后拿锁，新快照） | 前提/结果 |
|---|---|---|---|
| d0 | 读 miss；`tryLock` 拿锁 T0；双检 miss；开始查 DB | — | 锁 T0（5 秒） |
| d1 | DB 查询超过 5 秒 | — | T0 到期（无续期） |
| d2 | 仍在查 DB | 读 miss；`tryLock` 拿锁 T1；双检 miss；查 DB | 两个独立调用各持一次锁 |
| d3 | `cacheTicketStatistics` 写回旧四项（本调用唯一一次写） | `cacheTicketStatistics` 写回新四项（本调用唯一一次写） | 哪个后写，由写回先后决定 |
| d4 | `finally` 用 T0 解锁 | `finally` 用 T1 解锁 | 令牌不匹配，互不删对方的锁 |

统计缓存没有版本号，两个写回顺序互斥，结果取决于实际先后：

| 写回顺序 | 最终缓存 | 成立条件 |
|---|---|---|
| A 旧写 → B 新写（B 后写） | 新值 | B 的 DB 查询/写回在 A 之后完成 |
| B 新写 → A 旧写（A 后写） | 旧值 | A 的慢查询最后才写回 |

第二种情况下旧值覆盖新值。旧值能留多久**不是无条件“最长 20～30 秒”**：只有在 A 这次写入确实成功写进 Redis、之后没有待执行或新登记的删除、没有并发读者再次写回、该 key 没有被淘汰且 TTL 未被外部改动时，才可以说它在该次 `SET` 起的 20～30 秒后过期。若 A 的 `SET` 抛异常未落地、或 key 被淘汰，旧值可能更早消失或根本没出现。此前工单写入登记的 500ms 延迟任务若在 A 的旧值写回**之后**成功执行，可删除旧值；若在写回之前执行，则无法删除尚未写回的旧值。之后仍可能有其他已登记任务或新写入触发删除，也可能被覆盖、淘汰或修改 TTL。这是“锁到期 + 无版本 + 各调用只写一次”的直接后果，不承诺某一轮收敛。

### 5.5 首次删除与 500ms 延迟双删

写路径：

```java
// service/impl/TicketServiceImpl.java#afterTicketMutation（真实节选）
if (StringUtils.hasText(ticketId)) {
    afterCommit(() -> ticketPoolIndex.syncByTicketId(ticketId));
}
evictTicketStatistics();
```

```java
// service/impl/TicketServiceImpl.java#evictTicketStatistics（真实节选）
afterCommit(() -> {
    String cacheKey = RedisKeyConstants.ticketStatisticsKey(LocalDate.now());
    try {
        stringRedisTemplate.delete(cacheKey);
    } catch (RuntimeException e) {
        log.warn("工单统计缓存失效失败: key={}", cacheKey, e);
    }
    delayedCacheEvictor.evictAfter(cacheKey, TICKET_STATS_DELAYED_EVICT);
    log.debug("工单统计缓存已失效(双删): key={}", cacheKey);
});
```

调用条件：

- 只有走 `afterTicketMutation` 的工单写入才触发；`submitTicket` 和带 `@Transactional` 的状态动作在成功后调用。直接改数据库、外部脚本、未来不调用该方法的写路径不会失效缓存。
- 第一次删除在提交后（有同步时）或立即（无同步时）执行；无论第一次是否成功，都会尝试登记 500ms 延迟删除。
- 第一次删除只捕获 `RuntimeException`；`Error` 不会被捕获，回调可能在登记延迟删除前中断。延迟删除任务内部捕获 `RuntimeException`；`RejectedExecutionException`（执行器已关闭）只记日志。
- `DelayedCacheEvictor` 是单个 daemon 线程；进程退出/崩溃时，未执行的延迟任务丢失。`@PreDestroy` 会 `shutdown` 并最多等 5 秒，之后 `shutdownNow`；不能把“已登记”当成“一定执行”。

**旧值回填窗口**（T1 读、T2 写）：

| 时刻 | 读者 T1 | 写者 T2 | 缓存 |
|---|---|---|---|
| e0 | 读缓存 miss | — | 空 |
| e1 | 查 DB，读到旧四项 | — | 空 |
| e2 | — | 提交工单；afterCommit 第一次删除并登记 500ms 延迟删除 | 空 |
| e3 | 把旧四项写回，TTL 20～30 秒 | — | 旧值 |
| e4 | 另一个读者命中旧值 | — | 旧值 |
| e5 | — | 延迟删除执行（若 T1 写回早于 500ms） | 空 |
| e6 | 下一次读取 miss；重建还需锁可用、四次 DB 读取与写回成功，且无更晚的旧写入 | — | 满足前提才有新值；不保证 |

如果 T1 的 DB 查询/写回超过 500ms：

| 时刻 | 事件 | 缓存 |
|---|---|---|
| f0 | T2 提交并删除 | 空 |
| f1 | 500ms 延迟删除执行，此时 T1 还没写回 | 空 |
| f2 | T1 在 700ms 写回旧值（前提：`SET` 确实成功） | 旧值，TTL 从 f2 起 20～30 秒 |
| f3 | 若 T2 已登记的延迟删除都已执行（本例 f1 执行过一次）、此后没有新的写路径删除、也没有并发重写/淘汰且 TTL 未被外部改动 | 旧值留到 TTL；这些是前提条件，不是保证 |

所以“双删”不是“旧值一定在 500ms 内消失”。它只是多了一次延迟删除，覆盖“读线程在第一次删除前已经查到旧快照、稍后回填”的常见窗口；回填晚于延迟删除、删除失败、执行器关闭、进程退出都会留下残余。

**单次成功写入的 TTL 不等于系统整体收敛上界**。TTL 20～30 秒只描述“某一次 `SET` 成功写入的 key，在没有其他干预时多久过期”。写回响应丢失时服务端可能已写入也可能没写；旧值可能被后续成功删除、被并发读者写回的新值覆盖、被 Redis 淘汰，TTL 也可能被外部改动。因此不能把“旧值一直到 TTL”“到点即正确”当成无条件结论；“强一致”“到期前一定正确”更不成立。反复慢写回没有全局有界的收敛保证——每一轮都可能重新写入一个旧值并重新计时。

还有两个方向要区分：

- 延迟删除可能删掉另一个读者在提交后写回的**新值**，造成一次额外的 miss 和 DB 查询；这不会产生旧值，只是降低命中率。
- 如果第一次删除失败、延迟删除也失败，旧值可能留到它自己的 TTL；如果旧值是在很早以前写的，TTL 可能已经接近到期，实际残余时间取决于写入时刻，不由本次删除决定。

### 5.6 工单池与统计缓存的降级差异

| 故障 | 工单池 `slice` | 统计缓存 |
|---|---|---|
| Redis 读异常 | `slice` 捕获 `RuntimeException`，本次整页回 DB | `getCachedTicketStatistics` 的 `get` 不捕获，异常上抛，通常 500 |
| 拿不到重建锁 | 立即返回 empty，本次回 DB | 等 3×60ms，仍 miss 则直接查 DB（不写缓存） |
| 缓存值损坏 | `readSlice` 解析失败/ready 不匹配，返回 empty；非法成员不会自动修复，只能等 ready/ZSet 过期、下一次成功失效或外部删除——这些前提不保证发生 | 尝试删除坏 JSON 后重建；删除本身失败则异常上抛，且删除是否已在服务端生效未知 |
| DB 查询失败 | 重建的 DB 查询在 `slice` 的 catch 内，先降级；调用方再查 DB，可能再次失败并 500 | 重建查询不在 catch 内，直接异常上抛 |
| 写缓存失败 | `PUBLISH` 返回 0/异常，本次回 DB；下次能否再试取决于锁/DB/Redis | `cacheTicketStatistics` 捕获异常，仍返回 fresh；下次是否重建取决于下一次读的结果 |
| 失效失败 | 响应丢失时可能已换版本/删池，也可能没执行；旧池是否残留取决于是否真没删、TTL 是否在、有无并发重写；无重试 | 首次/延迟删除失败只记日志；旧值是否残留取决于是否真没删、TTL、有无并发重写；无重试 |

“下次再试/无重试”都要按条件理解：重试是否成功、旧值是否真的残留，取决于响应是否丢失、缓存是否被并发写回或淘汰、TTL 是否被改动，不由本次失败唯一决定。这张表说明：两个“缓存”虽然都叫读侧缓存，失败策略并不一样。不要用“工单池能降级”推断“统计也能降级”。

---

## 六、可达并发/失败时间线

### 6.1 时间线 A：冷池 + 重建锁到期

前提：池冷；T1 先拿锁后 DB 查询很慢；T2 在 10 秒后到达；MySQL/Redis 正常。

| 时刻 | T1 | T2 | Redis/DB |
|---|---|---|---|
| a0 | `READ` 无 ready，拿锁 T0（10 秒） | — | 锁 T0 |
| a1 | 双检，`setIfAbsent(V1)`，`selectList` 开始 | — | version=V1 |
| a2 | 查询超过 10 秒 | — | 锁 T0 过期 |
| a3 | 仍在查询 | 到达，`READ` 无 ready，抢到锁 T1 | 锁 T1 |
| a4 | 构造 V1/T0，`PUBLISH` | 双检，`setIfAbsent` 保留 V1，`selectList` | 当前锁=T1 |
| a5 | 锁校验失败，返回 0 → 回 DB | 构造 V1/T1，`PUBLISH` 成功 | ready/ZSet V1 快照 |
| a6 | `finally` 用 T0 解锁，不匹配，不删 T1 的锁 | 再 `READ`，回表，返回索引页 | 热池 |

结论：锁到期不会让旧持有者覆盖新索引，因为发布同时校验锁令牌和版本；旧持有者本次只能回 DB。T2 的发布仍可能因版本变化失败（见 B）。

### 6.2 时间线 B：失效与重建竞争

前提：T1 重建 status=0；T2 提交一笔状态变更并触发整池失效。

| 时刻 | T1 | T2 | Redis/DB |
|---|---|---|---|
| b0 | 读空，拿锁 T0，双检，version=V1 | — | V1，无池 |
| b1 | `selectList(status=0)` 读旧快照 | 提交；afterCommit `INVALIDATE` | version=V2，删除所有 ready/ZSet |
| b2 | 构造 V1/T0，`PUBLISH` | — | 版本不符 → 0 |
| b3 | 返回 empty → 本次 DB | — | 无池 |
| b4 | 释放 T0 | — | 本次回退；V2 的重建仅在后续读取拿到锁且 DB/Redis 正常时才可能，不保证 |

失效发生在 T1 发布之后时，结果取决于它落在哪个检查点之前，不能一概写成“再读取必然发现”：

| 失效位置 | T1 这次的结果 |
|---|---|
| `PUBLISH` 之前（上表 b1～b2） | 版本校验失败，返回 0，本次回 DB |
| 发布成功后、`slice` 内再 `readSlice` 之前 | 再读取发现版本/ready 不符，本次回 DB |
| 再 `readSlice` 返回后、`isCurrent` 检查前 | `isCurrent` 见 V2 → false，整页回 DB |
| `isCurrent` 检查通过后、HTTP 响应前 | 检查不会再跑，本次可能返回 V1 旧页 |

逐情形见 4.5。它们都不保证“下一次读取用 V2 重建成功”。`INVALIDATE` 只捕获 `RuntimeException`；失败或响应丢失时脚本可能已换版本并删池，也可能没执行，旧池可能继续存在到自身 2 小时 TTL 或下一次成功失效——这些是条件结果，不是无条件兜底。

### 6.3 时间线 C：统计缓存旧值回填

前提：T1 读统计 miss；T2 提交工单；T1 的 DB 查询慢于 500ms。

| 时刻 | T1 | T2 | 缓存 |
|---|---|---|---|
| c0 | 读 miss，DB 查到旧四项 | — | 空 |
| c1 | — | 提交；afterCommit 删除（空），登记 500ms | 空 |
| c2 | — | 500ms 延迟删除执行 | 空 |
| c3 | 700ms 写回旧四项 | — | 旧值，TTL 20～30 秒 |
| c4 | 其他读者命中旧值 | — | 旧值 |
| c5 | TTL 到期（前提：到期前没有被删除/覆盖/淘汰、TTL 未被外部改动） | — | 下一次读取 miss；重建需锁可用、四次 DB 读取与写回成功，不保证 |

如果 T1 在 300ms 写回且 c2 确实执行成功，延迟删除会清掉它，之后的 miss 才可能重建新值；如果 c2 删除失败或响应丢失（可能已删也可能没删），旧值是否残留取决于真实删除结果、并发重写与该 key 的 TTL。两条路径都说明“双删”降低窗口，不消灭窗口，也不保证某一轮收敛。

### 6.4 时间线 D：统计锁到期 + 两个重建者

前提：调用 A（第一次）拿锁后 DB 超过 5 秒；调用 B 是锁过期后**新发起的另一请求**，此时才拿到新锁。B 读到提交后的新数据，A 读到提交前的旧数据。一次调用只 `tryLock` 一次、最多写缓存一次，所以不会在同一调用里出现“先写旧值又写新值”。

| 时刻 | A（第一次调用） | B（新调用） | 缓存 |
|---|---|---|---|
| d0 | 读 miss；拿锁 T0；双检 miss；查旧数据 | — | 空 |
| d1 | 慢查询，超过 5 秒 | — | T0 到期 |
| d2 | 仍在查 DB | 读 miss；拿锁 T1；双检 miss；查新数据 | 锁 T1 |
| d3 | — | 写回新四项（本调用唯一一次写） | 新值 |
| d4 | 写回旧四项（本调用唯一一次写） | — | 旧值覆盖新值 |

两个写回顺序互斥 d3/d4 可互换：

| 写回顺序 | 最终缓存 |
|---|---|
| A 旧写先 → B 新写后 | 新值 |
| B 新写先 → A 旧写后 | 若两次写入均成功，最后一次写入后为旧值；之后何时消失取决于删除、重写、淘汰和 TTL |

没有版本号，谁最后写谁生效；`RedisLock` 只能防止“同时持有锁”，不能防止“锁过期后的旧写者”。旧值留到 TTL 需满足“A 写入成功 + 无后续删除/重写 + 未被淘汰 + TTL 未改”，不是无条件保证（见 5.4）。

### 6.5 失败矩阵

| 事件 | 可见状态 | 本次结果 | 下一次/恢复 |
|---|---|---|---|
| `PUBLISH` 版本不符 | ready/ZSet 未改（脚本先校验） | `slice` empty → DB | 下一次读取若拿到锁、DB/Redis 正常且发布校验通过，才可能用当前版本重建；不保证 |
| `PUBLISH` 锁令牌不符 | 未改 | empty → DB | 当前持有者在锁/DB/Redis 正常时才可能发布成功；旧持有者不误删新锁 |
| `PUBLISH` 脚本中途错误 | 取决于失败位置：`SET ready` 前多为 ready 缺失/ZSet 部分；`SET ready` 后可能 ready 与完整 ZSet 都在、pool 无 TTL | 异常被 `slice` 捕获 → DB | ready 缺失时 `READ` 返回空，若后续取得锁并通过检查才可能重建；ready 已写且匹配时 `READ` 可能返回该池；都不保证下次重建成功（见 4.6） |
| `INVALIDATE` 抛 `RuntimeException`/响应丢失 | 结果未知：脚本可能已换版本并删池，也可能未执行 | 只记日志，不向调用方抛异常（`syncByTicketId` 捕获 `RuntimeException`）；只有 `Error` 等超出 catch 的异常才会向上传播 | 不能凭结果断言 Redis 未变；若已换版本，下一次读取仍需锁/DB/Redis 正常才可能重建；旧池若确实未删，可能残留到自身 TTL（前提：TTL 仍在且未被改动、未被淘汰） |
| `READ` 遇到坏成员 | `Long.valueOf` 抛异常 | `slice` empty → DB | 坏成员不会被自动修复；只能等 ready/ZSet 过期、下一次成功失效或外部删除——这些前提不保证发生 |
| `selectByIds` 行缺失/状态不符 | 索引页与 DB 不一致 | 整页回 DB | total 来自 DB 的 count；并发下 count 与列表可能不同时点 |
| `isCurrent` 版本变化 | 索引页已旧 | 整页回 DB | 下一次读取若拿到锁、DB/Redis 正常且发布校验通过，才可能重建；不保证 |
| `selectByIds`/DB 回退 SQL 异常 | 无索引结果 | 异常上抛，通常 500 | 取决于 DB 恢复 |
| 统计缓存 `get` 异常 | 无缓存值 | 异常上抛，通常 500 | 不会自动查 DB |
| 统计坏 JSON 删除失败 | 结果未知：删除可能已执行也可能没执行（响应丢失） | 异常上抛 | 下一次读取取决于 Redis 真实状态：可能命中坏值、miss 重建，也可能已被并发写覆盖；不能断言坏值仍在 |
| 统计第一次删除失败 | 删除响应丢失时可能已删也可能没删 | 延迟删除仍登记 | 延迟执行成功则可能清；是否残留取决于真实删除结果、并发重写与 TTL |
| 统计延迟删除失败/进程退出 | 旧值可能还在 | 日志/任务丢失 | 无全局兜底；残留取决于该 key 的 TTL、有无后续删除/重写/淘汰 |
| 写路径未调用 `afterTicketMutation` | 版本/统计未变 | 本次写成功 | 索引/统计可能旧到自身 TTL、被淘汰或下一次成功失效；不保证下一轮重建 |

---

## 七、设计取舍与边界

### 7.1 为什么整池重建，而不是单条更新

`TicketPoolIndex` 的类注释和 `syncByTicketId` 注释给出的效果是：工单写入频率较低，选择重建完整状态池，换取分页总数和成员集合的一致性；不把单条写入当成完整索引。这里能直接从源码推导的是：

- 单条更新要同时维护旧状态和新状态两个 ZSet、总数、顺序和 ready，跨状态变更容易漏；
- 整池失效把复杂度交给下一次读取，读侧只需要“版本 + ready + 锁 + 发布校验”；
- 代价是每次重建 `selectList(status=?)` 全量读该状态，工单量大时内存/耗时/ARGV 都增加，10 秒锁可能不够，发布失败就继续回 DB。

这是当前代码的取舍，不代表唯一正确方案；源码没有记录作者否决过哪些增量方案，本章不编造设计史。

### 7.2 版本、ready、锁不是互相替代

- **版本**解决“旧快照不要覆盖新索引”；它不解决“根本没有重建”“重建失败”“DB 写入绕过失效”。
- **ready**解决“空池和未就绪无法区分”以及“成员数不完整”；它不解决行内容变化。
- **锁**解决“多个重建者同时写”；它不解决锁到期后的旧写者，也不保证等待者一定能拿到新值。
- **TTL**给“某一次成功写入的 key”一个到期时间；它不保证到点前正确，也不保证到点后一定重建成功。“长期残留的上界”只在写入确实成功、无覆盖/删除/淘汰、TTL 未被外部改动时成立；反复慢写回和重新发布不受单次 key 的 TTL 约束。
- **回表**解决“ZSet 只有 ID，行可能被删/改状态”；它不解决检查后到响应前的并发变化。

### 7.3 统计缓存的保证范围

统计缓存是“短 TTL + 互斥重建 + 提交后双删”的读侧优化：

- 命中时返回的是最近一次成功写回的 JSON，可能是 20～30 秒内的旧值；
- 四个计数在**当前 HTTP 调用链**（`getTicketStatistics` 无 `@Transactional`、Controller 调用无外层事务）下是四条独立语句，不构成同一快照；方法没有本地事务注解不等于任何调用都无事务——若被有事务的调用方调用，四条语句可能共享事务快照，实际行为取决于隔离级别与数据库；
- 锁只覆盖重建临界区，锁到期后可能有两个写者；
- 双删只覆盖常见回填窗口，不覆盖慢于 500ms 的回填、删除失败、进程退出；
- 没有版本号/墓碑/空值哨兵；“零”是正常值，“坏 JSON”才走删除重建。

对“接近 DB”要按条件表达，不能承诺某一轮成功：只有在 DB/Redis 正常、写路径都调用失效且删除确实执行、读者在 key 过期后重新读取、锁竞争下有一位写者成功写回、之后没有并发旧写者覆盖/没有外部改 TTL/没有淘汰等条件同时成立时，缓存里的值才会在该次写入 TTL 到期后的下一次 miss 重建中接近 DB。若写回响应丢失、删除未执行、锁持续到期导致旧快照后写、key 被淘汰或 TTL 被外部改动，某一轮可能仍返回旧值；反复慢写回没有全局有界的收敛上界。不能说“强一致”“删除后立刻正确”“缓存一定最终正确”。

### 7.4 安全与调用边界

- `POST /ticket/admin/page` 和 `GET /ticket/admin/statistics` 的 ADMIN/SUPPORT 检查在 Controller 注解；Service 不重新读 token。直接调用 Service（测试、未来内部调用）不会自动获得权限检查。
- 管理端 `TicketQueryReqDTO.userId` 不参与过滤；不要把它当成“查某个用户的工单”的 B 端参数。C 端 `getUserTicketPage` 才用 userId。
- 统计接口没有参数，不能查历史日期；缓存 key 只按当天。历史统计不在 Redis 也不在 MySQL 的汇总表里。

### 7.5 性能与部署边界

- 索引重建按状态全量 `selectList`；`sys_ticket` 没有单独的 `ticket_status` 索引，实际执行计划未验证。
- 索引页要 `selectByIds` 回表；大 `size` 会生成大 IN 列表，分页校验只限制到 `Integer.MAX_VALUE`，没有业务上限。
- 重建锁 10 秒、统计锁 5 秒，都没有续期；DB 慢/工单多时可能到期。
- Lua 的 ARGV 随成员数增长；Redis 脚本参数、网络包大小和 `lua-time-limit` 的实际限制未在仓库配置中体现。
- 多 key Lua（版本、ready、ZSet、锁）在 Redis Cluster 下要求同一 slot；这些 key 没有显式 hash tag。仓库没有声明部署拓扑，实际是单机、哨兵还是 Cluster 未知；若是 Cluster，读侧重建立即 `catch` 并回 DB，而 `syncByTicketId` 的失效只记日志，旧索引可能留到 TTL（静态推导，未实测）。
- `ticket:pool:version` 虽然不设 TTL，但在 `allkeys-lru` 等淘汰策略下仍可能被驱逐；仓库没有 Redis maxmemory 配置。版本丢失会触发重建，不是数据真值丢失。
- 应用配置中的 Redis 主机/密码是占位符，实际超时 3 秒、连接池 12 等是否适合生产未知；不要从示例值推断真实部署。

### 7.6 真值、恢复与未知

- **真值**：`sys_ticket`。池和统计都可以从 DB 重建；Redis 丢池不等于丢工单。
- **恢复（有条件）**：池在冷池读取拿到锁、DB/Redis 正常且发布校验通过时才重建；统计在下一次 miss 且重建成功时才刷新。两者都不保证某一轮成功，锁占用/DB 报错/响应丢失/并发旧写者都可能让本次继续回 DB 或保留旧值。旧统计的历史日期不会恢复，因为没有持久化。
- **不能恢复/只能等待**：未提交的 DB 变更；失效失败期间已写入但未被索引看到的顺序（只能等该索引自身 TTL、被淘汰或下一次成功失效——前提分别是该 key 的 TTL 仍在且未被改动、淘汰真的发生、下一次失效真的执行；不保证下一轮重建）；已经丢失的延迟删除任务。
- **未知**：真实 MySQL 隔离级别/执行计划/时区、Redis 拓扑/淘汰/网络超时、JVM 时区、实际 TTL 与锁竞争、HTTP 序列化、测试是否可通过；本章都没有运行验证。

---

## 八、测试阅读：覆盖什么、没覆盖什么

本章只读了测试源码，**没有运行任何测试**；没有连接业务 MySQL/Redis，也没有发送 HTTP 请求。

### 8.1 不需要外部端口的回归/mock

| 测试 | 门控、真实/替身 | 与本章相关断言 | 没覆盖 |
|---|---|---|---|
| `TicketPoolRegressionTest` | 无外部端口；`TableInfoHelper` 初始化 `Ticket` 元数据；mock `TicketMapper`、`TicketPoolIndex`、`RedisLock`、`StringRedisTemplate` | 索引页保持 Redis 顺序、附加过滤整页回 DB、缺行/错状态/版本变化回退、空热池不查 DB、超大 offset 不溢出、非法分页在查询前拒绝、实际事务活跃时 `slice` 不碰 Redis/DB、Redis 读失败返回 empty | 真实 Redis/MySQL、真实 score、ready/版本 Lua、统计缓存 |
| `CacheConsistencyRegressionTest` | 无外部端口；多数用例 mock Redis/`DelayedCacheEvictor`/`TicketPoolIndex`，手工 `initSynchronization`；`delayedEvictorSurvivesDeleteFailuresAndShutsDown` 用真实 `DelayedCacheEvictor`（Redis 仍为 mock） | `submitTicket` 在 afterCommit 前不删统计/不调索引/不登记延迟任务；手动触发两个回调后，首次删除异常仍调索引和延迟 evictor；回滚不失效；真实 evictor 首次删除异常不影响后续任务，关闭后再调度不抛 | 统计缓存读/重建、真实事务提交、Redis 网络、真实统计计数 |

这些测试直接 `new TicketServiceImpl(...)`，不经过 Spring 的 `@Transactional` 代理；`CacheConsistencyRegressionTest` 手工初始化的是事务同步器，不是实际数据库事务。因此它们验证 Java 编排和回调注册，不验证真实 `FOR UPDATE`、提交/回滚或 Redis 网络。

### 8.2 需要隔离中间件的集成测试

| 测试 | 门控与真实组件 | 创建/删除影响 | 主要断言 | 没覆盖 |
|---|---|---|---|---|
| `TicketPoolRedisIntegrationTest` | `@EnabledIfSystemProperty(named="test.redis.port", matches="\\d+")`；真实 Redis、真实 `RedisLock`；`TicketMapper` 为 Mockito mock | 每轮 `redis.keys("ticket:pool:*")` 后删除；不创建 MySQL schema | 冷池失效不把单条当整池、优先级/时间/ID 排序、空池 ready 不反复重建、跨状态失效、旧成员/坏成员、DB 读期间变更拒绝发布、锁过期/新持有者、版本丢失、并发冷读、2 小时 TTL 与 ready 对齐、读取不续期 | 真实 DB、HTTP、统计缓存、真实 mysql |
| `TicketPoolDatabaseIntegrationTest` | `@EnabledIfSystemProperty(named="test.mysql.port", matches="\\d+")`，但 `setupServices` 还读取 `test.redis.port`；真实 MySQL/Redis/MyBatis Mapper；mock `TicketChatMapper`、`UserAuthMapper`、`DelayedCacheEvictor` | 在 `127.0.0.1:<test.mysql.port>` 创建随机 schema `ticket_pool_regression_<uuid>`；从 `sql/init.sql` 正则抽取 `CREATE TABLE sys_ticket`；每轮 `DELETE FROM sys_ticket` 并插 4 条；每轮删 `ticket:pool:*`；结束 drop schema | 索引页与 DB 页顺序/total 一致、附加过滤整页走 DB、删除/改状态行整页回退、优先级变更后显式失效重排、超大 offset 的 DB 回退 | 统计缓存、真实 HTTP/权限、真实 `DelayedCacheEvictor`、真实锁竞争 |
| `SecurityRepairRegressionTest` / `RepairDatabaseIntegrationTest` | 无端口 mock / `test.mysql.port` 门控 | 只构造 `TicketServiceImpl`，不覆盖池读侧 | 工单状态/归属/事务相关断言 | 与本章池/统计无关 |

`TicketPoolDatabaseIntegrationTest` 的 JDBC URL 使用 `serverTimezone=UTC`，而应用配置使用 `serverTimezone=Asia/Shanghai`；测试种子时间是固定字符串，排序只依赖相对大小，所以不能据此推断生产时区行为。测试连接用户名/密码是源码字面量，端口由系统属性注入；这里不复制具体连接值，也不代表它们适用于共享环境。

**清理风险**：`redis.keys("ticket:pool:*")` 会删除该 Redis 实例上**所有**匹配的版本、ready、ZSet、重建锁，不只是测试创建的。只能指向一次性专用 Redis；数据库测试会建库、清表、删库，只能指向一次性专用 MySQL。测试没有使用 `FLUSHALL`，教材也不给这种命令。

### 8.3 断言没证明的事

- 没有测试调用 `getTicketStatistics`，所以统计的四个计数、缓存命中/坏值/重建/双删窗口都没有行为测试；`CacheConsistencyRegressionTest` 只测了失效回调注册，不测统计值。
- 没有测试直接断言某个 score 的数值；`TicketPoolRedisIntegrationTest` 通过排序结果间接验证，但不覆盖 2286 年、异常 priority、`DATETIME(3)` 等边界。
- 没有 HTTP/权限测试；Controller 注解和 `TicketQueryReqDTO` 绑定都是静态阅读。
- 没有真实 Redis Cluster、网络超时、响应丢失、进程退出、时钟跳变测试。
- 集成测试即使代码写了断言，本章也**没有运行**；不能写成“已验证通过”。

---

## 九、动手验证

### 9.1 纯阅读追踪（不需要启动服务）

**练习 A：一条索引页**

1. 从 `TicketController#adminPage` 开始，找到 `getAdminTicketPage`、`canUseTicketPoolIndex`、`pageByTicketPoolIndex`。
2. 假设请求 `{"status":0,"current":2,"size":2}`，按 1.1 的 7 条工单，写出完整顺序和第 2 页。
3. 列出第一次冷池访问依次读写的 Redis key 和 MySQL 方法；标出哪一步使用 `PUBLISH_TICKET_POOL`，哪一步使用 `READ_TICKET_POOL`。
4. 把 `size` 改成 10、`current` 改成 1，回答是否还会查 DB 的 `selectCount`/`selectList`。

**检查点**：能说出“索引提供顺序和 total，行内容仍回表”；能说出空池/冷池/offset 超界三种空结果的区别。

**练习 B：版本与锁**

1. 在 `slice` 中找到第一次读、抢锁、双检、`setIfAbsent`、DB 快照、发布、再读、解锁。
2. 假设 T1 的 DB 查询超过 10 秒，T2 拿到新锁；写出 T1 的发布和释放分别发生什么。
3. 假设 T1 发布前发生 `INVALIDATE`；写出 `PUBLISH_TICKET_POOL` 的返回值、T1 本次结果和下一次读取的版本来源。

**检查点**：能区分版本不符和锁令牌不符；知道旧持有者不会误删新锁。

**练习 C：统计缓存**

1. 从 `GET /ticket/admin/statistics` 追到 `getTicketStatistics`，列出命中、坏值、miss、拿不到锁、等待超时五条路径。
2. 按 1.1 的 7 条工单，手算四个统计项；说明 `todayCompletedCount` 为什么用 `status=3 + updated_at`。
3. 画出“T1 读旧值 → T2 提交删除 → T1 700ms 后回填”的时间线，标出 500ms 延迟删除的位置和 TTL。

**检查点**：能说出统计 Redis `get` 异常不会自动查 DB；能说出双删不覆盖慢于 500ms 的回填。

### 9.2 隔离实验（本章未运行）

只有在**一次性专用 MySQL 和 Redis** 上才做以下实验。不要指向业务库/共享 Redis；不要使用 `FLUSHALL` 或清空业务库。

前置条件：

- 一台临时 MySQL，端口通过 `-Dtest.mysql.port` 指定；测试会用固定的本机连接参数创建随机 schema，并从 `sql/init.sql` 抽取 `sys_ticket` 建表。
- 一台临时 Redis，端口通过 `-Dtest.redis.port` 指定；测试会删除 `ticket:pool:*`。
- 已安装 Maven/JDK 21；不要求在业务配置里填真实凭据。

命令示例（PowerShell；尖括号是占位，**未执行**）：

```powershell
# 只跑真实 Redis 的池测试；会删除该实例上所有 ticket:pool:* 键
mvn -Dtest=TicketPoolRedisIntegrationTest -Dtest.redis.port=<ISOLATED_REDIS_PORT> test

# 同时需要临时 MySQL 和 Redis；会创建/清空/删除随机 schema
mvn -Dtest=TicketPoolDatabaseIntegrationTest `
  -Dtest.mysql.port=<ISOLATED_MYSQL_PORT> `
  -Dtest.redis.port=<ISOLATED_REDIS_PORT> test
```

预期观察（来自测试断言，未实测）：

- 冷池第一次读取只 `selectList` 一次；空池有 ready 但无 ZSet；后续读取不重复重建。
- 同一 priority 下更新时间新的在前；时间相同则主键数值大的在前。
- 数据库删除/改状态后，索引页整页回退，total 用 DB 的计数。
- 版本变化/锁过期时旧发布被拒绝，新持有者的锁不被旧持有者删除。

实验影响与清理：

- Redis：测试清理 `ticket:pool:*`（包括版本、ready、ZSet、锁）；如果实例上还有别的应用 key，必须换实例，不能只依赖 pattern 删除。
- MySQL：测试创建 `ticket_pool_regression_<uuid>`，每轮清空 `sys_ticket`，结束 `DROP DATABASE`；只能用于临时实例。
- 测试不覆盖统计缓存；不要因为跑完池测试就声称统计缓存已验证。
- 如果只想观察 key，不要手工 `ZADD` 到业务实例；可以在一次性 Redis 上按本章 key 结构做只读 `TYPE`/`TTL`/`ZCARD` 检查，并记录清理范围。

---

## 十、一页复习

**一句话**：工单池是“按状态排序的 ZSet + 版本 + ready + 重建锁”的读侧索引；统计是“短 TTL JSON + 互斥重建 + 提交后双删”的读侧缓存；MySQL 才是真值。

**工单池查询**：

1. `POST /ticket/admin/page` → `getAdminTicketPage`。
2. 只有 `status` 非空且无 `type/userType/handlerId/keyword` 才尝试索引；实际事务中也跳过索引。
3. `slice`：读 → 锁 10 秒 → 双检 → `setIfAbsent` 版本 → `selectList(status)` → 校验行 → `PUBLISH` 校验版本/锁 → 再读。
4. 索引页：`READ` 返回 `version,total,ids`；`selectByIds` 回表；按 ids 重排；行缺失/状态不符/版本变化 → 整页回 DB。
5. 空热池：ready=`V:0`、ZSet 不存在，返回 total 0，不查 DB；冷池/锁占用/坏成员 → 回 DB。

**score**：

- `priority * 1e13 + updated_at(UTC 映射) 的 epoch 毫秒`；`ZREVRANGE` 降序；等分按 19 位补零 ID 字典序降序。
- 正常数据下与 `ORDER BY priority DESC, updated_at DESC, id DESC` 一致；时间超过约 2286 年、priority 越界、DATETIME 带小数秒时不再保证。

**四个键**：

- `ticket:pool:version`：无 TTL；失效换新；发布时校验。
- `ticket:pool:ready:{status}`：`版本:成员数`；2 小时；区分空池/未就绪。
- `ticket:pool:{status}`：ZSet；2 小时；成员=19 位 ID。
- `ticket:pool:rebuild:lock:{status}`：UUID 令牌；10 秒；发布校验、解锁比对。

**统计**：

- key `ticket:statistics:yyyyMMdd`，JSON，TTL 20～30 秒；锁 5 秒；抢不到锁等 3×60ms，然后直查 DB 且不写缓存。
- 四个计数：状态 0；状态 1 且有 handler；今日创建；状态 3 且今日更新。当前 HTTP 调用链下是四条独立 `selectCount`、不是同一快照；被有事务的调用方调用时取决于隔离级别与 DB。
- 写路径 afterCommit：首次删除 + 500ms 延迟删除；失败只记日志；删除是否真执行、旧值是否残留取决于响应、并发重写、淘汰与 TTL，不保证某轮收敛。

**必须记住的边界**：

- Lua 原子隔离 ≠ 出错回滚；失败停在哪一步决定留下什么状态，`SET ready` 之后的失败仍可能被 `READ` 当完整池（见 4.6）；多 key 在 Cluster 可能不同槽。
- 版本检查只在检查点有效；回表后还会再查一次，但检查通过后到响应前仍可能失效。
- 单次成功写入的 TTL ≠ 系统整体收敛上界；反复慢写回没有全局有界保证。
- `syncByTicketId` 捕获 `RuntimeException` 不抛给调用方；`Error` 等非捕获异常会向上传播。
- 统计 Redis 读/锁异常不自动降级；工单池 Redis 读异常会回 DB。
- 测试阅读不是运行通过；集成测试只能对一次性专用中间件运行。

---

## 十一、自测与答案

**1. 按本章的四条待分配工单（id 11 priority 2 updated 2026-11-14 18:00；id 12 priority 1 updated 2026-11-15 09:30:01；id 10 priority 1 updated 2026-11-15 09:30:00；id 9 priority 1 updated 2026-11-15 09:30:00），写出 score 和 ZSet 顺序，并说明 id 10 与 9 谁在前。**

<details>
<summary>参考答案</summary>

源码公式是 `priority * 1e13 + updatedAt.toInstant(ZoneOffset.UTC).toEpochMilli()`。手算：

- id 11：`2e13 + 1,794,679,200,000 = 21,794,679,200,000`
- id 12：`1e13 + 1,794,735,001,000 = 11,794,735,001,000`
- id 10：`1e13 + 1,794,735,000,000 = 11,794,735,000,000`
- id 9：同上 `11,794,735,000,000`

`ZREVRANGE` 按 score 降序：11 先；同 priority 1 中 12 的时间晚 1 秒，排第二；10 和 9 同分，成员分别是 `0000000000000000010` 和 `0000000000000000009`，19 位字典序降序时 10 在前。所以顺序 `[11,12,10,9]`。这不是“按插入顺序”，也不是“ID 先于时间”；ID 只在同 priority、同更新时间时生效。

</details>

**2. 待分配池的 ready 是 `V1:0`，ZSet 键不存在；另一个时刻 ready 不存在、版本是 V1。两次 `/admin/page?status=0` 分别会怎样？**

<details>
<summary>参考答案</summary>

第一次是**空热池**：`READ_TICKET_POOL` 读到 version=V1、`ZCARD=0`、ready=`V1:0`，匹配，返回 `{V1,0}`；`pageByTicketPoolIndex` 看到 `ids` 为空，`isCurrent` 仍为 true，返回 total 0、空 records，不查 DB。第二次是**冷池/未就绪**：`READ` 发现 ready 不存在（不匹配 `V1:0`），返回 `{}`；`slice` 尝试拿重建锁，抢到就查 DB 重建，抢不到就返回 empty，本次整页回 DB。两者在“没有记录”上看起来一样，但只有空热池能确认“确实没有工单”且不查 DB；冷池需要 DB 才能确认。

</details>

**3. 请求带 `status=0` 和 `type=2`，索引里有 status=0 的 ZSet，为什么不直接取 ZSet 再过滤？**

<details>
<summary>参考答案</summary>

`canUseTicketPoolIndex` 要求 `type == null`，只要带了类型就整页走 DB。原因不是“实现懒得做”，而是分页窗口：ZSet 的 offset 是在全部 status=0 工单上滑动的；先取第 N 页再按 type 过滤，过滤后每页剩下的行数不可预期，页码越靠后越像丢数据，而且总数也无法从 ZSet 得到。源码注释明确要求这类查询整体交回 DB。DB 路径用 `type` 条件 + `selectCount` + `priority/updated_at/id` 排序 + `LIMIT`。

</details>

**4. T1 冷池重建，DB 快照读到旧 4 行；T2 在 T1 发布前提交了工单并触发失效。T1 的 `PUBLISH_TICKET_POOL` 返回什么？T1 本次返回什么？下一次读取会怎样？**

<details>
<summary>参考答案</summary>

T2 的 `syncByTicketId` 先 `SET ticket:pool:version=V2` 并删除所有 ready/ZSet。T1 手里是 V1，`PUBLISH` 的第一个校验 `GET version ~= ARGV[1]` 失败，返回 0。T1 的 `slice` 返回 `Optional.empty()`，`pageByTicketPoolIndex` 返回 null，`getAdminTicketPage` 本次走 `pageAdminTicketsFromDb`。下一次读取发现 ready 缺失/版本 V2 不匹配，重新拿锁、用 V2 查 DB、发布；不保证这次重建一定成功（可能 DB/Redis 失败或锁被占），失败就继续回 DB。T1 不会把旧 4 行发布成完整索引。

</details>

**5. T1 持重建锁超过 10 秒，T2 拿到新锁；T1 随后发布和释放锁分别会发生什么？**

<details>
<summary>参考答案</summary>

T1 的 `PUBLISH_TICKET_POOL` 检查 `GET lockKey ~= ARGV[2]`：当前锁值是 T2 的令牌，不等于 T1 的 T0，返回 0，不修改 ready/ZSet。T1 的 `finally` 调 `RedisLock#unlock`，`RELEASE_LOCK_IF_MATCH` 比较当前锁值与 T0，不匹配返回 0，不会删除 T2 的锁。T1 本次回 DB。T2 可以正常发布；如果 T2 也遇到版本变化，它的发布同样会被拒绝。锁到期只允许新持有者进入，不保证旧持有者的快照一定旧或新。

</details>

**6. 统计缓存 miss，T1 拿到锁，T2 拿不到锁。T2 会等多久？期间 T1 写回缓存会怎样？T2 最后做什么？**

<details>
<summary>参考答案</summary>

T2 抢锁失败后进入 `for (i=0; i<3; i++)`：每次 `sleepQuietly(60ms)` 后读一次缓存，最多约 180ms（加 Redis 读耗时）。如果 T1 在这期间写回，T2 某次读取命中就返回。3 次都 miss，T2 记 warn 并 `queryTicketStatisticsFromDb(bizDate)` 直接查 DB 返回，**不写缓存**。所以 T2 的降级不会热池；T2 这次调用结束，不会再回过头抢锁。

关键点：一次调用只执行一次 `tryLock`。T2 若要在锁过期后“拿到锁重建”，只能是**另一个新发起的调用**（前提：T1 的 4 个 `selectCount` 超过 5 秒导致锁过期，且该新调用在 T2 之后到达）。于是形成两个**独立调用**各持一次锁、各写缓存一次；写回顺序有两种互斥情况：旧快照先写则最终新值，新快照先写则旧值覆盖、留到该次写入 TTL 或下次删除（前提是写入成功且无后续删除/重写/淘汰；见 5.4、6.4）。不能写成“T2 等待后又抢到锁”。

</details>

**7. 统计缓存的“双删”能保证旧值在 500ms 内消失吗？举一个反例。**

<details>
<summary>参考答案</summary>

不能。源码是在 afterCommit 里先尝试删除，然后 `delayedCacheEvictor.evictAfter(cacheKey, 500ms)`；延迟任务从登记时刻起算，且第一次删除失败也照常登记。反例：T1 在 T2 提交前读到旧四项，T2 提交后在 t=0 删除并登记 t=500ms 的第二次删除；T1 的 DB 查询很慢，t=700ms 才写回旧四项，此时已登记的延迟删除已执行完。此后若没有新的工单写入触发删除、没有并发重写/淘汰、TTL 未被外部改动，且该次 `SET` 确实成功，旧值才留到 20～30 秒 TTL；第一次/延迟删除的响应丢失时结果未知（服务端可能已删），执行器关闭或进程退出也会留下残余。双删只覆盖“回填早于 500ms”的常见窗口，不提供强一致。

</details>

**8. Redis 读异常时，工单池和统计接口的降级行为一样吗？坏 JSON 删除失败呢？**

<details>
<summary>参考答案</summary>

不一样。工单池的 `TicketPoolIndex#slice` 把 Redis 读取/锁/发布和 DB 重建包在 `try/catch (RuntimeException)` 里，失败返回 empty，`getAdminTicketPage` 再走 DB；因此 Redis 读异常通常还能用 DB 返回。统计的 `getCachedTicketStatistics` 只在 JSON 反序列化外层有 try/catch，`stringRedisTemplate.opsForValue().get` 在 try 外；Redis 读异常直接上抛，`getTicketStatistics` 没有兜底，HTTP 通常 500。坏 JSON 时统计会尝试 `delete`，但 `catch` 块里的 `delete` 没有第二层 catch；如果删除也失败，异常继续上抛，而且删除是否已在服务端生效未知（响应可能丢失）。工单池遇到非法成员则是 `readSlice` 抛异常，`slice` 捕获后回 DB，但不会自动修复该索引；它只能等 ready/ZSet 过期、下一次成功失效或外部删除——这些前提本身不保证发生。

</details>

**9. 索引页取到 ids `[10,9]`，回表只查到 10，或者 9 的状态变成了 1。页面会只显示 10 吗？total 会是多少？**

<details>
<summary>参考答案</summary>

不会直接返回“原索引中过滤后只剩 10”的结果。`pageByTicketPoolIndex` 检查回表数量或状态不匹配后返回 `null`，调用方整页走 `pageAdminTicketsFromDb`。DB 路径先 `selectCount`，再排序和 `LIMIT` 取页；如果此时 DB 符合条件的页面确实只有 10，仍可能合法地只返回 10。结果不使用原索引的残页和 total；但两条 DB 语句之间若有并发变更，total 与列表可能来自不同时点，不保证同一快照。

</details>

**10. 某个未来代码直接执行 `UPDATE sys_ticket SET priority=3 WHERE ticket_id='T...'`，没有调用 `afterTicketMutation`。工单池会立即发现吗？**

<details>
<summary>参考答案</summary>

不会。索引的版本只在 `syncByTicketId` 里换新；直接 SQL 不改变 `ticket:pool:version`，也不删除 ready/ZSet。`READ` 仍会看到旧的 score/顺序，`isCurrent` 仍返回 true；回表读到的 `priority` 字段可能是新值，但排序和 total 仍是旧索引的。它能被发现的情况只有：某次状态变更/正常写路径触发了失效、ready/ZSet 的 2 小时 TTL 到期（前提：该 key 的 TTL 仍在且未被外部改动、未被淘汰）、Redis 数据被删除/淘汰，或者外部手工清理。也就是说，索引的“版本一致”只证明“自上次失效以来没有经过受控失效”，不证明 DB 没有变化。统计缓存同理：不调用 `afterTicketMutation` 的写入不会删除统计 key，页面可能继续返回旧四项，直到该 key 的 TTL 到期、被删除/淘汰或 TTL 被外部改动；是否真的留到 TTL 取决于该次写入是否成功与后续干预。

</details>

---

## 十二、常见误区与导航

### 12.1 常见误区

- 把 `ticket:pool:0` 当成工单真值：删除 Redis 不会删工单，MySQL 才是真值。
- 把 score 说成“位运算拼接”：源码是双精度 `priority*1e13 + epochMillis`；正常范围内精确，但不是位域。
- 认为同分时按插入顺序：Redis `ZREVRANGE` 同分按成员字典序降序；补零让 ID 数值顺序与字典序一致。
- 认为空 ZSet 就是“没有工单”：也可能是索引还没建；必须看 ready。
- 认为 ready 存在就一定完整：ready 只保存 `版本:成员数`，成员内容仍要回表；行删除/状态变化在回表时才发现。
- 认为 `isCurrent` 通过就“响应一定最新”：检查后仍可能变化；DB 写入绕过失效时版本也不会变。
- 认为重建锁能保证“同一时刻只有一个重建者”：锁会到期，旧持有者可能还在查 DB；发布校验能阻止旧快照，但不能阻止两个写者先后写。统计也同理：一次调用只抢一次锁，锁过期后形成两个写者的是两次独立调用，各写一次。
- 认为 Lua 出错会自动回滚：Lua 只保证脚本执行不被插入；中途错误不回滚已执行命令。失败停在 `SET ready` 之后时，`READ` 仍可能把该池当完整池，只是 pool 可能无 TTL。
- 认为统计缓存和工单池一样会在 Redis 异常时回 DB：统计的 `get`/锁异常没有兜底；只有拿不到锁（返回 null）才会等/直查 DB。
- 认为双删能保证旧值 500ms 内消失：慢于 500ms 的回填、删除失败、响应丢失、执行器关闭都会留下旧值；单次写入的 TTL 只是该 key 的到期时间，不是系统整体收敛上界。
- 认为 `syncByTicketId` 的失效失败会抛给调用方：它只捕获 `RuntimeException` 并记日志；`Error` 等未捕获异常才会向上传播。
- 认为统计四个数字来自同一快照：当前 HTTP 调用链确实是四条独立 `selectCount`、无外层事务；但也不要反过来说“任何调用都必然无事务”——方法没有本地 `@Transactional` 不代表调用方没有外层事务，有外层事务时四条语句可能共享快照，取决于隔离级别与 DB。
- 看到 `POST /admin/page` 带 `userId` 就以为能按用户过滤：管理端 `pageAdminTicketsFromDb` 不读 `userId`；C 端才有该过滤。

### 12.2 导航与源码入口

- **上一章**：[第 12 章 工单生命周期与权限](01-ticket-lifecycle.md)——工单状态、角色边界、写路径和 `afterTicketMutation`。
- **下一章**：[第 14 章 缓存问题与并发窗口](03-cache-races.md)——穿透、击穿、雪崩、空值、墓碑与双删的进一步推导。
- **相关专题**：
  - 锁令牌、回填版本、两套唯一 ID → [第 15 章 锁、版本租约与唯一 ID](04-locks-leases-and-ids.md)
  - 附近订单池的版本/ready/重建锁 → [第 9 章 位置与发现](../02-orders/03-location-and-discovery.md)
  - 事务、提交后回调与跨库边界 → [第 3 章 数据与事务](../00-foundations/03-data-and-transactions.md)
  - Redis ZSet/TTL/Lua 基础 → [第 4 章 Redis 与流式响应基础](../00-foundations/04-redis-flux-and-sse-basics.md)

路径基准：Java 类相对 `src/main/java/com/fancy/rideagenthub/`；SQL、配置和测试写完整仓库相对路径。

| 结论 | 源码位置 |
|---|---|
| HTTP 入口 | `controller/TicketController.java#adminPage/#statistics` |
| Service 契约与实现 | `service/TicketService.java#getAdminTicketPage/#getTicketStatistics`；`service/impl/TicketServiceImpl.java#getAdminTicketPage/#canUseTicketPoolIndex/#pageByTicketPoolIndex/#pageAdminTicketsFromDb/#getTicketStatistics/#getCachedTicketStatistics/#cacheTicketStatistics/#queryTicketStatisticsFromDb/#evictTicketStatistics/#afterTicketMutation/#afterCommit` |
| 工单池索引 | `service/base/TicketPoolIndex.java#syncByTicketId/#slice/#isCurrent/#readSlice/#score` |
| 重建锁/延迟删除 | `service/base/RedisLock.java#tryLock/#unlock`；`service/base/DelayedCacheEvictor.java#evictAfter/#shutdown` |
| 键与 TTL | `constant/RedisKeyConstants.java#TICKET_POOL_VERSION_KEY/#ticketPoolKey/#ticketPoolReadyKey/#ticketPoolRebuildLockKey/#ticketStatisticsKey/#ticketStatisticsLockKey` |
| Lua 脚本 | `util/RedisScripts.java#INVALIDATE_TICKET_POOLS/#PUBLISH_TICKET_POOL/#READ_TICKET_POOL/#RELEASE_LOCK_IF_MATCH` |
| DTO/VO/响应 | `domain/dto/TicketQueryReqDTO.java`；`domain/vo/TicketVO.java`；`domain/vo/TicketDataVO.java`；`domain/response/PageResult.java`；`domain/response/Result.java` |
| 实体与 SQL | `domain/entity/Ticket.java`；`src/main/resources/sql/init.sql#sys_ticket` |
| 时间/异常/配置 | `util/BusinessTime.java`；`exception/GlobalExceptionHandler.java`；`src/main/resources/application.yaml`（Redis/数据源/MyBatis-Plus） |
| 测试阅读（未运行） | `src/test/java/com/fancy/rideagenthub/TicketPoolRegressionTest.java`、`TicketPoolRedisIntegrationTest.java`、`TicketPoolDatabaseIntegrationTest.java`、`CacheConsistencyRegressionTest.java`；旁证 `SecurityRepairRegressionTest.java`、`RepairDatabaseIntegrationTest.java` |
