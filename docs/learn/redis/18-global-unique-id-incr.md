# 全局唯一 ID：INCR 日序号 + 雪花 ID（节点租约）

> **Redis 考点**：两套唯一 ID 方案并存——对外的工单编号用 `INCR` 生成可读日序号；内部主键用雪花算法，节点号唯一性靠 Redisson 看门狗续期租约保证。
> **来源**：`docs/01-redis-application-points.md` §2.6（其它应用点）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/impl/TicketServiceImpl.java`（`generateTicketId` / `TICKET_SEQUENCE`）、`src/main/java/com/fancy/rideagenthub/config/IdGeneratorConfig.java`、`src/main/java/com/fancy/rideagenthub/util/SnowflakeIdWorker.java`

---

## 一、业务场景

项目有两类 ID 需求，特征截然不同：

| 需求 | 形态 | 方案 |
|---|---|---|
| 工单编号（对外展示 / 客服报号） | 可读、含日期、短 | `INCR` 日序号：`T20261113100001` |
| 订单 ID、userId、RAG 主键（内部主键） | 趋势递增、数字型 | 雪花 ID：64 位时间戳 + 节点 + 序列 |

方案对比（为什么不是单一方案）：

| 方案 | 优点 | 缺点 |
|---|---|---|
| DB 自增 | 简单 | 单点写入瓶颈；分库后要改表结构 |
| 号段模式（一次取一段） | DB 压力小 | 断电丢段（重启后 ID 跳跃）；要维护号段表 |
| `INCR` 日序号 | 可读、零额外表 | 每号一次 Redis 往返；Redis 故障时发不出号 |
| 雪花 | 算法本地计算、趋势递增；本项目封装每次发号前仍向 Redis 校验租约 | **节点号必须全局唯一**——两实例同节点号会产生重复 ID |

工单编号选 `INCR`（可读性是硬需求，量级低）；内部主键选雪花（数字型与趋势递增），把雪花的「节点唯一」弱点交给 Redis 租约补——**两套并存，各取所长**。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `ticket:no:{yyyyMMdd}` | String（计数器） | 48 小时（`TICKET_SEQUENCE` Lua 内设置） | 当日工单序号 |
| `id:node:{datacenterId}:{workerId}` | Redisson RLock | **看门狗持续续期，非 Redis 永不过期 key** | 雪花节点租约 |

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `TicketServiceImpl.generateTicketId` | `T` + `yyyyMMdd` + 类型码 + `TICKET_SEQUENCE` 的 5 位序号；`seq == null` 抛 503 |
| `TicketServiceImpl` 的 `TICKET_SEQUENCE` Lua | `INCR` + `TTL < 0` 时 `EXPIRE 172800` |
| `config/IdGeneratorConfig` | 启动时抢 `id:node:` 租约；`NodeLease` 包装「租约 + 受保护的 worker」 |
| `util/SnowflakeIdWorker` | 经典雪花实现（时间戳 41 位 + 数据中心 5 位 + 节点 5 位 + 序列 12 位） |
| `config/Config` 的 `jacksonCustomizer` | 绝对值超过 `2^53-1` 的包装类型 `Long` 序列化为字符串；`BigDecimal` 一律字符串（JS 精度防护） |

---

## 四、实现拆解

### 4.1 `TICKET_SEQUENCE`：`INCR` 与 `EXPIRE` 的原子化

```lua
local number = redis.call('INCR', KEYS[1])
if redis.call('TTL', KEYS[1]) < 0 then redis.call('EXPIRE', KEYS[1], 172800) end
return number
```

**为什么必须 Lua**：若 `INCR` 与 `EXPIRE` 是两条命令，进程在两条之间崩溃，key 将**永不过期**（TTL 永远不会被设置）——每天一个 key 永久累积。Lua 内「先增再判 TTL」原子完成：

- `TTL < 0` 才设过期（key 首次创建时 TTL = -1）：后续 `INCR` 不重置 TTL（`INCR` 本身不改过期时间），当天计数持续到 48 小时后整体消失；
- 48 小时跨两天缓冲：凌晨附近的查询仍能读到昨天的计数（防御时区 / 边界查询）。

`seq == null`（Lua 返回 null）抛 503「工单编号服务不可用」——**编号不唯一是不可接受的**，宁可拒绝提交也不生成可能重复的号。撞号兜底：工单表对编号建唯一索引，`DuplicateKeyException` 映射为 409（全局异常处理器）。

### 4.2 雪花节点租约：`IdGeneratorConfig`

```java
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
```

三个关键点：

1. **启动即抢**（`tryLockAsync(0, -1, ...)`，无等待、看门狗续期）：两个实例配了相同的 `(datacenterId, workerId)` 组合，**第二个启动直接失败**——比「运行中产生重复 ID」好排查一万倍；
2. **发号前校验租约**：看门狗依赖后台线程续期，Redis 长期故障时租约可能失效（锁被释放或被别的进程持有）。`nextId` 每次发号前 `isHeldByThread(owner)` 校验——**租约丢了就停止发号**（抛异常），绝不「无租约地继续生成可能重复的 ID」；
3. **`NodeLease` 实现AutoCloseable**：应用正常关闭时 `close()` 释放租约（`unlockAsync(owner)`），节点号可以被下一个实例立即接管。

`workerId` / `datacenterId` 从 `${id.worker-id:1}` / `${id.datacenter-id:1}` 读取——**未在 `application.yaml` 外置**，多实例部署必须显式配置唯一的 `(datacenterId, workerId)` 组合。

### 4.3 Jackson 精度防护

雪花 ID 是 64 位 long，最大值超过 JavaScript `Number.MAX_SAFE_INTEGER`（\(2^{53}-1\) = 9007199254740991）——前端拿到的 JSON 里数值会被**静默截断**。`config/Config.jacksonCustomizer()` 把绝对值超过安全范围的包装类型 `Long` 转字符串，安全范围内保留数值；`BigDecimal` 不论大小均转字符串，前端按字符串处理。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 工单号用 `INCR` | 雪花 / UUID | 客服场景要求「报得出、听得清」；`TyyyyMMdd` + 类型码 + 至少 5 位序号天然可读（%05d 是最小宽度，超过 99999 不截断） |
| 内部主键用雪花 | UUID | UUID 无序（InnoDB 页分裂）、36 字符太长；雪花趋势递增且是数字 |
| 节点号用 Redisson 租约 | 配置文件约定（人工保证不重复） | 人工约定靠纪律，租约靠机制：冲突时启动即失败 |
| 发号前校验租约 | 信任启动时的一次抢锁 | Redis 故障 / 网络分区后租约可能易主；宁可停发号不可发重复号 |
| TTL 48h 而非 24h | 恰好一天 | 跨天缓冲，防御边界查询与时区差 |
| 长整型 JSON 转字符串 | 前端用 BigInt 解析 | 字符串对任何前端透明；BigInt 兼容性仍有成本 |

---

## 六、边界与已知问题

1. **`INCR` 依赖 Redis 可用**：Lua 返回 null 时为 503；实际 Redis 连接等异常未在 generateTicketId 捕获，通常由全局处理器返回 500。设计取舍：编号不唯一不可接受，编号不连续可接受（Redis 重启且持久化未恢复该计数、或 key 被删除/淘汰后，当日计数可能归零；重启本身并不必然丢 key。计数丢失时序号才会从 1 重来，**撞号由唯一索引兜底**为 409）。
2. **雪花时钟回拨**：`SnowflakeIdWorker.nextId` 检测到 `timestamp < lastTimestamp` 会抛 RuntimeException 并拒绝发号；序列耗尽时自旋等下一毫秒。此检查针对同一 worker 实例，不是跨重启的持久时间围栏。
3. **租约校验有粒度**：`isHeldByThread` 是发号时的检查，Redis 故障的极短窗口内（检查通过 → 故障 → 发号）理论可越过；这是性能与一致性的折中。
4. **`id.worker-id` 未外置提醒**：默认值 1 对单实例开发够用；多实例部署漏配会在第二实例启动时被拦下（明确的报错信息）。

---

## 七、如何验证

```bash
# 1. 工单序号
redis-cli GET ticket:no:<yyyyMMdd>          # 提交工单后：递增的数字
redis-cli TTL ticket:no:<yyyyMMdd>          # 期望：≤ 172800（48 小时）
#    连续提交后 GET 单调递增；key 过期后（或手工 DEL 后）重新从 1 开始

# 2. 唯一索引兜底：手工 SET ticket:no:<yyyyMMdd> 0 后提交 → 生成已存在的编号 → 409

# 3. 雪花节点租约
redis-cli HGETALL id:node:1:1               # Redisson RLock 为 Hash，查看持有者与重入计数
redis-cli TTL id:node:1:1                   # 期望：~30 附近波动（看门狗续期）
#    同一 workerId 启动第二个实例 → 启动失败「雪花ID节点号已被其他实例使用」

# 4. 精度防护：GET 任意返回订单 ID 的接口
#    → JSON 中 id 为字符串（不是数字）
```

---

## 八、延伸阅读

- [`13-distributed-lock.md`](13-distributed-lock.md) —— 看门狗续期的原理（节点租约同款机制）
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— `TICKET_SEQUENCE` 速查
- [`../agent/03-security-and-boundaries.md`](../agent/03-security-and-boundaries.md) —— 租约丢失停止发号的安全测试
- [Snowflake 原始设计](https://blog.twitter.com/engineering/en_us/a/2010/announcing-snowflake) —— Twitter 的雪花算法
