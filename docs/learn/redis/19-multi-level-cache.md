# 多级缓存 L1/L2/L3：分层读取 + 租约回填

> **Redis 考点**：Heap → Redis → MySQL 三层缓存，逐级回填用版本租约保护（Lease 机制的完整应用）；写入顺序与读取相反——先落权威库，再按租约追加缓存。
> **来源**：`docs/01-redis-application-points.md` §2.6（其它应用点）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/agentbase/memory/`（`MessageMemory` / `HeapMemory` / `RedisMemory` / `MysqlMemory` / `MessageWindow`）

---

## 一、业务场景

AI 对话的上下文读取频率极高（每轮对话至少读一次），且**消息量随对话增长**。三层结构按「读取速度 × 数据寿命」分层：

| 层 | 实现 | 介质 | 生命周期 | 角色 |
|---|---|---|---|---|
| L1 | `HeapMemory` | JVM 堆（`ConcurrentHashMap`） | 进程存活期 | 当前聚焦会话的热数据 |
| L2 | `RedisMemory` | Redis List | 聚焦会话 24h / 非聚焦 30min | 短中期缓存 |
| L3 | `MysqlMemory` | MySQL 表 | 永久 | **权威源** |

核心矛盾：**读回填（miss 时逐层补数据）与写追加（每轮对话保存新消息）并发**。回填如果直接覆盖，会把并发写入的新消息抹掉（完整竞态推导见 [09 篇](09-lease-token.md)）——本篇聚焦三层结构本身。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `chat:history:{chatId}` | List（JSON 字符串） | 聚焦 24h / 非聚焦 30min | L2 消息列表 |
| `chat:history:version:{chatId}` | String（整数） | 无（`PERSIST`） | L2 版本租约 |
| `chat:history:staging:{chatId}:{uuid}` | List | 1 分钟 | 回填暂存 |
| （L1 无 Redis 结构） | `ConcurrentHashMap` + 版本号 | — | 每条目 `record Entry(List<Message>, long version)` |

TTL 常量：`FOCUS_CHAT_TTL = 24h`、`NON_FOCUS_CHAT_TTL = 30min`（`MessageMemory`）——**聚焦会话才值得 24 小时的热缓存**；用户切到别的会话后，旧会话的 L2 降为 30 分钟（`switchFocusChatIfNeeded`）。

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `MessageMemory.get(userId, chatId, lastN)` | 归属守卫 → L1 → L2 → L3 逐层 `getAll` + `tail` 截窗口；逐级持租约回填 |
| `MessageMemory.save(userId, chatId, msgs)` | `assertOwned` → **先 `mysqlMemory.append`** → 按租约追加 L2 / L1 |
| `MessageMemory.getPersisted` | 恢复链路专用：直读 MySQL 权威数据（绕过缓存） |
| `MessageMemory.getUserMessage` | 过滤 `UserMessage` 的读取（同构的分层读取） |
| `MessageMemory.clearChat` | DB 墓碑（`retireHistory`）→ 清 MySQL → 清 Redis → 清 Heap |
| `MessageMemory.switchFocusChatIfNeeded` | 用户切会话：清 L1、旧会话 L2 降 30 分钟 |
| `MessageWindow.tail` | 从完整的工具批次边界截取窗口 |
| `HeapMemory` / `RedisMemory` / `MysqlMemory` | 各层读写与租约实现 |

---

## 四、实现拆解

### 4.1 读路径：三层全量 + 消息窗口

```
归属守卫 isNewOwnedChat（新会话直接空，防止读到无元数据的旧缓存）
heapLease  = heap.currentVersion(chatId)          ← 取租约必须在耗时读取之前
L1 命中 → tail(heapAll, lastN) 返回
redisLease = redis.currentVersion(chatId)
L2 命中 → fillHeapWithLease(...) → tail(redisAll, lastN) 返回
L3 读取（权威源）
   → fillRedisWithLease(...) 成功才 fillHeapWithLease(...)
   → tail(mysqlAll, lastN) 返回
```

两个要点：

- **三层都取全量（`getAll`）再截窗口**，而不是每层取「最近 N 条」：`MessageWindow.tail` 需要看到完整的消息序列才能找到**工具调用批次边界**（`AssistantMessage` 的 tool calls 与对应 `ToolResponseMessage` 不能被拆开，否则模型看到「孤立的工具结果」直接报协议错误）。截窗口的代价由最上层（内存列表）承担；
- **L1 命中直接返回，不碰 L2 / L3**——L1 是为「当前聚焦会话」优化的快路径。

### 4.2 写路径：先 MySQL，再按租约追加

```
assertOwned(userId, chatId, false)     ← 写入前归属校验（404/403/410）
switchFocusChatIfNeeded(userId, chatId)
mysqlMemory.append(chatId, newMessages)          ← ① 权威源先落库
redisMemory.appendIfVersionMatch(chatId, msgs, FOCUS_TTL, redisVersion)   ← ②
heapMemory.appendIfVersionMatch(chatId, msgs, heapVersion)                ← ③
（② 或 ③ 抛异常 → heapMemory.clear 后上抛，防止半写状态被后续读取固化）
```

**为什么先写 MySQL**：数据库写入失败时缓存不能「先返回了未持久化的消息」；权威源先行，缓存失败只是慢（下次读回源）。这与「先写缓存再落库」的顺序差别是本质性的——后者在 DB 失败时会返回**幻觉持久化**的数据。

`appendIfVersionMatch`（L1 / L2 同构）：版本仍等于捕获值且原缓存非空/存在才追加，缺失时保持空缓存等待全量回源；版本变了（期间有回填或其他写入）→ L2 删除数据由后续读重建 / L1 丢弃本次追加的合并（`HeapMemory.appendIfVersionMatch` 在版本不符时重建空列表，保留递增的版本号）。

### 4.3 回填：宁可放弃，不可覆盖

`fillRedisWithLease` / `fillHeapWithLease` 的租约语义（细节见 [09 篇](09-lease-token.md)）：

- L2 回填失败 → **仍然返回 MySQL 数据**（`get` 的返回不依赖回填成功——权威源数据是对的，被拒的只是「写进缓存」这个动作）；
- L1 租约失效时**保留并发写入内容**（不清空）：写顺序已保证 L1 里的列表包含最新消息，清空反而丢数据。

### 4.4 切换会话：L1 与 L2 的寿命策略

`switchFocusChatIfNeeded`（save 时调用）：

- 同一会话：L2 续 24 小时；
- 切换到新会话：**旧会话清 L1 消息**（保留版本条目）、旧会话 L2 降为 30 分钟、新会话 L2 续 24 小时。

聚焦跟踪仅在 `save` 时更新，读回填也能创建 L1 条目；`HeapMemory.clear` 保留空列表与递增版本。没有容量上限或自动淘汰，不能保证堆空间只随聚焦会话数增长。

### 4.5 清空会话：墓碑先行的三连清

`clearChat` 顺序：`retireHistory`（DB 置 `historyDeleted=true` + `locked=true`，**所有实例的后续读取立刻被 `assertOwned` 410 拦截**）→ 清 MySQL → 清 Redis（`INCR` 版本 + `DEL` 数据）→ 清 Heap。**DB 墓碑先立**：即使某个实例的 L1 还残留旧消息，下一轮读取在归属校验就被拒——缓存清理的时序不再影响正确性。

### 4.6 `MessageWindow.tail`：工具批次配对

```
遍历消息：AssistantMessage 的 toolCalls 登记 pending；
ToolResponseMessage 的 responses 消除 pending；
孤立的 ToolResponse / 交叉配对 → 抛 IllegalStateException（损坏历史明确拒绝，不静默截断）
窗口起点只取「pending 为空的消息边界」——批次不被腰斩；
最后一个未完成的确认批次整体保留（HITL 断点在窗口内必须完整）。
```

**为什么必须是完整批次**：Spring AI 协议要求 `ToolResponseMessage` 必须紧跟包含对应 tool call 的 `AssistantMessage`。窗口腰斩批次 → 模型收到协议非法的上下文。这也解释了 4.1 的「全量读取」：只有看到序列全貌才能找对边界。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| 三层（含堆内 L1） | 只 Redis + MySQL | 聚焦会话的读取是每轮对话的必经路径，堆内命中省掉网络往返；代价是要维护 L1 租约与切换清理 |
| 全量读取 + 窗口截取 | 每层取最近 N 条 | 工具批次配对需要完整序列；截取在内存列表上做，成本可控 |
| 先写 MySQL 再追加缓存 | 先写缓存再落库 | DB 失败时缓存会返回「幻觉持久化」的数据 |
| 切换会话清 L1 | L1 常驻全部会话 | 堆空间随会话数线性膨胀不可接受 |
| 清空时 DB 墓碑先行 | 只清三层数据 | 墓碑使清理时序不再影响正确性（残留缓存读不到） |
| 回填失败仍返回 DB 数据 | 回填失败报错 | 读的正确性不依赖缓存写入成功 |

---

## 六、边界与已知问题

1. **`getAll` 的成本随对话长度线性增长**（L3 的全表行读取、L2 的 `LRANGE 0 -1`）——Lease 解决正确性，没有解决读取成本。超长对话需要分页或截断策略（当前未实现）。
2. **L1 与 L2 内容可能短暂不一致**（L2 回填成功、L1 被拒的组合）——下次 L1 miss 自愈；读取结果以当次返回的层为准。
3. **回填无 single-flight**：N 个并发 miss 产生 N 次回源 + 回填尝试（只有租约仍有效的那次成功）。
4. **版本 key 不回收**（[09 篇](09-lease-token.md) 的通用代价）：每个 chatId 留一个永久版本 key。
5. **多实例各存一份 L1**：堆内缓存无跨实例一致性——L1 版本只在当前进程内变化，实例 B 的写入不会使实例 A 的 L1 自动失效；单轮执行锁只防并发执行，不刷新旧 L1。持续命中时可能长期陈旧，需等本实例清理、切换会话或重启后才回源，当前没有跨实例主动失效机制。

---

## 七、如何验证

```bash
# 1. 三层形态：对话进行中
redis-cli LLEN chat:history:<chatId>              # L2 消息数（≥ 已保存轮数）
redis-cli GET chat:history:version:<chatId>       # 版本号，随 save 递增
redis-cli TTL chat:history:<chatId>               # 聚焦会话 ≈ 86400

# 2. 切换会话：另开会话对话后
redis-cli TTL chat:history:<旧chatId>             # 期望：降到 ≤ 1800（非聚焦 30 分钟）

# 3. 写顺序：MySQL 先行 —— 观察保存一轮对话后的持久化
#    （DB chat 消息表的行数 ≥ L2 LLEN；缓存写失败时 DB 仍齐全）

# 4. 清空会话
redis-cli EXISTS chat:history:<chatId>            # 期望 0
redis-cli GET chat:history:version:<chatId>       # 仍存在且值已递增（PERSIST）
#    继续在该会话发消息 → 410「会话已清空，请新建对话」

# 5. 工具批次完整性：截断历史到任意长度，观察下一轮对话是否正常
#    （MessageWindow 保证窗口从批次边界开始，模型不会收到孤立 ToolResponse）
```

---

## 八、延伸阅读

- [`09-lease-token.md`](09-lease-token.md) —— 租约机制的完整竞态推导（本篇的 4.3 只是摘要）
- [`20-tool-response-cache.md`](20-tool-response-cache.md) —— 工具结果的两级辅助索引（与消息缓存的分工）
- [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) —— `chat:info` 元数据与会话锁
- [`../agent/01-orchestration-and-sse.md`](../agent/01-orchestration-and-sse.md) —— `MessageMemory` 在对话链路中的调用位置
