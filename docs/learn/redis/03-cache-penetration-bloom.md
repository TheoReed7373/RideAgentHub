# 缓存穿透：布隆过滤器

> **Redis 考点**：Redisson `RBloomFilter` 拦截「一定不存在」的查询，保护数据库不被恶意 / 高频的不存在 key 查询打穿。
> **来源**：`docs/01-redis-application-points.md` §2.2（缓存三大经典问题）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/base/UserUsernameBloomFilterService.java`

---

## 一、业务场景

缓存穿透与击穿 / 雪崩的区别：**穿透查询的是「数据库里也不存在」的数据**。缓存无法存一个不存在的值（除非用哨兵，见 [06 篇](06-null-sentinel-and-prewarm.md)），于是每次请求都直捣数据库——攻击者拿随机用户名刷注册查重接口，就能把 DB 打挂。

本项目的场景是**用户名占用查询**：注册时用户输入用户名，前端实时提示「是否已被占用」。这个查询：

- 输入完全由外部控制（任意字符串）；
- 大部分输入是「不存在」的（正常用户试的名字基本没被注册过）；
- 有现成的全量数据可加载（用户名集合）。

正是布隆过滤器的标准适用面：**把全量「存在」的集合放进内存可负担的概率结构，用 O(1) 判断「一定不存在」**。

当前代码中的角色划分：

| 调用点 | 用途 |
|---|---|
| `AuthServiceImpl` 注册流程 | `addUsername`——新用户名落库后登记进布隆 |
| `POST /user/rebuild`（ADMIN） | `rebuild`——全量重建 |
| `isUsernameAvailable`（`AuthServiceImpl`） | **直查数据库**，布隆不在可用性主链路上（见 4.3） |

---

## 二、Redis 结构选型

| 项 | 值 |
|---|---|
| 结构 | Redisson `RBloomFilter<String>` |
| key | `RedisKeyConstants.userUsernameBloomKey()` |
| 容量 | `EXPECTED_INSERTIONS = 1000000`（100 万） |
| 误判率 | `FALSE_PROBABILITY = 0.01`（1%） |

布隆过滤器的误判是**单向**的：

- 判「不存在」→ **一定不存在**（无假阴性）；
- 判「存在」→ 可能不存在（1% 假阳性）。

所以它只能用在「拦截不存在」的方向：判不存在直接回「可用」，判「存在」仍要查库确认。反过来说，**它永远不会把一个已存在的用户名判成「不存在」**（除非数据没进来，见 4.2）。

---

## 三、代码落点

| 方法 | 职责 |
|---|---|
| `mightContain` | `getBloomFilter().contains(username)`；**当前无生产调用方** |
| `addUsername` | `getBloomFilter().add(username)`，注册落库后调用 |
| `rebuild` | `delete` → `tryInit` → `selectUsernamesForBloom` 全量回灌，返回登记数 |
| `getBloomFilter` | `getBloomFilter(key)` + `tryInit`（幂等初始化，未初始化时惰性建） |

---

## 四、实现拆解

### 4.1 参数推导：100 万 / 1% 意味着什么

误判率 \(p\) 与元素数 \(n\)、位数 \(m\)、哈希数 \(k\) 的关系：\(m \approx -n \ln p / (\ln 2)^2\)。代入 \(n = 10^6\)、\(p = 0.01\)：约 **9.6 Mbit ≈ 1.2 MB**、7 个哈希函数。这是一个可以被任何实例整份缓存的体积，也是「全量加载」可行的前提。

`tryInit` 幂等：已初始化的过滤器直接返回，参数只第一次生效——重建时要先 `delete` 才能换参数。

### 4.2 重建：原地删除 + 全量回灌

`rebuild` 的顺序是 `delete → tryInit → 回灌`。`delete` 到回灌完成之间存在窗口：**窗口内 `contains` 会把已注册的用户名判为「不存在」（假阴性）**。这是布隆「无假阴性」性质在重建期的例外——不是概率问题，是数据缺失问题。

后果评估：用户名可用性检查**直查数据库**（见 4.3），并发注册重名由数据库生成列唯一索引 `uk_active_username_role` 裁决（`DuplicateKeyException` → 409）。当前 `mightContain` 无生产调用方，重建期缺失不会改变可用性查询的结果或查询次数；并发注册仍由唯一索引裁决。

若要彻底消除窗口，标准做法是**双 key 切换重建**：新过滤器写满后原子改名。当前未采用——重建是低频 ADMIN 操作，DB 唯一索引已兜底，复杂度不值得。

### 4.3 `mightContain` 不在可用性主链路上

用户名可用性查询（`AuthServiceImpl.isUsernameAvailable`）当前直查数据库。布隆在本项目承担的是「注册后登记 + 可重建的演示能力」，而不是可用性接口的前置拦截。源码能确认的事实是 `isUsernameAvailable` 直接查库，`mightContain` 没有生产调用方。不能据此推断项目流量或作者的性能取舍。若接入布隆，应在判「可能存在」时继续查库确认，假阳性不必变成错误的占用提示；另需解决重建期的数据缺失。


---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| Redisson `RBloomFilter` | 自研 bitmap + 多哈希 | Redisson 处理了分片与初始化竞争；`tryInit` 幂等 |
| 容量 100 万 / 1% | 更大容量换更低误判 | 1.2 MB 与 1% 已是甜点位；注册查重有 DB 兜底，更低误判收益有限 |
| 全量重建（原地） | 双 key 切换重建 | 重建窗口的假阴性由唯一索引兜底；双 key 的复杂度当前不值得 |
| 可用性直查 DB | 布隆前置拦截 | 当前未接入布隆读判断；若接入，可能存在仍须查库确认 |

---

## 六、边界与已知问题

1. **重建期假阴性**：`rebuild` 原地 `delete → 回灌`，窗口内已注册用户名会被判「不存在」。后果由 DB 唯一索引兜底，不影响正确性。
2. **容量溢出行为**：插入数超过 100 万后误判率上升（位数组过载）。当前用户量级远未达到；达到前应重建更大容量的过滤器。
3. **逻辑删除的用户名可复用**：`uk_active_username_role` 只约束未删除账号；布隆里的旧用户名残留会造成假阳性（判「存在」→ 查库发现可用），方向安全。
4. **`mightContain` 无生产调用方**：作为能力保留；若把可用性查询改为「布隆判不存在 → 直接返回可用」的结构，需先处理重建期假阴性，并让「可能存在」继续回源确认。

---

## 七、如何验证

```bash
# 布隆过滤器底层是 Redisson 的 bitmap 结构，不能直接 GET
redis-cli EXISTS <bloomKey>            # 期望 1（初始化后存在）
redis-cli TYPE <bloomKey>              # 期望 string（位图）；配置另存于 Redisson 派生的配置 Hash）

# 重建后观察登记数（POST /user/rebuild，需 ADMIN）
# 返回体 data 为登记的用户名数量

# 行为验证：
# 1. 注册新用户 → addUsername 登记
# 2. 重建期间调用可用性查询 → 仍正确（直查 DB）
# 3. 并发注册同名 → 后者收到 409（DuplicateKeyException 映射）
```

---

## 八、延伸阅读

- [`06-null-sentinel-and-prewarm.md`](06-null-sentinel-and-prewarm.md) —— 穿透的另一种解法：空值哨兵（把「已知的空」缓存下来）
- [`04-cache-breakdown-mutex.md`](04-cache-breakdown-mutex.md) —— 穿透与击穿的区分
- [`../agent/03-security-and-boundaries.md`](../agent/03-security-and-boundaries.md) —— 生成列唯一索引与迁移脚本
- 布隆过滤器原理：[Bloom filter - Wikipedia](https://en.wikipedia.org/wiki/Bloom_filter)
