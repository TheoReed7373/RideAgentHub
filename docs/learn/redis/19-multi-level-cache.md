# 多级缓存 L1/L2/L3：堆内 → Redis → MySQL

> **Redis 考点**：多级缓存的读路径编排与逐级回填；本地缓存与分布式缓存的分工；用版本号（Lease）保证回填不覆盖更新的数据。
> **来源**：`docs/01-redis-application-points.md` 第一节（现状盘点，多级缓存 L1/L2/L3）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/agentbase/memory/`、`src/main/java/com/fancy/rideagenthub/service/base/ChatRequestRunner.java`、`src/main/java/com/fancy/rideagenthub/agents/AgentStreams.java`
> **代码版本**：当前工作区为 `RideAgentHub` / `com.fancy.rideagenthub`（基准路径 `src/main/java/com/fancy/rideagenthub/`）。本系列撰写于历史快照 `bdc40d7`（旧名 `TaxiAgent`、旧包 `com.fancy.taxiagent`，属已被重写合并的旧分支），文中的 `文件:行号` 与部分代码块是**改造前快照**，整体已漂移；学习时以「类名 + 方法名 + 章节名」定位当前源码，具体行为一律以当前代码为准。

---

## 【当前实现总览】（先看这一节，再读下方改造前推导）

> **审计阅读约定（2026-11-13 代码反向复核）**：本篇二～八章正文与七章 `redis-cli` 命令写于改造前快照，**作为缺陷推导教材保留**；凡与本节冲突，一律以本节与当前源码为准。

| # | 正文中的旧说法 | 当前代码事实 |
|---|---|---|
| 1 | 结构表 / 4.5「版本 key 与数据 key 同寿命、严格同步」、验证 #3 `TTL chat:history:version ≈ 数据 key`、#4 `DEL` 版本 key 后「回填时 INCR 到 1」 | 版本 key 经 `PERSIST` **无 TTL**（`TTL = -1`，`RedisScripts.java:177,184,200,223,232`）；`DEL` 版本 key 后 `CURRENT_HISTORY_VERSION` **重置为 0**。旧 TTL 期望全部失效 |
| 2 | 3.1/4.4/4.6/6.1/6.2 写序「L1 → L2 → L3」「缓存超前于 DB」「L1 租约失效清空」「Redis 故障 SSE 永不结束」 | 当前写序 **L3(MySQL) → L2 → L1**（`MessageMemory.java:100-108`）；L1 租约失效**保留并发写入内容**（`:196-198`）；异常由 `AgentStreams.guard` 发 `error` 并结束 sink。**仍成立的只有**「L2 读取不降级 MySQL」这一条 |
| 3 | "`MessageWindow.tail` 损坏历史从下一个用户轮重新开始 / 拒绝孤立工具结果" | `tail` 遇损坏历史**直接抛 `IllegalStateException`**（`MessageWindow.java:40-42`），会让 `MessageMemory.get/getPersisted` 失败；「从下一条 UserMessage 重建」是 `MessageParser.unparseHistory:17-35` 的行为。两者错误语义相反，不能混写 |
| 4 | 缺写路径的三道正确性机制 | 见下方补充 |

### 【补充】写路径为什么不会写幽灵消息

`getPersisted` 直查 MySQL 供 HITL 恢复（`MessageMemory.java:171-175`），不经过可能被污染的 L1/L2；`MysqlMemory.append` 用 `FOR UPDATE` 行锁并校验 410 墓碑（`:66-70`），防止往已停用的会话追加；`APPEND_HISTORY_IF_VERSION_MATCH` 在版本不符时直接 `DEL` 数据 key（`RedisScripts.java:197-210`），让旧租约的回填不仅失败、还会清掉可能被覆盖的缓存。三者合起来解释了一个容易只记结果的问题：**为什么 L3 先写、L2/L1 后写仍然安全** —— 因为权威源始终可回查，缓存层只是可丢弃的加速副本。

---

### 1. 业务背景与模块关系

每轮对话都要把上下文交给 LLM，但「完整历史」放在 MySQL、「近期热数据」放 Redis 与堆内能显著降低延迟。难点是**逐级回填可能覆盖并发写入的新消息**（Lease 令牌，见 [09 篇](09-lease-token.md)），以及**多实例各存一份 L1** 带来的不一致。当前分层：`HeapMemory`(L1) → `RedisMemory`(L2) → `MysqlMemory`(L3)，由 `agentbase/memory/MessageMemory.java` 统一编排；`ToolResponseMemory` 是**独立的 Redis + MySQL 两级辅助索引**，不属于这条链（见 [20 篇](20-tool-response-cache.md)）。

### 2. 完整读写链路（与原文 4.1/4.2 的顺序相反）

**读** `MessageMemory.get(userId, chatId, lastN)`：

```java
if (chatManager.isNewOwnedChat(userId, chatId)) return List.of();   // 归属守卫：新会话不回源
long heapLease  = heapMemory.currentVersion(chatId);
long redisLease = redisMemory.currentVersion(chatId);                // 先取租约，再耗时读取
List<Message> heapAll = heapMemory.getAll(chatId);
if (!heapAll.isEmpty())  return MessageWindow.tail(heapAll, lastN);
List<Message> redisAll = redisMemory.getAll(chatId);
if (!redisAll.isEmpty()) { fillHeapWithLease(...); return tail(redisAll, lastN); }
List<Message> mysqlAll = mysqlMemory.getAll(chatId);                  // 权威源
if (!mysqlAll.isEmpty()) { if (fillRedisWithLease(...)) fillHeapWithLease(...); return tail(mysqlAll, lastN); }
```

三层都改成 **`getAll` + `MessageWindow.tail`**（不再 `getLastN`），因为工具调用批次不能被从中间截断；`ChatServiceImpl` 现在用 `memory.get(userId, id, 100)` 而不是 `get(..., 1)`。

**写** `MessageMemory.save(userId, chatId, msgs)`：`assertOwned` → `switchFocusChatIfNeeded` → **先 `mysqlMemory.append`（L3）** → `redisMemory.appendIfVersionMatch`（L2）→ `heapMemory.appendIfVersionMatch`（L1）；L2/L1 抛异常时 `heapMemory.clear(chatId)` 后上抛。这与原文「L1→L2→L3 同步写入」正好相反，也是 4.7 节「L1 租约失效要清空」结论反转为「保留」的根因。

### 3. 版本 key 的生命周期（原文 3.2 / 6.7 结论反转）

| 项 | 改造前（原文） | 当前 |
|---|---|---|
| 版本 key | 与数据 key 同 TTL | **`PERSIST` 永久保留（TTL `-1`）**，5 段 Lua 共 6 处 |
| `clear` | 两条 `DEL` 删数据 + 删版本 | `CLEAR_HISTORY_WITH_VERSION_BUMP`：`INCR` 版本 + `PERSIST` + `DEL` 数据，原子 |
| L1 租约失效 | **清空** | **保留**并发写入内容（`HeapMemory.overwriteIfVersionMatch` 返回 `existing`） |
| 工具结果缓存 | 三级 | Redis + MySQL 两级 |

为什么反转：版本 key 若随数据过期/清空归零，`INCR` 会从 1 重新开始，某个在途租约恰好等于 1 时校验会通过，把旧快照「复活」。详见 [09 篇](09-lease-token.md) 4.8。

### 4. SSE 异常出口与 3 分钟超时（原文 6.1 的核心结论仍成立）

原文指出「L2 读取没有 `try/catch`、Redis 故障时不降级 MySQL，`CompletableFuture.runAsync` 的异常会被静默丢弃、SSE 永不结束」—— **前半句至今成立**：L2 异常不会自动回退到 L3，这是当前已知的可用性边界。**后半句已被修复**：`ChatController` 委托 `service/base/ChatRequestRunner.run`，`agents/AgentStreams.guard` 捕获异常后 `sink.tryEmitError` 并结束流，用 `takeUntilOther(Mono.delay(Duration.ofMinutes(3)))` 给模型调用设 3 分钟超时，完成回调失败也会结束 sink；单轮执行锁在 `doFinally` 释放，客户端断开不提前放锁。线程池由 `config/AsyncWorkConfig` 的 `chatExecutor`（8/32/64）与 `ragExecutor`（4/8/32）隔离。

### 5. 归属守卫、消息窗口与损坏历史

- `isNewOwnedChat` / `assertOwned`：非本人会话 404、停用会话 410，由 `ChatInfoService` + `GlobalExceptionHandler` 映射（状态码 400–599 内直接透传）。
- `MessageWindow.tail` 保证完整的「`AssistantMessage`(含 tool call) + `ToolResponseMessage`」批次不被拆开，**拒绝孤立的工具结果**；遇到损坏历史会从下一个用户轮重新开始，避免把半个批次喂给模型（`SecurityRepairRegressionTest` 有对应断言）。
- `ESMemory` 仍是空占位类、`MemoryAdvisor` 仍返回 `null`（0 调用方），属于 📐 设计中的骨架，不要当作第四层记忆。

### 6. 边界与面试延伸

| 问题 | 当前答案 |
|---|---|
| 多实例 L1 不一致怎么办 | 每实例一份，靠版本租约保护回填；不追求强一致，只保证「不覆盖更新的数据」 |
| Redis 全挂会怎样 | L2 读取异常上抛，不自动降级 MySQL；SSE 由 `guard` 发 error 并结束，不会永久挂起 |
| 为什么 `getAll` 而不是 `getLastN` | 工具调用批次的完整性；`MessageWindow.tail` 在截取后保证配对 |
| 为什么先写 MySQL | 持久化权威优先，避免「缓存里有、库里没有」的幽灵消息 |
| 原文哪些已过时 | `getLastN`、L1→L2→L3 写顺序、L1 清空、版本 key 同寿命、`clear` 两 key 一起删、`ToolResponseMemory` 三级缓存、八章「对照三级缓存两种写法」 |

详见 [`../agent/01-orchestration-and-sse.md`](../agent/01-orchestration-and-sse.md)、[`../agent/03-security-and-boundaries.md`](../agent/03-security-and-boundaries.md)。

---

## 一、业务场景

Agent 每轮对话都要把历史消息拼进 prompt。历史落在 MySQL 的 `sys_messages` 表（`init.sql:87-93`），但**一轮对话要读很多次历史**：

- `ChatServiceImpl.chat` 每轮开头 `memory.get(userId, id, 1)` 取最近 1 条（`ChatServiceImpl.java:97`）；
- Agent 内部还要按需取全量 `getUserMessage` 交给模型；
- 一次会话十几轮下来，同样的历史被反复读十几遍。

每次都查 DB 的话：一次索引扫描 + 反序列化 + 网络往返，量级在毫秒；而它读的是一份**写完就基本不再变**的数据（历史只会追加，不会修改）。这正是多级缓存的经典场景。

所以有了三层：

```
MessageMemory.get(userId, chatId, lastN)
        │
        ├─ L1  HeapMemory   （进程内 ConcurrentHashMap，纳秒级）
        ├─ L2  RedisMemory  （Redis List，跨实例共享，亚毫秒级）
        └─ L3  MysqlMemory  （sys_messages 表，权威数据源）
```

---

## 二、Redis 结构选型

| 层 | 实现类 | 结构 | Key | TTL | 语义 |
|---|---|---|---|---|---|
| L1 | `HeapMemory.java:22-108` | `ConcurrentHashMap<String, Entry>`，`Entry = (List<Message>, long version)`（`:28`） | —— | 无 | 进程内缓存，所有修改产生新的不可变 List 实例 |
| L2 | `RedisMemory.java:26-217` | List（每元素 = 一条消息的 JSON） | `chat:history:{chatId}`（`RedisKeyConstants.java:61`） | 聚焦会话 24 小时 / 非聚焦 30 分钟 | 追加型结构，尾部读最近 N 条 |
| L2 版本 | `RedisMemory.java:71-85` | String（整数） | `chat:history:version:{chatId}`（`RedisKeyConstants.java:67`） | 与数据 key 同寿命 | 写入版本号，供租约校验 |
| L2 暂存 | `RedisMemory.java:128-167` | List | `chat:history:staging:{chatId}:{token}`（`RedisKeyConstants.java:73`） | 60 秒（`RedisMemory.java:34`） | 回填缓冲区，由 Lua 消费掉 |
| L3 | `MysqlMemory.java:14-102` | 表 `sys_messages` | —— | 永久 | 权威 |
| 调度 | `MessageMemory.java:16-211` | —— | —— | —— | 编排读路径、回填、聚焦切换 |
| （空） | `ESMemory.java:9-10` | —— | —— | —— | **空实现**，见 6.6 |

TTL 常量：`MessageMemory.java:18`（`FOCUS_CHAT_TTL = 24h`）、`:19`（`NON_FOCUS_CHAT_TTL = 30min`）。

**为什么 L2 用 List 而不是 Hash / String（整段 JSON）**：

| 结构 | 追加 | 取最近 N 条 | 取全量 | 结论 |
|---|---|---|---|---|
| List | `RPUSH`，O(1) | `LRANGE size-N -1`，O(N) | `LRANGE 0 -1` | **选它** |
| String（整段 JSON） | 读-改-写，非原子且要传整段 | 要取全量再截 | 反序列化全量 | 并发追加会丢数据 |
| Hash | `HSET seq msg`，O(1) | 需另存序号，两次往返 | `HVALS` 顺序不保证 | 多一次元数据查询 |

历史消息的访问模式是"只追加 + 只读尾部"，List 天然贴合。

---

## 三、代码落点

### 3.1 调度层 `MessageMemory`

| 位置 | 方法 | 职责 |
|---|---|---|
| `MessageMemory.java:39-77` | `get` | 三级读路径 + 逐级回填 |
| `MessageMemory.java:82-101` | `save` | 同步写 L1 → L2 → L3 |
| `MessageMemory.java:106-145` | `getUserMessage` | 同上，但取全量并只筛 `UserMessage` |
| `MessageMemory.java:147-154` | `clearChat` | 三层同时清空 |
| `MessageMemory.java:161-165` | `fillRedisWithLease` | 持租约回填 L2，成功后补 TTL |
| `MessageMemory.java:179-183` | `fillHeapWithLease` | 持租约回填 L1，失败则清 L1 |
| `MessageMemory.java:185-197` | `switchFocusChatIfNeeded` | 聚焦会话切换：清旧 L1 + 调 TTL |
| `MessageMemory.java:199-210` | `tail` | 取尾部 N 条，返回拷贝 |

### 3.2 三层实现

| 位置 | 方法 | 说明 |
|---|---|---|
| `HeapMemory.java:33-42` | `getAll` | 未命中返回 `List.of()`；命中返回**拷贝** |
| `HeapMemory.java:47-53` | `currentVersion` | 取租约（读版本号） |
| `HeapMemory.java:58-75` | `append` | `compute` 内追加 + 版本 +1 |
| `HeapMemory.java:86-100` | `overwriteIfVersionMatch` | 持租约覆盖（L1 的版本校验点） |
| `HeapMemory.java:102-107` | `clear` | 仅 `remove`，无 TTL、无容量上限 |
| `RedisMemory.java:44-57` | `getLastN` | `LLEN` 算起点 + `LRANGE` 取尾部 |
| `RedisMemory.java:71-85` | `currentVersion` | 读 `chat:history:version:{chatId}`，非法值按 0 |
| `RedisMemory.java:95-114` | `append` | 走 Lua `APPEND_WITH_VERSION_BUMP` |
| `RedisMemory.java:128-167` | `overwriteIfLeaseValid` | 暂存 key + Lua 原子校验替换 |
| `RedisMemory.java:169-177` | `expire` | **数据 key 与版本 key 一起续期** |
| `RedisMemory.java:179-185` | `clear` | 两个 key 一起删 |
| `MysqlMemory.java:24-49` | `getLastN` | `orderByDesc(id) limit N` 后 `Collections.reverse` |
| `MysqlMemory.java:51-74` | `getAll` | `orderByAsc(id)` |
| `MysqlMemory.java:76-94` | `append` | 逐条 `insert` |

### 3.3 关联

| 位置 | 说明 |
|---|---|
| `RedisScripts.java:75-84` | `APPEND_WITH_VERSION_BUMP`：`INCR` 版本 + `RPUSH` 消息，同一脚本内 |
| `RedisScripts.java:103-118` | `OVERWRITE_IF_VERSION_MATCH`：校验版本 → `DEL` 旧数据 → `RENAME` 暂存 key |
| `MessageParser.java` | `Message` ↔ JSON 的序列化（`parse` / `unparse`） |

---

## 四、实现拆解

### 4.1 三级读路径与逐级回填

`MessageMemory.java:52-76`：

```java
List<Message> heapAll = heapMemory.getAll(chatId);
if (!heapAll.isEmpty()) {
    return tail(heapAll, lastN);              // L1 命中，直接返回
}

// 取租约：必须在任何"耗时读取"之前捕获版本号。
// 若读 L2/MySQL 期间有 save 落库，版本号会变化，本次回填随即被拒绝。
long heapLease = heapMemory.currentVersion(chatId);
long redisLease = redisMemory.currentVersion(chatId);

List<Message> redisLastN = redisMemory.getLastN(chatId, lastN);
if (!redisLastN.isEmpty()) {
    fillHeapWithLease(chatId, redisLastN, heapLease);   // L2 命中 → 只回填 L1
    return redisLastN;
}

List<Message> mysqlLastN = mysqlMemory.getLastN(chatId, lastN);
if (!mysqlLastN.isEmpty()) {
    // 持租约回填：宁可放弃回填（下次再查一次 DB），也不覆盖掉并发写入的新消息
    fillRedisWithLease(chatId, mysqlLastN, redisLease); // L3 命中 → 回填 L2 + L1
    fillHeapWithLease(chatId, mysqlLastN, heapLease);
    return mysqlLastN;
}
```

三点值得注意：

1. **回填是"逐级向上"而不是"越级填"** —— 命中 L2 只填 L1，命中 L3 填 L2 和 L1。这样每一层都保持"它下面那一层的子集"的语义，不会出现 L1 有数据而 L2 是空的怪状态。
2. **往下查一层不会顺手把上层也填了** —— 比如 L1 未命中就直接查 L2，此时不会去动 L1（要等 L2 的结果回来再填）。顺序是单向的。
3. **租约必须在"耗时读取之前"捕获**（`:57-60` 的注释专门强调）。这是整个机制的关键：`redisMemory.getLastN` 和 `mysqlMemory.getLastN` 可能耗时几十毫秒，如果先读数据再取版本号，那么"读取期间发生的写入"就会被漏掉，回填照样会覆盖新消息。

### 4.2 L1 的租约：版本校验怎么做

L1 的版本号不是独立存的 key，而是**跟数据放在同一个 `Entry` 里**（`HeapMemory.java:28`）：

```java
private record Entry(List<Message> messages, long version) {
}
```

写入路径 `append`（`HeapMemory.java:58-75`）在 `ConcurrentHashMap.compute` 内完成"合并 + 版本 +1"，因此**对同一个 `chatId` 是原子的**；版本递增与数据追加不存在"数据已加、版本未加"的窗口。

回填路径 `overwriteIfVersionMatch`（`HeapMemory.java:86-100`）：

```java
boolean[] written = {false};
heapCache.compute(chatId, (key, existing) -> {
    long current = existing == null ? 0L : existing.version();
    if (current != expectedVersion) {   // 租约已失效
        return existing;                // 原样返回 = 不覆盖
    }
    written[0] = true;
    return new Entry(new ArrayList<>(messages), current + 1);
});
return written[0];
```

**校验与替换必须在 `compute` 内一起完成** —— 若拆成"先 `get` 比对版本、再 `put` 覆盖"，两步之间就又能被并发 `append` 插队，租约形同虚设。这与 Lua 脚本要做到"判断与写入在同一次执行内"是同一个道理，只是这里用 `ConcurrentHashMap.compute` 提供了 per-key 的原子性。

L2 的同款实现在 `RedisMemory.java:71-85` + `:128-167`：版本号是一个独立的 String key，由 Lua `OVERWRITE_IF_VERSION_MATCH` 原子完成"读版本 → 比对 → 替换"。之所以要绕一个暂存 key（先 `rightPushAll` 到 `chat:history:staging:...`，再让 Lua `RENAME` 过去），是因为回填数据可能很大，不适合整体当 `ARGV` 塞进脚本参数。

**回填失败时为什么要清 L1**（`MessageMemory.java:179-183`）：

```java
private void fillHeapWithLease(String chatId, List<Message> messages, long leaseVersion) {
    if (!heapMemory.overwriteIfVersionMatch(chatId, messages, leaseVersion)) {
        heapMemory.clear(chatId);
    }
}
```

类注释（`:167-177`）解释得很清楚：L1 是优先读取的层，若在此处覆盖掉并发 `save` 刚追加的消息，后续读取会被脏 L1 短路，L2 修得再好也救不回来 —— 所以 L1 同样必须校验租约。而租约失效时**清空**而不是保留：此刻 L1 的内容不完整（可能只含并发追加的那几条），保留它反而会让后续读取拿到残缺上下文；清空后下次读取会从 L2 重新加载，而 L2 是权威的。

### 4.3 L1 用 `ConcurrentHashMap` 的取舍

选择本身是合理的：

- **优点**：无序列化、无网络、纳秒级；`compute` 提供 per-`chatId` 的原子性；所有修改产生新 List 实例，所以 `getAll` 返回的拷贝与缓存内部永不共享可变状态（`HeapMemory.java:41`，`MessageMemory.tail:206-209`）。
- **代价 1：多实例不一致。** 每个 JVM 一份。A 实例 append 的消息，B 实例的 L1 看不到 —— 但这不是正确性问题，B 实例的 L1 未命中会去查 L2，而 L2 是共享的。
- **代价 2：进程重启即失效。** 冷启动后第一轮必然走 L2/MySQL 回源，属于可接受的抖动。
- **代价 3：无容量上限、无 TTL。** `heapCache` 只靠 `clear(chatId)` 清理（`:102-107`），没有任何过期或淘汰机制。长跑进程里访问过的 `chatId` 只增不减 —— 见 6.3。

### 4.4 `MessageMemory` 作为统一调度层的职责

它不做任何存储，只做四件事：

1. **编排读顺序** —— L1 → L2 → L3，逐级回填（4.1）。
2. **编排写顺序** —— `save` 依次写 L1、L2、L3 并同步 `chatManager.updateChatTime`（`:95-100`）。注意 L2 的 `append` 之后还跟了一次 `expire`（`:98`），把数据 key 与版本 key 的 TTL 一起续到 24 小时。
3. **管理"聚焦会话"** —— `switchFocusChatIfNeeded`（`:185-197`）在进程内用 `userCurrentChat`（`ConcurrentHashMap<userId, chatId>`，`:22`）记住用户当前在哪个会话。用户切走旧会话时：清旧会话的 L1、把旧会话的 L2 TTL 降到 30 分钟、把新会话的 TTL 提到 24 小时，并 `chatManager.lockChat(prevChatId)` 停掉旧会话。
   **这是"用户只有一个活跃会话"这一业务约束在缓存层的落点**：活跃的会话留着完整上下文（24h），被切走的会话降级为短命缓存（30min），自然淘汰。
4. **惰性初始化 L1 的租约语义** —— 上层调用者完全看不到版本号，租约的取、验、失败处理全部封在 `MessageMemory` 里。

### 4.5 各级 TTL 与淘汰策略

| 层 | TTL | 淘汰方式 | 说明 |
|---|---|---|---|
| L1 | **无** | 仅 `clear(chatId)`：租约失败时（`:181`）、用户切换会话时（`:193`）、`clearChat` 时（`:151`） | 进程重启是唯一的大规模回收手段 |
| L2 数据 | 24h（聚焦）/ 30min（非聚焦） | Redis 被动过期；`save` 与回填成功时续期（`:97-98`、`:163`） | 每次 `save` 都会 `expire` 续期，所以活跃会话的 TTL 一直回满 |
| L2 版本 | 与数据**严格同步**（`RedisMemory.java:173-176`） | 同上 | 版本 key 若先过期会归零，使在途租约被误判为有效 |
| L2 暂存 | 60 秒（`RedisMemory.java:34`） | Lua 消费（`RENAME`）或 TTL 兜底 | 进程在写入暂存后崩溃时靠 TTL 自清理 |
| L3 | 永久 | 无 | 权威数据 |

**L2 的读写不对称**是有意的：`getLastN` 不会续期（只有 `save` 和回填成功才续期）。所以"24 小时"的准确语义是"**自最后一次写入或回填起 24 小时**"，而不是"自最后一次读取起 24 小时"。若改成读时续期，一个被反复读取但从不再写的僵尸会话会永久占用内存。

### 4.6 「缓存只是加速，绝不成为正确性依赖」在本项目的体现

这条原则在 `OrderGrabService`（抢单预检）里是显式写进类注释的。在 `MessageMemory` 这里的体现是：

1. **写路径一定写 L3** —— `save` 同步写三层，MySQL 不会被跳过（`:95-99`）。缓存不是"先写缓存再异步落库"，不存在丢数据的窗口。
2. **任何一层为空都继续往下查** —— 不会因为"L1 为空"就返回空结果。
3. **回填永不覆盖更新的数据** —— 宁可放弃回填（下次再查一次 DB），也不覆盖并发写入的新消息（`:70`）。
4. **L2 和 L3 都没有，才返回空列表** —— `:76` 的 `List.of()` 是"真的没有历史"，不是"缓存没命中"。

**但这条原则在本项目落地得并不彻底**，见 6.1：读路径上 Redis 异常不会降级到 MySQL。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| L1 用 `ConcurrentHashMap` + 不可变 List | Caffeine / Guava Cache | 需要 per-key 的原子"比对版本并替换"，`compute` 直接给了；引入缓存库反而要额外实现带条件的写入 |
| 版本号内嵌在 `Entry` 里（L1） | 独立的版本 key | L1 是进程内的，一个 `record` 字段就够，省一次 map 查询 |
| L2 用独立 version key | 从 List 长度推断版本 | 长度不能区分"追加"与"覆盖"，也识别不出"清空后重建" |
| L2 回填走暂存 key + Lua | 把整个列表作为 `ARGV` 传进 Lua | 历史可能很大，`ARGV` 会撑爆脚本参数；暂存 key 还能让"写数据"这一步失败时不触碰线上数据（`RedisMemory.java:139`） |
| L2 的 `append` 与版本递增同一脚本 | 先 `RPUSH` 再 `INCR` | 两步之间留下的窗口会让手持旧租约的读线程通过校验，覆盖掉刚追加的消息（`RedisScripts.java:63-66`） |
| 聚焦 / 非聚焦两档 TTL | 统一 TTL | 缓存空间应该向"用户正在看的那个会话"倾斜 |
| 切换会话时清 L1 | 保留旧会话的 L1 | L1 是进程内空间，用户切走后旧会话几乎不会再被读；保留只会占内存 |

---

## 六、边界与已知问题

### 6.1 读路径上 Redis 异常不会降级到 MySQL（**与项目自身原则不一致**）

`RedisMemory.getLastN:44-57` 与 `getAll:59-64` **没有 try/catch**。Redis 连接失败时异常会直接冒泡出 `MessageMemory.get`。

对比项目里其它两处缓存，它们的做法是相反的：

| 组件 | Redis 故障时的行为 |
|---|---|
| `ClassificationCache.get:64-68` / `put:92-94` | catch 后降级为"未命中 / 不写" |
| `ChatRateLimiter.tryAcquire:72-75` | catch 后放行 |
| **`MessageMemory`（经 `RedisMemory`）** | **异常上抛** |

而 `MessageMemory.get` 的调用点 `ChatServiceImpl.java:97` 也没有包 try/catch，再往外是控制器（`ChatController.java:41-47`）：

```java
Sinks.Many<AgentEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
// 异步启动
CompletableFuture.runAsync(() -> chatService.chat(id, chatParam, sink, userId));
return sink.asFlux();
```

对话逻辑跑在 `CompletableFuture.runAsync` 的独立线程里，**没有任何 `exceptionally` / `handle`**。所以异常会被 `CompletableFuture` 捕获后**静默丢弃**，`GlobalExceptionHandler` 也不会介入（异常根本没到 MVC 层）。

实际表现因此比"报错"更糟：**SSE 流既不发送数据也不结束，前端的对话界面一直转圈**，直到客户端或容器超时。排查时只能靠日志里那条 Redis 连接异常。

一个更符合"缓存只是加速"的实现应当在 `MessageMemory.get` 里把 L2 的读取包进 try/catch，失败就跳到 L3 直查 MySQL。当前是"Redis 挂了，聊天就不能用了（而且是静默地不能用）"。这是本篇最值得指出的落地缺口。

### 6.2 `save` 的写入顺序让缓存可能"超前"于 DB

`MessageMemory.java:95-99` 的顺序是 L1 → L2 → L3：

```java
heapMemory.append(chatId, newMessages);
redisMemory.append(chatId, newMessages, FOCUS_CHAT_TTL);
redisMemory.expire(chatId, FOCUS_CHAT_TTL);
mysqlMemory.append(chatId, newMessages);
```

`mysqlMemory.append` 逐条 `insert`（`MysqlMemory.java:80-93`），任何一条失败都会抛异常。此时 L1 与 L2 **已经**含有这条消息，而 DB 没有 —— 缓存比权威数据源"超前"了。没有补偿、没有回滚、没有延迟双删一类的兜底。

后果：这条消息在本次会话内可见（读 L1/L2 都能拿到），但刷新/重启后消失。严重程度取决于 `save` 的调用方是否在事务里（`MessageMemory` 本身没有 `@Transactional`）。

### 6.3 L1 无界：两个 map 都没有上限与过期

- `heapCache`（`HeapMemory.java:31`）：只有 `clear` 会删除条目。
- `userCurrentChat`（`MessageMemory.java:22`）：一个用户一条，**从来没有删除逻辑**。用户量增长时它是稳定增长的。

在长跑进程里这是内存泄漏。要修的话，`heapCache` 应当有容量上限或淘汰策略，`userCurrentChat` 至少要有 TTL。

### 6.4 多实例下 `userCurrentChat` 各存一份

`userCurrentChat` 是进程内 `ConcurrentHashMap`，没有共享。多实例部署时同一用户的请求可能落到不同实例，于是"当前会话是哪个"在每个实例上可能不同 —— 影响的是"哪个会话被当作聚焦会话给 24 小时 TTL"，不会造成数据错误，但会让 L2 的 TTL 策略在实例间不一致。

### 6.5 回填的破坏性问题

L1 与 L2 的回填都有"用旧快照覆盖新数据"的风险，项目用 Lease 令牌机制（版本号 + 原子校验替换）解决了。完整的推演（时序图、放弃回填 vs 覆盖的代价对比、为什么版本号必须在耗时读取之前取）在 [`09-lease-token.md`](09-lease-token.md) 中详述，本文只做简述。

### 6.6 `ESMemory` 是空实现

`ESMemory.java` 全文只有 10 行：

```java
/**
 * ElasticsearchMemory (规划中): 仅用于检索，不参与日常上下文加载。
 */
@Component
public class ESMemory {
}
```

**没有任何方法**。ES 在本项目里用于聊天记录的 N-Gram 全文检索，与 `MessageMemory` 的三级上下文加载是两条独立的链路 —— 所以它既不是 L4，也不是 `MessageMemory` 的一部分。文档里不应把它算作"四级缓存"。

### 6.7 其它

1. `MysqlMemory.getLastN` 用 `orderByDesc(id)` 再 `Collections.reverse`（`:31-32`、`:47`），依赖自增主键与时间同序。若同一 `chatId` 的消息在时间上倒挂（并发写入跨毫秒），排序会与实际时间不符。
2. `RedisMemory.currentVersion` 解析失败时按 0 处理（`:81-84`）并打 warn。版本号被外部改坏会让在途租约全部"看起来有效"（当前版本 0，租约可能是 0 或非 0），这是一个故障时的静默弱化。
3. `tail()` 每次都拷贝一份新 `ArrayList`（`MessageMemory.java:207-209`），避免调用方改动缓存内部列表，代价是每次读都拷贝。

---

## 七、如何验证

```bash
# 1. 观察 L2 的两个 key
redis-cli TYPE  chat:history:{chatId}          # list
redis-cli LLEN  chat:history:{chatId}          # 历史条数
redis-cli LRANGE chat:history:{chatId} 0 0     # 首条（JSON）
redis-cli GET   chat:history:version:{chatId}  # 版本号

# 2. 验证版本号随写入递增
#    发一条消息前 GET version，发完再 GET → 应 +1

# 3. 验证 TTL 两档
redis-cli TTL chat:history:{chatId}            # 当前会话 ≈ 86400（24h）
redis-cli TTL chat:history:version:{chatId}    # 应与数据 key 几乎相等（同寿命）
#    切换到另一个会话后再看旧会话 → 应降到 ≈ 1800（30min）

# 4. 验证逐级回填：清掉 L2，模拟冷启动
redis-cli DEL chat:history:{chatId} chat:history:version:{chatId}
# 再请求一次上下文（同时会命中 L1；要真正验证需重启应用）
# 期望：LLEN 恢复为 MySQL 中的条数，version 重新出现（回填时被 Lua INCR 到 1）

# 5. 验证回填暂存 key 只在回填瞬间存在
redis-cli --scan --pattern "chat:history:staging:*"
# 正常应查不到；若查到，它的 TTL 不会超过 60 秒
redis-cli TTL chat:history:staging:{chatId}:{uuid}

# 6. 验证 6.1：停掉 Redis（或改错连接配置）后再发一条消息
#    期望（当前实现）：日志出现 Redis 连接异常；
#    而 SSE 流既不返回数据也不结束（CompletableFuture 吞掉了异常），
#    前端界面一直等待 —— 而不是降级为直查 MySQL
```

---

## 八、延伸阅读

- [`09-lease-token.md`](09-lease-token.md) —— Lease 令牌机制完整推演：为什么"放弃回填"优于"覆盖"，版本号为什么必须在耗时读取之前取
- [`20-tool-response-cache.md`](20-tool-response-cache.md) —— 工具结果的 **Redis + MySQL 两级辅助索引**（旧「同一目录另一套 L1/L2/L3」已过时）；可对照「版本 key `PERSIST`」与「固定 24h TTL」两种生命周期策略
- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— 空值哨兵与预热，缓存穿透的另一面
- [`../02-cache-consistency-race.md`](../../02-cache-consistency-race.md) —— 2.3 节专门分析本文这套 L2 回填的竞态
- [`../01-redis-application-points.md`](../../01-redis-application-points.md) —— 现状盘点表中的「多级缓存 L1/L2/L3」条目
