# LLM 分类结果缓存

> **Redis 考点**：昂贵的外部调用（LLM 分类）结果按「输入指纹」缓存；指纹编码保留字段边界并区分上下文；不承担提示词注入防护；空结果不缓存、TTL 可禁用。
> **来源**：`docs/01-redis-application-points.md` §2.6（其它应用点）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/base/ClassificationCache.java`、`src/main/java/com/fancy/rideagenthub/service/impl/ChatServiceImpl.java`（`resolveClassification`）

---

## 一、业务场景

路由分类模型（`qwen3-max-preview`）每轮普通对话都先查缓存，未命中时才调用，结果只取决于「历史特征 + 上下文文本 + 本轮输入」这一小段文本。相同输入重复计费毫无意义，缓存它们是最直接的降本手段。

与普通缓存相比，它有三个特殊约束：

1. **key 必须带上下文指纹**：分类结果并非只由本轮输入决定——多轮对话下提示词里拼了「上一次路由给谁」和「助理最后的回复」。只用本轮输入作 key，同一句话在「上轮是 ORDER」和「上轮是 DAILY」两种语境下会互相串味；
2. **空结果是瞬时故障不是结论**：分类器返回空 / 空白意味着这次调用出了问题（超时、限流、模型异常），缓存它等于把一次故障固化成持续数小时的错误路由；
3. **要能一键禁用**：排查分类问题时需要绕过缓存直接打模型。

---

## 二、Redis 结构选型

| 项 | 值 |
|---|---|
| key | `chat:classify:{contextFingerprint}:{fingerprint}`（`RedisKeyConstants.chatClassifyKey`） |
| 结构 | String（分类标签） |
| TTL | `chat.guard.classify-cache-ttl-seconds`（默认 21600 秒 = 6 小时）；**≤ 0 时缓存整体禁用** |

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `ClassificationCache.get(ctxFeature, ctxText, prompt)` | `TTL ≤ 0` 直接返回 null（禁用）；读缓存，Redis 异常降级为未命中 |
| `ClassificationCache.put(...)` | **空结果不写**；`TTL ≤ 0` 不写 |
| `ClassificationCache.buildKey` | `"v2" + encode(ctxFeature) + encode(ctxText) + encode(prompt)` 指纹 |
| `ClassificationCache.fingerprint` | SHA-256 截断（长度由 `fingerprintLength` 配置，默认 32，钳制在 [8, hex 长度]） |
| `ClassificationCache.encode` | 长度前缀：`value == null ? "-1:" : value.length() + ":" + value` |
| `ChatServiceImpl.resolveClassification` | 调用方：归一化、模型调用、降级（见 4.2） |

---

## 四、实现拆解

### 4.1 指纹构造：`v2` 长度前缀

```java
String raw = "v2" + encode(ctxFeature) + encode(ctxText) + encode(prompt);
String contextFingerprint = fingerprint("CTXv2" + encode(ctxFeature));
return chatClassifyKey(contextFingerprint, fingerprint(raw));

private String encode(String value) {
    return value == null ? "-1:" : value.length() + ":" + value;
}
```

每个字段编码为「长度:内容」——**长度前缀保留字段边界**：

- 没有分隔符可以被用户输入伪造：使用分隔符时，`("a\0b", "c")` 与 `("a", "b\0c")` 都拼成 `a\0b\0c`；长度前缀分别为 `3:a\0b1:c` 与 `1:a3:b\0c`，能区分字段边界；
- `null` 与空串语义不同（`-1:` vs `0:`），可区分。

`v2` 前缀是版本号：指纹算法将来变化时，新旧 key 天然不冲突。指纹用 **SHA-256**（截断到配置长度）而非 MD5——用户输入是外部可控文本，拿已被攻破的散列处理外部输入是没有必要的将就。

### 4.2 调用方的归一化与降级（`ChatServiceImpl.resolveClassification`）

读侧链路：

1. 查缓存（`get`）——未命中才调模型；
2. 模型结果经 `normalizeClassification` **归一化**：只接受 5 个合法标签（`ORDER` / `DAILY` / `SUPPORT` / `OTHER` / `DANGER`），模型输出其他内容一律按无效处理；
3. 模型调用 try/catch 降级返回 null——上游发送「路由失败，请换种方式问问题。」并结束本轮，不调用 FallbackAgent，也不初始化新会话；
4. 只有「归一化后的非空结果」才 `put` 进缓存（空结果不缓存）。

读上下文的口径：`memory.get(userId, chatId, 100)` 取历史窗口，上下文文本用**助理最后一轮回复**（`lastAssistantText`，直接取最后一条 AssistantMessage 的完整 getText，无截断）——不是用户全部历史（太长、指纹不稳定）。

### 4.3 `TTL ≤ 0` 禁用分支

`get` 与 `put` 开头都检查 `classifyCacheTtlSeconds <= 0`：

- `get` 直接返回 null（永远未命中 → 每轮真实调用模型）；
- `put` 直接 return（不写）。

排查分类问题时把配置调成 0 即可完全旁路缓存，不需要改代码。对应的回归测试（`ChatGuardRegressionTest`）覆盖了禁用分支。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| key 带上下文指纹 | 只用本轮 prompt | 多轮语境下分类结果依赖上文（一的约束 1） |
| 长度前缀编码 | `\0` 等分隔符拼接 | 输入可含分隔符，长度前缀明确字段边界；最终截断哈希仍存在碰撞概率，也不防模型提示词注入 |
| SHA-256 截断 | MD5 | 外部输入 + 已攻破散列 = 习惯性将就 |
| 空结果不缓存 | 缓存空值哨兵（防穿透） | 分类空值是**瞬时故障**而非稳定结论；分类可随时重算，写哨兵反而把故障固化 |
| `TTL ≤ 0` 禁用 | 删代码 / 注释开关 | 运维可配置的旁路；回归测试覆盖 |
| 结果归一化白名单 | 直接缓存模型输出 | 模型输出不可控（可能带前后缀、大小写、编造标签）；白名单收敛到 5 个合法值 |

**关于「空值哨兵防穿透」的对照**：[06 篇](06-null-sentinel-and-prewarm.md) 的 `__NULL__` 适合「DB 查询的稳定空结果」；本篇刻意**不**用——两类空值语义不同（稳定事实 vs 瞬时故障），缓存策略应当相反。

---

## 六、边界与已知问题

1. **命中依赖缓存实际存续**：相同输入三元组生成相同 key，但禁用、Redis 故障、淘汰/删除、过期或缓存值不合法时仍会调模型。key 未含模型版本、提示词版本或完整业务状态，修改这些内容后旧分类可能继续复用到过期，需主动失效或调整 key 版本。
2. **指纹截断的碰撞**：截断到 32 hex 字符（128 bit），生日碰撞概率在工程上可忽略；截断长度可配置（钳制下限 8）。
3. **Redis 异常的降级方向是未命中**（放行真实调用）：缓存是加速手段，Redis 抖动不应让对话直接失败——代价是故障期间全部真实计费。
4. **上下文文本取最后一条助理消息**：更长历史可纳入更多语境，但也会使不同历史更难共享 key。长度前缀仅防不同字段拼接歧义；模型输出仍不确定，缓存不等于分类正确性证明。

---

## 七、如何验证

```bash
# 1. 触发几轮对话后观察分类缓存
redis-cli KEYS "chat:classify:*"
# 期望：chat:classify:{ctx指纹}:{raw指纹}

# 2. TTL 与禁用
redis-cli TTL chat:classify:<ctx>:<raw>          # 期望 ≤ 21600
# chat.guard.classify-cache-ttl-seconds 设为 0 后：新对话不再产生 key

# 3. 语境串味验证：同一句话在不同语境（上轮 ORDER vs DAILY）
#    → 产生两个不同的 key（ctx 指纹不同）

# 4. 空结果不缓存：制造模型调用失败（如断网 mock）
#    → 不产生新 key；恢复后同一输入再次真实调用

# 5. 归一化：模型输出 " order "（带空格/小写）
#    → 缓存与路由行为等价于 ORDER
```

---

## 八、延伸阅读

- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— 空值哨兵与本篇「空结果不缓存」的语义对照
- [`22-sliding-window-rate-limit.md`](22-sliding-window-rate-limit.md) —— 分类调用的前置限流（缓存挡不住的量）
- [`../agent/01-orchestration-and-sse.md`](../agent/01-orchestration-and-sse.md) —— 分类在对话链路中的位置与 DANGER 守卫
- Redis 官方文档：[SET with options](https://redis.io/commands/set/)
