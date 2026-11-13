# AGENTS.md

本文件为 Codex (OpenAI Codex / OpenCode) 在处理本仓库代码时提供相关指导。

## 项目概述

RideAgentHub 是一个基于 Spring Boot 3 的 AI 驱动的出租车预订和客户服务平台，包含 4 个专用的 AI 代理（DailyAgent、OrderAgent、SupportAgent、FallbackAgent）。

## 构建与运行命令

```bash
mvn compile                    # 编译项目
mvn compile -DskipTests        # 编译并跳过测试
mvn clean package              # 构建 JAR 文件
mvn test                       # 运行单元测试
mvn spring-boot:run            # 直接运行应用程序
```

## 核心技术栈

- **语言**: Java 21
- **框架**: Spring Boot 3.5.9 与 Spring AI Alibaba Agent 1.1.0.0-RC2
- **数据库**: MySQL（用户、订单、工单、对话消息、工具结果、RAG 映射），MongoDB（订单路线轨迹）
- **搜索与缓存**: Elasticsearch（知识库 `qa_knowledge_base`，向量 + BM25 + RRF 混合检索），Redis（缓存、会话状态、并发协调）
- **ORM**: MyBatis-Plus 3.5.9
- **大模型供应商**: DashScope（向量化/Embedding），DeepSeek（兼容 OpenAI API）

## 架构

### AI 代理层 (`agents/`)
- 4 个专用代理（`DailyAgent`、`OrderAgent`、`SupportAgent`、`FallbackAgent`）位于 `<base>/agents/`，负责处理不同领域的任务
- 分类不是独立类：`service/impl/ChatServiceImpl.resolveClassification()` 内联执行，结果由 `service/base/ClassificationCache` 缓存
- 记忆（Memory）系统为 **Heap(L1) → Redis(L2) → MySQL(L3)**；工具结果另存 `agentbase/memory/ToolResponseMemory`（Redis + MySQL 两级）；`ESMemory` 是空占位类、`MemoryAdvisor` 返回 null，均无业务调用方
- 用于函数调用的工具回调（Tool callbacks）位于 `agentbase/tool/` 目录
- SSE 编排与单轮执行锁位于 `service/base/ChatRequestRunner.java`、`agents/AgentStreams.java`；领域提示词契约位于 `config/ChatProperties.java`

### API 层 (`controller/`)
- 采用 控制器 (Controllers) -> 服务 (Services) -> 映射器 (Mappers) 架构模式
- 基于 Token 的身份验证，支持邮箱一次性密码 (OTP)；鉴权链为 `interceptor/AuthTokenInterceptor` → `security/UserTokenContext` → `aspect/PermissionAspect` → `@RequirePermission`
- API 文档位于 `src/main/resources/api/`

### 数据层
- MySQL: 关系型数据（用户、订单、工单、对话消息、工具调用结果、RAG 映射表）
- MongoDB: 仅订单路线轨迹（`OrderRoute` / `OrderRouteService`），**不存对话记录**
- Elasticsearch: 知识库索引 `qa_knowledge_base` 与混合检索（`config/chat-es-setting.json` 为遗留 N-Gram 配置，当前代码未引用）

### 配置与异步任务 (`config/`)
- `AsyncWorkConfig` 提供 `ragExecutor` / `chatExecutor` 双线程池隔离；`agentbase/rag/QaIndexSynchronizer` 负责知识库索引最终一致性
- `IdGeneratorConfig`、`PaymentConfig`、`ChatGuardProperties`、`WebMvcConfig` 分别约束雪花 ID 节点租约、支付门禁、守卫参数与拦截器/CORS

## 开发注意事项

- 在 `agentbase.amap` POJO 类中，对于返回类型为 String 的字段，请使用 `StringOrEmptyArrayToNullDeserializer` 进行反序列化
- 创建新工具时，请遵循现有的函数模式
- 在服务层（services）中使用 MyBatis-Plus 的 `LambdaQueryWrapper` / `LambdaUpdateWrapper` 构造查询条件（不存在 `LambdaQueryMapper` 类型）
- 修改控制器（controllers）时，请同步更新 `resources/api/` 中的 API 文档
- 应用补丁（patch）时，请使用相对路径，例如 `src/main/java/com/fancy/rideagenthub/controller/AuthController.java`
