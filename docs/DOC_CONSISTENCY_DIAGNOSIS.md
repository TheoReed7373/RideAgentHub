# RideAgentHub Markdown 文档与代码一致性诊断报告

> 核查日期：2026-11-13
> 核查范围：仓库内全部 37 篇 `.md`（根目录 3 篇、`docs/` 29 篇、`src/main/resources/api/` 5 篇）
> 核查基线：当前工作区源码（264 个 `.java`）、`pom.xml`、`application.yaml`、`sql/`、`config/`、Git 提交历史
> **声明**：本报告为新增的诊断文件，**未修改仓库中任何既有 Markdown 文档或代码**。下文所有「建议更新写法」仅为可直接采纳的文案建议。
> 代码路径如无特别说明，均相对仓库根目录；`src/main/java/com/fancy/rideagenthub/` 缩写为 `<base>/`。

---

## 零、第二轮复核裁决修订（阅读本报告前必读）

> 本报告是**第一轮诊断，仅作为待核验线索**。第二阶段已依据 `docs-maintenance/文档更新执行计划.md`（下称《执行计划》）逐条复核，并按「当前真实代码 > 可执行测试与断言 > 当前学习文档 > 诊断报告推断」的事实优先级完成文档更新。下表 8 条结论经复核属于**误判或需收窄**，一律以复核裁决为准；本报告其余内容保留为历史诊断记录，不删除。另：本报告中所有 `文件:行号` 基准同样已漂移，定位请按「类名 + 方法名 + 章节名」。

| # | 本报告原判断 | 复核裁决 | 关键证据 | 第二阶段处理 |
|---|---|---|---|---|
| M1 | 根 `README.md` L59「工具结果指针化已取消」 | **误判（过度断言）**。历史消息改为保存全文，但 `getCalling` 反查链路、`ToolResponseMemory` 两级辅助索引与提示词契约完整保留 | `agentbase/tool/ToolRepPointerTool.java` 的 `getCalling`/`recordCalling`；`config/ChatProperties.java` 的 `getCalling` 契约；`agentbase/memory/MessageParser.java` 的 TOOL 分支 | 保护 README「需要时再反查」原文，只把「只保留 call_id」收窄为「历史保存全文 + 辅助反查」；见 `L/20` |
| M2 | 1.3 类型 E / 3.1 L70「全项目 `*Classifier*` 0 命中」 | **部分成立，需收窄**。不存在同名类/Bean，分类内联在 `ChatServiceImpl.resolveClassification()`；但 `CLASSIFIER_SYS_PROMPT` 等常量、`ClassificationCache`、分类概念均存在 | `config/ChatProperties.java`；`service/impl/ChatServiceImpl.java`；`service/base/ClassificationCache.java` | 架构图节点改为「内联分类 + 常量 + 缓存」，不删节点 |
| M3 | 1.1 时间线「新增 8 个核心类」 | **数字不准且漏双分支**。hash 均真实；仓库有两条被重写/合并的分支（`75a68df` merge），`8d5d574`/`bdc40d7` 属旧分支；`a8f4e33` 实际新增 **16 个 Java 主源码类** | `git log --oneline --all`；`git show --name-status a8f4e33` | 已修正本报告 L21 数字；行号大面积漂移的根因已写入 `L/README.md` 阅读约定 |
| M4 | 3.2 `docs/01`「整篇改造前规划稿」 | **误判**。是「规划稿 + P1-3 事后补记」混合体：P1-3 描述的 `TicketPoolIndex` 由后续提交引入，且第四节优先级表与之自相矛盾 | `docs/01` P1-3 与四节；`TicketPoolIndex.java` | 抬头标注混合体，P1-3 全段保留；优先级表另改「已实施」 |
| M5 | 3.2 `docs/01` 第一节「行号全漂移」 | **部分成立**。`UserUsernameBloomFilterService` 一节仍逐行吻合，`ChatManager` 部分行号未漂移 | 同上 | 改为「主要行号已漂移」 |
| M6 | 3.2 `docs/02` 2.2「墓碑建议已全部实现 / 已彻底修复」 | **部分实现**。`CityCodeUtil.invalidate(String)` 在生产代码 **0 调用方**，仅 `RedisCacheRegressionTest` 调用；key 大小写规范化确已修复 | `agentbase/amap/util/citycode/CityCodeUtil.java`；`RedisCacheRegressionTest.java` | `docs/02` 2.2、`L/08` 统一标注「机制已就位·写路径未接入」 |
| M8 | 3.2 `L/README.md`「目录表 / 数据结构表一致无需修改」 | **误判（漏 4 处）**：#17 实际是 `CLAIM_DUE_ORDERS` Lua、#19 漏 Lease、#20 三级实为两级、#25 漏 `OrderConfirmation` | `util/RedisScripts.java`；`MessageMemory.java`；`ToolResponseMemory.java`；`OrderConfirmation.java` | `L/README.md` 目录表逐行修正，并新增能力索引 |
| M9/M10 | 3.3 四篇 API「未发现不匹配」；3.7 `L/21`「建议补入 TTL≤0 禁用分支」 | **有误判**：`ChatRequestRunner` 只做 `UUID.fromString`（chatId 不必来自 `/newuuid`）；`ChatInfoService` 抛 404；`PaymentVerifier` 默认 Bean 抛 503、从不核验；`RagController` 7 个 ADMIN 端点未列；`ClassificationCache` 的 TTL≤0 禁用分支**已实现** | `service/base/ChatRequestRunner.java`；`service/base/ChatInfoService.java`；`config/PaymentConfig.java`；`controller/RagController.java`；`service/base/ClassificationCache.java` | `api/*.md` 4 处补正；`L/21` 由「建议补入」改为「已实现能力补讲」 |

> 复核裁决共 14 条（含 M7 GEOSEARCH 语境、M11/M12 概念性 `GEOSEARCH` 不批量替换、M13 工具结果 MySQL 重复行、M14 写路径/回填路径脚本区分），完整表见《执行计划》3.3；任务矩阵见其第六章。**本报告不再作为文档更新的唯一依据。**

---

## 一、结论摘要

| 文档分组 | 篇数 | 存在不匹配 | 确凿差异条数 | 过时程度 |
|---|---|---|---|---|
| 根目录主文档 | 3 | 3 | 16 | 中（局部章节） |
| 规划稿与学习索引 | 4 | 4 | 31 | 高（整篇基于历史快照） |
| `src/main/resources/api/` 变更说明 | 5 | 1 | 1 | 低（与当前代码高度一致） |
| `docs/learn/redis/` 考点笔记 | 25 | 25 | 约 120 | 高（实现被重写） |
| **合计** | **37** | **33** | **约 168** | — |

**核心判断**：过时的直接来源是最近一次大规模代码提交 `a8f4e33`（"修复订单与会话安全漏洞并完善并发一致性保护"），它改动 75 个文件、删除 2 个测试控制器、新增 **16 个 Java 主源码类**（复核修订，见第零章 M3），但**只新增了 4 篇 API 变更说明，没有回写 README 与 25 篇学习笔记**。

### 1.1 过时根因（Git 时间线）

| 提交 | 内容 | 与文档的关系 |
|---|---|---|
| `8d5d574` | `import TaxiAgent project` | `docs/01`、`docs/02` 自称基于该 commit，项目名 `TaxiAgent`、包 `com.fancy.taxiagent` |
| `d534157` | 盘点 Redis 应用插入点与缓存一致性竞态 | 产出 `docs/01`、`docs/02`（改造前规划稿） |
| `dd7769f` ~ `db311d2` | 原子化会话锁、延迟双删、墓碑、Lease 令牌、分类缓存/限流、抢单 Lua、延迟队列、订单锁、工单池 ZSet、GEO 附近排序 | 把 `docs/01` 的 P0/P1「建议改造」逐项落地 |
| `bdc40d7` | 司机工单池改用 GEO 附近排序（P0-2） | `docs/learn/redis/` 自称的代码版本基准 |
| `153c511` | 新增 `docs/learn` 25 篇考点文档 | 25 篇笔记 + `README.md` 索引一次性写入，全系列带 `文件:行号` |
| `ef4abf1` | README 扩充 Redis 高并发主线 | 根 `README.md` 最后一次内容扩充 |
| **`a8f4e33`** | **安全与一致性大改**（2152 插入 / 958 删除，75 文件） | **主要过时来源**：删除 `Chat2Controller.java`、`TestController.java`；重写 `TokenServiceImpl`、`EmailCodeService`、`ToolResponseMemory`、`MessageParser`、`RedisMemory`、`TicketServiceImpl`、`OrderTool`、`OrderSearchTool`；新增 `OrderConfirmation`、`ChatRequestRunner`、`RidePricing`、`PaymentConfig`、`IdGeneratorConfig`、`AsyncWorkConfig`、`MessageWindow`、`QaIndexSynchronizer`、`RequestRateLimitInterceptor`；新增 4 篇 `api/*.md` |

### 1.2 当前代码事实基线（用于判定差异）

| 项 | 当前事实 | 证据 |
|---|---|---|
| 项目名/包 | `RideAgentHub` / `com.fancy.rideagenthub` | `pom.xml:12,14`、`application.yaml:5`、主类 `RideAgentHubApplication.java` |
| Agent 目录 | `<base>/agents/`（非 `agentbase/agents/`） | `agents/OrderAgent.java:1` 等 |
| Lua 脚本 | `util/RedisScripts.java` 有 **22** 个 `DefaultRedisScript`；另有 **11** 个内联脚本散在 5 个业务类 | `RedisScripts.java:10,27,34,48,55,70,88,97,112,127,136,142,150,157,169,182,197,213,230,238,250,260` |
| GEO 命令 | `GEORADIUS`（兼容 Redis 5），**不是** `GEOSEARCH` | `RedisScripts.java:69,77` |
| 记忆分层 | Heap(L1) → Redis(L2) → MySQL(L3)；`ESMemory` 为空占位类；MongoDB 只存订单路线 | `MessageMemory.java:24-34`、`ESMemory.java:9`、`OrderRoute.java:8` |
| Elasticsearch | 仅 RAG 知识库 `qa_knowledge_base`，向量 + BM25 + RRF；`chat-es-setting.json` 的 N-Gram 无 Java 引用 | `QaIndexSynchronizer.java:47-53`、`RagService.java:306-380` |
| 工具结果 | `ToolResponseMemory` 为 Redis + MySQL **两级**，key = `chat:tool:v2:{chatId}:{callId}`；历史消息保存工具结果**全文** | `ToolResponseMemory.java:29-41`、`MessageParser.java:56-74` |
| 确认链路 | `confirmationId` + `confirmed` + 15 分钟报价有效期 + 一次性消费 | `OrderConfirmation.java:16-39`、`ChatServiceImpl.java:231-241`、`ChatParamDTO.java:14-15` |
| 聊天端点 | `POST /c/{id}`、`POST /r/{id}`、`GET /restore`、`POST /restore`、`POST /lock/{id}`、`POST /newuuid`（均在 `/chat/v2` 下） | `ChatController.java:42,49,56,62,75,83` |
| 测试控制器 | 已删除 | `controller/` 下 7 个文件无 `/order/chat`、`/order/resume`、`/order/daily/chat`、`/test/rag` |
| 版本 key | 聊天历史版本 key 现在 `PERSIST` 永久保留，数据 key 才带 TTL | `RedisScripts.java:168-235`、`README.md:132` |

### 1.3 差异类型分布

| 类型 | 说明 | 典型例子 |
|---|---|---|
| A 基准性过时 | 项目名/包名/commit/路径基准/`文件:行号` | 27 篇仍写 `TaxiAgent` / `com.fancy.taxiagent` / `bdc40d7` |
| B 实现已重写 | 文档描述的代码片段与当前实现不同 | `TokenServiceImpl`、`EmailCodeService`、`RedisMemory`、`OrderGeoPool`、`TicketPoolIndex` |
| C 「已修复 / 仍是坑」标注反转 | 文档标为缺陷的已被修复，或标为已修复的仍有残留 | 验证码原子化、Lease 版本 key、`CANCEL` Lua、`CHECK_DRIVER_ONLINE` |
| D 接口/配置/数值变化 | 端点增删、TTL、命令名、模型/依赖版本 | `/order/chat/*` 移除、`GEORADIUS`、5 分钟 TTL、`spring-ai-bom 1.1.0` |
| E 类名/事实性错误 | 文档写了不存在的类或错误类名 | `LambdaQueryMapper`（不存在的类型）、`Chat2Controller`（已删除）；**复核修订（第零章 M2）**：`Classifier` 是架构图里的概念节点名而非类名误用，分类实际内联在 `ChatServiceImpl.resolveClassification()`，不应简单归入此类 |

---

## 二、跨文档共性差异（先统一处理，逐篇不再重复）

### 共性 1：项目名、包名、路径基准与代码版本（涉及 27 篇）

- **不匹配段落**：`docs/01:3,5`、`docs/02:3,5`、`docs/learn/redis/README.md:18-19`、`docs/learn/agent/README.md:3`、`docs/learn/redis/01`~`25` 各篇 **L5** 的「完整路径 / 来源」块（17 篇另有 L246 写 `TaxiAgentApplication.java:8`；09 篇 L519 写 `TaxiAgentApplicationTests`）。
- **对应代码**：`pom.xml:12,14`（`RideAgentHub`）、`application.yaml:5`、`<base>/RideAgentHubApplication.java:8-9`、`src/test/java/com/fancy/rideagenthub/RideAgentHubApplicationTests.java`。全仓 264 个 `.java` 均在 `com/fancy/rideagenthub/` 下。
- **差异说明**：`TaxiAgent` / `com.fancy.taxiagent` 已废弃，`8d5d574`、`bdc40d7` 是历史提交；按文档给出的路径找不到任何源码，且全部 `文件:行号` 基准失效。根 `README.md:145` 已自认「部分项目名、行号与实现描述仍沿用旧版本」。
- **建议更新写法**（统一替换 27 篇的抬头）：
  > 「> **代码版本**：当前工作区（RideAgentHub / `com.fancy.rideagenthub`）。本文撰写于历史快照 `bdc40d7`（旧名 TaxiAgent / 包 `com.fancy.taxiagent`），**所有 `文件:行号` 已漂移，具体行为以当前源码为准**；当前路径基准为 `src/main/java/com/fancy/rideagenthub/`。」
  >
  > 对 `docs/01`、`docs/02` 额外加一句：「本文为**改造前规划稿**，其中『现状：未实现』与『建议改造』均为历史快照；P0-1/P0-2/P0-3/P1-1/P1-2 已全部落地，实施状态见 `docs/learn/redis/README.md` 与根 `README.md` 的 Redis 高并发实战表。」

### 共性 2：`文件:行号` 整体漂移（涉及 25 篇笔记）

- **不匹配段落**：各篇「三、代码落点」「四、实现拆解」「七、如何验证」中的全部行号引用。代表：`RedisKeyConstants.java` 的 `TOKEN_PREFIX` `:19`→`:20`、`emailCodeKey` `:212-214`→`:201-203`、`ticketStatisticsKey` `:292-295`→`:285-288`；`TicketServiceImpl.getTicketStatistics` `:1089-1126`→`:1135-1171`；`RedisScripts.java` 234 行 → 278 行；`ChatManager.java` 95 行 → 103 行；`CityCodeUtil.java` 122 行 → 129 行；`RedisLock.java` 72 行 → 42 行；`init.sql:118/136`→`:125/143`。
- **对应代码**：`constant/RedisKeyConstants.java`、`service/impl/TicketServiceImpl.java`、`util/RedisScripts.java` 等当前文件。
- **差异说明**：`a8f4e33` 等提交导致整体行号偏移 10~90 行，逐条修正成本极高且会再次漂移。
- **建议更新写法**：「本篇代码落点一律改用 **类名 + 方法名 + 章节名** 定位，不再标注行号；确需精确位置的，改链接到 `docs/learn/redis/README.md` 的统一落点表（该表按当前源码维护）。」

### 共性 3：「已修复 / 仍是坑」标注反转对照表（最高优先级）

| 文档位置 | 文档标注 | 当前代码事实 | 建议标注 |
|---|---|---|---|
| `docs/02:299-326` 3.1；`12-chat-lock-atomic.md` 4.2 | `lockChat` 的 `put`+`expire` 非原子（缺陷） | `RedisScripts.HASH_SET_WITH_EXPIRE:136-139` 已 Lua 原子化 | ✅ 已修复；但 `ChatInfoService.java:120` 的 `persist` 仍在，属**部分修复** |
| `docs/02:328-345` 3.2；`docs/01:27-29` | 无条件 `delete(lockKey)` 误删他人锁 | `RedisLock.java:36` + `RELEASE_LOCK_IF_MATCH:142-147` 校验令牌 | ✅ 已修复 |
| `docs/02:347-367` 3.3；`docs/learn/redis/README.md:146`；`11-two-phase-confirm-token.md` 4.2.3/6.2 | 「3.3 未修复」「校验+删除非原子」 | `OrderSearchTool.CANCEL:46-53` 已原子校验 + `HDEL`（绑定 orderId、5 分钟时效）；`OrderConfirmation.CONSUME:32-39` 一次性消费 | ✅ 核心竞态已修复；**仍在**的是 `hasKey` 预检（`:240`）、`finally` 冗余 delete（`:282`）、令牌消费与业务执行分离（`:269` vs `:275`） |
| `docs/02:369-380` 3.4；`README.md:145` | 布隆过滤器原地 delete 重建 | `UserUsernameBloomFilterService.java:43-44` 仍是 `delete()+tryInit()` | ⚠️ 缺陷仍在；但 `isUsernameAvailable` 已直查 DB + 唯一索引兜底，**后果已变**（见 03 篇） |
| `09-lease-token.md` 4.8 / 6-3 / 七-3；`19-multi-level-cache.md` 3.2 | 版本 key 与数据 key 同 TTL；`clear` 两条 `DEL` 非原子 | 版本 key 被 `PERSIST` 永久保留；`clear()` 走 `CLEAR_HISTORY_WITH_VERSION_BUMP:230-235` 原子脚本 | 结论**完全反转**，需整节重写 |
| `02-email-code-cooldown.md` 4.2/4.4/六-2；`01-shared-session-token.md` 4.4/六-2 | 验证码可重复消费、无限次尝试；Token 三步非原子 | `EmailCodeService` 四段 Lua（ISSUE/VERIFY/CLEANUP/COMPLETE）+ 5 次上限；`TokenServiceImpl` 三段 Lua（SAVE/REVOKE/REMOVE） | ✅ 全部已修复 |
| `13-distributed-lock.md` 4.3/4.4/6.5；`14-grab-order-lua.md` 6.1 | 手写锁用固定 10s 租约；`rollbackGrab` 读写非原子；非法参数返回 null | `RideOrderServiceImpl.java:317` 用 `tryLock(3, TimeUnit.SECONDS)` **启用看门狗**；`rollbackGrab:112-119` 走 `RELEASE_LOCK_IF_MATCH`；`RedisLock.java:23-25` 抛异常 | ✅ 全部已修复 |
| `17-order-timeout-delay-queue.md` 6.1/6.2 | 无 DB 兜底扫描、失败不重试 | `OrderTimeoutJob.recoverOverdueOrders():78-108` 每分钟扫 DB；`:110-118` 失败重新入队 | ✅ 已修复 |
| `18-global-unique-id-incr.md` 6.2 第 2/3 条 | `INCR`+`EXPIRE` 非原子、`seq==null` 生成畸形编号 | `TICKET_SEQUENCE` Lua（`TicketServiceImpl.java:76-81`）；`:713` 抛 503 | ✅ 已修复 |
| `10-lua-scripts-overview.md` 六-3 | `CHECK_DRIVER_ONLINE` 脚本 | 全库 0 命中，已删除 | 整节删除，改为三脚本（见该篇诊断） |

### 共性 4：Lua 脚本数量与集中度

- **不匹配段落**：`docs/learn/redis/README.md:30,65`、`10-lua-scripts-overview.md:1,5,17,29,35,56-66,96,151,179,334,344`、`11-two-phase-confirm-token.md:318,560`、`12-chat-lock-atomic.md:367`、`14-grab-order-lua.md:221`、`09-lease-token.md:529` 中「7 个脚本」「全部集中在 `RedisScripts.java`」「与其余 6 个并列」等表述。
- **对应代码**：`util/RedisScripts.java`（278 行、**22** 个 `DefaultRedisScript`，其中 4 个返回 `List`）；另有 11 个内联脚本：`EmailCodeService.java:35,43,59,64`（4）、`TokenServiceImpl.java:29,36,43`（3）、`TicketServiceImpl.java:76`（1）、`OrderConfirmation.java:16,32`（2）、`OrderSearchTool.java:46`（1）。全项目合计 33 段 Lua。
- **差异说明**：脚本数 7→22，且「全部集中」不成立；旧的 `script(lua, Long.class)` 工厂方法已删除，改为 `new DefaultRedisScript<>(文本块, Long.class)` 直接构造。
- **建议更新写法**：「Lua 主流程脚本集中在 `util/RedisScripts.java`（当前 **22** 个，含 4 个返回 `List`）；`EmailCodeService`、`TokenServiceImpl`、`TicketServiceImpl`、`OrderConfirmation`、`OrderSearchTool` 另有 **11** 个内联脚本。第 10 篇的速查表需扩为 22 行并新增一节说明内联脚本。」

### 共性 5：订单确认链路重构（涉及 README、11、25、`docs/01`）

- **不匹配段落**：`README.md:205-215` 时序图、`docs/learn/redis/11` 全篇、`25` 的 4.1/4.2/4.3/6.4、`docs/01:24,243-247`、`docs/learn/redis/README.md:137,146`。
- **对应代码**：新增 `service/base/OrderConfirmation.java`（`CONFIRM`/`CONSUME` 两段 Lua）、`domain/dto/ChatParamDTO.java:14-15`（`confirmationId` / `confirmed`）、`ChatServiceImpl.java:231-241`、`OrderAgent.java:306-321`、`OrderTool.java:407-413`（`markOrderReadyForCreate` 降级为只读查询）、`src/main/resources/api/chat-security.md:10-12`。
- **差异说明**：确认流程由「模型自签令牌」变为「服务端签发 `confirmationId` + 前端原样回传 + 15 分钟有效期 + 创建权限一次性消费」；文档仍停留在「`resume(feedback)` 直接接回工具链 / 可重复下单 / 无幂等」。
- **建议更新写法**（可统一加到相关篇目）：「订单确认现由 `OrderConfirmation` 管理：`AgentEvent.comfirm(orderJson)` 携带 `confirmationId`（= `quoteId`），前端原样回传，`ChatServiceImpl.resume` 调 `confirm()` 由 `CONFIRM` Lua 校验 15 分钟有效期并置 `break=resuming`；`markOrderReadyForCreate()` 只查询 `isConfirmed`，`createOrder()` 落单前调 `consume()` 一次性消费 `ReadyForCreate`。」

---

## 三、逐篇诊断

> 表头字段说明：**段落位置**＝文档中不匹配的章节/行号；**文档原文**＝简短摘录；**代码位置/模块**＝当前源码证据；**差异说明**＝为何不匹配；**建议更新写法**＝可直接替换/新增的文案（仅建议，未执行）。

### 3.1 根目录主文档

#### README.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 亮点「工具结果指针化」L59；「记忆与数据存储」L268-269 | 「长工具结果可只保留 `call_id`，需要时再反查」 | `agentbase/memory/MessageParser.java:56-74`、`ToolResponseMemory.java:13-44`、`README.md:145` 自身 | `MessageParser` 的 TOOL 分支把 `responseData()` 全文写入历史；`ToolResponseMemory` 已从三级缓存退化为 Redis + MySQL 两级，只是按 `(callId, chatId)` 反查的辅助索引 | 「**工具结果随会话持久化**：TOOL 消息保存响应全文；另用 `ToolResponseMemory`（Redis 24h + MySQL 两级，key `chat:tool:v2:{chatId}:{callId}`）提供按 `getCalling(callId)` 的辅助反查。」 |
| 总体架构 mermaid L70 | `CS --> CLS[Classifier<br/>Qwen Router]` | `service/impl/ChatServiceImpl.java:164-203`、`config/ChatProperties.java:44`、`service/base/ClassificationCache.java:33` | 不存在 `Classifier` 类/Bean（全库 0 命中），分类是 `resolveClassification()` 内联调用 | 「`CS --> CLS[ChatServiceImpl 内联分类<br/>CLASSIFIER_SYS_PROMPT + ClassificationCache]`」，正文补「分类内联在 `ChatServiceImpl.resolveClassification()`，结果由 `ClassificationCache` 缓存」 |
| Agent 与 RAG 链路·时序图 L205-215；本地调试 L404 | `API->>OA: resume(feedback)`；「恢复订单确认后的会话」 | `ChatServiceImpl.java:231-241`、`OrderConfirmation.java:16-39`、`OrderAgent.java:306-321`、`ChatParamDTO.java:14-15` | 恢复请求已结构化，需 `confirmationId` + 布尔 `confirmed`；报价 15 分钟有效、创建权限一次性消费；时序图缺授权环节 | 「时序图在 `POST /chat/v2/r/{id}` 后增 `CS->>OrderConfirmation: confirm(confirmationId, confirmed)`，`markOrderReadyForCreate()` 标注『仅查询 isConfirmed』；接口说明改为：请求体 `{"confirmationId":"确认事件返回的编号","confirmed":true,"prompt":"可选补充说明"}`，报价 15 分钟内有效」 |
| 技术栈 L300 | `- Spring AI OpenAI Starter 1.1.0-RC1` | `pom.xml:41-46`（`spring-ai-bom 1.1.0`）、`:80-82`（starter 无 `<version>`） | pom 已删除 `1.1.0-RC1` 声明，该 starter 由 BOM 托管；全仓仅此一处出现该版本号 | 「`- Spring AI OpenAI Starter（版本由 spring-ai-bom 1.1.0 统一管理，pom 未单独指定）`」 |
| 本地调试方式 L403-406 | 只列 4 个 `/chat/v2` 接口，`GET /chat/v2/restore`：检查是否存在可恢复订单会话 | `controller/ChatController.java:62`（`POST /restore`）、`:75`（`POST /lock/{id}`）；`api/chat-security.md:7-8` | 漏了后期新增的两个端点 | 补两行：「`- POST /chat/v2/restore`：恢复当前用户最近的可恢复订单会话；`- POST /chat/v2/lock/{id}`：锁定本人会话」 |
| 本地调试方式 L410-421 | 「仓库里还留了一个测试控制器」＋ `POST /order/chat/{chatId}`、`/order/resume/{chatId}`、`/order/daily/chat/{chatId}` ＋ curl 示例 | `a8f4e33` 删除 `Chat2Controller.java`(77 行)、`TestController.java`(43 行)；`controller/` 7 个文件无对应映射；`api/chat-security.md:14` | 测试控制器已删除，整段（含示例）失效 | 删除整段，改为：「早期直连 Agent 的 `/order/chat`、`/order/resume`、`/order/daily/chat` 与 `/test/rag` 测试入口已移除（`a8f4e33`，见 `src/main/resources/api/chat-security.md`）；本地调试统一使用 `/chat/v2/**`，先 `POST /chat/v2/newuuid` 获取 chatId」 |
| 附注 L145 | 「工具确认标记的检查与业务执行仍分离」；`docs/02` 部分描述沿用旧版 | `OrderSearchTool.java:46-53,269-275`、`OrderTool.java:441-442`、`OrderConfirmation.java:32-39` | 表述易被读成「3.3 完全未修」；实际令牌消费已原子化，仅「消费与业务执行分离」仍在 | 「工具确认/取消令牌的消费已由 `CANCEL`、`CONSUME` Lua 原子化；仍存在的是令牌消费与业务执行分离、以及 `hasKey` 预检与 `finally` 冗余删除」 |
| 目录结构 L329-333 | 「25 篇考点文档，部分说明与行号待随代码同步」 | 全部 25 篇 | 声明方向正确但过于笼统，未点明已被 `a8f4e33` 大改 | 「25 篇考点文档撰写于 `bdc40d7`，`a8f4e33` 安全改造后大量实现被重写，阅读时以 `docs/DOC_CONSISTENCY_DIAGNOSIS.md` 与当前源码为准」 |

#### AGENTS.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 架构·AI 代理层 L30 | `### AI 代理层 (`agentbase/agents/`)` | `agents/OrderAgent.java:1` 等 4 个 Agent 在 `<base>/agents/`；`agentbase/` 下只有 chatinfo/tool/memory/rag/amap/qweather | 路径错误，无 `agentbase.agents` 包 | 「`### AI 代理层 (`agents/`)`」 |
| 核心技术栈 L23；架构 L32；数据层 L41 | 「MongoDB（订单路线、对话记录）」「记忆系统跨越 Redis、MongoDB、MySQL 和 Elasticsearch」 | `MessageMemory.java:24-34`、`ESMemory.java:9`（空类）、`MysqlMemory.java:31-33`、`OrderRoute.java:8` + `OrderRouteService.java:42` | MongoDB 只存订单路线轨迹；对话消息在 MySQL；记忆是 Heap/Redis/MySQL 三层；L23 与 L41 自相矛盾 | 「**数据库**：MySQL（用户、订单、工单、对话消息），MongoDB（订单路线轨迹）」；「记忆系统为 Heap(L1) → Redis(L2) → MySQL(L3)，工具结果另存 `ToolResponseMemory`；`ESMemory` 目前为空占位类」 |
| 核心技术栈 L24；数据层 L43 | 「Elasticsearch（聊天全文检索）」「基于 N-Gram 的聊天全文检索」 | `QaIndexSynchronizer.java:47-53`、`QaDocument.java:12`、`RagService.java:306-380`；`config/chat-es-setting.json` 的 `my_ngram_analyzer` 全库 0 引用 | ES 运行时只服务 RAG 知识库混合检索；N-Gram 配置是遗留资源 | 「**搜索与缓存**：Elasticsearch（知识库 `qa_knowledge_base`，向量 + BM25 + RRF 混合检索），Redis（缓存）」；「`- Elasticsearch: 知识库索引与混合检索（config/chat-es-setting.json 为遗留 N-Gram 配置，当前代码未引用）`」 |
| 开发注意事项 L49 | 「使用 MyBatis-Plus 的 LambdaQueryMapper」 | `service/impl/UserServiceImpl.java:3-4`、`TicketServiceImpl.java:3-4` 实际 import `LambdaQueryWrapper`/`LambdaUpdateWrapper`；全库 `LambdaQueryMapper` 仅命中两篇文档 | 不存在的类型名 | 「`- 在服务层使用 MyBatis-Plus 的 LambdaQueryWrapper / LambdaUpdateWrapper 构造查询条件`」 |

#### CLAUDE.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| Architecture L30 | `### AI Agent Layer (`agentbase/agents/`)` | 同 AGENTS 第 1 条 | 路径过时 | 「`### AI Agent Layer (`agents/`)`」 |
| Key Tech Stack L23；Architecture L32；Data Layer L41 | `MongoDB (order routes, conversations)`；`Memory system spans Redis, MongoDB, MySQL, and Elasticsearch` | 同 AGENTS 第 2 条 | 对话历史在 MySQL，MongoDB 仅订单路线；记忆不含 MongoDB/ES | 「`**Database**: MySQL (users, orders, tickets, chat messages), MongoDB (order route traces)`」；「`Memory: Heap(L1) → Redis(L2) → MySQL(L3), tool results in ToolResponseMemory; ESMemory is an empty placeholder`」 |
| Key Tech Stack L24；Data Layer L43 | `Elasticsearch (full-text chat search)`；`N-Gram full-text chat retrieval` | 同 AGENTS 第 3 条 | ES 实际只做 RAG 混合检索 | 「`- **Search**: Elasticsearch (qa_knowledge_base: vector + BM25 + RRF hybrid retrieval), Redis (cache)`」；「`- Elasticsearch: knowledge-base index and hybrid retrieval (config/chat-es-setting.json is a legacy N-Gram config, unused in code)`」 |
| Development Notes L49 | `Use MyBatis-Plus LambdaQueryMapper in services` | 同 AGENTS 第 4 条 | 类名错误 | 「`- Use MyBatis-Plus LambdaQueryWrapper / LambdaUpdateWrapper in services`」 |

---

### 3.2 规划稿与学习索引

#### docs/01-redis-application-points.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 抬头 L3、L5、L68、L278 | 「定位 TaxiAgent…」「基于 `main` 分支 `8d5d574`」；对照表「TaxiAgent」 | `pom.xml:12,14`、`application.yaml:5`、`git log` 中 `8d5d574` 为 `import TaxiAgent project` | 项目名/包/commit 全为历史值（见共性 1） | 「本文为改造前规划稿（历史快照 8d5d574，旧名 TaxiAgent / 包 `com.fancy.taxiagent`）。当前仓库为 RideAgentHub，包 `com.fancy.rideagenthub`，所有行号与结论已漂移」 |
| 一、已实现 Redis 应用表 L15-25 | `TokenServiceImpl:35-100`、`EmailCodeService:39-61,96-115`、`ToolResponseMemory:28-72…callId 为 key，三级缓存`、`TicketServiceImpl:983-1053…手写 setIfAbsent 锁` | `TokenServiceImpl.java:29-47,74-77`（108 行，3 段 Lua）；`EmailCodeService.java:35-69,77-97,107-125`（182 行，4 段 Lua）；`ToolResponseMemory.java:39`（44 行，两级）；`TicketServiceImpl.java:1135-1171,1278-1290`；`RedisLock.java:22-41` | 9 个类都在，但行号全漂移，且 Token/验证码/工具缓存/锁的语义已重写 | 整表加注「以下行号基于历史快照，当前见 `docs/learn/redis/README.md`」；「ToolResponseMemory」行改为「key=`chat:tool:v2:{chatId}:{callId}`，Redis + MySQL 两级」；「手写 setIfAbsent 锁」改为「`RedisLock` 组件（setIfAbsent + Lua 令牌校验释放）」 |
| P0-1 抢单 L35-87 | 「`driverAcceptOrder:271-304` 完全依赖 DB 乐观锁」「`currentLat`/`currentLng` 从头到尾没有使用」「阶段一：用 Lua…」 | `RideOrderServiceImpl.java:254-306`（`withOrderLock` + `tryGrab`）、`:299-300`（`driverGeoIndex.refreshIfExist` 已用坐标）、`OrderGrabService.java:77`、`RedisScripts.java:238-247` | Lua 原子预检、Redisson 订单锁、GEO 位置刷新均已落地 | 「【已落地】抢单采用 Redisson 订单锁 + `OrderGrabService.GRAB_ORDER_ATOMIC` Lua 原子预检 + 司机账号行锁与 DB 条件更新兜底；`currentLat/currentLng` 用于刷新司机位置。本节代码块为改造前实现，仅作对照」 |
| P0-2 附近订单 GEO L91-125 | 「工单池查询是纯 DB 时间排序」「真实业务缺陷」「建议 `GEOSEARCH`」 | `RideOrderServiceImpl.java:1116-1140`、`OrderGeoPool.java`、`DriverGeoIndex.java`、`RedisScripts.java:70-85` | 缺陷已修复；实际命令是 `GEORADIUS`（兼容 Redis 5）而非 `GEOSEARCH` | 「【已落地】附近订单走 `OrderGeoPool` / `DriverGeoIndex`，`GEORADIUS` 按距离排序，索引不可用时 `legacyTimeSortedPool` 回退 DB；原纯 DB 排序仅作历史对照」 |
| P0-3 延迟队列 L129-153 | 「`CREATED` 状态订单没有任何超时处理」「建议 `ZADD`/`ZREM`」 | `OrderDelayQueue.java:56,82-86`、`OrderTimeoutJob.java:55,79,110`、`RedisScripts.java:127-133`、`application.yaml:89-97` | 三场景超时兜底、Lua 原子领取、DB 补偿扫描均已实现 | 「【已落地】`OrderDelayQueue` + `OrderTimeoutJob`（`@Scheduled` 轮询 + 每分钟 DB 补偿扫描），`CLAIM_DUE_ORDERS` Lua 用 `ZRANGEBYSCORE` + `ZREM` 原子领取；超时由 `ride-order.timeout.*` 配置」 |
| P1-1 分类缓存与限流 L157-189 | 「每轮对话都要调用两次 LLM」「全程没有任何限流」「建议改造」 | `ChatServiceImpl.java:164-192,220`、`ClassificationCache.java:51-60`、`ChatRateLimiter.java:49-55`、`RedisScripts.java:260-274`、`application.yaml:75-81` | 分类缓存与按 userId 的 ZSet 滑动窗口限流均已实现 | 「【已落地】`ClassificationCache`（prompt + 上下文指纹，空结果不缓存）+ `ChatRateLimiter`（`SLIDING_WINDOW_RATE_LIMIT` Lua，Redis 故障放行），参数见 `chat.guard.*`」 |
| P1-2 分布式锁 L193-214；四、优先级表 L276-286 | 「目前靠 DB 乐观锁兜底」「可直接使用 `RLock`」「顺带把散落的手写锁统一抽象成 `RedisLock`」 | `RideOrderServiceImpl.java:313-343`、`RedisLock.java:22-41`、`TicketServiceImpl.java:1278-1290` | 全部建议已落地，优先级表仍写成「预估改动范围 / 建议起手」 | 「【已落地】`withOrderLock` 使用 Redisson `RLock`（看门狗续期）；统计/索引锁统一走 `service/base/RedisLock.java`。第四节优先级表将 P0/P1 六行状态改为『已实施』」 |
| 三、现存缺陷第 1 条 L253-257 | 「`ToolResponseMemory.save:35` 用了 `set` 而非 `setIfAbsent`」「重复 tool call 会覆盖」 | `ToolResponseMemory.java:41`（仍是 `set`）、`:21-25`（`save` 先写 MySQL）、`:14`（`TTL = Duration.ofHours(24)`） | 缺陷本质仍在，但方法名、行号、常量名、key 结构、持久化层次全变，引用无法定位 | 「`ToolResponseMemory.cache():41` 仍用 `set`（非 `setIfAbsent`）；`save():21` 先写 MySQL 再缓存，key=`chat:tool:v2:{chatId}:{callId}`，TTL 24h。缺陷分析只针对 Redis + MySQL 两级」 |
| 三、缺陷 2/3；P2 清单 L243-247 | 「布隆过滤器重建期穿透」「`PermissionAspect` 每次请求查 DB 角色」「接口幂等｜见第三节第 1 条」「`getChatHistory:537` 是 DB 轮询」 | `UserUsernameBloomFilterService.java:43-44,63-67`（仍成立）；`PermissionAspect.java:33-46`（角色取自 `UserTokenContext`）；`OrderConfirmation.java:16-39`、`OrderTool.java:441`、`OrderSearchTool.java:46-53` | 缺陷 2/3 仍成立；但「权限缓存」证据描述错误；P2 把「接口幂等」整体列为待插入，遗漏了新增的 confirmationId 一次性令牌 | 「布隆过滤器两条缺陷仍成立；`PermissionAspect` 现在从登录态上下文读角色，不查 DB。P2『接口幂等』已局部落地：`OrderConfirmation`（`confirmationId` + CONFIRM/CONSUME）、`OrderSearchTool.CANCEL` Lua、`OrderGrabService.GRAB_ORDER_ATOMIC`；通用幂等注解/切面仍未实现」 |
| P1-3「已实现」L218-233 | 工单池 ZSet 的 score 公式 / 2 小时 / 版本令牌 / 回退 DB | `TicketPoolIndex.java:33,34,108,154-158`、`TicketServiceImpl.java:361-390,1216` | **一致，无需修改**（唯一与代码完全吻合的规划段落） | 保留原文 |

#### docs/02-cache-consistency-race.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 抬头 L3、L5 | 「定位 TaxiAgent 中…」「基于 `main` 分支 `8d5d574`」 | 见共性 1 | 历史声明需更新，并明确全文「现状」均为改造前快照 | 「本文为改造前规划稿（旧名 TaxiAgent / `com.fancy.taxiagent` / `8d5d574`）；当前 RideAgentHub 中 2.1/2.2/2.3 三解法及 3.1/3.2 已落地，详见 `docs/learn/redis/` 07–09 篇」 |
| 一、排查结论总览 L20-24 | `CityCodeUtil`「无失效路径·真竞态」；统计缓存「无」；`ChatManager`「双写非原子」；`RedisMemory`「`clear` + 全量 `append`·会丢数据」；`ToolResponseMemory:28-72`「按 callId 不可变」 | `CityCodeUtil.java:106-114`（`invalidate` 墓碑）；`TicketServiceImpl.java:1236-1251`（延迟双删）；`ChatManager.java:39-44`（原子 HSET+EXPIRE）；`RedisMemory.java:138-186`（`overwriteIfLeaseValid`，已无 `overwrite`）；`ToolResponseMemory.java:39`（key 含 chatId、44 行） | 四格「无失效路径/双写非原子/会丢数据」是全文立论基础，代码中已分别由墓碑、延迟双删、原子 HSET+EXPIRE、Lease 版本令牌解决 | 把四格结论改为「⚠️ 改造前竞态，当前已由 07/08/09 篇所述机制解决」；`ESMemory`「空实现（规划中）」与布隆「重建期穿透」两格仍成立可保留；`ToolResponseMemory` 行改为「key 含 chatId，Redis + MySQL 两级」 |
| 2.1 L40、L50、L52-64 | 「`ticketStatisticsKey` 全项目只有 `getTicketStatistics` 读写，没有任何一处删除」「所有写工单的方法没有一个碰缓存」 | `TicketServiceImpl.java:1213-1219`（`afterTicketMutation`，10 处写方法调用）、`:1236-1251`（`evictTicketStatistics`）、`DelayedCacheEvictor.java:30-42` | 两处核心「现状」断言被延迟双删 + 统一失效路径完全推翻 | 「【已落地】所有工单写方法经 `afterTicketMutation()` → `evictTicketStatistics()`（提交后删除 + `DelayedCacheEvictor` 500ms 二次删除），并同步失效 `TicketPoolIndex`。以下为改造前时序对照」 |
| 2.1 L43-47、L79、L100 | `RedisKeyConstants.java:165/173`；`TicketServiceImpl.java:985/1002/1035/993-1006/65-66/1039-1040` | `RedisKeyConstants.java:92,98,285-288,293-296`；`TicketServiceImpl.java:1135-1171,1154,1187-1195,1145,1278-1290,87-88,1191-1192` | 行号全漂移；「20+0-10 秒」数值仍准，但「脏窗口本来有界」的论证前提已变 | 「统一替换行号，并注明：TTL 20+0~10s 未变，但已叠加延迟双删，写方走统一失效路径」 |
| 2.2 L113-171 | 「现在没有任何失效路径」「墓碑只是把 `__NULL__` 换成 `__TOMBSTONE__` + 5 秒」「改造方向：写城市表 → `SET … EX 5`」 | `CityCodeUtil.java:31,41,67-77,84-90,106-114`；`RedisScripts.java:150-166`；`RedisKeyConstants.java:236-238` | **复核修订（第零章 M6）：⚠️ 部分实现**——两段 Lua、版本 key 与读路径均已就位并通过回归测试，但 `CityCodeUtil.invalidate()` 在生产代码 0 调用方，城市表写路径未接线；另额外加入版本号升级防止墓碑过期后旧快照回填 | 「【机制已就位·写路径未接入】`CityCodeUtil.invalidate()` 走 `INVALIDATE_CITY_CODE`（`INCR` 版本 + `SET` 墓碑 5s）；读侧命中墓碑不回填；正常回填由 `CACHE_CITY_CODE_IF_VERSION_MATCH` Lua 校验租约后 `SET NX EX`。城市编码 TTL 仍为 7 天」 |
| 2.2「顺带一个现存 bug」L173-197 | 「DB 查询用原始 `cityName`（未 trim、大小写敏感）」「`.or()` 未用 `.and()` 包裹…属于隐患」 | `CityCodeUtil.java:64`（`trim().toLowerCase(Locale.ROOT)`）、`:83`、`:117-126`（`.and(w -> ...)` 已包裹并有注释） | key 分裂/缓存污染 bug 与 `.or()` 隐患**均已修复**；`RedisKeyConstants:131`→`:229-234` | 「【已修复】cache key 与 DB 查询统一使用 `normalized`（trim + `toLowerCase(Locale.ROOT)`）；`.or()` 已用 `.and(w -> ...)` 包裹。此段仅作改造前缺陷记录」 |
| 2.3 L207-235、L248 | 「`MessageMemory.get:57-69`…`redisMemory.overwrite(...)` 破坏性回填」「`RedisMemory:94-100`…`clear + append`」「唯一一处回填会丢数据」 | `MessageMemory.java:61,72,182-184`；`RedisMemory.java:138-186`（`overwriteIfLeaseValid` + `OVERWRITE_IF_VERSION_MATCH`）；`HeapMemory.java:86-100`；`RedisScripts.java:169-235` | 破坏性 `overwrite` 已不存在，L1/L2 均改为「取租约 + 版本校验回填」；版本 key 实际为 `chat:history:version:{chatId}` | 「【已落地】`MessageMemory.get` 先 `currentVersion` 取租约，`RedisMemory.overwriteIfLeaseValid` 经暂存 key + `OVERWRITE_IF_VERSION_MATCH` Lua 原子校验后 `RENAME` 回填；写路径 `appendIfVersionMatch` 使在途租约失效」 |
| 3.1 L299-326 | 「`ChatManager:24-29` put + expire 非原子」「`ChatInfoService:82-85` 的 `restoreChat` 去掉 TTL」「`ChatManager:41` 注释 `//其实这里始终为真`」 | `ChatManager.java:34-45` + `RedisScripts.java:136-139`；`ChatInfoService.java:110-123`（`:120` 仍 `persist`）；该注释全库 0 命中 | 非原子已修复；注释引用失效；但 `persist` 语义问题仍在（属部分修复） | 「【已修复】`lockChat` 用 `HASH_SET_WITH_EXPIRE` Lua 原子化，`//其实这里始终为真` 注释已删除。【仍在】`ChatInfoService.restoreChat():120` 仍 `persist(chatInfoKey)`，TTL 语义矛盾未梳理」 |
| 3.2 L328-345 | 「`TicketServiceImpl:1005` 不校验持有者就删锁」 | `TicketServiceImpl.java:1288-1290`、`RedisLock.java:27,36`、`RedisScripts.java:142-147` | 已改为 UUID 令牌 + Lua 校验持有者释放（用的是手写 `RedisLock`，非文档建议的 Redisson） | 「【已修复】统计缓存锁统一由 `service/base/RedisLock.java` 管理，`unlock` 走 `RELEASE_LOCK_IF_MATCH` Lua 校验令牌」 |
| 3.3 L347-367；第一节 L20 交叉引用 | 「`:293-295` finally 无条件 delete」「`hasKey` 与 `delete` 之间没有原子性」「见 3.3」（布隆缺陷实际在 3.4） | `OrderSearchTool.java:46-53,218-219,240,269-272,275,282`；`OrderConfirmation.java:32-39` | 核心「并发双通过」已由 `CANCEL` Lua 修复；`hasKey` 预检、`finally` 冗余 delete、消费与业务执行分离仍在；L20 交叉引用编号错误 | 「【部分修复】`CANCEL` Lua 已把令牌校验 + `HDEL` 合并为原子消费（绑定 orderId、含 5 分钟过期校验）；【仍在】`:240` 的 `hasKey` 预检、`:282` 的 `finally` 无条件 delete，且令牌消费与 `:275` 业务执行分离。另将 L20 的『见 3.3』改为『见 3.4』」 |
| 3.4 L369-380 | 布隆过滤器原地 delete 后重建 | `UserUsernameBloomFilterService.java:43-58` | **一致，无需修改**（仍未修复，根 `README.md:145` 自述准确） | 保留原文 |
| 四、演练顺序 L384-397 | 「建议起手：从第 1 步开始」 | 见 2.1/2.2/2.3 各条 | 四步演练均已落地，仍是「建议」口吻 | 「本表为改造前的实施顺序记录；四步均已完成，可作为理解 07/08/09 篇设计动机的阅读顺序」 |

#### docs/learn/redis/README.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 阅读约定 L18-19；开头 L10 | 「路径基准…`TaxiAgent/src/main/java/com/fancy/taxiagent/`」「代码版本 `main` 分支 `bdc40d7`」；L10 自称「本系列一律以当前代码为准」 | `pom.xml:12`、`application.yaml:5`、`<base>/`（264 个 `.java`） | 自称现状却指向不存在的包，且 `bdc40d7` 之后有 `a8f4e33` 大改 | 「路径基准：`RideAgentHub/src/main/java/com/fancy/rideagenthub/`；`bdc40d7` 为本系列撰写时的历史快照，当前行为一律以源码为准（后续被 `a8f4e33` 安全改造大幅改写）」 |
| 基础设施现状 L28 | 「Lettuce 配置在 `application.yaml:25-38`」 | `application.yaml:25` 是 ES 段；Redis 段为 `:27-40`，`lettuce.pool` 在 `:35-40` | 数值（12 / 3000ms）正确，范围起点落在 ES 配置上 | 「Lettuce 配置在 `application.yaml:27-40`（`lettuce.pool` 在 `:35-40`，`max-active 12`、`max-wait 3000ms`、`timeout 3000ms`）」 |
| 基础设施现状 L30；目录 #10 L65 | 「Lua 脚本**全部集中**在 `util/RedisScripts.java`」「**7 个脚本**的原子性速查表」 | `RedisScripts.java` 22 个脚本 + 5 个业务类中 11 个内联脚本 | 见共性 4 | 「Lua 主流程脚本集中于 `util/RedisScripts.java`（当前 22 个）；`TokenServiceImpl`、`TicketServiceImpl`、`EmailCodeService`、`OrderConfirmation`、`OrderSearchTool` 另有本地脚本。目录 #10 改为『22 个脚本速查表』」 |
| 基础设施现状 L31 | 「Key 常量**全部集中**在 `RedisKeyConstants.java`，**不在业务代码里就地拼 key**」 | `TicketServiceImpl.java:85,709`（`ticket:no:`）、`ToolResponseMemory.java:39`（`chat:tool:v2:`）、`IdGeneratorConfig.java:16`（`id:node:`）、`TokenServiceImpl.java:48`（`auth:session-version:`）、`EmailCodeService.java:89`（`:attempts/:owner/:claim`） | 至少 6 处就地拼 key；根 `README.md:141` 已承认 `ticket:no:` 例外，本篇过于绝对 | 「主要 key 由 `RedisKeyConstants` 统一生成；例外：`TicketServiceImpl` 的 `ticket:no:`、`ToolResponseMemory` 的 `chat:tool:v2:`、`IdGeneratorConfig` 的 `id:node:`、Token/EmailCode 的会话版本与预留 key 后缀」 |
| 目录 #05 L50 | `service/impl/TicketServiceImpl.java:1146` | `TicketServiceImpl.java:1146` 是 `if (lockToken != null)`；随机 TTL 常量在 `:87-88`、应用处 `:1191-1192` | 行号指向锁逻辑而非随机 TTL | 「`service/impl/TicketServiceImpl.java:87-88, 1191-1192`」 |
| 目录 #15 L75 | 「`GEOSEARCH` 按距离排序」 | `RedisScripts.java:69,77` 实际 `GEORADIUS`（兼容 Redis 5）；`api/driver-location.md:54`、`README.md:124` 均为 GEORADIUS | 命令名错误，与 15 篇正文同源 | 「`GEORADIUS` 按距离排序（兼容 Redis 5）」 |
| 未实现的考点 L135 | 「权限缓存｜`aspect/PermissionAspect.java` **每次请求直查 DB**」 | `PermissionAspect.java:33-46`（角色来自 `UserTokenContext.getNullable()`，全类无 Mapper/RedisTemplate）；DB 校验在 `TokenServiceImpl.java:74-77` | 结论「无权限缓存」仍成立，证据描述错误 | 「权限缓存｜全项目无 `user:role:*` 缓存；`PermissionAspect` 直接读取 `UserTokenContext` 中的角色，角色值随登录令牌在 `TokenServiceImpl.getToken()` 处由 DB 校验」 |
| 未实现的考点 L136 | 「注意：`ChatManager.java:62` 续的是会话 key」 | `ChatManager.java:62` 是 `initChat`；续期在 `:70` | 语义正确、行号漂移 | 「（注意：`ChatManager.java:70` 续的是会话 key，不是 token）」 |
| 未实现的考点 L127、L137 | 「当前代码中确认没有实现」「接口幂等｜无幂等注解/切面；**仅有局部近似**（14 抢单预检、11 取消令牌）」 | `OrderConfirmation.java:16-39`、`ChatServiceImpl.java:231-239`、`OrderTool.java:441`、`OrderSearchTool.java:46-53`、`OrderGrabService.java:77` | 「无通用幂等注解」仍准确，但遗漏 `confirmationId` 一次性令牌 / CANCEL 原子消费；`11-two-phase-confirm-token.md:472` 所称「可重复下单」也已过时 | 「接口幂等（通用注解/切面）仍未实现；局部一次性令牌已有三处：`OrderConfirmation`（`confirmationId` + CONFIRM/CONSUME，`createOrder` 前消费）、`OrderSearchTool` 的 `CANCEL` Lua、`OrderGrabService.GRAB_ORDER_ATOMIC`」 |
| 与原始文档的关系 L145-146 | 「3.1/3.2 已修复；**3.3、3.4 未修复**」「`docs/01`…**P2 未实现**」 | 见共性 3：`CANCEL` Lua 已修 3.3；`ChatInfoService.java:120` 的 `persist` 说明 3.1 是部分修复；P2 接口幂等已局部落地；3.4 仍未修 | 三格与当前代码不符 | 「2.1/2.2/2.3 三解法已实现；3.1 部分修复（`HSET+EXPIRE` 原子化，`restoreChat` 的 `persist` 仍在）、3.2 已修复、3.3 已用 `CANCEL` Lua 原子消费（但预检/冗余删除/检查与业务分离仍在）、3.4 未修复。P2 除接口幂等有局部实现外，其余未实现」 |
| 基础设施 L27、L29；目录表 25 篇；按数据结构表 L110-121 | 「只用 `StringRedisTemplate`、没有裸 `RedisTemplate`」「没有自定义 `RedissonConfig`」；25 篇核心代码路径 | 全库 `\bRedisTemplate\b` 0 匹配；`config/` 9 个文件无 `RedissonConfig`；25 个类均在位 | **一致，无需修改** | 保留原文 |
| 未实现的考点其余 5 行 L131-134、L136 | BitMap / ZINCRBY / HyperLogLog / Pub-Sub / Token 滑动续期 | 全库 `setBit\|bitCount\|BITFIELD`、`incrementScore\|ZINCRBY`、`pfAdd\|pfCount`、`convertAndSend\|RedisMessageListenerContainer` 均 0 匹配；`AuthTokenInterceptor.java:29-51` 只 get 不刷新 | **一致，无需修改** | 保留原文 |

#### docs/learn/agent/README.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 首段 L3 | 「本项目 AI Agent 层（`src/main/java/com/fancy/taxiagent/agentbase/`、`agents/`）」 | `<base>/agentbase/`（122 个条目）、`<base>/agents/`（5 个文件）；`application.yaml:5` | 本篇未标注历史版本，属纯现状描述错误，路径无法定位任何源码 | 「…（`src/main/java/com/fancy/rideagenthub/agentbase/`、`agents/`）…（旧包 `com.fancy.taxiagent` 已随项目改名 RideAgentHub 废弃）」 |
| 候选主题表 L15、L16 | 「记忆系统的分层存储｜`agentbase/memory/`（Heap / Redis / MySQL / **ES**）」「消息解析与 Advisor 机制｜`MessageParser`、`MemoryAdvisor`」 | `ESMemory.java:6,9`（「规划中」空类，仅 `@Component`）；`MemoryAdvisor.java:14,19`（两个 advise 方法均 `return null`）；`MessageParser.java`（真实实现） | `ESMemory` 并非「ES 实现」，`MemoryAdvisor` 是空壳骨架；作为「可学习内容」会误导 | 「记忆系统的分层存储｜`agentbase/memory/`（Heap / Redis / MySQL 已实现；`ESMemory.java` 为规划中的空占位类）」；「消息解析与 Advisor 机制｜`MessageParser`（已实现）、`MemoryAdvisor`（骨架，advise 方法返回 null）」 |
| 候选主题其余行 L13-14、L17-18；L7 | 4 个 Agent、工具注册、RAG、高德/和风 | `agents/`、`agentbase/tool/`、`agentbase/rag/`、`agentbase/amap/`、`agentbase/qweather/` | **一致，无需修改** | 保留原文 |

---

### 3.3 `src/main/resources/api/` 变更说明（整体与代码高度一致）

这 5 篇写于 `a8f4e33`（`driver-location.md` 写于 GEO 改造提交），是**当前代码最可靠的文档**。逐条核查结论如下。

#### auth-security.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| L3「接口路径保持 …」 | 「接口路径保持 /auth/email-code、/auth/register、/auth/login/password、/auth/login/email-code、/auth/password/reset、/auth/logout、/auth/account」 | `AuthController.java:82`（`GET /auth/username/available`）、`UserController.java:73`（`POST /user/current/update`，含 email 改绑）、`:83`（`/user/current/password/reset`）、`:93-157`（`/user/admin/**` 7 个）、`:163`（`/user/rebuild`） | 所列 7 个 `/auth` 路径仍全部存在；但作为《认证接口变更》的接口清单，漏了后期新增的用户名可用性检查与整套 `/user/**` 账户管理接口 | 「原有 7 个 `/auth` 路径保持不变；另新增 `GET /auth/username/available`（用户名可用性检查，无需登录、仅受来源地址限流），以及 `/user/current/update`（修改资料，含改绑邮箱）、`/user/current/password/reset`、`/user/admin/**`（管理员账户管理，需 ADMIN）、`/user/rebuild`（布隆过滤器重建）等账户类接口」 |

**其余段落经核查一致**：L5「5 次错误后失效 / 预留-消费-释放」→ `EmailCodeService.java:35-69,114,151`；L7「120 次/分钟、HTTP 429、直接来源地址」→ `RequestRateLimitInterceptor.java:20-23` + `WebMvcConfig.java:25` + `GlobalExceptionHandler.java:36`；L9「原子发布 / 会话版本 / DB 凭据摘要」→ `TokenServiceImpl.java:29-42,71-78`；L11「同角色唯一索引 / 404」→ `sql/init.sql:13-17`、`UserController.java:46`、`UserPOIController.java:42`。

#### chat-security.md — 经复核有补正（见第零章 M9）

逐条核查依据：`ChatController.java:42,49,56,62,75,83` 六个端点与 HTTP 方法一致；`ChatRequestRunner.java:30-47`（UUID 格式、Redisson 单轮锁、`assertOwned`）；`ChatParamDTO.java:13-15`；`OrderConfirmation.java:19,20-22,37`（15 分钟、幂等重放、`HDEL`）；`OrderTool.java:407-413`（mark 只读）；`ToolRepPointerTool.java:22-23`（`getCalling`/`callId`）；`GlobalExceptionHandler.java:15-29,36`（400/401/403/409/410）；`ChatInfoService.java:41-42,47-49`（永久停用、410）；`controller/` 下无清空端点。**建议**：L3「chatId 必须是 `/chat/v2/newuuid` 返回的 UUID」措辞偏严（代码只做 `UUID.fromString` 格式校验且不登记），可改为「chatId 必须为 UUID 格式，且会话须属于当前用户」。

#### driver-location.md — 经复核有补正（见第零章 M9）

逐条核查依据：`RideOrderController.java:235,252,266,281` 四端点；`DriverGeoIndex.java:23,31-35`（60 秒、经纬度范围）；`DriverLocationServiceImpl.java:31-36,53-58`（必填、503）；`OrderGeoPool.java:31-34`（3km/200）；`RedisScripts.java:55-66,77-78`（版本/锁令牌、`GEORADIUS … ASC COUNT 200 WITHDIST`）；`RideOrderServiceImpl.java:1127-1137,1187-1188,1197,1214-1216`（两位小数、total、降级倒序）；L54「`GEORADIUS`，兼容 Redis 5+」与代码**完全一致**。**建议**：在 L16 补一句「20 秒为客户端上报建议值，服务端只按 60 秒 `ONLINE_TTL` 判定」，避免读者以为代码有 20 秒常量。

#### order-security.md — 经复核有补正（见第零章 M9）

逐条核查依据：`RideOrderController.java:49-51,69,138-141`；`RideOrderServiceImpl.java:100-109,133,146,175-188,583-595,621-623,895`；`PaymentConfig.java:13-17`（默认 503）；`OrderSearchTool.java:48-52,94-96,116,275-288,301,420-421,482-483`；`RidePricing.java`；`BusinessTime.java:8`。**建议**：L11「改目的地与结束行程共享订单锁并比较旧轨迹标识」主语含糊——旧轨迹标识比较只在改目的地路径（`OrderSearchTool.java:420-421`）实现，`finishRideUnderLock`（`RideOrderServiceImpl.java:484-541`）未加该条件，宜拆成两句分别描述。

#### rag-consistency.md — 经复核有补正（见第零章 M9）

逐条核查依据：`RagController.java:29-89`（全 `@RequirePermission({"ADMIN"})`）；`RagService.java:188-291,466-491`（`@Transactional` + `syncJobs.enqueue`）；`QaIndexSynchronizer.java:31`（5000ms）；`QaSyncJobMapper.java:12-15`（`FOR UPDATE SKIP LOCKED`、30 秒）；`RagService.java:47-48,309,314,328,395-419`（8 秒共享 deadline、权威库核对）；`AsyncWorkConfig.java:9-25`（线程池 4/8/32、8/32/64）；`TicketController.java:47-50`、`TicketServiceImpl.java:112-118,202,473,507-513,518,555-563,561-563,581,598,613,724`。**建议**：补一句「`rag.sync.interval-ms`、`rag.search.timeout-ms` 及线程池参数当前只以 `@Value` / `@Scheduled` 默认值与 `AsyncWorkConfig` 形式存在，未在 `application.yaml` 中显式配置」，便于运维定位。

---

### 3.4 `docs/learn/redis/` 考点笔记 01–06

> 各篇 L5 的「完整路径」与全篇行号统一按**共性 1、共性 2** 处理，下表只列语义性差异。

#### 01-shared-session-token.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 二、结构选型 L34-39 | `auth:token:{token}` 会话主体字段清单；「索引 TTL 2 倍关系来自 `TokenServiceImpl.java:93`」 | `security/UserToken.java:61-63`（新增 `sessionVersion`、`credentialStamp`）；`TokenServiceImpl.java:48`（`auth:session-version:{userId}`）；`:33`（Lua 内 `EXPIRE … *2`） | 新增第三个 key 与两个载荷字段，key 表/字段清单不完整 | 「新增 `auth:session-version:{userId}`（无 TTL 的版本计数器）；`UserToken` 另含 `sessionVersion` / `credentialStamp`；索引 TTL ×2 由 `SAVE` Lua 一次设置」 |
| 三、代码落点 / 4.3 L48-53、L98-113 | 「`saveToken` 写主体 + 建索引，同一方法内但**不是**原子」「`addTokenToUserIndex` SADD + EXPIRE」「按用户踢下线遍历索引逐个删」 | `TokenServiceImpl.java:29-35`（`SAVE` 原子 SET+SADD+EXPIRE 且校验版本）、`:36-42`（`REVOKE` INCR 版本 + 批量 DEL）、`:93-101`（`afterCommit`）、`:102-104`（`addTokenToUserIndex` 抛 `UnsupportedOperationException`） | 写入、单删、全删三步全改为 Lua 原子操作，并引入会话版本号 | 「`saveToken` 用 Lua `SAVE` 原子写主体+索引；`deleteAllTokensByUserId` 用 `REVOKE` 递增版本并整批删除，注册到 `afterCommit`；`addTokenToUserIndex` 已废弃（抛异常）」 |
| 4.4 L115-135 | 「**非原子**：GET → SREM → DEL 三条命令」「当前保留现状（已知残留窗口）」 | `TokenServiceImpl.java:43-47`（`REMOVE`：`DEL` + `SREM`）、`:84-92` | 三步序列漏踢窗口已不存在，顺序也反了 | 「`deleteToken` 用 Lua `REMOVE` 把 `DEL` 与 `SREM` 收成一次原子执行；仅在 JSON 解析失败时退化为单条 `delete`（`:91`）」 |
| 4.1 / 六-6 L77、L182 | 「反序列化失败按未登录处理：`getToken` 捕获异常后 `log.error` 返回 null」 | `TokenServiceImpl.java:66-83`（校验 sessionVersion、查 `sys_user` status/is_deleted、比对 role 与 `CredentialStamp`）、`:79-81`（`log.warn`） | 读取路径新增会话版本 + DB 权威凭据/状态校验 | 「`getToken` 会二次校验会话版本、账号状态/角色与 `credentialStamp`；任一不符即返回 null，避免改密后旧令牌复活」 |
| 六-2、六-3 L178-179 | 「`deleteToken` 三步非原子」「`deleteAllTokensByUserId` 也是非原子序列」标为「真实、可触发的残留窗口」 | 同上 | 两条缺陷均已修复 | 「✅ 已修复：`deleteToken` / `deleteAllTokensByUserId` 均改为 Lua 原子执行；全量撤销额外递增会话版本并挂到 `afterCommit`，并发新登录的令牌也会因版本不符被拒绝」 |
| 五、设计取舍 L171 | 「存储与索引分两条命令写｜两条命令失败概率极低…」 | `TokenServiceImpl.java:29-35`（`SAVE` 一次完成 SET/SADD/EXPIRE，且先校验版本） | 「现状」描述已被实现推翻 | 「采用 Lua `SAVE` 一次原子写入，并以会话版本校验拒绝过期登录态签发」 |
| 一致要点 | TTL `604800`、索引 ×2、两个 key 前缀、验证命令 key 名、无滑动续期 | `AuthProperties.java:18`、`application.yaml:85`、`RedisKeyConstants.java:20,38`、`AuthTokenInterceptor.java:29-51` | **一致，无需修改** | 保留原文 |

#### 02-email-code-cooldown.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 二 / 七 L29、L46、L197 | 冷却 key `auth:email_code:cd:{scene}:{email}` 值「占位值 `"1"`」；`GET` 期望 `"1"` | `EmailCodeService.java:38,82,89-90`（值为 `issueId = UUID.randomUUID().toString()`） | 值改为本次发码 `issueId`，用于回滚归属校验 | 「冷却 key 值为本次发码 `issueId`（UUID），供 `CLEANUP` 校验归属；`redis-cli GET …cd:…` 返回 UUID 而不是 `"1"`」 |
| 二 / 三 L31、L42-43、L49-51 | 「`sendCode` 冷却检查 → 生成 → 写码 → 写冷却 → 发信」「`isInCooldown` 用 `hasKey`」 | `EmailCodeService.java:35-42`（`ISSUE` 原子检查 + 三条 SET）、`:89-91`（新增 `:attempts`/`:owner`/`:claim`）、`:130-134`（`isInCooldown` 已无调用方） | 冷却检查与写入合并为一次 Lua；key 表漏三个附属 key | 「新增 `{codeKey}:attempts` / `:owner` / `:claim`；`sendCode` 用 Lua `ISSUE` 原子完成冷却检查+写码+写冷却+写 owner，并发请求只有一个返回 1」 |
| 4.2 L74-84 | 「**当前实现没有这么做**，属于已知的并发重复发送窗口」「把 `set` 换成 `setIfAbsent`」 | `EmailCodeService.java:35-42,89-91` | 窗口已闭合，但采用 Lua 而非 `setIfAbsent` | 「✅ 已修复：改用 Lua `ISSUE` 原子检查冷却/`:claim` 并写码，抢不到的请求直接 429，不会重复发信」 |
| 4.4 L112-151 | 「GET 与 DEL 是两条独立命令…同一个验证码被消费两次」「**修复方向（代码未改）**」 | `EmailCodeService.java:43-58`（`VERIFY`）、`:107-125`（`verifyAndConsume` + `afterCompletion` → `COMPLETE`） | 已改为 Lua 原子消费 + 事务内「预留/提交」两阶段 | 「✅ 已修复：`VERIFY` Lua 在服务端完成『比对通过才消费』；事务内先写 `:claim` 预留，`afterCompletion(STATUS_COMMITTED)` 再由 `COMPLETE` 删除码，回滚则保留」 |
| 4.4 末 / 六-2 L153、L180 | 「比对失败时不计数、不锁定…可以**无限次尝试**」「**没有失败次数限制**」 | `EmailCodeService.java:47,49-52`（5 次上限 `DEL` 验证码） | 5 次失败上限已实现 | 「✅ 已修复：`:attempts` 计数，5 次错误即作废验证码；尝试次数 key 的 TTL 跟随验证码剩余寿命」 |
| 4.3 L86-110 | 「回滚本身是两条独立 `DELETE` + 抛异常，非原子」 | `EmailCodeService.java:59-63`（`CLEANUP` 校验 `:owner == issueId` 后 `DEL`）、`:151` | 回滚已原子化且带归属校验 | 「✅ 已修复：回滚改由 Lua `CLEANUP` 原子执行，并校验 `issueId` 归属，避免删掉重发的新码」 |
| 4.5 L159 | 「随机源是 `java.util.Random`（`static final Random RANDOM`）」 | `EmailCodeService.java:15,34,81`（`SecureRandom`） | 已换密码学安全随机源 | 「随机源已改为 `java.security.SecureRandom`（:34）」 |
| 4.4 修复方向 / 一 L151、L17 | 「项目已有 `RedisScripts` 集中存放 Lua 可直接复用」「校验成功立即失效」 | `EmailCodeService.java:35-69`（4 个脚本类内声明）；`RedisScripts.java` 无验证码脚本；`:113-123` 事务内为 `reserve` | 脚本未放进 `RedisScripts`，「立即失效」也不成立 | 「验证码的四个 Lua 脚本就地声明在 `EmailCodeService` 内；事务内校验成功只是预留，提交后才删除」 |
| 七、验证第 5 步 L210-220 | 「# 实际可能：两个都成功 → 即缺陷」 | `EmailCodeService.java:43-58` | 复现步骤的预期结论已反转 | 「期望：一个成功、一个 400；Lua 保证同一码至多被消费一次」 |
| 一致要点 | key 前缀、TTL 300/60、scene 参与 key、明文存码、三处消费点 | `RedisKeyConstants.java:26,32`、`AuthProperties.java:23,28`、`AuthServiceImpl.java:85,139,167` | **一致，无需修改** | 保留原文 |

#### 03-cache-penetration-bloom.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 一 / 三 / 4.1 L17-19、L52、L82-90 | 「把『一定不存在』在 Redis 侧就地得出，**不产生任何 SQL**」「`isUsernameAvailable` 唯一的查询调用方」「`if (!mightContain(username)) return true;`」 | `AuthServiceImpl.java:258-268`（只做参数校验后直查 `findByUsernameAndRole`）；全库 `mightContain` 仅剩定义 `UserUsernameBloomFilterService.java:27`，0 调用方 | 查询链路不再经过布隆过滤器，`mightContain` 成为死代码 | 「当前 `isUsernameAvailable`（`AuthServiceImpl.java:258-268`）直接查 DB；`mightContain` 暂无调用方，布隆过滤器仅剩注册/建号后的 `addUsername` 与 ADMIN 手动 `rebuild`」 |
| 4.4 L129-134 | 「`sys_user` 表**没有 username 唯一索引**」「用户按这个结果注册，就会产生**重名用户**」 | `sql/init.sql:13-17`（`active_username` 生成列 + `uk_active_username_role`）；`AuthServiceImpl.java:81`（注册前校验 409） | DB 唯一索引与注册前置校验两道兜底已存在 | 「`init.sql:16` 已有 `uk_active_username_role` 唯一索引，且 `registerByEmailCode:81` 会先校验用户名；重建空窗即便误报可用，也无法落库重名用户」 |
| 六-1 L183 | 「重建期正确性窗口…可能真的落库重名用户。**现存缺陷，未修**」 | `UserUsernameBloomFilterService.java:42-44`（仍原地 delete 重建）；`AuthServiceImpl.java:266`（直查 DB） | 「缺陷仍在」但后果已变 | 「原地删除后重建仍在（`:43-44`），存在重建期假阴性；但因 `isUsernameAvailable` 已直查 DB 且有唯一索引兜底，该窗口当前不影响业务正确性」 |
| 六-6 L188 | 「注册流程本身**只校验邮箱**，不校验用户名唯一性」 | `AuthServiceImpl.java:81-82` | 唯一性校验已补齐 | 「✅ 已修复：注册先校验用户名可用性（:81），再校验邮箱（:82）」 |
| 一致要点 | 类存在、`auth:user:username:bloom` key、`1000000` / `0.01`、SQL 口径、每次 `tryInit`、原地 delete 重建、`GET /auth/username/available`、`POST /user/rebuild` | `RedisKeyConstants.java:44,222-224`、`UserUsernameBloomFilterService.java:16-17,43-44,63-67`、`UserAuthMapper.java:16`、`AuthController.java:82-86`、`UserController.java:162-167` | **一致，无需修改** | 保留原文 |

#### 04-cache-breakdown-mutex.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 三 / 4.3 L66-68、L147-148、L160 | `RedisLock.java:49-56` tryLock、`:65-71` unlock、`RedisScripts.java:53-59` | `RedisLock.java:22-28,31-41`（全类 42 行）；`RedisScripts.java:142-147` | 组件压缩到 42 行，所有行号失效；脚本本体与「校验持有者」语义一致 | 「`RedisLock.java:22-28`（tryLock）、`:31-41`（unlock）、`RedisScripts.java:142-147`（`RELEASE_LOCK_IF_MATCH`）」 |
| 4.3 L135-142 | 「`if (key == null … ) { return null; }`」 | `RedisLock.java:23-25`（抛 `IllegalArgumentException`） | 非法参数由静默返回 null 改为抛异常 | 「`tryLock` 对空 key / 非正 TTL 抛 `IllegalArgumentException`；成功返回 UUID 令牌，竞争失败返回 `null`」 |
| 4.3 要点 4 / 六-3 L147、L160、L251 | 「`RedisLock.java:12-27` 类注释明确写了这次收拢」「类注释自己声明两点局限：不可重入、没有续期」 | `RedisLock.java:13`（类注释仅一行「用唯一令牌标识持有者的不可重入 Redis 锁」）、`:35-40`（unlock 吞异常只 `log.warn`） | 引用的长注释块不存在；「没有续期」是推断而非注释声明 | 「类注释只声明『不可重入』；无看门狗续期由固定 5s TTL（`TicketServiceImpl.java:89`）体现；`unlock` 释放失败只告警，由 TTL 兜底」 |
| 一致要点 | `setIfAbsent` + 双检 + 3×60ms 有限重试 + 降级直查 DB；令牌锁释放校验持有者；TTL 20+0~10s / 锁 5s / 延迟双删 500ms；两个 key 前缀 | `RedisLock.java:27,36`、`TicketServiceImpl.java:87-91,100,1148-1170,1191-1195,1236-1250`、`RedisScripts.java:142-147`、`RedisKeyConstants.java:92,98` | **一致，无需修改**（`README.md:145`「令牌锁释放会校验持有者」属实） | 保留原文 |

#### 05-cache-avalanche-random-ttl.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 二、结构选型末 L47 | 「`grep ThreadLocalRandom` 全项目只有两处使用…没有第三处」 | `TicketServiceImpl.java:50,1192`、`RideOrderServiceImpl.java:52,1386`、`ChatRequestRunner.java:34`、`IdGeneratorConfig.java:7,17` | 新增两处（实例 owner / 雪花 workerId 随机化），共 4 处 | 「全项目 4 处 `ThreadLocalRandom`：统计缓存 TTL（:1192）、订单号后缀（`RideOrderServiceImpl.java:1386`）、`ChatRequestRunner:34`、`IdGeneratorConfig:17`；只有第 1 处与 TTL 抖动有关」 |
| 六-1 L146 | 「这些索引**每次写入都会重算并续期整体 TTL**…只要还有流量就不会真的到期」 | `OrderGeoPool.java:64-73`（写入执行 `INVALIDATE_ORDER_GEO_POOL`：换版本 + `DEL`）、`:71` 注释「固定 TTL 不因写入续期」；`RedisScripts.java:64,107`（仅重建发布时 `PEXPIRE`） | 「写入即续期、永不到期」与当前「固定 TTL + 写入失效重建」语义相反 | 「`ticket:pool:*` 与 `order:geo:pool` 的 TTL 固定 2 小时（`OrderGeoPool.java:31`、`TicketPoolIndex.java:34`），只在读侧重建发布时由 Lua 重设为固定值；写入侧是换版本 + 删除，不续期」 |
| 一致要点 | `20 + nextInt(11)` → `[20,30]`、单条 `set(key,json,ttl,SECONDS)`、三个 key 前缀与验证命令 | `TicketServiceImpl.java:87-88,1191-1195`、`RedisKeyConstants.java:92,152,189,285-288,318-320` | **一致，无需修改** | 保留原文 |

#### 06-null-sentinel-and-prewarm.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 二、结构选型 L32-37 | `order:status` 2 小时、`__MISSING__` 60 秒、`driver:active` 6 小时；三个 TTL 常量 | `OrderGrabService.java:29-30`（只剩 `IDLE = "__IDLE__"`、`CACHE_TTL = Duration.ofMinutes(5)`）；`:63-64,74,80` 全用 5 分钟 | 三个 TTL 常量全消失，统一 5 分钟 | 「`OrderGrabService` 统一使用 `CACHE_TTL = Duration.ofMinutes(5)`（:30），订单状态与司机占位 key 均为 300 秒，不再有 2 小时 / 60 秒 / 6 小时分档」 |
| 二、哨兵常量 L39-44、L52 | 「两个哨兵常量…`IDLE_SENTINEL`、`MISSING_SENTINEL = "__MISSING__"`」 | 全库 `__MISSING__` 0 命中；`OrderGrabService.java:29` 为 `IDLE` | 空值哨兵整体移除、常量改名 | 「只剩 `IDLE = "__IDLE__"`（`OrderGrabService.java:29`）；订单不存在不再落 `__MISSING__`」 |
| 三 / 4.3 L62-65、L117-138 | 「`tryGrab` DB 也没有则落 `__MISSING__`」「`refreshOrderStatus` 落 60 秒哨兵」「`warmUpDriverActive` 回源 DB」「两种 TTL：7 天 vs 60 秒」 | `OrderGrabService.java:42-75`（`@Transactional(MANDATORY)` + `FOR UPDATE` 锁司机账号）、`:71-73`（`ORDER_NOT_FOUND` 不写 key）、`:107-110`（`refreshOrderStatus` 只 `afterCommit` 删缓存）；全类无 `warmUpDriverActive` | 预热合并进 `tryGrab`；刷新改为删缓存；TTL 对比整节失效 | 「`warmUpDriverActive` 已合并进 `tryGrab`（:42-75）：事务内 `FOR UPDATE` 查司机进行中订单并回填 `driver:active`；订单不存在直接返回 `ORDER_NOT_FOUND`，不写哨兵；`refreshOrderStatus` 只做提交后删除。保留的 TTL 分档只有 `CityCodeUtil` 的 7 天」 |
| 4.4 L140-162 | 「`releaseDriver(String driverId)` … `set(..., IDLE_SENTINEL, TTL)`」「最容易写错的是顺手写成 `DEL`」 | `OrderGrabService.java:121-126`（`releaseDriver(driverId, orderId)` → `afterCommit(() -> rollbackGrab)`）、`:112-119`（`RELEASE_LOCK_IF_MATCH` 按 orderId 比对后 `DEL`） | 释放改为「事务提交后按持有者原子条件删除」，签名多一个 orderId | 「`releaseDriver(driverId, orderId)` 在 `afterCommit` 调 `rollbackGrab`，用 `RELEASE_LOCK_IF_MATCH` 校验 orderId 后才删除占位；`__IDLE__` 由 `tryGrab:63-64` 在回源确认空闲时写入」 |
| 三 / 4.1-4.2 L60、L85-92、L108-115 | 「`getCityCode` 读缓存 → 命中哨兵返回 null → 回源 DB → 写值或哨兵」「`SET key "__NULL__" EX 7d`」 | `CityCodeUtil.java:31,67-77,84-90`；`RedisScripts.java:157-166` | 读路径多了墓碑分支，写回改为「版本校验 + `SET NX EX`」原子脚本 | 「`getCityCode` 先读 `amap:city_code:version:{cityName}` 与缓存；命中 `__TOMBSTONE__` 则查 DB 且不回填；其余回源结果由 Lua `CACHE_CITY_CODE_IF_VERSION_MATCH` 在版本一致且 key 仍为空时以 `SET NX EX 604800` 原子写入」 |
| 五 / 六 / 七 L180、L190、L196、L206、L228-237 | 「`__MISSING__` TTL 取 60 秒」「`GET order:status:{不存在}` 期望 `"__MISSING__"`」「TTL 期望接近 7200 / 21600」 | `OrderGrabService.java:30,71-73` | 所有依赖 60s/2h/6h 分档与 `__MISSING__` 的表格、边界条目、验证期望值全部失效 | 「删去 `__MISSING__` 相关行；验证命令改为：不存在的 orderId 不产生任何 key；真实 `order:status:*` / `driver:active:*` 的 TTL 均期望 ≈300；`driver:active` 在事务预热后为 `__IDLE__` 或 orderId」 |
| 一致要点 | `__NULL__` 仍是空值哨兵、城市编码 7 天、`amap:city_code:` 前缀、`__IDLE__` 语义、两个 ready 标记 | `CityCodeUtil.java:23,33,89`、`RedisKeyConstants.java:50,104,110,122,181,229-234,301-310` | **一致，无需修改** | 保留原文 |

---

### 3.5 `docs/learn/redis/` 考点笔记 07–12

#### 07-delayed-double-delete.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 来源块 L5；全篇行号 L26、L65-74、L82-93、L107-121、L250、L276、L296、L300 | 旧包路径；`submitTicket :100`、常量 `:77~:90` 等 | `TicketServiceImpl.java:110`（submitTicket）、`:174`（afterTicketMutation）、`:87-91,100`、`:1135-1171`、`:1173-1185`、`:1187-1199`、`:1213-1219`、`:1236-1251`、`:1256-1267`、`:1278-1280` | 路径基准 + 全部行号漂移（共性 1/2） | 按共性 1/2 统一处理，并给出当前行号清单 |
| 「一、第一步」L37-50 | 「`afterTicketMutation` 统一做两件事：`evictTicketStatistics(); … afterCommit(() -> ticketPoolIndex.syncByTicketId(...))`」 | `TicketServiceImpl.java:1213-1219`（顺序**相反**：先注册索引失效，再失效统计缓存）、`:1204-1209`（新注释） | 语句顺序与注释语义都变了 | 「`afterTicketMutation`（`:1213-1219`）**先**注册 `afterCommit(() -> ticketPoolIndex.syncByTicketId(ticketId))`，**再**调 `evictTicketStatistics()`，保证统计缓存的独立故障不阻止索引版本推进」 |
| 4.3 L140-150 | 「`stringRedisTemplate.delete(cacheKey);` // 第一次删除：立即失效」 | `TicketServiceImpl.java:1236-1251`（第一次删除包在 `try/catch(RuntimeException)` 内，失败只 `log.warn`；第二次删除仍在 `:1248`） | 删除失败被容忍并交给延迟删除兜底 | 「第一次删除包在 `try/catch` 内，失败只告警、不中断；无论成败都会调度第二次延迟删除（`:1248`）」 |
| 4.5 L199-210、L95 | 「构造器 `this.scheduler = Executors.newSingleThreadScheduledExecutor(...)`」「全类只有 80 行」 | `DelayedCacheEvictor.java:20-24`（字段初始化 `private final ScheduledExecutorService executor`）、`:26-28`（构造器只注入 template）、全类 56 行 | 线程池创建位置、字段名、行数全变 | 「`DelayedCacheEvictor`（56 行）在字段初始化处建单线程守护调度器（`delayed-cache-evictor`），构造器只注入 `StringRedisTemplate`」 |
| 4.5 L218-231 | 「`log.debug("延迟删除缓存完成…")`、`catch (Exception e)`」「理由在类注释（安全性）」「删除失败只记日志（`:66-68`）」 | `DelayedCacheEvictor.java:14`（类注释仅一行）、`:30-42`（无成功 debug 日志、`catch RuntimeException`、`log.warn("延迟删除缓存失败…")`）、`:39-41`（`RejectedExecutionException` 兜底） | 引用的注释与日志已删除 | 「删除失败只记 `warn`（`:34-37`），调度被拒绝时记 `warn`（`:39-41`）；成功路径不记日志。类注释为『提交后延迟删除缓存，清理并发读请求回填的旧快照』」 |
| 4.5 L233-239 | 「`evictAfter` 的参数校验（`:58-60`）宁可静默返回也不抛异常」 | `DelayedCacheEvictor.java:30-42`（无校验）、`:44-55`（`shutdown` 新增 `awaitTermination(5s)` + `shutdownNow()`） | 参数校验已删除，shutdown 也变了 | 「`evictAfter`（`:30-42`）不再做入参校验；`shutdown()`（`:44-55`）会 `awaitTermination(5s)` 后按需 `shutdownNow()` 并恢复中断标志」 |
| 4.5 设计点 1 L215 | 「自己起 `ScheduledExecutorService`，没有用 `@EnableScheduling`。**项目当时没有开启 Spring 的定时任务支持**」 | `RideAgentHubApplication.java:8` `@EnableScheduling` | 启动类已开启 Spring 调度 | 「启动类 `RideAgentHubApplication.java:8` 已带 `@EnableScheduling`；`DelayedCacheEvictor` 仍自建独立的单线程守护调度器，与 Spring 调度器互不干扰」 |
| 一致要点 | 两个 key、TTL 20+0~10s、500ms 延迟、10 个写方法、`RedisLock` 重建锁、验证命令 key 名 | `RedisKeyConstants.java:92,98`、`TicketServiceImpl.java:87-88,100,1191-1195,1236-1250,1256-1267` | **一致，无需修改** | 保留原文 |

#### 08-city-code-tombstone.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 来源块 L5；全篇行号 L38-43、L51-60、L113-117、L168、L184、L186-235、L258 | 「`com/fancy/taxiagent/.../CityCodeUtil.java`（全类 122 行）」+ 全篇行号 | `CityCodeUtil.java`（129 行）、`:23,31,33,41,55-92,106-114,117-127`、`RedisKeyConstants.java:229-234` | 路径/行数/行号漂移；常量值全部未变 | 「`com/fancy/rideagenthub/.../CityCodeUtil.java`（129 行）；常量值不变：`NULL_SENTINEL="__NULL__" :23`、`TOMBSTONE :31`、`CACHE_TTL_DAYS=7 :33`、`TOMBSTONE_TTL=Duration.ofSeconds(5) :41`」 |
| 4.5(a) L154-184；6.3 L301 | 「修复后（`:62`）：`String normalized = cityName.trim();`」「`amapCityCodeKey` **仍然**会做 `toLowerCase()`…属于**残留隐患**」「依赖 `utf8mb4_0900_ai_ci` 的隐式约定」 | `CityCodeUtil.java:64`（`trim().toLowerCase(Locale.ROOT)`）、`RedisKeyConstants.java:233`（同）、`:60-64`（注释已改写） | key 与 DB 查询输入已完全一致，大小写隐患已彻底消除；「修复前/修复后」两段代码与当前实现不符 | 「（4.5(a) 已彻底修复）`CityCodeUtil.java:64` 一次做完全部规范化：`cityName.trim().toLowerCase(Locale.ROOT)`，cache key 与 DB 查询使用同一个 `normalized` 值；删除 6.3 第 1 点『大小写隐式约定』隐患」 |
| 4.5(b)/延伸阅读 L348、L59、L104 | 「墓碑是**纯 Redis 命令实现，没有使用 Lua**」「`invalidate`:106 `SET key "__TOMBSTONE__"`」 | `CityCodeUtil.java:111-113`（`INVALIDATE_CITY_CODE`）、`:84-90`（`CACHE_CITY_CODE_IF_VERSION_MATCH`）；`RedisScripts.java:150-166` | 读路径多了版本 key + Lua 校验，`invalidate` 不再是单纯 SET | 「`invalidate()` 走 `RedisScripts.INVALIDATE_CITY_CODE`：`INCR amap:city_code:version:{city}` + `SET` 墓碑 `EX 5`；读 miss 走 `CACHE_CITY_CODE_IF_VERSION_MATCH`，只有『版本仍匹配且缓存为空』才 `SET NX EX` 回填，防止墓碑过期后旧快照复活」 |
| 二、结构选型 L30-34、L45 | 表格只列 `amap:city_code:{cityName}`；「同一个 key 承载三种互斥取值语义」 | `RedisKeyConstants.java:236-238`（`amapCityCodeVersionKey`）；`CityCodeUtil.java:67` | 现在有 2 个 key | 「新增 `amap:city_code:version:{cityName}`（String 整数，无 TTL），由 `INVALIDATE_CITY_CODE` 递增，供回填脚本做租约校验」 |
| 七、验证 L322-323、L337 | 「手工模拟一次 `invalidate()`（**等价于** `SET key "__TOMBSTONE__" EX 5`）」「`KEYS "amap:city_code:*"` **只应出现一个**」 | `CityCodeUtil.java:111-113`、`RedisKeyConstants.java:237`（版本 key 同前缀） | 手工 SET 不再等价；`KEYS` 会额外返回版本 key | 「`#3` 改为调用 `CityCodeUtil.invalidate("北京")`（或 EVAL `INVALIDATE_CITY_CODE`），并 `GET amap:city_code:version:北京` 确认版本递增；`#6` 的 `KEYS` 需排除 `:version:` 再断言唯一」 |
| 6.1 L275 | 「全项目没有任何一处调用它（`grep invalidate` 只命中定义本身）」 | `src/test/java/com/fancy/rideagenthub/RedisCacheRegressionTest.java:208,215` | 生产代码无调用方仍成立，但回归测试已覆盖 | 「生产代码无调用方（仅 `RegeoTool`/`GeoRegeoTool` 读），但 `RedisCacheRegressionTest.java:198-221` 已覆盖 `invalidate()` 与墓碑过期后旧快照拒绝回填；墓碑处于『已就位并通过测试、业务写接口未接线』状态」 |
| 一致要点 | 四个常量名与值、前缀、`.and(...)` 包裹、命中墓碑不回填、`TTL 604800` 期望 | `CityCodeUtil.java:23,31,33,41,71-77,89,120-125`、`RedisKeyConstants.java:50,229-234` | **一致，无需修改** | 保留原文 |

#### 09-lease-token.md（语义变化最重的一篇）

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 来源块 L5、L519；全篇行号 L94、L98-133 | 「`com/fancy/taxiagent/...`」「测试类 `TaxiAgentApplicationTests`」「`RedisMemory.java`（217 行）、`MessageMemory.java`（211 行）」 | `<base>/…`；`src/test/java/com/fancy/rideagenthub/RideAgentHubApplicationTests.java`；`RedisMemory.java` 224 行、`MessageMemory.java` 222 行、`RedisScripts.java` 278 行 | 路径/测试类名/行数全变 | 按共性 1/2 处理，测试类改为 `RideAgentHubApplicationTests` |
| ⭐ 4.8 整节、二 L59、L103、L190、五 L444、七-3 L499-501 | 「`chat:history:version:{chatId}` TTL **与数据 key 相同**」「版本 key 与数据 key **必须同生命周期**」「`TTL` 期望与上面基本相同」 | `RedisMemory.java:188-193`（`expire()` 只续数据 key）；`RedisScripts.java:168-179`（`CURRENT_HISTORY_VERSION` 注释「版本号独立保留」+ `PERSIST`）、`:182-194,:213-227,:230-235` 均 `PERSIST` 版本 key；`README.md:132` | 4.8 整节推理（「版本归零 → 时钟倒转」）被刻意设计成相反方向；验证命令期望失效 | 「4.8 改为『版本 key 必须**永久保留**（`PERSIST`），数据 key 才带 TTL』，避免数据过期/清空后版本归零、旧租约复活；七-3 改为 `TTL chat:history:version:{chatId}` → 期望 `-1`，`PTTL chat:history:{chatId}` → ≤86400000（24h）」 |
| ⭐ 六-3 L476；4.8 L423-429 | 「**`clear(chatId)` 的两条 `DEL` 不是原子的**…已知残留，需 Lua 合并」 | `RedisMemory.java:195-202`、`RedisScripts.java:230-235`（`CLEAR_HISTORY_WITH_VERSION_BUMP` 原子 `INCR`+`PERSIST`+`DEL`） | 「仍是坑」已修复；`clear` 现在只删数据 key、递增版本 key | 「✅ 已修复：`clear()` 走 `CLEAR_HISTORY_WITH_VERSION_BUMP` 原子脚本，一次完成 `INCR 版本 + PERSIST 版本 + DEL 数据`」 |
| ⭐ 4.5 / 五 L315-328 | 「`ARGV[1]` > 0 则 `EXPIRE KEYS[1]`」「`EXPIRE` 放在脚本里…`ARGV[1] <= 0` 用于版本 key 不随数据过期」 | `RedisScripts.java:182-194`（`INCR KEYS[1]` → `PERSIST KEYS[1]` → `RPUSH` → `PEXPIRE KEYS[2]`/`PERSIST KEYS[2]`）；`RedisMemory.java:96` | `ARGV[1]` 从「版本 key 过期秒数」变成「**数据 key TTL 毫秒**」 | 「`ARGV[1]` 是数据 key 的 TTL 毫秒数（由 `FOCUS_CHAT_TTL`/`NON_FOCUS_CHAT_TTL` 换算）；脚本 `INCR` 版本 → `PERSIST` 版本 → `RPUSH` 数据 → `PEXPIRE 数据`。版本 key 永远不带 TTL」 |
| ⭐ 4.4 / 4.5 / 五 L245-250、L111、L277-281 | 「`execute(OVERWRITE_IF_VERSION_MATCH, …, leaseVersion)`（1 个 ARGV）」「校验版本 → `DEL 数据` → `RENAME 暂存`」 | `RedisMemory.java:163-168`（**2 个 ARGV**：leaseVersion、`HISTORY_TTL.toMillis()`）；`RedisScripts.java:213-227`（`INCR` 版本 + `PERSIST` + `RENAME` + `PEXPIRE`，不再 `DEL 数据`） | 参数个数与成功出口语义全变 | 「`OVERWRITE_IF_VERSION_MATCH` 有 2 个 ARGV（leaseVersion、数据 TTL 毫秒）；成功出口额外 `INCR 版本 + PERSIST 版本 + PEXPIRE 数据`，使租约在回填后自动失效」 |
| ⭐ 4.4 L240-243 | 「`rightPushAll(stagingKey, jsonList); expire(stagingKey, STAGING_TTL);`」 | `RedisMemory.java:150-160`（`SessionCallback` 内 `multi()` / `rightPushAll` / `expire` / `exec()`） | 两步合并为 Redis 事务 | 「暂存数据写入与 `STAGING_TTL` 设置在同一个 `SessionCallback`（`MULTI/EXEC`）里完成」 |
| ⭐ 4.3 / 一 L19、L145-162、L194-213 | 「`MessageMemory.get:52-76` 逐层回源」「写路径是**同时写三层**（L1→L2→L3）」 | `MessageMemory.java:39-79`（`isNewOwnedChat` 守卫、三层都 `getAll`、`redisLease` 在 L1 miss 后才捕获、L3 命中且 L2 回填成功才回填 L1、`tail`）、`:84-110`（先 `mysqlMemory.append` → `redisMemory.appendIfVersionMatch` → `heapMemory.appendIfVersionMatch`） | 读路径与写顺序全部重写 | 「`MessageMemory.get`（`:39-79`）先做归属校验，L1/L2/L3 均用 `getAll`，只有 `fillRedisWithLease` 返回 true 才回填 L1；`save`（`:84-110`）先写 MySQL，再按租约追加 L2/L1，失败清 L1」 |
| ⭐ 4.7 / 五 / 六-5 L365-379、L446、L480 | 「`fillHeapWithLease` 失败时 `heapMemory.clear(chatId)`」「4.7 整节『为什么要清空而不是保留』」 | `MessageMemory.java:196-198`（只调 `overwriteIfVersionMatch`）、`:192` 注释「租约失效时保留并发写入的内容」；`HeapMemory.java:118-125`（`clear` 保留递增后版本） | 论证方向被反转，「clear 分支」已不存在 | 「4.7 改为『L1 租约失效时**保留**而非清空：旧读请求不能清掉并发 `save` 刚追加的消息；`HeapMemory.clear()` 也只清消息、保留版本号』」 |
| 五 L447；L100、L410 | 「`currentVersion` 解析失败按 `0` 处理」「不抛异常」 | `RedisMemory.java:67-77`（走 `CURRENT_HISTORY_VERSION` Lua，读不到结果抛 `IllegalStateException`） | 文档明确说不抛异常，代码现在抛 | 「`currentVersion` 走 `CURRENT_HISTORY_VERSION` Lua：数据 key 不存在时归零并 `DEL 数据 key`、版本 key 有 TTL 时 `INCR` 并 `DEL 数据 key`，最后 `PERSIST` 版本 key；Java 侧读不到结果抛 `IllegalStateException`」 |
| 一 L23-24 | 「`ChatServiceImpl.java:97` 每轮 `memory.get(userId, id, 1)`」+ 4 个 Agent 行号 | `ChatServiceImpl.java:101`（`memory.get(userId, id, 100)`）；`DailyAgent.java:203`、`OrderAgent.java:336`、`SupportAgent.java:200`、`FallbackAgent.java:113` | `lastN` 由 1 变 100，行号漂移 | 「`ChatServiceImpl.java:101` `memory.get(userId, id, 100)`；四个 Agent 的 `save` 调用分别在 `DailyAgent:203`、`OrderAgent:336`、`SupportAgent:200`、`FallbackAgent:113`」 |
| 一致要点 | 三个 key 名、`overwriteIfLeaseValid`、`STAGING_TTL=1min`、FOCUS 24h / NON_FOCUS 30min、日志「回填租约已失效，放弃覆盖」 | `RedisKeyConstants.java:62,68,74`、`RedisMemory.java:36,138,173`、`MessageMemory.java:18-19`、`HeapMemory.java:86-100` | **一致，无需修改** | 保留原文 |

#### 10-lua-scripts-overview.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 标题 L1；来源块 L5；全篇「7 个」L17、L29、L35、L56-66、L96、L151、L179、L334、L344 | 「项目全部 **7 个脚本**的原子性总表」「`RedisScripts.java`（**234 行，7 个脚本**）」 | `RedisScripts.java` 278 行、**22 个** `DefaultRedisScript`；另有 11 个内联脚本（见共性 4） | 脚本数、行数、集中度全过时 | 「# Lua 脚本总览：`RedisScripts.java` 的 22 个脚本 + 各 Service 内联脚本」，来源块改为「`com/fancy/rideagenthub/util/RedisScripts.java`（278 行，22 个脚本；另有 11 个内联脚本）」 |
| 3.1 七个脚本总表 L56-66；3.2 调用点表 L72-78 | 7 行速查表 | `RedisScripts.java` 22 个脚本（用途清单见共性 4 对应代码位置） | 漏 16 个主脚本 + 11 个内联脚本 | 「把 3.1 换成 22 行表：`UPDATE_DRIVER_LOCATION`、`REMOVE_DRIVER_LOCATION`、`READ_DRIVER_LOCATION`、`INVALIDATE_ORDER_GEO_POOL`、`PUBLISH_ORDER_GEO_POOL`、`SEARCH_ORDER_GEO_POOL`、`INVALIDATE_TICKET_POOLS`、`PUBLISH_TICKET_POOL`、`READ_TICKET_POOL`、`CLAIM_DUE_ORDERS`、`HASH_SET_WITH_EXPIRE`、`RELEASE_LOCK_IF_MATCH`、`INVALIDATE_CITY_CODE`、`CACHE_CITY_CODE_IF_VERSION_MATCH`、`CURRENT_HISTORY_VERSION`、`APPEND_WITH_VERSION_BUMP`、`APPEND_HISTORY_IF_VERSION_MATCH`、`OVERWRITE_IF_VERSION_MATCH`、`CLEAR_HISTORY_WITH_VERSION_BUMP`、`GRAB_ORDER_ATOMIC`、`SYNC_ORDER_STATUS`、`SLIDING_WINDOW_RATE_LIMIT`；另加一节列 11 个内联脚本」 |
| ⭐ 4.5.7 整节 L292-317；3.1 L66；3.2 L78；六-7 L370；七-7 L425-435；八 L449 | 「`CHECK_DRIVER_ONLINE`」「`ARGV[2]=当前毫秒`、`ARGV[3]=ONLINE_TTL`」「`DriverGeoIndex.java:128`」 | 全库 `CHECK_DRIVER_ONLINE` 0 命中；`DriverGeoIndex.java:51`（`UPDATE_DRIVER_LOCATION`）、`:78`（`READ_DRIVER_LOCATION`）；`RedisScripts.java:11-12,35-36` 用 `redis.call('TIME')` | 脚本被替换，时间源改为 Redis `TIME`，不再由应用传当前毫秒 | 「4.5.7 改为『`DriverGeoIndex` 的三脚本』：`UPDATE_DRIVER_LOCATION`（`TIME`+`GEOADD`+`HSET`）、`REMOVE_DRIVER_LOCATION`（`HDEL`+`ZREM`）、`READ_DRIVER_LOCATION`（`TIME` 判心跳 + `GEOPOS`）；七-7 改为 `HGET driver:online:beat 7` / `GEOPOS driver:geo:online 7`，不再手工传 `now`」 |
| ⭐ 4.6 L151、L321-335、L332-335、L102、L108 | 「`RedisScripts.script(lua, Long.class)`（`:227-232`）」「7 个脚本**每一个都显式 return 一个整数**」「`setScriptText` 而不是 `setScriptLocation`」「类注释模板」 | `RedisScripts.java:276-277`（只有私有构造）；18 个 `DefaultRedisScript<Long>` + 4 个 `DefaultRedisScript<List>`（`:34,70,112,127`）；`:1-9` 无类注释模板 | 工厂方法、返回类型、类注释模板三处全失效 | 「4.6 改为：脚本用 `new DefaultRedisScript<>(文本块, Long.class)`（18 个）或 `DefaultRedisScript<List>`（4 个）直接构造；`RedisScripts` 只有私有构造，无 `script()` 工厂方法、无类注释模板」 |
| 4.5.1 / 六-5 L60、L183-187、L366 | 「`HASH_SET_WITH_EXPIRE` 恒定 `return 1`」 | `RedisScripts.java:136-139`（`return redis.call('EXPIRE', KEYS[1], ARGV[3])`） | 返回值变成 `EXPIRE` 结果 | 「`HASH_SET_WITH_EXPIRE` 返回 `EXPIRE` 的结果（`1`/`0`），仍不反映字段是否新建」 |
| ⭐ 4.5.3/4.5.4/4.5.6 L62、L65、L209-218、L226-240、L271-290 | 「`ARGV[1]`=版本 key 过期秒数」「`ARGV[1]`=当前时间毫秒、`ARGV[4]`=member」「`ChatRateLimiter.java:67`」 | `RedisScripts.java:182-194`（`ARGV[1]`=数据 TTL 毫秒）、`:213-227`（新增 `ARGV[2]`）、`:260-274`（`redis.replicate_commands()` + `TIME`，3 个 ARGV）；`ChatRateLimiter.java:59-66` | 三支脚本的参数语义与 Lua 体全变 | 「`ARGV` 语义按当前脚本更新；七-6 的 `EVAL` 改为 `… 1 chat:ratelimit:1 60000 30 "m-$i"`（3 个参数，无 `date`）；`SLIDING_WINDOW_RATE_LIMIT` 的时间由 Redis `TIME` 提供」 |
| 4.5.5 / 4.3① L118、L127-131、L249-260 | 「`GRAB_ORDER_ATOMIC` 显式与 `false` 比较（`if status == false …`）」 | `RedisScripts.java:238-247`（`GET … ~= ARGV[1]`）；`false` 比较只在 `SYNC_ORDER_STATUS:252`；`OVERWRITE_IF_VERSION_MATCH:217` 用 `or '0'` | 4.3 的「实证」引用已对不上 | 「4.3① 改为引用 `SYNC_ORDER_STATUS`（`:252`）的 `current == false`；并补充『当前脚本常用 `GET … or '0'` 把缺失值归一成可比较字符串』」 |
| 六、边界 L362、L368、L364 | 「`rollbackGrab` 读-写不是原子的」「`GRAB_ORDER_ATOMIC` 的 **6 小时**占位 TTL」「`ChatGuardProperties.java:36/:41`」 | `OrderGrabService.java:112-119`（`RELEASE_LOCK_IF_MATCH`）、`:30`（`CACHE_TTL=5min`）；`ChatGuardProperties.java:37/:42` | 六-3 已修复；六-6 TTL 数值与「2 小时 / 6 小时」结构表全错 | 「六-3 改为 ✅ 已修复；六-6 与 3.1 结构表改为『`CACHE_TTL = Duration.ofMinutes(5)`，订单状态与司机占位 key 统一 5 分钟 TTL』；六-4 行号改为 `:37/42`」 |
| 3.2 调用点表 L72-78 | 「`HASH_SET_WITH_EXPIRE` 调用方含 `MessageMemory.java:196`」 | `MessageMemory.java` 全文 `lockChat` 0 命中（`switchFocusChatIfNeeded:200-211` 只清 L1 + 设 L2 TTL）；`ChatController.java:78`、`ChatStatusTool.java:20`、`OrderTool.java:448` | 「用户切换对话 → 锁定之前对话」入口已删除 | 「`HASH_SET_WITH_EXPIRE` 的业务入口现在只有 3 个：`ChatController.java:78`（`POST /chat/v2/lock/{id}`）、`ChatStatusTool.java:20`、`OrderTool.java:448`」 |
| 七、验证 L410-412、L419、L427-435 | 「`SET … 21600` / `EVAL … 21600`」「`EVAL … $(date +%s%3N) 60000 30`」 | `OrderGrabService.java:30`（300 秒）、`ChatRateLimiter.java:62-66`（3 参数）、`RedisScripts.java:260-274`（`TIME`） | TTL 数值、参数个数、时间源命令全错 | 「`#5` 的 `21600` 改为 `300`；`#6` 改为 `… 1 chat:ratelimit:1 60000 30 "m-$i"`；`#7` 换成 `UPDATE_DRIVER_LOCATION`/`READ_DRIVER_LOCATION` 的验证」 |
| 八 L447；11 篇 L560 | 「11 篇…『**校验 + 删除尚未原子化**』的缺陷」「7 个 Lua 脚本」 | `OrderSearchTool.java:46-53`、`OrderConfirmation.java:32-39`、`RedisScripts.java` 22 个 | 交叉引用的缺陷描述已失效 | 「工具令牌的校验+消费已用 `CANCEL`/`CONSUME` 原子脚本实现；`RedisScripts` 现有 22 个脚本」 |
| 一致要点 | `chat:info` 60 分钟、`chat:ratelimit:{userId}`、`order:lock:{orderId}`、Redis 异常放行、`remaining >= 0`、30/60 默认值、`RELEASE_LOCK_IF_MATCH` 逻辑、七章 `#0~#4` key 名 | `ChatManager.java:24`、`RedisKeyConstants.java:158`、`ChatRateLimiter.java:70-74`、`ChatGuardProperties.java:37,42`、`application.yaml:80-81` | **一致，无需修改** | 保留原文 |

#### 11-two-phase-confirm-token.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 来源块 L5；全篇 60+ 处行号 L53、L80-108、L119、L183、L276、L435、L442、L468-472、L483、L505 | 只列 `OrderTool.java`、`OrderSearchTool.java`（旧包路径） | 新增 `service/base/OrderConfirmation.java`；`RedisKeyConstants.java:56,243-245`；`OrderTool.java:50-69,407-413,416-454`；`OrderSearchTool.java:46-53,163,233-291` | 令牌机制抽到第三个类，全篇行号失效 | 来源块补 `OrderConfirmation.java`；「建议不再逐处标行号，改用方法名 + 章节定位」 |
| ⭐ 1.2 L36-47 | 「系统中有**两套**互不相干的令牌实现…A 套 3 阶段、B 套 1 个」「B 套校验与删除之间不原子（现存缺陷）」 | `OrderConfirmation.java:16-39,41-48,50-57,60-78`、`api/chat-security.md:12` | 现在是三套机制，且引入 `confirmationId` | 「1.2 改为三套：A 套 `OrderTool` 槽位/算价令牌；B 套 `OrderSearchTool` 取消令牌（`CANCEL` Lua 原子消费、绑定 orderId）；C 套 `OrderConfirmation` 服务端确认令牌（`confirmationId`=`quoteId`、CONFIRM/CONSUME 两个 Lua、15 分钟有效期、创建权限一次性消费）」 |
| ⭐ 4.1.3 / 6.3 L87、L166-183、L468-472、L512-515 | 「`markOrderReadyForCreate` HSET `ReadyForCreate`…无任何前置校验」「`ReadyForCreate` 全项目只有写与读两处，没有删除路径」「同一 chatId 再次调 `createOrder()` 会**再次真实下单**」 | `OrderTool.java:407-413`（只读 `isConfirmed`）、`:441`（`consume`）；`OrderConfirmation.java:16-39`（`CONFIRM` 签发、`CONSUME` `HDEL`） | 可重复下单已修；`ReadyForCreate` 由服务端签发/删除 | 「`markOrderReadyForCreate` 只查询 `OrderConfirmation.isConfirmed(chatId)`，不再签发创建权限；`ReadyForCreate` 由 `CONFIRM` Lua 在用户回传 `confirmationId` 后签发，`createOrder` 落单前调 `consume()` 一次性消费」；删除 6.3 并标 ✅ 已修复 |
| ⭐ 4.2.3/4.2.4/4.2.5/6.2/4.3 L299-312、L316-376、L380-398、L449-466 | 「`hasKey` 与 `delete` 之间没有原子性…可被消费两次」「建议新增 `CONSUME_TOKEN_IF_PRESENT`（放在 `RedisScripts.java`，与其余 6 个并列）」「两个并发 resume 双双通过」 | `OrderSearchTool.java:46-53`（`CANCEL`）、`OrderConfirmation.java:16-39`（`CONFIRM` 的 `resuming` 状态机）、`OrderAgent.java:101-150` | 代码已实现，但脚本名是 `CANCEL`/`CONFIRM`/`CONSUME` 且在各自 Service 内联 | 「✅ 已修复：取消令牌走 `OrderSearchTool.CANCEL`（原子比对 `HGET == orderId` + 5 分钟时效 + `HDEL`）；创建权限走 `OrderConfirmation.CONSUME` 原子消费；HITL 的 `break` 由 `CONFIRM` 原子推进到 `resuming`」；删除 4.2.4 的「建议新增脚本」段 |
| ⭐ 6.1 L433-447 | 「B 套令牌**不绑定订单号**（现存缺陷）」「修法…让 `readyForCancel` 存 orderId」 | `OrderSearchTool.java:218,227`（值就是 orderId）、`:219,228`（`cancelVerifiedAt`）、`:48-50`（CANCEL 比对 + 300000ms） | 文档建议的第二种修法已实现，并额外加 5 分钟时效 | 「✅ 已修复：`readyForCancel` 存 **orderId**（不再是 `"true"`），配套 `cancelVerifiedAt`；`CANCEL` Lua 原子比对且 5 分钟内有效」 |
| ⭐ 6.4 / 4.1.5 / 一 L474-478、L70、L234 | 「要让 confirm 事件携带服务端生成的确认令牌…**当前没有这层绑定**」「令牌自身没有独立 TTL」「做不到『令牌 5 分钟内有效』」 | `ChatParamDTO.java:14`、`api/chat-security.md:6,10,12`、`ChatServiceImpl.java:231,239`、`OrderAgent.java:307-308`、`OrderConfirmation.java:19,47`、`OrderSearchTool.java:50` | 「当前没有这层绑定」已完整实现；字段级时效通过时间戳 + Lua 实现 | 「✅ 已实现：`AgentEvent.comfirm(orderJson)` 携带 `confirmationId`（=`quoteId`），前端原样回传，`ChatServiceImpl.resume` 由 `CONFIRM` Lua 校验 15 分钟有效期并原子推进 `break`；`quoted()` 给 `chat:info` 设 24h TTL、取消令牌带 5 分钟 `cancelVerifiedAt` 时效」 |
| ⭐ 3.1 / 4.1.2 / 6.5 L82、L84、L485 | 「`saveNewOrderParam` 逐字段 HSET，无任何校验」「`getEstRouteAndPrice` `redisPut ReadyforConfirm`」「参数覆盖会使已签发 `ReadyforConfirm` 失效」 | `OrderTool.java:58-59`（先 `HDEL` 7 个令牌/报价字段）、`:169-170,:189-190`（`redisDel("ReadyforRoute")` + `orderConfirmation.quoted(...)`） | 保存参数会主动清除旧令牌（防止用旧报价下单），正是 6.5 缺口的修法 | 「3.1/4.1.2 按当前实现重写；6.5 标 ✅ 已修复（`saveNewOrderParam` 会先 `HDEL` 掉 7 个令牌/报价字段）」 |
| 七、验证 L512-515、L527-551 | 「手工 `HSET … ReadyForCreate true` → 现在调 `createOrder` 就能真的下单 → 再调仍成功」「#8 期望 TTL `-1`」 | `OrderTool.java:420,441`、`OrderConfirmation.java:33-38,47`、`OrderSearchTool.java:218` | 验证命令与当前令牌格式/消费逻辑全不对 | 「`#3` 改为『手工 `HSET ReadyForCreate true` 现在**会失败**：`CONSUME` 要求 `quoteId`/`confirmedQuote`/`ReadyForCreate` 三者都等于同一 `quoteId` 且在 15 分钟内』；`#5/#6` 改为验证 `CANCEL` 脚本；`#8` 补充『报价后 `chat:info` 会被设 24h TTL』」 |
| 八 / 4.2.4 L560、L318 | 「10 篇——**7 个 Lua 脚本**」「与其余 6 个并列」 | `RedisScripts.java` 22 个 + 11 个内联 | 计数失效（共性 4） | 「改为『`RedisScripts.java` 的 22 个脚本 + 11 个 Service 内联脚本』」 |
| 一致要点 | `chat:info:` 前缀、`readyForCancel`/`cancelFee` 字段名、`break` 与 `AgentEvent.comfirm` 拼写、60 分钟锁、`restoreChat` 的 `persist`、检查与业务执行仍分离 | `RedisKeyConstants.java:56,243-245`、`OrderSearchTool.java:55-56`、`ChatManager.java:24`、`ChatInfoService.java:120` | **一致，无需修改** | 保留原文 |

#### 12-chat-lock-atomic.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 来源块 L5、L11；全篇行号 L43、L49、L61-75、L83-87、L144、L247-249、L264、L270-282、L291-304 | 「`com/fancy/taxiagent/.../ChatManager.java`（95 行）」「**TaxiAgent** 的对话被设计成一次性的（`ChatServiceImpl.java:84`）」 | `ChatManager.java`（103 行）、`ChatInfoService.java`（124 行）、`ChatServiceImpl.java:87` | 项目名/包/行数/行号全变 | 按共性 1/2 处理；`:11` 的 `TaxiAgent` 改为 `RideAgentHub`、`ChatServiceImpl.java:84` 改为 `:87` |
| ⭐ 一、业务场景 L17-19 | 「用户切换对话｜`MessageMemory.java:196`｜`switchFocusChatIfNeeded` 检测到 chatId 变了」「前端主动调接口｜`ChatController.java:81`｜`POST /chat/lock/{id}`」 | `MessageMemory.java:200-211`（只清 L1 + 设 L2 TTL，**不再 lockChat**，全文 `lockChat` 0 命中）；`ChatController.java:75-80`（`POST /chat/v2/lock/{chatId}`）；`OrderTool.java:448` | 4 个入口只剩 3 个；接口路径变了 | 「锁定入口改为 3 个：`OrderTool.java:448`、`ChatController.java:78`（`POST /chat/v2/lock/{chatId}`）、`ChatStatusTool.java:20`；`MessageMemory.switchFocusChatIfNeeded`（`:200-211`）现在只清 L1 并把旧/新会话 L2 TTL 切成 30 分钟/24 小时，不再锁定之前对话」 |
| ⭐ 4.3 / 6.2 / 6.4 L149-159、L170、L267、L296 | 「`return "true".equals(locked); //其实这里始终为真`」「注释暴露了问题」「这就是该注释的另一半含义」 | `ChatManager.java:51-59`（`isLocked` 两分支 + 新注释「如果redis查不到有两种可能…」）；全库「其实这里始终为真」0 命中 | 反复引用的注释已删除，两处论证失去依据 | 「4.3 删除对 `//其实这里始终为真` 的引用，改为基于『写 `locked` 的位置只有 `ChatManager.java:42` 与 `ChatInfoService.java:119`』这一事实推断」 |
| ⭐ 4.2 L133-138、L189 | 「`-- RedisScripts.java:36-40` … `return 1`」 | `RedisScripts.java:136-139`（`return redis.call('EXPIRE', KEYS[1], ARGV[3])`）；`ChatManager.java:39-44`（Java 调用方参数与文档完全一致） | Lua 末行不再 `return 1` | 「4.2 的 Lua 代码块末行改为 `return redis.call('EXPIRE', KEYS[1], ARGV[3])`；并说明『返回值是 `EXPIRE` 的结果，仍不反映字段是否新建』」 |
| ⭐ 3 / 6.2 L70、L270-280 | 「`ChatManager.java:78-94`｜`getRestorableChatId`｜「最近一条 classification=ORDER 且 OrderId 为空」」 | `ChatManager.java:86`（`private String getRestorableChatId(String userId)`）、`:87`（先 `chatInfoService.getLatestChatId(userId)` 查 DB）、`:93-100` 再读 Redis Hash | 方法可见性、签名、判定来源都变了 | 「3 表改为 `private String getRestorableChatId(String userId)`（`:86-102`）；6.2 判据改为『先由 DB 取该用户最近一条未清空会话，再用 Redis Hash 的 `classification`/`OrderId` 判断』」 |
| 七、验证 L338、L341 | 「之后再调一次 **`GET /chat/restorable`**…`getRestorableChat:62` 又设回来了」 | `ChatController.java:56-59`（`GET /chat/v2/restore`）、`:62-71`（`POST /chat/v2/restore`）；`ChatManager.java:65-72`（需 userId）、`:70`（独立 expire） | 接口路径与无参假设都变了 | 「改为『再调 `GET /chat/v2/restore`（`ChatController.java:57-58`，内部 `ChatManager.getRestorableChat(userId)` 在 `:70` 续期）』；TTL 在 `-1` 与 `3600` 之间反复横跳的核心结论仍成立」 |
| 八、延伸阅读 L367、L370 | 「`HASH_SET_WITH_EXPIRE` **在 7 个脚本中**的位置」「09 篇——**版本号与数据为何必须同生命周期**」 | `RedisScripts.java` 22 个；版本 key 现在 `PERSIST` 永久保留（`README.md:132`） | 两处延伸阅读的标题/结论与当前代码相反 | 「`10-lua-scripts-overview.md` —— `HASH_SET_WITH_EXPIRE` 在 22 个脚本中的位置」；「`09-lease-token.md` —— 版本号为何**独立保留**、数据 key 才带 TTL」 |
| 一致要点 | `HASH_SET_WITH_EXPIRE` 脚本名/3 个 ARGV/顺序、`expireLock=60min`、`restoreChat` 的 `persist`、`getRestorableChat` 独立 expire、isLocked 两分支、`chat:info` key 与 3600 期望 | `ChatManager.java:24,39-44,51-59,70`、`ChatInfoService.java:120`、`RedisScripts.java:136-139` | **一致，无需修改** | 保留原文 |

---

### 3.6 `docs/learn/redis/` 考点笔记 13–19

#### 13-distributed-lock.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 来源块 L5、L246；全篇行号 L57-67 | 旧包路径；「`TaxiAgentApplication.java:8`」「`RedisLock.java`（72 行）、`RideOrderServiceImpl.java:388-409`」 | `RideAgentHubApplication.java:8-9`、`RedisLock.java` 42 行、`RideOrderServiceImpl.java:313` | 路径/应用类名/行数/行号全变（共性 1/2） | 按共性 1/2 处理 |
| 一、业务场景 L11-18、L390 | 「本类刻意保持"手写"实现，与 Redisson 的 {@code RLock} 并存」（引用长类注释 `:16-20,:21-26,:24,:25,:46`） | `RedisLock.java:13`（类注释仅一行「用唯一令牌标识持有者的不可重入 Redis 锁」） | 引用的长注释已不存在 | 「删除该引用，改为：类注释见 `RedisLock.java:13`，仅声明『不可重入的令牌锁』；两把锁分工见 `README.md:133`」 |
| 4.2 / 6.2 / 三 L137-144、L414、L58 | 「`public boolean unlock(...) { return released != null && released > 0; }`」「调用方全部忽略了返回值」 | `RedisLock.java:31-41`（`public void unlock`，自带 try/catch 异常隔离 + `log.warn`）、`:38` 注释；`TicketServiceImpl.java:1288-1290` | `unlock` 改为 void，「返回 false / 调用方忽略」失去前提 | 「`unlock` 返回 void：执行 `RELEASE_LOCK_IF_MATCH` 原子校验令牌后删除；释放失败只记 warn，不覆盖临界区结果」 |
| 6.5 L426-430 | 「`key` 为空 / `ttl` 非法时返回 `null`…修法很便宜」 | `RedisLock.java:23-25`（抛 `IllegalArgumentException`） | 「仍是坑」已修复，文档建议已被实现 | 「6.5 ✅ 已修复：`tryLock` 参数非法抛 `IllegalArgumentException`，仅竞争失败返回 `null`」 |
| ⭐ 4.3 / 4.4.2 / 6.3 / 二 / 七 L199-203、L258-264、L416-420、L42、L489-494 | 「显式给租约，而不是用 Redisson 默认的看门狗续期」「`tryLock(3, 10, TimeUnit.SECONDS)`」「TTL ≤ 10…等待 5 秒后 TTL 应继续下降而不是被续期回 10」 | `RideOrderServiceImpl.java:317`（`tryLock(ORDER_LOCK_WAIT_SECONDS, TimeUnit.SECONDS)`，**两参、无 leaseTime**）、`:88-93`、`:310` 注释「使用看门狗续期」；`README.md:133` | 已启用看门狗，10 秒固定租约及「确定性优于自动续期」整段作废 | 「订单锁用 `tryLock(3, TimeUnit.SECONDS)` 不带 leaseTime，启用 Redisson 看门狗续期；固定 TTL 只用于统计/索引重建锁（5s/10s）。验证改为观察 TTL 被看门狗续期」 |
| 4.4 `finally` L294-301、L343 | 「`finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }`」 | `RideOrderServiceImpl.java:329-340`（有事务时注册 `afterCompletion` 回调 `releaseOrderLock`）、`:343-349` | 解锁延迟到事务提交/回滚之后 | 「有事务时通过 `TransactionSynchronization.afterCompletion` 解锁；`releaseOrderLock` 内仍判断 `isHeldByCurrentThread()` 并吞掉解锁异常」 |
| 4.5 / 三 / 6.4 L66-67、L321-355、L424 | 「`OrderGeoPool.java:206-226 ensureWarm`」「`TicketPoolIndex.java:170-191 ensureWarm`」「hasKey 与预检之间的窗口」 | `OrderGeoPool.java:77-131`（`search`）、`TicketPoolIndex.java:67`（`slice`）；全文无 `ensureWarm`/`rebuild`/`markReady`；两处只在 `:88`/`:80` 调 `redisLock.tryLock` | 已重写为「整池失效 + 完整重建 + 版本令牌发布」，6.4 窗口失效 | 「手写锁现有三处调用：`TicketServiceImpl.getTicketStatistics`（5s）、`OrderGeoPool.search`（10s）、`TicketPoolIndex.slice`（10s）；后两者为『读不到完整索引 → 抢锁 → 全量回表 → Lua 校验版本与锁令牌后发布』」 |
| 一致要点 | 四个锁 key 前缀、`RELEASE_LOCK_IF_MATCH` 脚本体、`ORDER_LOCK_WAIT_SECONDS=3`、「订单正在处理中」409、中断标志恢复 | `RedisKeyConstants.java:98,123,164,187,293,332-334,353`、`RedisScripts.java:141-147` | **一致，无需修改** | 保留原文 |

#### 14-grab-order-lua.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 二 / 4.x / 六 L52-62、L55、L194 | 「`order:status` 2 小时（哨兵 60 秒）、`driver:active` 6 小时」「两个哨兵值 `__IDLE__` / `__MISSING__`」 | `OrderGrabService.java:29-30`（`IDLE` + `CACHE_TTL=5min`）、`:36-38`（`GrabResult` 枚举）、`:71-73`（`ORDER_NOT_FOUND`） | 统一 5 分钟，`__MISSING__` 与 2h/6h 常量已删除 | 「两个 key 均为 5 分钟（`CACHE_TTL`）；只有 `__IDLE__` 一个哨兵，订单不存在由 `GrabResult.ORDER_NOT_FOUND` 表达」 |
| 三 / 六-2 / 五 L81、L181、L193 | 「`warmUpDriverActive` 司机占位缺失时回源 DB」「Redis 只做预检，DB 乐观锁仍是唯一裁决」「预热与 Lua 之间存在窗口」 | `OrderGrabService.java:41-42`（`@Transactional(MANDATORY)`）、`:45-50`（`FOR UPDATE` 锁司机账号）、`:56-64`（活跃订单行锁 + 修复 driverActiveKey）、`:67-75` | 预热合并进 `tryGrab`，新增两道 DB 行锁兜底 | 「预热已合并进 `tryGrab`：事务内 `SELECT … FOR UPDATE` 锁定司机账号与活跃订单，据 DB 修复 `driver:active`，再执行 Lua 预检；正确性由司机账号行锁 + 订单条件更新兜底」 |
| 4.3 / 六-1 L144-158、L192 | 「先读回当前值确认是我这次写入的占位才释放」「`rollbackGrab` 读-写不是原子的…可能误释放」 | `OrderGrabService.java:112-119`（`RELEASE_LOCK_IF_MATCH`）、`:87-94`（`afterCompletion` 自动回滚） | 改为 Lua CAS 且事务回调自动触发 | 「✅ 已修复：`rollbackGrab(driverId, orderId)` 用 `RELEASE_LOCK_IF_MATCH` 原子校验 orderId 后删除；事务未提交由 `afterCompletion` 自动回滚」 |
| 4.4 L160-173 | 「`public void releaseDriver(String driverId)` … `set(..., IDLE_SENTINEL, TTL)`」 | `OrderGrabService.java:122-126`（`releaseDriver(String driverId, String orderId)` → `afterCommit(rollbackGrab)`）；`RideOrderServiceImpl.java:561,715,843,908`；`:107-110`（`refreshOrderStatus` 改为删缓存） | 签名新增 orderId，实现改为按订单原子释放 | 「`releaseDriver(driverId, orderId)` 在事务提交后调 `rollbackGrab`，只释放该订单持有的占位；`refreshOrderStatus` 提交后删除状态缓存」 |
| 4.1 / 八 L82、L91、L221 | 「`RedisScripts.java:144-155` `GRAB_ORDER_ATOMIC`」「项目全部 7 个 Lua 脚本」 | `RedisScripts.java:238-247`（`GRAB_ORDER_ATOMIC`）、`:250-257`（`SYNC_ORDER_STATUS`）；全类 22 个 | 行号漂移 + 脚本总数 7→22 | 「`GRAB_ORDER_ATOMIC` 现位于 `RedisScripts.java:238-247`；`RedisScripts` 共 22 个脚本，10 篇的『7 个』需同步更新」 |
| 一致要点 | 两个 key、返回码 -1/-2/1、调用链 `withOrderLock → tryGrab → 条件更新 → rollback/sync`、`CREATED=10` | `RedisKeyConstants.java:104,110,301,308`、`RedisScripts.java:238-247`、`RideOrderServiceImpl.java:266-305`、`RideOrderStatus.java:11` | **一致，无需修改** | 保留原文 |

#### 15-nearby-order-geo.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| ⭐ 考点 / 4.3 / 七 / 八 L3、L151-177、L282-284、L315 | 「换成以司机为圆心的 `GEOSEARCH`」「`opsForGeo().search(key, GeoReference, …)`」「`redis-cli GEOSEARCH …`」 | `RedisScripts.java:69,77`（`GEORADIUS … 'ASC','COUNT',200,'WITHDIST'`，注释「兼容本地 Redis 5」）；`api/driver-location.md:54`、`README.md:124` 均为 GEORADIUS；全库 `GEOSEARCH` 调用 0 命中（仅 `RideOrderVO.java:53` 残留注释） | 命令名与调用方式全错，`api/driver-location.md` 才是对的 | 「查询由 `SEARCH_ORDER_GEO_POOL` Lua 内 `GEORADIUS order:geo:pool {lng} {lat} 3 km ASC COUNT 200 WITHDIST` 完成（兼容 Redis 5）；验证命令改为 `redis-cli GEORADIUS order:geo:pool 116.397 39.909 3 km ASC WITHDIST COUNT 5`；`docs/learn/redis/README.md:75` 同步改为 `GEORADIUS`」 |
| 二、结构选型 L42-50、L71-75 | 「三个 key 的注释写在 `RedisKeyConstants.java:118-138`」「`order:geo:pool:ready` =『已预热』标记」 | `RedisKeyConstants.java:119-124`（**4 个**常量，新增 `:124 ORDER_GEO_POOL_VERSION_KEY`）；`RedisScripts.java:65,74`（ready 值为 `版本:成员数`） | 新增版本令牌 key，ready 不再是布尔标记 | 「四个 key：`order:geo:pool`、`order:geo:pool:ready`（值 `版本:成员数`）、`order:geo:pool:rebuild:lock`、`order:geo:pool:version`」 |
| ⭐ 三 / 4.1 / 4.2 / 4.4 L83-94、L110-149、L181-211 | 「`add`：GEOADD + 续期 + 标记」「`ensureWarm`：已预热校验 → 抢重建锁 → 双检 → 重建」「`rebuild` 批量 GEOADD」 | `OrderGeoPool.java:44-46`（`add` 仅 `remove`）、`:48-62`（`remove` 提交后 `invalidate`）、`:64-74`（`invalidate`）、`:77-131`（`search` 抢锁 → 全量回表 → `PUBLISH_ORDER_GEO_POOL` 原子发布）；无 `ensureWarm`/`rebuild`/`markReady` | 从「成员级增量维护」改为「整池失效 + 完整重建 + 版本/锁令牌发布」 | 「`add/remove` 统一在事务提交后使整池失效；`search()` 读不到完整索引时抢重建锁、全量回表、用 `PUBLISH_ORDER_GEO_POOL` 校验版本与锁令牌后一次发布」 |
| ⭐ 二 / 6.1-1 L44、L262 | 「2 小时，**每次写入续期**」「高峰时段 TTL 被不断续期，漏摘脏成员不会被兜底清掉」 | `OrderGeoPool.java:31`（`INDEX_TTL=2h`）、`:71` 注释「固定 TTL 不因写入续期」；`RedisScripts.java:64-65` | 「持续续期导致漂移无界」结论完全反转 | 「数据与 ready 标记在发布时一次性设置固定 2 小时 TTL，不因写入续期；完整性由整池失效 + 回表版本校验保证」 |
| 4.5 / 6.4-4 L213-239、L265 | 「回表是正确性的最后一道…脏数据只会浪费一次查询」「池可用但半径内无单时返回空池」 | `RideOrderServiceImpl.java:1174-1177`（候选数不符或 `!isCurrent` → `legacyTimeSortedPool`）、`:1153-1155`；`OrderGeoPool.java:133-140` | 新增回表前后版本二次校验，任何不一致都整页降级 | 「回表后若候选数不一致或 `isCurrent(slice)` 为 false，整页降级为 DB 时间排序；只有空候选且版本仍有效才返回空池」 |
| 6.1-8 L269 | 「`ORDER_GEO_POOL_KEY` 上**没有 Lua 参与**：本方案全篇是普通命令」 | `RedisScripts.java:47-52,54-67,69-85`；`OrderGeoPool.java:66,119,143` | 现有三支专用 Lua | 「本方案由三支 Lua 驱动：`INVALIDATE_ORDER_GEO_POOL`、`PUBLISH_ORDER_GEO_POOL`、`SEARCH_ORDER_GEO_POOL`」 |
| 七、验证 L276-298 | 「`redis-cli GEOSEARCH`」「`DEL order:geo:pool order:geo:pool:ready`」「日志『订单地理池已重建: 扫描=?, 入池=?』」 | `OrderGeoPool.java:128`（日志为「读取订单地理池失败，降级为数据库时间排序」）；`RedisKeyConstants.java:124` | GEO 命令、清理 key 集合、日志文案全过时 | 「改用 `GEORADIUS`；模拟冷启动需 `DEL order:geo:pool order:geo:pool:ready order:geo:pool:version`；观察 `isCurrent` 校验失败后的整页降级日志」 |
| 一致要点 | 3km / 200 / 2h / 10s、三个原 key 名、离线空池/降级/距离排序三分支、`emptyPool` total=0、`legacyTimeSortedPool` 倒序 | `OrderGeoPool.java:31-34`、`RedisKeyConstants.java:120-123`、`RideOrderServiceImpl.java:1126-1139,1213-1216,1230-1237` | **一致，无需修改** | 保留原文 |

#### 16-driver-online-heartbeat.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 三 / 4.3 / 4.4 L85、L144-161、L190-194 | 「`RedisScripts.java:210-225` `CHECK_DRIVER_ONLINE`」「`ARGV[2]=当前毫秒`、`ARGV[3]=ONLINE_TTL`」「判定用 `now - beat <= TTL`（应用时间）」 | `RedisScripts.java` 无该脚本；`UPDATE_DRIVER_LOCATION:10-25`、`REMOVE_DRIVER_LOCATION:27-31`、`READ_DRIVER_LOCATION:34-45`、`:35-36`（`redis.call('TIME')`）；`DriverGeoIndex.java:78-79`（只传 driverId 与 TTL 毫秒） | 时间源改为 Redis `TIME`，参数与脚本全变 | 「状态检查由 `READ_DRIVER_LOCATION` Lua 完成：脚本内 `redis.call('TIME')` 取 Redis 时间，比较 `now - beat < 60000`，一次返回坐标或在同脚本内 `HDEL`+`ZREM` 清理」 |
| 4.1 / 六-3 L101-123、L309 | 「上线/心跳：两次写入，先心跳后位置」「`goOnline` 的两次写入不是原子的」 | `DriverGeoIndex.java:37-39,51-53`；`RedisScripts.java:22-23`（`GEOADD` + `HSET` 单脚本） | 已原子化 | 「`goOnline` 走 `UPDATE_DRIVER_LOCATION` 单脚本，`GEOADD` 与 `HSET` 原子完成」 |
| ⭐ 4.7 / 六-2 L249-285、L308 | 「`refreshIfExist`：先 `status()` 判定在线 → `goOnline()` 写入」「两步不是原子的…位置会被重新写回池中」 | `DriverGeoIndex.java:42-44`（`refresh` 模式）、`RedisScripts.java:13-21`（校验 beat 未过期且 ZSCORE 成员存在，否则清理并返回 0）；`api/driver-location.md:24` | 判定与写入合并进同一 Lua | 「`refreshIfExist` 用 `UPDATE_DRIVER_LOCATION` 的 `refresh` 模式：脚本内校验心跳未过期且 GEO 成员存在，否则顺手清理并返回 0，不会把已下线司机重新上线」 |
| 4.6 / 4.5 / 六-5 L171-186、L237-245、L311 | 「查询路径必须先 `status()` 再 `position()`」「`position()` 不做在线判定」 | `DriverGeoIndex.java:21`（`record Location(OnlineStatus, Point)`）、`:73-93`（`location` 一次 Lua）、`:95-101`；`RideOrderServiceImpl.java:1126-1131` | 状态与坐标一次原子读取 | 「查询统一走 `location(driverId)`，一次 Lua 返回 `Location(status, point)`；工单池据此三分支」 |
| ⭐ 4.5 / 一 / 六-3 L200-235、L309 | 「`UNAVAILABLE` 与 `OFFLINE` 必须区分…调用方应当降级到 DB」「`getStatus` 返回 online=false」「三态枚举带长 javadoc」 | `DriverLocationServiceImpl.java:53-58`（UNAVAILABLE 抛 `BusinessException(503)`，OFFLINE 才 `online(false)`）；`DriverGeoIndex.java:19`（单行枚举）；`api/driver-location.md:37` | UNAVAILABLE 现在抛 503，不再误报离线 | 「`getStatus` 对 `UNAVAILABLE` 抛 503，`OFFLINE` 才返回 `online=false`；工单池侧对非 ONLINE 一律降级为 DB 时间排序」 |
| 一致要点 | 两个 key 名、无整体 TTL、`ONLINE_TTL=60s`、验证命令 key 名、6.1-1 心跳 Hash 孤儿 | `RedisKeyConstants.java:136,146`、`DriverGeoIndex.java:23` | **一致，无需修改** | 保留原文 |

#### 17-order-timeout-delay-queue.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| ⭐ 4.3 / 五 / 六-3 L159-210、L339、L352 | 「先按 score 区间取出候选，再逐个 `ZREM` 抢占」「`claimDue` 是 N 次 ZREM，不是批量操作」 | `OrderDelayQueue.java:82-90`（执行 `CLAIM_DUE_ORDERS`）、`RedisScripts.java:127-133`（`ZRANGEBYSCORE` + `ZREM` 单脚本）、`:126` 注释「保护同时发生的改期」 | 领取改为单个 Lua 原子读取+移除，顺带解决改期误删 | 「`claimDue` 执行 `CLAIM_DUE_ORDERS` Lua：一次调用内 `ZRANGEBYSCORE 0 now LIMIT 0 batch` + 逐条 `ZREM` + 返回 orderId 列表；原子性由脚本保证」 |
| ⭐ 六-1/2 / 五 / 一 L350-351、L343、L20-29 | 「队列不设 TTL，**也没有 DB 兜底扫描**…目前没有」「认领后处理失败不重试」 | `OrderTimeoutJob.java:78-108`（`@Scheduled(fixedDelay=60000L) recoverOverdueOrders()` 三类锚点扫 DB）、`:26-28` 类注释、`:110-118`（失败按 `poll-interval-ms` 重新入队） | 两处「仍是坑」均已修复 | 「✅ 已修复：`OrderTimeoutJob.recoverOverdueOrders()` 每分钟扫描 DB 补偿，`processOrders` 单条失败重新入队重试」 |
| 4.1 / 4.4 L124-145、L214-246 | 「`redisTemplate.opsForZSet().add(DELAY_ORDER_TIMEOUT_KEY, orderId, deadline)`」「`cancel` 也只做 `ZREM`」 | `OrderDelayQueue.java:51-57,67-72`（`Math.addExact` + `afterCommit`）、`:92-109`（失败 `log.warn("…等待数据库补偿扫描")`） | 入队/撤单延迟到事务提交后 | 「`schedule`/`cancel` 均通过 `afterCommit` 在事务提交后执行，避免未提交状态排期；写入失败记 warn 并由 DB 补偿扫描兜底」 |
| 七、验证第 6 步 L385-389 | 「并发执行两次同样的 `ZREM`，只有一次返回 1」 | `OrderDelayQueue.java:86-89`（无逐条 ZREM） | 演示机制已不存在 | 「第 6 步改为并发调用两次 `claimDue`，观察两个实例返回的 orderId 列表无交集；或直接 `EVAL` `CLAIM_DUE_ORDERS` 验证原子性」 |
| 三 / 4.4 / 4.5 行号 L87-114、L239-248、L252-310 | `OrderDelayQueue.java:50-56`、`OrderTimeoutJob.java:46-73`、`RideOrderServiceImpl.java:767-794` | `OrderDelayQueue.java:51/67/82`、`OrderTimeoutJob.java:55/79/110`、`RideOrderServiceImpl.java:737/924`；`RideAgentHubApplication.java:8` | 全篇行号与应用类名失效 | 「按当前源码重排；`@EnableScheduling` 在 `RideAgentHubApplication.java:8`」 |
| 一致要点 | `delay:order:timeout`、不设过期、`application.yaml:92-97` 的 enabled/15/10/30/5000/100、`@Scheduled(fixedDelayString=...)`、`reschedule`、验证 key 名 | `RedisKeyConstants.java:117`、`application.yaml:89-97`、`RideOrderTimeoutProperties.java:29-49`、`OrderDelayQueue.java:22-26,55`、`RideOrderServiceImpl.java:924` | **一致，无需修改** | 保留原文 |

#### 18-global-unique-id-incr.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 四 / 6.2-2 L46-56、L86-100、L182 | 「`if (seq == 1) { expire(..., ofDays(2)); }`」「`INCR` 与 `EXPIRE` 不是原子的…这里没有沿用」 | `TicketServiceImpl.java:76-81`（`TICKET_SEQUENCE` Lua：`INCR` + `TTL < 0` 才 `EXPIRE 172800`）、`:712` | 已在一次 Lua 内完成 | 「✅ 已修复：`generateTicketId` 执行 `TICKET_SEQUENCE` Lua，`INCR` 后判断 `TTL < 0` 才 `EXPIRE 172800`（2 天），永不过期窗口已消除」 |
| 6.2-3 L183 | 「`seq` 若为 null 会静默生成一个畸形编号」 | `TicketServiceImpl.java:713`（`throw new BusinessException(503, "工单编号服务不可用")`） | 已显式校验 | 「✅ 已修复：`seq == null` 抛 503，不再生成畸形编号」 |
| 6.1 L168 | 「`DuplicateKeyException` 冒泡…用户拿到 500」 | `GlobalExceptionHandler.java:26-28`（转 `409 记录已存在或请求已被处理`） | 撞号不再冒泡为 500；「无撞号重试/无启动预热」仍成立 | 「6.1 保留『序号回退 → `uk_ticket_id` 拒绝』的结论，但结果改为由 `GlobalExceptionHandler.java:26` 返回 409；撞号重试循环仍未实现」 |
| ⭐ 5.3 与雪花算法对比 L135-149 | 「本项目恰好三条全反…正确的演进方向不是换雪花」「换雪花算法：去掉对 Redis 状态的依赖」（列为改造建议） | `config/IdGeneratorConfig.java:11-29`（`SnowflakeIdWorker` Bean + Redisson 节点租约）、`util/SnowflakeIdWorker.java:11`；使用点 `RideOrderServiceImpl.java:161`、`AuthServiceImpl.java:90,238`、`RagService.java:196,200` | `a8f4e33` 已引入雪花 ID 机制，「未来演进方向」的语境过时 | 「工单号仍用 `ticket:no:{yyyyMMdd}` + INCR；项目另已引入 `SnowflakeIdWorker`/`IdGeneratorConfig`（带 Redisson 节点租约）生成订单 ID、userId、RAG 主键，与对外工单号是两套并存机制」 |
| 三 / 七 行号 L20、L29、L73-80 | `TicketServiceImpl.java:661-674`、`init.sql:118/:136` | `TicketServiceImpl.java:85/142/706-716`、`init.sql:125/143` | 行号漂移 | 「按当前源码重排：`TicketServiceImpl.java:85/142/706`、`init.sql:125/143`」 |
| 一致要点 | `ticket:no:` 局部前缀、方法名、`T+yyyyMMdd+类型码+%05d`、TTL 172800、`ticket_id varchar(16)`、`uk_ticket_id`、验证 key 名 | `TicketServiceImpl.java:85,706,715`、`init.sql:125,143` | **一致，无需修改** | 保留原文 |

#### 19-multi-level-cache.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 4.1 / 3.1 L63-65、L105-137 | 「`List<Message> redisLastN = redisMemory.getLastN(chatId, lastN);` … `return redisLastN;`」 | `MessageMemory.java:53-78`（三层都 `getAll` + `MessageWindow.tail`）、`:51`（`isNewOwnedChat` 守卫）、`:72-74`（回填成功才填 L1）、`:220` | 三级读全量再 tail；新增所属会话守卫 | 「`get` 依次 `getAll` 读 L1/L2/L3 全量快照，末尾用 `MessageWindow.tail(..., lastN)` 截取；L3 命中且 L2 回填成功才回填 L1；开头有 `chatManager.isNewOwnedChat` 守卫」 |
| 4.2 / 3.1 L169-179、L196-198 | 「`if (!overwriteIfVersionMatch(...)) heapMemory.clear(chatId)`」「租约失效时**清空**而不是保留」 | `MessageMemory.java:186-198`（只调 overwrite）、`:192` 注释「保留并发写入的内容」；`HeapMemory.java:118-125`（clear 保留版本号） | 「清空」论证反转 | 「租约失效时保留 L1 中并发写入的内容，不再 `clear`；`HeapMemory.clear` 也保留递增后的版本号」 |
| ⭐ 4.4 / 4.5 / 6.2 L194-196、L216、L268-281 | 「`save` 依次写 L1、L2、L3」「L1 与 L2 已含这条消息，而 DB 没有 —— 缓存比权威数据源超前」 | `MessageMemory.java:100-108`（先 `mysqlMemory.append` → `redisMemory.appendIfVersionMatch` → `heapMemory.appendIfVersionMatch`）；`MysqlMemory.java:61-85`（`@Transactional` + `FOR UPDATE`）；`RedisScripts.java:197-210` | 6.2 整节「缓存超前于 DB」已修复 | 「✅ 已修复：`save` 先 `mysqlMemory.append` 持久化，再以写库前捕获的版本号调用 `appendIfVersionMatch` 追加 L2/L1；DB 失败时缓存不会有新消息」 |
| ⭐ 3.2 / 4.5 / 6.7-2 L82、L85-86、L206、L316 | 「`currentVersion` 非法值按 0」「`expire` 数据 key 与版本 key 一起续期」「`clear` 两个 key 一起删」 | `RedisMemory.java:67-77`（Lua，失败抛 `IllegalStateException`）、`:188-193`（只续数据 key）、`:195-202`（`CLEAR_HISTORY_WITH_VERSION_BUMP`）；`README.md:132` | 版本 key `PERSIST` 独立保留 | 「版本 key 由三段 Lua 维护并 `PERSIST`，独立于数据 TTL；`expire()` 只续数据 key；`clear()` 递增版本而非删除版本 key」 |
| 6.1 异常链 L241-266 | 「对话逻辑跑在 `CompletableFuture.runAsync` 的独立线程里，没有任何 `exceptionally`/`handle`…SSE 流既不发送数据也不结束」 | `ChatController.java:43-46`（委托 `requestRunner.run`）、`ChatRequestRunner.java:53-62`（`AgentStreams.guard` + `sink.tryEmitError` + `doFinally`） | 故障表现已变；「Redis 异常不降级 MySQL」核心结论仍成立 | 「6.1 保留核心结论，但更新故障表现：`ChatController` 已委托 `ChatRequestRunner.run`，由 `AgentStreams.guard` 捕获并 `sink.tryEmitError`，SSE 以 error 事件结束而非静默挂起」 |
| 八、延伸阅读 L359 | 「同一目录下另一套 L1/L2/L3，可以对照看『三级缓存』的两种写法」 | `ToolResponseMemory.java:13-44`（仅 Redis + MySQL 两级，key `chat:tool:v2:{chatId}:{callId}`，TTL 24h，无堆内 L1） | `ToolResponseMemory` 已不是三级缓存 | 「`ToolResponseMemory` 现为 Redis + MySQL 两级缓存，Redis 读取失败降级查库；`docs/learn/redis/README.md:85` 的『callId 为 key 的三级缓存』需一并更正」 |
| 一致要点 | L1→L2→L3 分层 + Lease 版本校验、三个 TTL、三个 key 名、`ESMemory` 空实现、`MysqlMemory.getLastN` 排序、`HeapMemory.getAll` 返回拷贝 | `MessageMemory.java:18-19`、`RedisMemory.java:36`、`RedisKeyConstants.java:62,68,74`、`ESMemory.java:1-10` | **一致，无需修改** | 保留原文 |

---

### 3.7 `docs/learn/redis/` 考点笔记 20–25

#### 20-tool-response-cache.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 来源块 L5；行号 L54-60、L78-79、L103-104、L240 | 旧包路径 + 行号漂移 | `ToolResponseMemory.java`（44 行）、`RedisKeyConstants.java:80,271-273` | 共性 1/2 | 按共性 1/2 处理 |
| ⭐ 二、结构选型 L54-60 | 「L1 `ConcurrentHashMap` / L2 Redis String / L3 MySQL，**三级缓存**」 | `ToolResponseMemory.java:13-20`（只有 `StringRedisTemplate` 与 `ChatToolResponseMapper`，无 `heapCache`）、`:14`（`TTL = Duration.ofHours(24)`） | 退化为 MySQL + Redis 两级，`DEFAULT_TOOL_CACHE_TTL` 更名 `TTL` | 「L1（已移除）｜旧版进程内 `heapCache` 已删除；L2｜`chat:tool:v2:{chatId}:{callId}`，固定 24 小时；L3｜MySQL `sys_chat_tool_repsonse`，永久；TTL 常量为 `ToolResponseMemory.TTL = Duration.ofHours(24)`」 |
| 二 / 三 / 七 L57、L78-79、L103-104、L240 | 「`tool:{callId}`（`RedisKeyConstants.java:79`）」「`toolResponseKey(callId)`」 | `ToolResponseMemory.java:39`（就地拼 `chat:tool:v2:`）；`RedisKeyConstants.java:80,271-273` 已不被调用 | key 格式与来源都变了 | 「Redis key 为 `chat:tool:v2:{chatId}:{callId}`，在 `ToolResponseMemory.key()` 内就地拼接；`RedisKeyConstants.TOOL_RESPONSE_KEY`/`toolResponseKey()` 为未再引用的遗留常量」 |
| ⭐ 一、业务场景 L19-31 | 「持久化历史时只写一个指针……`"callId: " + response.id()`」 | `MessageParser.java:56-74`（TOOL 分支保存 `responseData()` 全文）；`OrderAgent.java:268-271`（同样写全文） | 工具结果指针化已取消；根 `README.md:59` 同样过时 | 「当前 `MessageParser.parse()` 的 TOOL 分支直接序列化 `responseData()` 全文，工具结果随会话历史保存；`ToolResponseMemory` 只作为按 `(callId, chatId)` 反查的辅助索引」 |
| 一 / 三 L33-44、L80 | 「`// ToolRepPointerTool.java:20-27` `@Tool(description=...)` `get(callId)`」 | `ToolRepPointerTool.java:22-29`（`@Tool(name="getCalling")`、`get(callId, chatId)`、`chatManager.assertOwned`）；`OrderAgent.java:422-425`；`api/chat-security.md:12` | schema 名统一，get 变双参并带归属校验 | 「`@Tool(name = "getCalling")`，参数 `callId`；调用 `ToolResponseMemory.get(callId, chatId)`，先经 `chatManager.assertOwned` 校验会话归属」 |
| ⭐ 4.1 / 4.2 L94-160 | 「`heapCache.put` → `set` → `mapper.insert`」「读命中即 `expire` 续期」 | `ToolResponseMemory.java:21-38`（`save` 先 `mapper.insert` 再 `cache`；`get` 命中直接 return，全类无 `expire`；L3 查询加 `.eq(chatId)`） | 写顺序反转、无 L1、无滑动过期 | 「`save` = 先 `mapper.insert` 落 MySQL，再 `set(key, response, TTL)` 写 Redis；`get` 依次查 Redis → MySQL（按 `call_id + chat_id`），命中即回填；**Redis 不续期**，TTL 自写入起固定 24 小时」 |
| 五、现存缺陷 L168-201 | 「代码在 `ToolResponseMemory.java:35`…三层都『以最后一次为准』」 | `ToolResponseMemory.java:41`（仍是 `set`）、`:23`（MySQL 先写）、无 L1 | 缺陷事实成立，「三层覆盖语义」表格与行号失效 | 「Redis 层仍用 `set`（未改 `setIfAbsent`），但 L1 已删除、MySQL 先写；重复 `save` 仍会新增行，靠 `orderByDesc(id) LIMIT 1` 取最新，缺陷分析应只针对 L2/L3 两层」 |
| 六-1、六-2 L226-227 | 「L1 无淘汰，且比 L2 更『长寿』」「`save` 与 `get` 都没有 try/catch」 | `ToolResponseMemory.java:28-31,40-43`（均有 `catch(RuntimeException)` + `log.warn`）；`ToolRepPointerTool.java:48-53`（降级「结果随会话保存」） | L1 已不存在，且 Redis/MySQL 抖动已降级 | 「1. L1 已移除，仅 Redis + MySQL 两级；2. `get`/`cache`/`recordCalling` 均有 try/catch 降级，Redis 写入失败时结果仍随会话历史持久化」 |
| 七、验证 L240-266 | 「`--scan --pattern "tool:*"` / `GET tool:{callId}`」「验证滑动过期」 | `ToolResponseMemory.java:39` | key 名全失效；滑动过期不再成立 | 「`redis-cli --scan --pattern "chat:tool:v2:*"`；`GET chat:tool:v2:{chatId}:{callId}`；`TTL` ≈ 86400 且**读一次后不回满**（固定 TTL，无滑动续期）」 |
| 一致要点 | TTL 24h、表名 `sys_chat_tool_repsonse` 与无唯一索引、L3 取最新行、chatId 缺失静默不写、`get` 返回 null 合法 | `sql/init.sql:101-107`、`ChatToolResponse.java:18` | **一致，无需修改** | 保留原文 |

#### 21-llm-classification-cache.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 来源块 L5 | 旧包路径 | `ClassificationCache.java:1` | 共性 1 | 按共性 1 处理 |
| ⭐ 4.2 / 4.3 / 4.7 L104-120、L158-192、L284-291 | 「`raw = nullToEmpty(contextFeature) + SEPARATOR + …`」「`SEPARATOR = '\0'`」「SHA256(`"CTX\0"+…`) 前 32 位」 | `ClassificationCache.java:95-99`（`"v2" + encode(ctxFeature) + encode(ctxText) + encode(prompt)`）、`:120-123`（`encode()` 长度前缀，含 null → `-1:`）、`fingerprint("CTXv2"+…)`；全类无 `SEPARATOR` | 拼接方式由 `\0` 改为长度前缀 + `v2` 版本标记 | 「`buildKey` 用 `encode()` 长度前缀保留字段边界（含 null），原料为 `"v2"+encode(ctxFeature)+encode(ctxText)+encode(prompt)`；第一段指纹为 `SHA256("CTXv2"+encode(ctxFeature))` 前 32 位；不再使用 `\0` 分隔符」 |
| 4.1 cache-aside L74-98 | 「`classification = chatClient.prompt()…call().content(); classificationCache.put(...)`」 | `ChatServiceImpl.java:164-192`（`normalizeClassification`、单轮判定 `SINGLE_TURN_CONTEXT`、模型调用 try/catch 返回 null、`classification != null` 才 put）、`:194-203`（5 个合法标签） | 新增归一化、失败降级、条件回填 | 「`resolveClassification` 先归一化缓存/模型输出（仅 5 个合法标签），模型调用 try/catch 降级返回 null，`classification != null` 时才 `put`；单轮分支以 `SINGLE_TURN_CONTEXT` + `contextText==null` 判定」 |
| 4.2 多轮上下文 L126-130、L328 | 「`messages.getFirst().getText()`」「`memory.get(userId, id, 1)` 的第 0 条」 | `ChatServiceImpl.java:101`（`memory.get(userId, id, 100)`）、`:109-110`（`lastAssistantText(messages)`）、`:205-212`（倒序找最后一条 `AssistantMessage`） | 由「最近 1 条第 0 条」改为「最近 100 条里最后一条助理消息」 | 「多轮场景 `contextText = lastAssistantText(messages)`，即 `memory.get(userId, id, 100)` 中倒序找到的最后一条 `AssistantMessage` 文本；已避开工具消息」 |
| 一致要点 | `qwen3-max-preview`、温度 0.5、5 个标签、TTL 21600 / 指纹 32、空结果不写哨兵、读写 try/catch 降级、无双检、TTL 读不续期、`chat:classify:` 前缀 | `ChatServiceImpl.java:36,180,199-202`、`ChatProperties.java:5`、`application.yaml:78-79`、`ClassificationCache.java:58,77` | **一致，无需修改** | 保留原文；**复核修订（第零章 M10）**：`ClassificationCache` 中 `TTL ≤ 0` 时禁用分类缓存的分支**已经实现**（非「建议补入」），属于文档缺失的已实现能力，须补讲而非建议 |

#### 22-sliding-window-rate-limit.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 来源块 L5 | 旧包路径 | `ChatRateLimiter.java:1` | 共性 1 | 按共性 1 处理 |
| ⭐ 4.2 / 三 L71、L74-75、L119-133 | 「`local now = tonumber(ARGV[1])`」「`ARGV[2]=window`/`ARGV[3]=limit`/`ARGV[4]=uuid`」「传入 `now`」 | `RedisScripts.java:260-274`（`redis.replicate_commands()` + `redis.call('TIME')`，`window=ARGV[1]`、`limit=ARGV[2]`、`member=ARGV[3]`）；`ChatRateLimiter.java:59-66`（仅 3 参数） | 时间源改为 Redis 服务器 `TIME`，`now` 参数移除 | 「`SLIDING_WINDOW_RATE_LIMIT` 位于 `util/RedisScripts.java:260-274`；脚本以 `TIME` 取服务器毫秒时间，ARGV 仅 `window / limit / UUID` 三个」 |
| ⭐ 6.1 L306-314 | 「`now` 由应用进程提供，不是 Redis 的 `TIME`…更严谨的做法是在 Lua 里用 `TIME`」 | `RedisScripts.java:261-263`（已用 `TIME`）、`ChatRateLimiter.java:62` | 「已知问题」已被修复 | 「6.1（✅ 已修复）旧版 `now` 由客户端 `System.currentTimeMillis()` 传入，多实例时钟偏斜会影响窗口；现改为脚本内 `redis.call('TIME')`」 |
| 一 / 4.8 / 七-2 L23、L272、L367 | 「`AgentEvent.notify("消息发送得太快啦，休息一下再继续吧～")`」 | `ChatServiceImpl.java:214-227`（`canStartChat()` 内 `notify("消息发送过于频繁，请稍后重试。")` + `tryEmitComplete()`） | 文案与调用点都变 | 「限流前置在 `ChatServiceImpl.canStartChat()`（`:214-227`），拒绝时通过 SSE `notify` 事件提示『消息发送过于频繁，请稍后重试。』并结束流」 |
| 4.6（建议补充） | 未提及另一套按来源地址限流 | `RequestRateLimitInterceptor.java:20-22`（`request:rate:{来源地址}`、60000/120）、`WebMvcConfig.java:25`（`/auth/**`、`/order/estimate`）、`api/auth-security.md:7` | 两套限流共用同一 Lua，但对象/阈值不同，易混淆 | 「补一句：项目另有按直接连接来源地址的 120 次/分钟限流（`RequestRateLimitInterceptor`，作用于 `/auth/**` 与 `/order/estimate`），与本篇按 userId 的 30 次/60 秒是两套独立策略，共用 `SLIDING_WINDOW_RATE_LIMIT` 脚本」 |
| 一致要点 | ZSet + Lua、`PEXPIRE window`、超限不 ZADD、`return limit-used-1`、30/60、Redis 异常放行、空 userId 放行、配置 ≤0 放行、UUID member、`chat:ratelimit:{userId}` 与验证命令 | `RedisScripts.java:260-274`、`ChatRateLimiter.java:70-74`、`application.yaml:80-81`、`RedisKeyConstants.java:158` | **一致，无需修改** | 保留原文 |

#### 23-ticket-pool-zset.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 来源块 L5 | 旧包路径 | `TicketPoolIndex.java:1` | 共性 1 | 按共性 1 处理 |
| ⭐ 二 / 4.2 / 4.3 L51、L177-231、L239-286 | 「`ensureWarm` 标记检查 + 抢锁 + 双检 + 重建」「`syncByTicketId` 回表 → 从所有池摘除 → 加入当前池 → 续期 → 写标记」 | `TicketPoolIndex.java:23-26`（类注释「写入提交后失效所有状态池」）、`:48-64`（`INVALIDATE_TICKET_POOLS` 换版本 + DEL 全部池）、`:67-122`（`slice`/`readSlice`/`isCurrent`）；无 `ensureWarm`/`rebuild`；`RedisScripts.java:88-124` 新增三脚本 | 整篇「逐条同步 + ensureWarm 双检」被「整池失效 + 版本/锁令牌 Lua 校验发布」取代 | 「写路径 `syncByTicketId` 用 `INVALIDATE_TICKET_POOLS` 更换 `ticket:pool:version` 并 DEL 全部状态池；读路径 `slice` 先 `READ_TICKET_POOL` 试读，未命中则抢重建锁、双检、全量回表，再由 `PUBLISH_TICKET_POOL` 校验版本与锁令牌后原子发布；`isCurrent` 做最终版本校验」 |
| ⭐ 二 / 4.2 L52、L209-237、L496、L522 | 「`ticket:pool:ready:{statusCode}` 值 `"1"`」「`GET … # "1"`」 | `RedisScripts.java:106,115`（值为 `version .. ':' .. count`）；`RedisKeyConstants.java:172-173`（新增 `TICKET_POOL_VERSION_KEY`） | ready 值改为「版本:成员数」 | 「ready 值为 `{version}:{count}`，与 `ticket:pool:version` 联合校验；`GET ticket:pool:ready:0` 期望形如 `uuid:12` 而非 `"1"`」 |
| ⭐ 二 / 4.3 L51、L280-284、L61 | 「2 小时（**每次写入续期**）」「只要还有工单在变动，索引就不会过期」 | `TicketPoolIndex.java:24-26,61`（「固定两小时过期」「索引固定到期，不因写入续期」）；`RedisScripts.java:106-107`（仅发布时设一次） | TTL 改为固定 2 小时、写入不续期 | 「数据 key 与 ready 标记在 `PUBLISH_TICKET_POOL` 发布时一次性设置 2 小时 TTL，写入不续期；任何工单变更直接失效整池」 |
| 4.1 L106-118、L150-152、L480 | 「`updatedAt` 为空时回退 `createdAt`」「`atZone(ZoneId.systemDefault())`」 | `TicketPoolIndex.java:154-159`（空值按 `0L`、`toInstant(ZoneOffset.UTC).toEpochMilli()`，注释「避免实例时区和夏令时改变排序」） | 时区改 UTC、空值不回退；6.7「多实例必须同区」已修复 | 「`score = priority * 1e13 + updatedAt.toInstant(ZoneOffset.UTC).toEpochMilli()`；`updatedAt` 为空按 0 处理；固定 UTC 映射，不依赖实例时区/夏令时」 |
| 二 / 4.3 L65、L253 | 「`String member = String.valueOf(ticket.getId());`」 | `TicketPoolIndex.java:107-108`（`String.format(Locale.ROOT, "%019d", ticket.getId())`） | 成员改为 19 位补零 | 「member 为 19 位补零的主键，保证 score 相同时 Redis 字典序与主键数值序一致；`readSlice` 用 `Long.valueOf` 还原」 |
| ⭐ 三 / 4.5 L79、L359-375、L434-441 | 「`record PoolSlice(total, ids)`」「`.filter(Objects::nonNull)` 容忍『索引里有、DB 里已删』」 | `TicketPoolIndex.java:37`（`record PoolSlice(String version, long total, List<Long> ids)`）；`TicketServiceImpl.java:384-391`（数量/状态/版本任一不符 → `return null`，注释「删除或跨状态变更不能靠过滤成员处理」） | 由「逐条过滤」改为「整页校验不一致即退回 DB」 | 「`PoolSlice` 为 `(version, total, ids)`；`pageByTicketPoolIndex` 用 `selectByIds` 取回实体并按 ids 重排，若数量不符、状态不符或 `isCurrent` 失败，则**整页退回 DB**」 |
| 4.2 / 七-5 L209-222、L526 | 「`log.info("工单池索引已重建: status={}, size={}")`」「日志中不应出现『已重建: status=4』」 | `TicketPoolIndex.java:62,119,129`（仅 3 条 `log.warn`，无 info 重建日志）；`TicketServiceImpl.java:1213-1218`（`afterTicketMutation` 顺序与文档相反） | 引用日志不存在，验证判据失效 | 「重建完成后没有 info 日志，仅异常路径有 warn；验证空池不重建应观察 `ticket:pool:ready:4` 的值（形如 `{version}:0`）稳定、且 `ticket:pool:version` 未被更换」 |
| 一致要点 | score 公式与 `1e13`、5 个状态池、三个 key 格式、2h / 10s、只有 status 单条件走索引、重建全量无分页、事务中不发布、拿不到锁返回 `Optional.empty()`、异常降级、第七节 key 名 | `TicketPoolIndex.java:33,34,158`、`RedisKeyConstants.java:170-187`、`TicketServiceImpl.java:348-355` | **一致，无需修改** | 保留原文 |

#### 24-user-location-hash.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 来源块 L5、L17-19、L31、L50-55 | 「`com/fancy/taxiagent/.../UserServiceImpl.java:263-283`」「`RedisKeyConstants.java:282-287`」「`OrderAgent.java:317-326` / `DailyAgent.java:203-212` / `SupportAgent.java:200-209`」「`UserController.java:40-57`」 | `UserServiceImpl.java:264-283`（`saveUserLocation :264-269`、`getUserLocation :272-283`）；`RedisKeyConstants.java:275-280`；`OrderAgent.java:340-349`、`DailyAgent.java:207`、`SupportAgent.java:204`；`UserController.java:41-57` | **仅包名与行号漂移，实现语义未变**；`saveUserLocation` 仍在 `UserServiceImpl`，未迁移到 `DriverLocationService` | 「> **完整路径**：`src/main/java/com/fancy/rideagenthub/service/impl/UserServiceImpl.java:264-283`；三处 `buildLocationString` 行号更新为 `OrderAgent.java:340-349`、`DailyAgent.java:207`、`SupportAgent.java:204`」；并补一句「用户位置 Hash 与司机在线 GEO 是两套独立数据，互不迁移」 |
| 其余全文 | `user:loc:{userId}`、三字段 Hash、无 TTL、三次无条件 HSET、HGETALL 空返回 null、404「用户定位数据不存在」、Redis 为唯一存储 | `RedisKeyConstants.java:86,278-280`、`UserServiceImpl.java:266-268`、`UserLocation.java:13-15`、`UserController.java:45-47` | **一致，无需修改**（本系列中过时程度最低的一篇） | 保留原文 |

#### 25-agent-hitl-state.md

| 段落位置 | 文档原文 | 代码位置 / 模块 | 差异说明 | 建议更新写法 |
|---|---|---|---|---|
| 来源块 L5 | 「`com/fancy/taxiagent/agents/OrderAgent.java:99-141`、`:262-305`」 | `OrderAgent.java:101-150`（resume）、`:283-328`（handleNotifyUser） | 共性 1 + 行号漂移 | 「`src/main/java/com/fancy/rideagenthub/agents/OrderAgent.java:101-150`、`:283-328`」 |
| 二、结构选型 L27-33 | 「`break=yes` 表示该会话正等待用户确认」（单态） | `OrderConfirmation.java:56`（`break=yes`）、`:25-29`（确认时 `break='resuming'`、`confirmationAccepted`、`confirmedQuote`、`ReadyForCreate`）、`:45-46`（`quoteId`、`ReadyforConfirm`、`quoteCreatedAt`） | `break` 现有两态，状态机扩展到 6+ 字段 | 「字段：`break`（`yes`=等待确认 / `resuming`=确认已登记待恢复）、`pendingToolCall`、`quoteId`、`quoteCreatedAt`、`confirmationAccepted`、`confirmedQuote`、`ReadyforConfirm`、`ReadyForCreate`；由 `OrderConfirmation` 的 Lua 脚本统一维护」 |
| ⭐ 4.1 L71-83、L121 | 「`handleNotifyUser` 依次做四件事…`break` 是最后一步」「挂起触发条件：无额外条件」 | `OrderAgent.java:306-321`（先 `orderConfirmation.begin(chatId, toolCall.id())` 取 `confirmationId` 写入 orderJson）；`OrderConfirmation.java:50-58`（`begin` 要求有效报价，否则 409） | 新增 confirmationId 与「必须先有效报价」前置条件 | 「`handleNotifyUser` 先调 `orderConfirmation.begin(...)` 取得 `confirmationId` 并写入 orderJson，再落 history、`HSET break yes`、发 `AgentEvent.comfirm(orderJson)`；`begin` 校验有效报价，无报价抛 409」 |
| ⭐ 4.2 L87-90 | 「`ChatServiceImpl.chat` 通路里**只看 `isLocked` 与分类结果**，全文没有 `break`」 | `ChatServiceImpl.java:95-99`（`break=yes` 时 `notify("请先确认或拒绝当前订单，再继续对话")` 并结束流）；`api/chat-security.md:5` | 已主动检查 break | 「`ChatServiceImpl.chat` 在分类前先检查 `break`：为 `yes` 时通过 `notify` 提示并结束流，等待确认期间 `/chat/v2/c/{id}` 不能追加普通聊天」 |
| ⭐ 4.2 / 4.3 / 6.1 L43-47、L92-104、L108-117、L148 | 「`HDEL chat:info:{chatId} break`」「要求 history 里能找到 `notifyUser` 的 toolCallId」「把用户回复包装成工具结果 `feedback`」 | `OrderAgent.java:107-108`（校验 `break=="resuming"`）、`:115`（`messageMemory.getPersisted`）、`:121-127`（从 `pendingToolCall` 取 toolCallId）、`:137-145`（先持久化工具应答）、`:146`（一次 `HDEL` 三个字段） | 守卫值、toolCallId 来源、历史读取、删除字段数全变 | 「`resume` 校验 `break == "resuming"`（由 `OrderConfirmation.confirm` 的 Lua 设置），从 `pendingToolCall` 取 toolCallId，用 `messageMemory.getPersisted` 读历史、先持久化 `ToolResponseMessage` 再进循环，最后 `HDEL break, pendingToolCall, confirmationAccepted`」 |
| ⭐ 三 / 4.3 L18、L50、L119、L151 | 「`ChatServiceImpl.java:180-184 resume` 直接转发给 `orderAgent.resume`」「自然接着调 `markOrderReadyForCreate` → `createOrder`」 | `ChatServiceImpl.java:230-241`（先 `orderConfirmation.confirm(...)`，再以固定文本调 resume）；`OrderTool.java:412`（mark 只读）；`api/chat-security.md:12` | resume 不再转发用户原话；授权发生在 `OrderConfirmation` | 「`POST /chat/v2/r/{id}` 传 `{confirmationId, confirmed, prompt}`；`ChatServiceImpl.resume` 先 `orderConfirmation.confirm(...)` 校验并签发 `ReadyForCreate`，再以固定文本调 `orderAgent.resume`；`markOrderReadyForCreate()` 只查询 `isConfirmed`」 |
| ⭐ 三节代码落点 L52 | 「`controller/Chat2Controller.java:53-60` `POST /order/resume/{chatId}` 恢复接口（测试用）」 | `a8f4e33` 删除 `Chat2Controller.java`、`TestController.java`；`api/chat-security.md:14`；`ChatController.java:49-53` | 类与接口均已删除（根 `README.md:412-414` 同样过时） | 「删除该行；恢复接口仅 `POST /chat/v2/r/{id}`（`controller/ChatController.java:49-53`，内部走 `ChatRequestRunner.run(..., true)`）」 |
| ⭐ 6.3 TTL L159-168 | 「`chat:info` key 首次创建不带 TTL…`break` 会一直驻留」 | `OrderConfirmation.java:41-48`（`quoted()` 中 `redis.expire(key, Duration.ofHours(24))`）；`ChatManager.java:24`（60 分钟锁） | 挂起期间有 24 小时 TTL | 「`OrderConfirmation.quoted()` 在算价时即对 `chat:info:{chatId}` 设置 24 小时过期；`ChatManager.lockChat` 另设 60 分钟；挂起超过 24 小时后 `break` 随 key 过期消失」 |
| ⭐ 6.4-2 L173 | 「挂起没有时间约束…跨天用户再说『确认』，`resume` 依然会接着往下走」 | `OrderConfirmation.java:19,34`（`CONFIRM`/`CONSUME` 均校验 `quoteCreatedAt` 900000ms）；`api/chat-security.md:10` | 已有 15 分钟报价有效期 | 「6.4（✅ 已修复）`CONFIRM`/`CONSUME` Lua 均以 `quoteCreatedAt` 校验 15 分钟；超时返回 0 → `BusinessException(409, "确认已失效或已被使用，请重新核对订单")`，创建权限只能消费一次」 |
| 引用 README 时序图 L18；`README.md:205-211` | 「恢复 → `markOrderReadyForCreate()` → `createOrder()`」；`Note：设置 break=yes` | `api/chat-security.md:10-12`、`ChatServiceImpl.java:239`、`OrderTool.java:412`、`OrderConfirmation.java:25-29` | 时序图省略 `confirm(confirmationId, confirmed)` 授权环节，易被理解为「由该工具签发创建权限」 | 「时序图在 `POST /chat/v2/r/chatId` 后补 `CS->>Redis: orderConfirmation.confirm(confirmationId, confirmed)`（校验 15 分钟、置 `break=resuming`、签发 `ReadyForCreate`），并把 `markOrderReadyForCreate()` 标注为『仅查询 isConfirmed』」 |
| 一致要点 | `chat:info` Hash 与 `chatInfoKey()`、`notifyUser` 只检查 `ReadyforConfirm`、按工具名中断循环、补 `ToolResponseMessage` 后 `runLoopStep`、`break` 由 HDEL 消费、`AgentEvent.comfirm`、验证命令 key 名 | `RedisKeyConstants.java:56,243-245`、`OrderTool.java:395-404`、`OrderAgent.java:129-149,253-263,318` | **一致，无需修改** | 保留原文 |
| 6.4-1（建议补充） | 「用户到底确认了没有没被结构化记录」 | `OrderConfirmation.java:25-29`（`confirmationAccepted`/`confirmedQuote` 已结构化）；`config/ChatProperties.java:80` 提示词仍写「调用 markOrderReadyForCreate() 后 createOrder()」 | 结论已不成立；代码提示词也有旧称 | 「6.4-1 改为 ✅ 已实现：`confirmationAccepted`/`confirmedQuote` 已结构化记录；并提示 `ChatProperties` 的提示词文案需与该工具降级为只读查询同步」 |

---

## 四、建议的更新优先级

| 优先级 | 范围 | 理由 | 建议动作 |
|---|---|---|---|
| **P0** | `README.md` L59、L205-215、L300、L403-421；`AGENTS.md`/`CLAUDE.md` 各 4 条；`docs/learn/agent/README.md` L3 | 面向所有读者的入口文档，错误会直接误导；`AGENTS.md`/`CLAUDE.md` 还是 AI 辅助编码的指导文件，错误路径/`LambdaQueryMapper` 会传导到新代码 | 按本报告「建议更新写法」逐条替换；`README.md` 的测试控制器整段删除 |
| **P0** | `docs/learn/redis/README.md` L18-19、L30-31、L65、L75、L127-146 | 25 篇笔记的总索引，其「基础设施现状」「未实现的考点」「与原始文档的关系」三张表是读者判断其余笔记可信度的依据 | 统一改路径基准、脚本数、`GEORADIUS`、幂等/3.3 状态 |
| **P1** | `docs/learn/redis/` 中「已修复 / 仍是坑」标注反转的篇目：01、02、03、06、09、11、12、13、14、17、18、19、22、25 | 这类差异会给出**错误的安全/并发结论**，风险高于纯行号漂移 | 按第 2 节「共性 3 对照表」批量改写状态标注 |
| **P1** | `docs/01`、`docs/02` 抬头 + 各 P0/P1 节 + 第四节优先级表 | 规划稿仍以「待改造」口吻描述已落地能力 | 加历史快照声明，并在各节加「【已落地】」标注，不改历史代码块 |
| **P2** | `docs/learn/redis/` 其余实现拆解与「七、如何验证」章节 | 代码片段/redis-cli 命令与当前实现不符，但主要影响动手复现 | 按本报告逐篇替换；建议同时把 `文件:行号` 改为「类名 + 方法名」定位 |
| **P3** | 5 篇 `api/*.md` | 与代码高度一致，仅需 `auth-security.md` 补接口清单、3 处措辞澄清 | 小改即可；可作为回写其他文档时的**事实基准** |

---

## 五、核查一致、无需修改的部分（避免误改）

- `src/main/resources/api/` 5 篇的绝大多数断言：`EmailCodeService` 5 次上限与事务预留、`RequestRateLimitInterceptor` 120 次/分钟与 429、Token 会话版本与凭据摘要、`init.sql` 唯一索引、6 个 `/chat/v2` 端点、`confirmationId`/15 分钟/一次性消费、`getCalling`/`callId`、四千/四万状态码、4 个司机端点与经纬度范围、`GEORADIUS` 与三公里/200 候选/整页降级、订单安全各条、`PaymentVerifier` 默认 503、RAG 同步任务 5s/30s/8s 预算、工单状态机。
- 根 `README.md` 的绝大部分：4 个 Agent 与 5 个分类标签、Redis 高并发实战表全部代码落点、GEORADIUS、成员级 TTL 60 秒、TTL 随机抖动、墓碑 5 秒/7 天、滑动窗口 ZSet+Lua、工单池 ZSet、MessageMemory 分层、RAG 五步流程、5 类 `AgentEvent`、4 个模型名、技术栈版本（除 `1.1.0-RC1`）、目录结构、快速启动配置层级与两个 sql 文件、L145 附注中「会话锁已原子化 / 令牌锁校验持有者 / 布隆仍原地重建」三项。
- `AGENTS.md`/`CLAUDE.md`：`StringOrEmptyArrayToNullDeserializer` 确实存在且被 amap POJO 使用；`resources/api/` 目录存在；技术栈版本号与 `pom.xml` 一致。
- `docs/01` P1-3「已实现」段落（工单池 ZSet 的 score 公式/2 小时/版本令牌/Lua 发布/回退 DB）、第三节缺陷 2/3；`docs/02` 3.4 与 `ESMemory` 空实现结论。
- `docs/learn/redis/README.md`：全项目只用 `StringRedisTemplate`、无自定义 `RedissonConfig`、25 篇核心代码路径全部存在、按数据结构表、BitMap/ZINCRBY/HyperLogLog/Pub-Sub/Token 滑动续期确未实现。
- 各笔记中的 TTL 数值与 Redis key 名：工单统计 20+0~10s、城市编码 7 天/墓碑 5 秒、会话锁 60 分钟、FOCUS 24h/NON_FOCUS 30min/STAGING 1min、`chat.guard` 30/60、`ride-order.timeout` 15/10/30/5000/100、`auth.token-ttl-seconds` 604800、`email-code` 300/60 —— 均与 `application.yaml` 和代码常量一致。

---

## 六、存疑与未能从仓库验证的项（不作为差异结论）

| 项 | 位置 | 说明 |
|---|---|---|
| `spring-ai-starter-model-openai` 解析后的确切版本 | `README.md:300` | 由 BOM 1.1.0 托管，仓库内不可见；可确定的是 pom 已无 `1.1.0-RC1` 声明 |
| 「双模型编排」与「路由默认模型」 | `README.md:57,284` | 代码有 3 个对话模型常量 + 1 个 Embedding；`qwen-plus-2025-12-01` 只作为 `ChatClient.defaultOptions`，唯一分类调用被 `CLASSIFIER_MODEL` 覆盖，是否真实生效存疑 |
| 历史 commit 号 | `docs/01:5`、`docs/02:5`、`docs/learn/redis/README.md:19` | `8d5d574`、`bdc40d7` 均真实存在于 Git 历史，建议改标「撰写时的历史快照」而非删除 |
| `chat-security.md` L3「chatId 必须是 newuuid 返回的 UUID」 | `ChatRequestRunner.java:30`、`ChatManager.java:82-84`、`ChatInfoService.java:34-36` | 代码只做 `UUID.fromString` 格式校验且不登记，措辞偏严；属文档精度而非后期改动 |
| `ChatProperties` 提示词中的 `getCalling(call_id)` | `config/ChatProperties.java:92,121,123,162-163` | 与 `@Tool` schema 的 `callId` 不一致，是**代码内部**残留，不是文档过时 |
| `driver-location.md` L16「每 20 秒上报一次」 | 全库无 20 秒常量 | 属客户端建议值，无法从代码验证 |
| `order-security.md` L11「改目的地与结束行程…比较旧轨迹标识」 | `OrderSearchTool.java:420-421` vs `RideOrderServiceImpl.java:484-541` | 主语含糊，`finishRideUnderLock` 未加 `mongoTraceId` 条件；证据不足以判定文档错误 |
| `RideOrderVO.java:53` 的「GEOSEARCH 结果」注释 | `domain/vo/RideOrderVO.java:53` | 代码内旧称残留，与 `docs/learn/redis/15` 同源，建议随文档一起清理（本报告不修改代码） |
| `application.yaml:19` 数据源库名 `taxi_agent` | `application.yaml:19` | 是 DB schema 名而非 artifactId/包名，是否随项目改名由运维决定，未列为差异 |
| `CityCodeUtil.invalidate()` 的 `cityName.trim()` 与读路径 `toLowerCase(Locale.ROOT)` | `CityCodeUtil.java:110,112`、`RedisKeyConstants.java:233` | 含大写英文的 cityName 可能出现失效 key 与读 key 不一致的边界；需实测，未列为差异 |
| `versionKey`（`auth:session-version:*`）无 TTL 的长期累积 | `TokenServiceImpl.java:48` | 是否需要清理机制，源码与注释均未说明 |
| `docs/02:20` 交叉引用「见 3.3」 | 布隆缺陷实际在 3.4 | 文档内部编号错误，与代码无关，已在 3.3 差异条中顺带给出修正 |

---

**报告结束。** 本报告仅提供诊断结论与建议文案，未对仓库中任何既有 `.md` 或代码文件执行修改。
