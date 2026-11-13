# Facebook Lease 令牌：当回填本身就是破坏性覆盖

> **Redis 考点**：缓存的 L2 回填使用 `clear + 全量 append` 这一**破坏性覆盖**时，「删除」和「状态标记」都无从下手 —— 唯一的解法是给回填加一道**版本租约**（Lease），用 Lua 保证「版本校验 + 替换」的原子性。
> **来源**：`docs/02-cache-consistency-race.md` 2.3；`docs/02` 第四节演练顺序第 4 步；Facebook *Scaling Memcache at Facebook*（NSDI '13）第 3.2 节 "Leases"
> **完整路径**：`src/main/java/com/fancy/rideagenthub/agentbase/memory/RedisMemory.java`、`MessageMemory.java`、`HeapMemory.java`，`src/main/java/com/fancy/rideagenthub/util/RedisScripts.java`、`constant/RedisKeyConstants.java`
> **代码版本**：当前工作区为 `RideAgentHub` / `com.fancy.rideagenthub`（基准路径 `src/main/java/com/fancy/rideagenthub/`）。本系列撰写于历史快照 `bdc40d7`（旧名 `TaxiAgent`、旧包 `com.fancy.taxiagent`，属已被重写合并的旧分支），文中的 `文件:行号` 与部分代码块是**改造前快照**，整体已漂移；学习时以「类名 + 方法名 + 章节名」定位当前源码，具体行为一律以当前代码为准。

---

## 【当前实现对照 / 本篇勘误】（2026-11-13 代码反向复核）

> 本篇二～八章正文、`文件:行号` 与 `redis-cli` 命令写于改造前快照，**作为缺陷推导教材保留**；凡与本节冲突，一律以本节与当前源码为准。

| # | 正文中的旧说法 | 当前代码事实（`util/RedisScripts.java`） |
|---|---|---|
| 1 | 「五段 Lua 里的 `PERSIST versionKey`（共 6 处调用）」 | 6 处 `PERSIST`（`:177,184,191,200,223,232`）中 **5 处作用于版本 key，`:191` 作用于数据 key**（`APPEND_WITH_VERSION_BUMP` 的 `ARGV[1] <= 0` 分支） |
| 2 | 【当前实现】块把五段脚本概括为 `INCR KEYS[1]; PERSIST KEYS[1]`、注「KEYS[1]=版本 key」 | `OVERWRITE_IF_VERSION_MATCH` 的版本 key 是 **`KEYS[3]`**、`CLEAR_HISTORY_WITH_VERSION_BUMP` 是 **`KEYS[2]`**；`CURRENT_HISTORY_VERSION` 只在分支内 `INCR`，不能一概而论 |
| 3 | 六-5「`fillHeapWithLease` 的 `clear` 分支」 | ✅ 结论已反转：L1 租约失效时**保留并发写入内容**（`MessageMemory.java:196-198`），不再清空 |
| 4 | 一章「写路径同时写三层」与 `memory.get(...,1)` | 当前写序 **L3(MySQL) → L2(Redis) → L1(Heap)**（`MessageMemory.java:100-104`），消息窗口 `get(...,100)`（`ChatServiceImpl.java:101`） |
| 5 | 「三层现已统一用 `getAll`，不再有 `getLastN`」 | `RedisMemory.java:47`、`MysqlMemory.java:27` 仍保留 `getLastN`，只是读路径不再调用 |
| 6 | `CURRENT_HISTORY_VERSION` 描述「清暂存」 | `DEL KEYS[2]` 清的是 `chat:history:{chatId}` **数据 key**（`:171,175`）；`APPEND_HISTORY_IF_VERSION_MATCH` 还要求 `EXISTS KEYS[2] == 1`，缓存缺失时即使版本匹配也不追加 |
| 7 | 「版本 key 与数据 key 同 TTL」的验证期望 | ✅ 已反转为 `PERSIST`（`TTL = -1`）。当前应验证 `TTL chat:history:version:{chatId}` → `-1`，`DEL` 版本 key 后 `CURRENT_HISTORY_VERSION` 重置为 0 |

### 【原文恢复】4.8「`clear` 两条 DEL 非原子」被删的推导（改造前快照）

> 按 `git show HEAD:` 恢复。当前 `clear` 已由 `CLEAR_HISTORY_WITH_VERSION_BUMP`（`RedisScripts.java:230-235`）单段 Lua 原子化，**缺陷已修复**，但推导仍是理解「为什么需要 Lua」的最好例子。

**`clear(chatId)` 的两条 `DEL` 不是原子的**：极端情况下（`DEL` 数据 key 成功、`DEL` 版本 key 失败），会出现「数据已空、版本号仍是旧值」的状态。此时一个手持旧租约的读线程校验会**通过**，把旧快照回填进去 —— 方向上不会丢并发新增的数据（因为数据已经被 `clear` 了），但会把已清空的会话「复活」成旧内容。窗口极小，且 `clearChat` 只在用户主动清空会话时调用。属于已知残留，若要彻底消除需用 Lua 把两条 `DEL` 合并 —— 这正是当前实现采用的做法。

**验证命令补充**（七-3「执行清空历史后」）：`GET chat:history:version:{chatId}`（版本号递增且无 TTL）+ `EXISTS chat:history:{chatId}`（应为 0）。

---

## 一、业务场景

AI 客服的对话上下文按 `chatId` 维护，是一个三层记忆结构：

| 层 | 实现 | 介质 | 生命周期 |
|---|---|---|---|
| L1 | `HeapMemory` | JVM 堆（`ConcurrentHashMap`） | 进程存活期 |
| L2 | `RedisMemory` | Redis List | 聚焦会话 24 小时 / 非聚焦 30 分钟 |
| L3 | `MysqlMemory` | MySQL | 永久 |

读路径是 **L1 → L2 → L3** 逐层回源并回填（`MessageMemory.get:52-76`）；写路径是 **同时写三层**（`MessageMemory.save:95-100`，L1 + L2 是追加、L3 是 insert）。

触发频率决定了这个问题的严重性：

- `ChatServiceImpl.java:97` 每轮对话都调 `memory.get(userId, id, 1)`；
- 四个 Agent（`DailyAgent:199`、`OrderAgent:313`、`SupportAgent:196`、`FallbackAgent:114`）每轮结束都调 `messageMemory.save(...)`。

**也就是说，「读回填」和「并发追加」每轮对话都可能撞上。** 这不是理论上的偶发竞态，而是一个高频路径。

### 旧实现的破坏性：`overwrite = clear + append`

改造前 `RedisMemory` 有一个 `overwrite` 方法，语义是「用给定列表**整体替换**缓存」：

```java
public void overwrite(String chatId, List<Message> messages) {
    if (chatId == null) return;
    clear(chatId);          // ← 先全量清空
    append(chatId, messages);
}
```

而 `MessageMemory` 在读 L2 miss、从 MySQL 拿到快照后，用它回填（旧版 `MessageMemory.get:65`）：

```java
List<Message> mysqlLastN = mysqlMemory.getLastN(chatId, lastN);
if (!mysqlLastN.isEmpty()) {
    redisMemory.overwrite(chatId, mysqlLastN);       // ← 破坏性回填
    ...
}
```

「回填」这个词在这里有误导性。它听起来像是「把缺的补上」，实际做的是「**把现在的内容整个删掉，换成我手里的这份**」。这两者在数据只增不减、且并发写入存在时，差别是**数据丢失**。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 语义 |
|---|---|---|---|
| `chat:history:{chatId}` | List（JSON 字符串） | 聚焦 24 小时 / 非聚焦 30 分钟 | 消息队列，`RPUSH` 追加、`LRANGE` 读取 |
| `chat:history:version:{chatId}` | String（整数） | **`-1`（`PERSIST` 永久保留）** —— 改造前写的是「与数据 key 相同」，这个结论**已反转**，见 4.8 | Lease 版本号，`INCR` 递增、永不归零 |
| `chat:history:staging:{chatId}:{token}` | List（JSON 字符串） | 1 分钟（`STAGING_TTL`） | 回填暂存区，`token` 为 UUID |

前缀常量定义在 `RedisKeyConstants.java:63-73`：

```java
/** 聊天历史版本号 Key 前缀（Lease 令牌机制） */
public static final String CHAT_HISTORY_VERSION_PREFIX = "chat:history:version:";   // :67

/** 聊天历史回填暂存 Key 前缀 */
public static final String CHAT_HISTORY_STAGING_PREFIX = "chat:history:staging:";   // :73
```

三个 key 的组合关系：

```
chat:history:{chatId}            ← 数据（List）。带 TTL
chat:history:version:{chatId}    ← 版本号（租约）。PERSIST 永久保留，见 4.8
chat:history:staging:{chatId}:{uuid}
                                 ← 临时落地区。写完立刻被 Lua 消费（RENAME 或 DEL），
                                    1 分钟 TTL 只是崩溃兜底
```

TTL 常量：

| 常量 | 位置 | 值 |
|---|---|---|
| `FOCUS_CHAT_TTL` | `MessageMemory.java:18` | 24 小时 |
| `NON_FOCUS_CHAT_TTL` | `MessageMemory.java:19` | 30 分钟 |
| `STAGING_TTL` | `RedisMemory.java:34` | 1 分钟 |

> 【当前实现补充】上面两个 TTL 只作用于**数据 key**；版本 key 由 `CURRENT_HISTORY_VERSION`、`APPEND_WITH_VERSION_BUMP`、`APPEND_HISTORY_IF_VERSION_MATCH`、`OVERWRITE_IF_VERSION_MATCH`、`CLEAR_HISTORY_WITH_VERSION_BUMP` 五段 Lua 里的 `redis.call('PERSIST', versionKey)` 永久保留（共 6 处调用）。`RedisMemory.expire()` 现在只给数据 key 设 `PEXPIRE`（毫秒），`clear()` 也不再删版本 key，而是 `INCR` 版本 + 删数据。这是全篇最需要注意的一处反转：原文 4.8 的「同生命周期」推导是**改造前**的设计，保留它正是为了讲清「为什么后来改成永久保留」。

---

## 三、代码落点（按「类名 + 方法名」定位）

> 原表里的 `文件:行号` 与「全类 217 行 / 211 行」都是改造前快照，已整体漂移，不再作为依据。当前一律按下表的方法名检索；脚本清单见 [10 篇](10-lua-scripts-overview.md) 3.1。

### L2：`agentbase/memory/RedisMemory.java`

| 方法 | 职责 | 当前调用的脚本 |
|---|---|---|
| `currentVersion(chatId)` | **取租约**：走 `CURRENT_HISTORY_VERSION` Lua；Java 侧读不到结果会抛 `IllegalStateException`（保守失败），不再「解析失败按 0」 | `CURRENT_HISTORY_VERSION` |
| `appendIfVersionMatch(chatId, msgs, ttlMillis, version)` | **写路径**：版本不符就 `DEL` 数据（让在途租约失效），否则 `RPUSH` + `PEXPIRE` 数据；版本 key `PERSIST` | `APPEND_HISTORY_IF_VERSION_MATCH` |
| `append(chatId, msgs, ttlMillis)` | **回填 / 测试路径**：`INCR` 版本 + `PERSIST` + `RPUSH` + `PEXPIRE`（或 `PERSIST`）数据 | `APPEND_WITH_VERSION_BUMP` |
| `overwriteIfLeaseValid(...)` | 持租约回填：`SessionCallback`/`MULTI` 写暂存 → 执行 `OVERWRITE_IF_VERSION_MATCH`（**2 个 ARGV**，成功时 `INCR` 版本 + `PERSIST` + `RENAME`） | `OVERWRITE_IF_VERSION_MATCH` |
| `expire(chatId, Duration)` | **只给数据 key** 设 TTL；版本 key 不触碰 | — |
| `clear(chatId)` | **只删数据、递增版本**：`INCR` + `PERSIST` + `DEL` 数据，一条 Lua 原子完成 | `CLEAR_HISTORY_WITH_VERSION_BUMP` |
| `getAll(chatId)` | `LRANGE 0 -1` 读全量；三层现已统一用 `getAll`，不再有 `getLastN` 的「最近 N 条」语义 | — |

### Lua 脚本：`util/RedisScripts.java`（当前 5 段）

| 脚本 | 职责 |
|---|---|
| `CURRENT_HISTORY_VERSION`(:169) | 取租约；处理 `EXISTS`/`PTTL` 分支、清暂存、**`PERSIST` 版本 key** |
| `APPEND_HISTORY_IF_VERSION_MATCH`(:197) | 写路径；版本不符 `DEL` 数据，否则追加 + 数据 `PEXPIRE` |
| `APPEND_WITH_VERSION_BUMP`(:182) | 回填路径；`INCR` + **`PERSIST`** + `RPUSH` |
| `OVERWRITE_IF_VERSION_MATCH`(:213) | 暂存 → `RENAME`；成功时 `INCR` 版本并 `PERSIST` |
| `CLEAR_HISTORY_WITH_VERSION_BUMP`(:230) | `INCR` + `PERSIST` + `DEL` 数据 |

### 读 / 写路径：`agentbase/memory/MessageMemory.java`

| 方法 | 职责 |
|---|---|
| `get(userId, chatId, lastN)` | 归属守卫 `chatManager.isNewOwnedChat()`（新会话直接返回空）；三层都 `getAll` 后由 `MessageWindow.tail` 截出完整工具调用批次；持 `heapLease`/`redisLease` 回填 |
| `save(userId, chatId, msgs)` | `assertOwned` → `switchFocusChatIfNeeded` → **先 `mysqlMemory.append`（L3）** → 再按租约追加 L2/L1；若 L2/L1 抛异常则 `heapMemory.clear()` 后上抛 |
| `getPersisted` / `getUserMessage` | 供 Agent 恢复与分类缓存使用；`ChatServiceImpl` 现在用 `memory.get(userId, id, 100)` 而非 `get(..., 1)` |
| `fillRedisWithLease` / `fillHeapWithLease` | 逐级回填；**L1 租约失效时保留并发写入内容**（见 4.7 的反转） |
| `clearChat` | `retireHistory` + MySQL 清理 + `try { redisMemory.clear() } finally { heapMemory.clear() }` |

### L1：`agentbase/memory/HeapMemory.java`

`record Entry(List<Message> messages, long version)`；`currentVersion` / `append` / `overwriteIfVersionMatch` 都在 `ConcurrentHashMap.compute` 内完成「校验 + 替换/追加」，对同一 `chatId` 原子。当前 `overwriteIfVersionMatch` 在租约失效时 `return existing`（**保留**，不再清空）—— 理由见 4.7。

### 新增的相关类

`agentbase/memory/MessageWindow.java`（`tail` 保证工具调用批次完整、拒绝孤立 `ToolResponseMessage`）、`service/base/ChatRequestRunner.java`（单轮执行锁）、`service/base/ChatInfoService.java`（`assertOwned` 404/410）。

---

## 四、实现拆解

### 4.1 竞态形态：这不是脏读，是数据丢失

> 【当前实现对照】下方时序是**改造前**代码（`getLastN` + `overwrite = clear + append`，写顺序 L1→L2→L3）。当前 `MessageMemory.save` 改为 `assertOwned` 归属守卫 → `switchFocusChatIfNeeded` → **先 `mysqlMemory.append`** → 再按租约追加 L2/L1；三层读取都是 `getAll` + `MessageWindow.tail`，`ChatServiceImpl` 用 `memory.get(userId, id, 100)` 而不是 `get(..., 1)`。破坏性 `overwrite` 已不存在，但「回填本身是破坏性覆盖」这一竞态模型仍是理解 Lease 的最佳入口，所以整段时序保留。

```
时间 →
T1 读线程                                            T2 写线程
─────────────────────────────────────────────────────────────────────────────
MessageMemory.get:52  L1 miss
:62  redisMemory.getLastN → 空
:68  mysqlMemory.getLastN
     拿到快照 S = [消息 1..10]
                                                     MessageMemory.save:95
                                                       heapMemory.append(11)
                                                     :97 redisMemory.append(11)
                                                       → INCR 版本 + RPUSH 消息11
                                                     :99 mysqlMemory.append(11)
:71  fillRedisWithLease(S)
     → overwrite(chatId, S)
       = clear + append
       → DEL chat:history:xxx
       → RPUSH 消息 1..10
                    ↑ 消息 11 被整段抹掉，MySQL 里的 11 也救不回来（L2 已经"齐了"）
```

第 4.1 步之后，L2 里是 `[1..10]`，而 MySQL 里是 `[1..11]`。**L2 看起来是完整的**（非空），所以后续读请求会一直从 L1/L2 拿到 `[1..10]`，**永远不会再回源 MySQL**。消息 11 从对话上下文里永久消失。

第二个后果比第一个更隐蔽：T2 的 `save` 明明写成功了（MySQL 里有 11），但用户看到的上下文里没有 11 —— 而且是**持续地**没有，不是一次性的。这就是「脏读」和「丢失」的区别：脏读会自愈（TTL 到期或下次写入修正），丢失不会。

### 4.2 为什么延迟双删和墓碑都解不了

两个机制各自缺一个**必要前提**：

| 机制 | 需要的前提 | 本场景 |
|---|---|---|
| **延迟双删** | 存在「写线程删除了缓存，但被读线程回填覆盖」这个序列 | ❌ 写路径**根本没有删除动作** —— `save` 是 `RPUSH` 追加，不删任何东西。没有第一次删除，就无从谈「第二次删除」 |
| **墓碑** | 存在一个「删除 / 失效」的**动作**可以去标记 | ❌ 同上。更重要的是：问题出在**读路径**（回填是破坏性的），不在写路径。墓碑是给写线程用的信号，而这里写线程没有做错任何事 |

把这两个机制硬套上去会得到荒谬的结果：

- 给 `save` 加「追加后删缓存」—— 那 `save` 自己刚追加的消息立刻没了，**每轮对话都退化成冷读 MySQL**，L2 缓存在写路径上完全失效；
- 给 `save` 写墓碑 —— 墓碑的作用是让读线程「别回填」，可这里读线程要做的事情是「**必须把数据补上**」（L2 是空的），不让它回填只会让 L2 永远空着。

**根因是「回填」这个动作本身带有破坏性。** 前两个机制都是在「回填的结果何时能被接受」上做文章（时机 / 状态），而这里的回填结果**在任何时机都不能被接受** —— 除非能证明「我手里这份快照仍然是最新的」。这就是 Lease 要回答的问题。

### 4.3 Lease 令牌的对应关系

| Facebook Memcache Lease | 本项目实现 | 位置 |
|---|---|---|
| 读 miss 时返回 lease token（而非空值） | `MessageMemory.get` 在任何耗时读取**之前**取 `chat:history:version:{chatId}`（`RedisMemory.currentVersion` → `CURRENT_HISTORY_VERSION` Lua） | `MessageMemory.get` / `RedisMemory.currentVersion` |
| 只有持有效 lease 才能回填 | `overwriteIfLeaseValid(chatId, msgs, leaseVersion)` / `appendIfVersionMatch(...)` | `RedisMemory` |
| 写路径使 lease 失效 | **当前写路径**是 `MessageMemory.save` → `RedisMemory.appendIfVersionMatch` → `APPEND_HISTORY_IF_VERSION_MATCH`：版本不符先 `DEL` 数据，否则 `RPUSH` + `PEXPIRE`；`APPEND_WITH_VERSION_BUMP` 主要在回填/测试路径 | `RedisScripts:182-227` |
| 过期 lease 的回填被拒绝 | 版本不匹配 → 脚本 `DEL` 掉暂存数据并返回 0 → 回填放弃 | `OVERWRITE_IF_VERSION_MATCH` |
| ~~lease 有 TTL，过期后自动回收~~ | **这一条已反转**：版本 key 由 `PERSIST` 永久保留、无 TTL；只有数据 key 有 24h/30min 生命周期。旧实现「`expire` 同时续期两者」正是 4.8 要纠正的设计 | 五段历史脚本中的 6 处 `PERSIST` |

> 【当前读路径】三层现在都读**全量**（`getAll`），再交给 `MessageWindow.tail` 截取末尾的完整工具调用批次 —— 因为 Spring AI 的 `ToolResponseMessage` 不能与它的 `AssistantMessage`（含 tool call）被拆开，否则模型会看到「孤立的工具结果」。伪代码：
>
> ```java
> if (chatManager.isNewOwnedChat(userId, chatId)) return List.of();   // 归属守卫
> long heapLease  = heapMemory.currentVersion(chatId);
> long redisLease = redisMemory.currentVersion(chatId);                // 取租约
> List<Message> heapAll = heapMemory.getAll(chatId);
> if (!heapAll.isEmpty())  return MessageWindow.tail(heapAll, lastN);
> List<Message> redisAll = redisMemory.getAll(chatId);
> if (!redisAll.isEmpty()) { fillHeapWithLease(...); return tail(redisAll, lastN); }
> List<Message> mysqlAll = mysqlMemory.getAll(chatId);                  // 权威源
> if (!mysqlAll.isEmpty()) { if (fillRedisWithLease(...)) fillHeapWithLease(...); return tail(mysqlAll, lastN); }
> ```
>
> 「取租约必须在耗时读取之前」这一原则完全没变；变的是读的形状（全量 + 窗口）、归属守卫、以及写顺序（先 MySQL 再 L2/L1）。

下面是**改造前** `MessageMemory.java:57-74` 的读路径代码，保留作对照（注意 `getLastN` 已不存在）：

```java
// 取租约：必须在任何"耗时读取"之前捕获版本号。
// 若读 L2/MySQL 期间有 save 落库，版本号会变化，本次回填随即被拒绝。
long heapLease = heapMemory.currentVersion(chatId);
long redisLease = redisMemory.currentVersion(chatId);

List<Message> redisLastN = redisMemory.getLastN(chatId, lastN);
if (!redisLastN.isEmpty()) {
    fillHeapWithLease(chatId, redisLastN, heapLease);
    return redisLastN;
}

List<Message> mysqlLastN = mysqlMemory.getLastN(chatId, lastN);
if (!mysqlLastN.isEmpty()) {
    // 持租约回填：宁可放弃回填（下次再查一次 DB），也不覆盖掉并发写入的新消息
    fillRedisWithLease(chatId, mysqlLastN, redisLease);
    fillHeapWithLease(chatId, mysqlLastN, heapLease);
    return mysqlLastN;
}
```

**「必须在任何耗时读取之前捕获」是这套机制的全部要害。** 如果在查完 MySQL 之后才取版本号，那么「查 MySQL」这个窗口就没有被租约覆盖 —— 恰恰是这段窗口里 T2 提交了追加，而 T1 捕获到的版本号是**更新后**的，校验反而会通过，回填照样覆盖。

再看竞态重放（对比 4.1）：

```
T1  get:59-60  捕获 redisLease = 5
T2  save:97    INCR → 版本变成 6，同时 RPUSH 消息 11
T1  :71        overwriteIfLeaseValid(chatId, S, 5)
               → Lua: current(6) ~= ARGV[1](5) → DEL 暂存 → return 0
               → 回填被拒绝，L2 保持 [1..11] 不变
```

**关键点：回填被拒绝不是失败，而是正确行为。** 注释（`:70`）把它写成了设计原则：

> 宁可放弃回填（下次再查一次 DB），也不覆盖掉并发写入的新消息

代价是「下次读 L2 仍然 miss，会再查一次 MySQL」—— 一次多余的 DB 查询。相比「永久丢失一条消息」，这个代价可以忽略。

### 4.4 为什么回填要绕一次暂存 key

> 【当前实现】「暂存 key + `RENAME` O(1)」这套思路完全保留，下方推导仍然是最值得读的部分。两处细节变化：
> - 写暂存不再靠「`rightPushAll` + `expire` 两条独立命令」：Java 侧用 `SessionCallback` / `MULTI` 把「写暂存 + 执行回填脚本」收进一次 Redis 交互，减少中间态暴露；
> - `OVERWRITE_IF_VERSION_MATCH` 现在有 **2 个 ARGV**（租约版本号 + 数据 TTL 毫秒），且成功分支会 `INCR` 版本并 `PERSIST` 版本 key —— **回填成功后主动让所有在途租约失效**，否则「刚回填完又被另一个旧快照覆盖」。
>
> `STAGING_TTL = 1min` 的崩溃兜底、三个出口（`-1` 暂存不存在 / `0` 租约失效 / `1` 成功）都不变；下方代码块与「只传一个版本号」是改造前版本。

`RedisMemory.java:137-149`：

```java
String stagingKey = RedisKeyConstants.chatHistoryStagingKey(chatId, UUID.randomUUID().toString());
try {
    // 1. 待回填数据写入暂存 key。此步失败只影响本次回填，不触碰线上数据。
    redisTemplate.opsForList().rightPushAll(stagingKey, jsonList);
    redisTemplate.expire(stagingKey, STAGING_TTL);

    // 2. 版本校验 + 原子替换。租约失效时由脚本丢弃暂存数据。
    Long result = redisTemplate.execute(
            RedisScripts.OVERWRITE_IF_VERSION_MATCH,
            List.of(stagingKey,
                    RedisKeyConstants.chatHistoryKey(chatId),
                    RedisKeyConstants.chatHistoryVersionKey(chatId)),
            String.valueOf(leaseVersion));
```

**为什么不能直接把消息列表作为 `ARGV` 传给 Lua？** 因为消息列表**可能很大**：

- `getUserMessage`（`MessageMemory.java:106-145`）走的是 `redisMemory.getAll(chatId)` —— `LRANGE 0 -1`，**消息量不可控**，一个长期对话可能是几千条；
- Lua 的 `ARGV` 是随 `EVAL` 一起发送的字符串数组，Redis 需要先把整个脚本调用**缓冲在内存里**才能执行。几千条 JSON 串成一个 `EVAL` 请求，会：
  - 在客户端产生一个巨大的请求体（网络 + 序列化开销）；
  - 在 Redis 侧占用一份与数据等量的临时内存；
  - 受 `proto-max-bulk-len` 等协议限制约束。

**暂存 key 把「数据搬运」和「原子性保证」分开了**：

```
RPUSH → staging（普通命令，可以分批、可以很大）
EVAL  → 只传三个 key 名 + 一个版本号（极小）
        脚本内部做 DEL + RENAME（O(1) 的元数据操作，不搬数据）
```

`RENAME` 是 O(1) 的 —— 它只改 key 的元数据指针，**不复制 value**。所以整个回填过程无论消息列表多大，Lua 脚本的入参始终是一个版本号字符串。

`STAGING_TTL = Duration.ofMinutes(1)`（`RedisMemory.java:34`）是崩溃兜底：

> 正常路径下暂存 key 会被 Lua 脚本消费（RENAME 或删除）；若进程在写入暂存后崩溃，靠此 TTL 自清理，避免残留。

正常路径下暂存 key 一定被消费 —— `OVERWRITE_IF_VERSION_MATCH` 的两条出口都消费了它：

| 出口 | 对暂存 key 的操作 | 行号 |
|---|---|---|
| 版本匹配 → 回填成功 | `RENAME` 到数据 key（消费） | `RedisScripts.java:116` |
| 版本不匹配 → 拒绝 | `DEL` 暂存（丢弃） | `RedisScripts.java:112` |
| 暂存 key 不存在 | 返回 -1，什么都不做 | `RedisScripts.java:104-106` |

`RedisMemory.java:157-165` 的 `catch` 分支还会尽力手工删一次暂存 key（「TTL 是兜底」）。

### 4.5 为什么版本递增必须与数据追加在同一次原子操作内

> 【当前实现】「`INCR` 必须在 `RPUSH` 之前、且必须在同一次 EVAL 内」这个结论**完全不变**，下方时序推导原样保留。变化在于脚本被拆成两条、参数语义不同：
>
> - **写路径**：`MessageMemory.save` → `RedisMemory.appendIfVersionMatch` → `APPEND_HISTORY_IF_VERSION_MATCH`。版本不符时脚本会 `DEL` 数据 key（让在途租约必然失效），否则 `RPUSH` + `PEXPIRE` 数据。
> - **回填 / 测试路径**：`RedisMemory.append` → `APPEND_WITH_VERSION_BUMP`（下方 Lua 的当前版本）。
> - **`ARGV[1]` 现在是「数据 key 的 TTL 毫秒」**，不是旧文档说的「版本 key 过期秒数」：`> 0` 时 `PEXPIRE` 数据 key，`<= 0` 时 `PERSIST` 数据 key；**版本 key 一律 `PERSIST`**，永远不 `EXPIRE`。
>
> 所以下方代码块里 `redis.call('EXPIRE', KEYS[1], ARGV[1])`（给版本 key 设过期）是改造前写法，今天已经不存在；把它与当前实现对照，正好能看懂 4.8 为什么要反转结论。

`RedisScripts.java:62-66` 的注释：

> 版本号与数据必须在同一次原子操作中更新。否则存在如下窗口：数据已追加、版本尚未递增时，一个手持旧租约的读线程仍能通过版本校验，用旧快照把刚追加的消息覆盖掉。

如果把 `append` 拆成两条独立命令：

```java
redisTemplate.opsForList().rightPushAll(key, jsonList);        // ① 追加数据
redisTemplate.opsForValue().increment(versionKey);             // ② 递增版本
```

那么 ① 与 ② 之间存在一个窗口，重放 4.1 的时序：

```
T1  get:60   捕获 redisLease = 5
T2  save     ① RPUSH 消息 11        ← 数据已进 L2
T1  :71      overwriteIfLeaseValid(S, 5)
             → 版本仍是 5，校验【通过】！
             → DEL 数据 key（把 [1..11] 删掉）
             → RENAME 暂存 → 数据变成 [1..10]
T2  save     ② INCR → 版本 6        ← 太晚了，数据已经被抹掉
T1  :71      回填"成功"，但消息 11 已丢失
```

注意这个窗口比 4.1 那个**更隐蔽**：T1 的租约校验**是通过的**，日志上看不出任何异常，回填还返回了 `true`。表面上一切都对，数据却丢了。

所以 Lua 脚本里 `INCR` 必须放在 `RPUSH` **之前**（`RedisScripts.java:76-83`）：

```lua
local version = redis.call('INCR', KEYS[1])
if tonumber(ARGV[1]) > 0 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
for i = 2, #ARGV do
    redis.call('RPUSH', KEYS[2], ARGV[i])
end
return version
```

`INCR` 在前、`RPUSH` 在后，而且整个脚本在 Redis 内单线程原子执行 —— 从外部看，「版本号变了」和「消息可见了」是**同一个瞬间**发生的。不存在「消息可见但版本未变」的中间态。

> 顺带一个细节：`EXPIRE` 也放在了脚本里（`RedisScripts.java:77-79`）。这与 `ChatManager` 的 `HSET + EXPIRE` 是同一类问题 —— 两条命令之间崩溃会留下永不过期的 key。`ARGV[1] <= 0` 时不设过期，用于「版本 key 不随数据过期」的场景。

### 4.6 L1 为什么也要租约

只修 L2 是不够的。`MessageMemory.java:170-171` 的注释：

> L1 是优先读取的层，若在此处覆盖掉并发 save 刚追加的消息，后续读取会被脏 L1 短路，L2 修得再好也救不回来 —— 故 L1 同样必须校验租约。

L1 是**最先被读到的层**（`MessageMemory.get:52-55`：`heapAll` 非空就直接返回）。假设 L1 的回填不校验租约：

```
T1  查 MySQL 得到 S = [1..10]
T2  save → heapMemory.append(11)
T1  heapMemory.overwrite(chatId, S)    ← L1 变成 [1..10]
后续所有读 → L1 命中 [1..10] → 直接返回，连 L2 都不看
           → L2 里明明有正确的 [1..11]，却永远读不到
```

**L1 的数据丢失比 L2 更致命**，因为 L1 短路了整条回源链路。`HeapMemory` 因此也引入了版本号（`HeapMemory.java:28-29` 的 `Entry` 记录），用 `ConcurrentHashMap.compute` 保证「校验 + 替换」对同一 `chatId` 原子（`:91-98`）：

```java
heapCache.compute(chatId, (key, existing) -> {
    long current = existing == null ? 0L : existing.version();
    if (current != expectedVersion) {
        return existing;          // 租约失效 → 原样返回，不覆盖
    }
    written[0] = true;
    return new Entry(new ArrayList<>(messages), current + 1);
});
```

**L1 与 L2 分居两个进程内/外的存储，不可能用同一个原子操作覆盖**，所以它们是两份独立的租约（`heapLease` 与 `redisLease`，`MessageMemory.java:59-60`），各自校验、各自可能失效。

### 4.7 L1 租约失效时：结论反转为「保留」（改造前是「清空」）

> 【当前实现 · 结论反转】`HeapMemory.overwriteIfVersionMatch` 在租约失效时 **`return existing`（保留）**，`MessageMemory.fillHeapWithLease` 也不再调 `heapMemory.clear(chatId)`。下方原文给出的「清空」推导在**旧写顺序**下是对的，但前提已经被改掉：
>
> | | 改造前 | 当前 |
> |---|---|---|
> | 写顺序 | 先写 L1/L2，再落 MySQL | **先落 MySQL(L3)，再按租约追加 L2/L1** |
> | L1 的内容 | 可能只有并发追加的那几条（`[11]`），是残缺快照 | `append` 追加到已有列表尾部，包含并发写入的最新消息，不是残缺快照 |
> | 清空后果 | 无害：下次从权威 L2 重新加载 | **会丢掉刚写入 L1 的新消息** |
>
> 因此当前选择「保留并发写入内容」，让 L1 要么是完整上下文、要么包含最新追加；后续由 `MessageWindow.tail` 与 L2/MySQL 校正。这也说明一个通用道理：**缓存一致性结论依赖于写顺序，写顺序一改，原来的正确结论可能整个反过来**。见 [19 篇](19-multi-level-cache.md) 的读写顺序对照。

以下为改造前的原文（保留推导，标题中的「清空」已不适用于当前代码）：

`MessageMemory.java:179-183`：

```java
private void fillHeapWithLease(String chatId, List<Message> messages, long leaseVersion) {
    if (!heapMemory.overwriteIfVersionMatch(chatId, messages, leaseVersion)) {
        heapMemory.clear(chatId);
    }
}
```

注意失败分支的动作是 **`clear`**，而不是「什么都不做」。注释（`:173-175`）解释了：

> 租约失效时清空 L1：此刻 L1 的内容不完整（可能只含并发追加的那几条），保留它反而会让后续读取拿到残缺上下文；清空后下次读取会从 L2 重新加载，而 L2 是权威的。

这个理由需要想一下才通。假设 T2 的 `save` 只往 L1 追加了消息 11（`heapMemory.append:95`），此时 L1 = `[11]` —— 这是个**残缺的上下文**（缺了 1..10）。如果保留它，下一次 `get` 会在 `:52-55` 检查 `heapAll` 非空 → **直接返回 `[11]`**，把残缺当完整用。

清空 L1 后，下一次读会 miss L1 → 走 L2（`[1..11]`，权威）→ 持新租约正确回填 L1。**代价是一次 L2 读取，换来的是「L1 要么完整、要么为空」这个不变式。**

### 4.8 版本 key 必须永久保留（`PERSIST`），而不是与数据同生命周期 —— 结论完全反转

> 本节原来的结论「版本 key 与数据 key 必须同 TTL」是**改造前**的设计。当前代码已在五段历史脚本的 **6 处**调用 `redis.call('PERSIST', KEYS[1])`，让版本 key 永不归零。下面先讲当前实现与反转原因，再把原推导完整保留作「为什么当初会那样想」的对照。

**当前实现**（`util/RedisScripts.java`：`CURRENT_HISTORY_VERSION`、`APPEND_WITH_VERSION_BUMP`、`APPEND_HISTORY_IF_VERSION_MATCH`、`OVERWRITE_IF_VERSION_MATCH`、`CLEAR_HISTORY_WITH_VERSION_BUMP`）：

```lua
-- 五段脚本的共同动作（KEYS[1] = chat:history:version:{chatId}）
redis.call('INCR', KEYS[1])
redis.call('PERSIST', KEYS[1])          -- 版本 key 去掉 TTL，永不归零
```

- `RedisMemory.expire(chatId, duration)` **只给数据 key** `PEXPIRE`（24h / 30min），不再碰版本 key；
- `RedisMemory.clear(chatId)` 走 `CLEAR_HISTORY_WITH_VERSION_BUMP`：`INCR` 版本 + `PERSIST` + `DEL` 数据 —— **只删数据、递增版本**，不再是两条 `DEL`；
- `redis-cli TTL chat:history:version:{chatId}` 恒为 **`-1`**；数据 key 的 PTTL ≤ 86400000。

**为什么必须反转**：旧方案让两个 key 同生共死，但「同生命周期」并不能保证版本号单调 —— 因为 `clear()` 会**同时删掉两个 key**，把版本归零：

```
T1  捕获 redisLease = 1（会话刚创建，版本号 1），开始耗时读 MySQL
T2  clearChat() → 旧实现 DEL 数据 key + DEL 版本 key → 版本归零
T3  save → INCR 新建版本 key → 值又变成 1
T1  overwriteIfLeaseValid(S, 1)
    → current(1) == 1 → 校验【通过】！
    → 已清空会话被旧快照"复活"
```

数据 key 与版本 key **一起 TTL 到期**时也一样：`INCR` 从 1 重新开始，而某个在途租约恰好可能就是 1。原推导只分析了「版本 key 先过期」和「归零后从 1 开始不会撞上 0 租约」，漏掉的正是「在途租约 = 1」这一支。只要版本 key 永不过期、且 `clear` 改为**递增而非删除**，版本号对同一 `chatId` 就是全局单调递增的，任何旧租约都不可能重新匹配。

**边界与代价（面试常问）**：版本 key 无 TTL 意味着它不会随会话数据一起被回收，Redis 中该前缀的 key 数随历史 `chatId` 单调增长。这是用少量内存换「租约时钟绝不倒转」的取舍。可选优化是定期扫描「长期无数据且无写入」的版本 key 并清理，但清理本身必须与在途租约协调，否则又会把版本号重置到小值 —— 这正是当前代码**不做自动清理**的原因。不要把这个方向写成已实现能力。

**「版本号是租约的时钟」—— 时钟可以停，但不能倒转。** 这句话依然成立，只是实现从「两个 key 同生共死」改成了「版本 key 永不死」。

---

**改造前原文（保留推导，作动机对照）**

`RedisMemory.java:169-177` 当时同时给两个 key 设过期：

```java
public void expire(String chatId, Duration duration) {
    if (chatId == null || duration == null) {
        return;
    }
    redisTemplate.expire(RedisKeyConstants.chatHistoryKey(chatId), duration);
    // 版本号与数据保持同一生命周期：版本 key 若先过期会归零，
    // 使在途租约被误判为有效
    redisTemplate.expire(RedisKeyConstants.chatHistoryVersionKey(chatId), duration);
}
```

如果只给数据 key 续期、让版本 key 单独过期，会发生什么：

```
T1  捕获 redisLease = 5
    版本 key 过期（数据 key 还在）
T2  save → INCR → 版本键被重新创建，值 = 1
T1  overwriteIfLeaseValid(S, 5)
    → current(1) != 5 → 拒绝
```

这个方向是安全的（拒绝回填）。当时担心的是反方向 —— **如果版本号「归零」后恰好等于某个在途租约的值**：

```
T1  捕获 redisLease = 0（版本 key 不存在时 currentVersion 返回 0）
    ...耗时读取...
    save 了若干次，版本到 7
    版本 key 过期
T2  save → INCR 新建版本 key → 值 = 1
T1  overwriteIfLeaseValid(S, 0) → current(1) != 0 → 拒绝 ✓
```

`currentVersion` 当时把「key 不存在」映射成 `0`（现在改由 `CURRENT_HISTORY_VERSION` Lua 处理，Java 侧读不到结果抛 `IllegalStateException`，属于**保守失败**），而 `INCR` 从 1 开始，所以「归零后重建」不会与 `0` 租约撞上。真正的风险是**版本号被重置到一个小值后与在途租约重叠** —— 旧方案认为「只要两个 key 生命周期严格一致，版本号就单调递增」，这个前提被 `clear()` 同时删两个 key 打破了。

`clear` 当时同样删两个 key：

```java
public void clear(String chatId) {
    if (chatId == null) {
        return;
    }
    redisTemplate.delete(RedisKeyConstants.chatHistoryKey(chatId));
    redisTemplate.delete(RedisKeyConstants.chatHistoryVersionKey(chatId));
}
```

两条 `DEL` 还带来第二个问题：它们**不是原子的**（六-3 的旧缺陷），极端情况下会留下「数据已空、版本仍旧」。当前 `CLEAR_HISTORY_WITH_VERSION_BUMP` 用一条 Lua 同时解决原子性与版本归零。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| **版本号 + 拒绝回填** | 回填时合并（union）而非替换 | 需要按消息身份去重，而消息没有稳定 ID（`Message` 是 Spring AI 的对象，靠 JSON 序列化存储）。且合并的语义在「删除过消息」的场景下同样错 |
| **版本号** 而非**时间戳** | 用 `System.currentTimeMillis()` 当 lease | 多实例时钟不同步；同一毫秒内的两次写入无法区分。`INCR` 由 Redis 单点产生，全局单调 |
| 暂存 key + `RENAME` | 消息列表作为 `ARGV` 传入 Lua | 消息量不可控（`getUserMessage` 走 `getAll`）。`RENAME` 是 O(1)，不搬数据。见 4.4 |
| `INCR` 与 `RPUSH` 同脚本 | 两条独立命令 | 否则存在「数据已追加、版本未递增」的窗口。见 4.5 |
| **版本 key `PERSIST` 永久保留**（当前实现） | 与数据 key 同 TTL（改造前）；单独设更长 TTL | 同 TTL 会被 `clear()` 同时删除或一起过期而归零，旧租约可能撞上重置后的小版本号「复活」；永久保留后版本号全局单调。见 4.8 |
| L1 也引入版本号 | 只修 L2 | L1 会短路整条回源链路。见 4.6 |
| L1 租约失效时**保留并发写入内容**（当前实现） | 清空（改造前） | 写顺序已改为「先 MySQL，再按租约追加 L2/L1」，L1 里是包含最新追加的列表而非残缺快照，清空反而丢消息。见 4.7 |
| `currentVersion` 读不到结果时**抛 `IllegalStateException`**（保守失败） | 解析失败按 `0` 处理（改造前） | 版本号是正确性依赖，读不到时不应「猜一个 0」继续回填；`CURRENT_HISTORY_VERSION` Lua 统一处理 `EXISTS`/`PTTL` 分支，Java 侧对空结果保守失败。见 4.8 |

### 更轻的替代方案：按消息数增量补齐尾部

既然数据形态是「只追加」，可以完全不用版本号，改成**按长度增量补齐**：

```java
// L2 已有 n 条，MySQL 快照有 m 条（m > n）→ 只 RPUSH 第 n+1..m 条
// m <= n → 说明 L2 比快照还新（或一样新），直接返回，什么都不做
```

这个方案的优点是**简单**：不需要版本 key、不需要暂存 key、不需要两个 Lua 脚本，`LLEN` + `LRANGE` 就够了。而且它从根本上避开了「覆盖」这个动作 —— **不覆盖，就没有覆盖竞态**。

但本项目仍然选了 Lease，原因有两条：

1. **增量补齐只适用于「只追加、不修改、不删除」的数据形态。** 一旦数据可以被修改（比如某条消息被编辑、被撤回），「第 n+1..m 条」这个位置语义就崩了 —— MySQL 里的第 5 条可能已经被改过，而 L2 里的第 5 条是旧值，补齐尾部永远修不到它。本项目当前的消息确实是只追加的，但这个约束是**隐含的、脆弱的**：`MessageMemory` 还提供 `clearChat`（`:147-154`），未来还可能加「删除单条消息」。

2. **Lease 是一个通用原语。** 它回答的是「我手里的这份快照还能不能用」，与数据形态无关。学会它之后，换成任何会被修改的缓存都能直接用；而增量补齐是一份「针对只追加数据的特化代码」，换个场景就得重写。

作为教学，Lease 更能体现原理 —— 这也是它被放在演示顺序第 4 步（最后一步）的原因：它是这四种机制里唯一**必须依赖原子版本校验**的。

---

## 六、边界与已知问题

1. **回填被拒时没有 single-flight**。租约失效后 L2 保持为空（或保持旧值），下一次读会再查一次 MySQL。如果此时有 N 个并发读同时 miss 且同时被拒，会有 N 次 MySQL 查询（而不是一次）。这是一个**性能**问题，不是正确性问题；本项目未引入请求合并（single-flight），属于已知缺口。

2. **回填被拒时读线程仍然返回 MySQL 快照**（`MessageMemory.java:71-73`）。这是刻意的：MySQL 是权威源，即使回填失败，本次读的结果也应该是正确的。被拒的只是「把这份快照**写进缓存**」这个动作。

3. ✅ **已修复：`clear(chatId)` 不再是两条 `DEL`**。当前走 `CLEAR_HISTORY_WITH_VERSION_BUMP` 一条 Lua：`INCR` 版本 + `PERSIST` 版本 key + `DEL` 数据 key，原子完成。既消除了「数据已空、版本仍旧，旧快照把会话复活」的窗口，也避免了版本号归零（旧的六-3 与 4.8 是同一个根因）。回归测试 `RedisCacheRegressionTest` 覆盖清空后版本仍递增、数据为空。

4. **`getUserMessage` 走 `getAll`（`LRANGE 0 -1`）**（`MessageMemory.java:122`、`:127`），读取整个会话的全部消息。配合 `getAll` 的无界性，这个方法的成本随对话长度线性增长。Lease 解决了正确性问题，没有解决这个读取成本问题。

5. **L1 与 L2 的租约互不相关**。`heapLease` 与 `redisLease` 各自独立失效（`MessageMemory.java:59-60`）。可能出现「L2 回填成功、L1 回填被拒」的组合 —— 这正是 `fillHeapWithLease` 的 `clear` 分支要处理的场景（见 4.7）。

6. **版本号没有上界检查**。`INCR` 在 `long` 溢出的理论边界上会回绕（Redis 的整数是 64 位有符号）。实际不可能达到，但代码里没有防御。

---

## 七、如何验证

```bash
# 1. 观察三个 key 的关系
redis-cli KEYS "chat:history:*"
# 期望看到 chat:history:{chatId} 与 chat:history:version:{chatId}
# 以及偶发的 chat:history:staging:{chatId}:{uuid}（正常应立刻被消费）

# 2. 版本号随每次 save 递增
redis-cli GET chat:history:version:{chatId}     # 例如 "5"
# 调一次对话接口（会触发 save）
redis-cli GET chat:history:version:{chatId}     # 期望 "6"

# 3. 版本 key 永久保留（当前实现；旧「同生命周期」期望已作废）
redis-cli TTL  chat:history:{chatId}            # 例如 86390（数据 key 有 TTL）
redis-cli TTL  chat:history:version:{chatId}    # 期望 -1 —— PERSIST，不是与上面接近
redis-cli PTTL chat:history:{chatId}            # 期望 ≤ 86400000（24h）/ ≤ 1800000（非聚焦 30min）
# 执行清空历史后：版本 key 仍存在且值继续递增，数据 key 被删除

# 4. 手工构造"租约失效"：把版本号改掉，模拟并发 save
redis-cli SET chat:history:version:{chatId} 9999
# 此时若有一个持旧租约的读线程尝试回填，会被拒绝。
# 观察应用日志中的 DEBUG 行：
#   "回填租约已失效，放弃覆盖: chatId=..., leaseVersion=..., result=0"

# 5. 暂存 key 的正常生命周期：回填成功时应被 RENAME 消费（不是残留）
redis-cli EXISTS chat:history:staging:{chatId}:xxxxx    # 期望 0（已被 RENAME 或 DEL）
redis-cli TTL chat:history:staging:{chatId}:xxxxx       # 若存在，期望 <= 60（兜底 TTL）

# 6. 端到端验证（需完整环境）：
#    并发发起「一轮对话（触发 get）+ 一次 save」，检查 L2 的 LLEN 与 MySQL 的 count(*) 是否一致。
#    LLEN 应 >= MySQL count，绝不应小于 —— 小于就说明发生了 4.1 的丢失。
redis-cli LLEN chat:history:{chatId}
```

> 说明：本项目的两个测试（`RideAgentHubApplicationTests`、`MongoDbTest`）都是 `@SpringBootTest` 集成测试，需要 MySQL / Redis / MongoDB / ES 全套环境。上面第 6 条的并发验证需要手工构造。

---

## 八、延伸阅读

- [`07-delayed-double-delete.md`](07-delayed-double-delete.md) —— 为什么「第二次删除」在这里无从下手（问题在回填而不是删除）
- [`08-city-code-tombstone.md`](08-city-code-tombstone.md) —— 为什么「状态标记」在这里也无从下手（写路径没有可标记的动作）
- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— 状态编码的另一种形态；`__NULL__` 与本篇的版本号都是「把状态放进 value」
- [`19-multi-level-cache.md`](19-multi-level-cache.md) —— L1/L2/L3 的整体设计与逐级回填策略；本篇只展开其中的竞态部分
- [`10-lua-scripts-overview.md`](10-lua-scripts-overview.md) —— 五段历史 Lua 在**当前 22 个主脚本**中的位置，以及 11 个内联脚本的清单（旧「7 个脚本」已过时）
- [`12-chat-lock-atomic.md`](12-chat-lock-atomic.md) —— 同一个 `chat:info` 结构上的 `HSET + EXPIRE` 原子化；版本 key 的 `PERSIST` 与会话 key 的 `persist` 是两件不同的事，不要混淆
- [`23-ticket-pool-zset.md`](23-ticket-pool-zset.md) —— `ticket:pool:version` 同样无 TTL，是「版本令牌永久保留」这一模式的第二处落地
- [`README.md`](README.md) —— 术语与状态标注规范、跨文档引用枢纽
- [`13-distributed-lock.md`](13-distributed-lock.md) —— `RELEASE_LOCK_IF_MATCH`：另一处「校验 + 动作」的原子合并
- Facebook *Scaling Memcache at Facebook*（NSDI '13），第 3.2 节 "Leases" —— Lease 令牌的原始论文
- [`../../02-cache-consistency-race.md`](../../02-cache-consistency-race.md) —— 2.3 节的原始盘点（行号已漂移，以代码为准）
