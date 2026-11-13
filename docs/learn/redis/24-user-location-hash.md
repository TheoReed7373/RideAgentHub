# 用户位置 Hash

> **Redis 考点**：结构化小对象用 Hash 而非 JSON String——字段级读写、无序列化开销；与司机在线 GEO 是两套独立数据。
> **来源**：`docs/01-redis-application-points.md` §2.6（其它应用点）
> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/impl/UserServiceImpl.java`（`saveUserLocation` / `getUserLocation`）、`src/main/java/com/fancy/rideagenthub/controller/UserController.java`

---

## 一、业务场景

乘客端通过 `POST /user/loc` 保存一个当前位置，`GET /user/loc` 读取。Agent 构造系统提示词时读取该位置。家、公司等命名常用地点属于独立的 `UserPOIService` / MySQL 链路，不能与当前位置 Hash 混为一谈。数据特征：

- **小**：三个字段（经度 / 纬度 / 地址）；
- **整体读写**：保存时一次写、读取时一次全取；
- **无 TTL 语义**：当前实现未主动设置过期，也未将此位置写入 MySQL；保存多久取决于 Redis 数据保留；
- **更新即覆盖**：同一用户只有一个「当前位置」。

---

## 二、Redis 结构选型

| Key | 结构 | TTL | 字段 |
|---|---|---|---|
| `user:loc:{userId}`（`RedisKeyConstants.userLocKey`） | Hash | **无** | `latitude` / `longitude` / `address` |

**Hash 而非 JSON String** 的理由（本篇的主要教学内容）：

| 维度 | Hash（本项目选择） | String 存 JSON |
|---|---|---|
| 读一个字段 | `HGET` O(1) | `GET` + 反序列化整个 JSON |
| 改一个字段 | `HSET` O(1) | 读 → 改对象 → 序列化 → `SET`（读改写） |
| 三个字段全读 | `HGETALL` / `entries` | `GET` + 反序列化 |
| 部分字段存在 | 天然支持（字段可缺省） | 要 null 判断 + 结构约定 |

本项目实际用法是「整体读写」，Hash 的字段级优势用得不深，但**免序列化**（`StringRedisTemplate` 直接存字符串字段）与**字段可缺省**（`getUserLocation` 对缺失字段返回 null 而不是整个对象作废）仍然成立。

**与司机在线数据（[16 篇](16-driver-online-heartbeat.md)）是两套独立数据**：用户位置是「乘客最近上报的当前位置」（无 TTL、Hash、按 userId 寻址）；司机在线池是「司机的瞬时状态」（成员级 60 秒判定、GEO + 心跳 Hash）。两者互不迁移——查询附近订单用司机池，填充订单槽位用用户位置。

---

## 三、代码落点

| 位置 | 职责 |
|---|---|
| `UserServiceImpl.saveUserLocation(userId, location)` | 三个字段依次 `HSET` |
| `UserServiceImpl.getUserLocation(userId)` | `entries` 全读；空 Map 返回 null；缺失字段按 null 组装 |
| `UserController` 的位置端点 | HTTP 层：POST /user/loc 保存成功返回 Result.ok()（data=null），GET /user/loc 不存在抛 404；均只允许 USER |

---

## 四、实现拆解

### 4.1 写入：三次 HSET 的原子性够用吗

三个字段是三条独立的 `HSET`——中间崩溃会留下「只有两个字段」的部分状态。当前实现会容忍缺失字段，但不能据此断言部分状态无害：读取侧对每个字段独立判 null（`entries.get("latitude") != null ? ... : null`），部分状态表现为「部分字段可用」而非错误。并发读可能拿到不同次上报混合的经纬度与地址，当前没有原子快照保证。

若换到「字段间有一致性约束」的场景（例如经纬度必须成对），部分状态就是错误——那时应该用 `HMSET` 一条命令或 Lua 收口。

### 4.2 读取：空 Map 与缺失字段的区分

`entries.isEmpty()` → 返回 null（用户从未保存过位置，上层走「无默认位置」分支）；非空但某字段缺失 → 该字段 null（部分写入 / 历史数据）。「完全没存」与「存了一半」两种语义分开处理，不把后者当前者。

### 4.3 更新语义：字段级覆盖

再次 `saveUserLocation` 只覆盖写入的三个字段——由于固定写全三个字段，实际是「整体覆盖」。但 Hash 的字段级语义意味着：将来加第四个字段（如 `city`），旧数据的三个字段保留、新字段缺失——**schema 演化比 JSON 反序列化宽容**（JSON 缺字段的行为取决于 DTO 与反序列化配置，不能一概判为异常）。

---

## 五、设计取舍

| 选择 | 备选 | 为什么选这个 |
|---|---|---|
| Hash | JSON String | 免序列化、字段可缺省、schema 演化宽容（二节对照表） |
| 无 TTL | 过期 + 重保存 | 当前未设置 TTL，不代表已做数据库持久化或永久保留承诺 |
| 三次 HSET（读侧容忍） | Lua / HMSET 原子 | 当前三条命令可被并发读穿插；缺字段可返回 null，混合坐标/地址仍是一致性边界 |
| key 按 userId 寻址 | 按「位置名」多个 key | 当前业务只有「一个当前位置」；扩展成多个命名位置时再演进 |

---

## 六、边界与已知问题

1. **部分写入状态可出现**（4.1）：读侧容忍；有字段一致性要求时需原子化。
2. **位置数据无版本 / 无审计**：覆盖即丢失旧值——业务上「当前位置」无需历史。
3. **地址字段是自由文本**：长度与内容由调用方约束（服务端未校验上限），异常超长会原样进 Redis——上层接口应做长度控制。

---

## 七、如何验证

```bash
# 1. 保存位置后
redis-cli HGETALL user:loc:<userId>
# 期望：三个字段 latitude / longitude / address
redis-cli TTL user:loc:<userId>          # 期望 -1（无 TTL）

# 2. 部分写入模拟
redis-cli HDEL user:loc:<userId> address
#    读取接口 → latitude/longitude 可用、address 为 null（不报错）

# 3. 覆盖更新：保存新位置后三个字段全部替换
# 4. 从未保存过的用户 GET /user/loc → HTTP 404，Result.code=404、data=null
```

---

## 八、延伸阅读

- [`16-driver-online-heartbeat.md`](16-driver-online-heartbeat.md) —— 司机侧位置数据（两套独立数据的对照）
- [`11-two-phase-confirm-token.md`](11-two-phase-confirm-token.md) —— Hash 字段级语义的另一处应用（`chat:info` 的流程门禁）
- Redis 官方文档：[HSET](https://redis.io/commands/hset/)、[HGETALL](https://redis.io/commands/hgetall/)
