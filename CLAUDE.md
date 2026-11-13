# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

RideAgentHub is a Spring Boot 3 AI-powered taxi booking and customer service platform with 4 specialized AI agents (DailyAgent, OrderAgent, SupportAgent, FallbackAgent).

## Build & Run Commands

```bash
mvn compile                    # Compile the project
mvn compile -DskipTests        # Compile without running tests
mvn clean package              # Build JAR file
mvn test                       # Run unit tests
mvn spring-boot:run           # Run application directly
```

## Key Tech Stack

- **Language**: Java 21
- **Framework**: Spring Boot 3.5.9 with Spring AI Alibaba Agent 1.1.0.0-RC2
- **Database**: MySQL (users, orders, tickets, chat messages, tool results, RAG mappings), MongoDB (order route traces)
- **Search**: Elasticsearch (`qa_knowledge_base`: vector + BM25 + RRF hybrid retrieval), Redis (cache, session state, concurrency control)
- **ORM**: MyBatis-Plus 3.5.9
- **LLM Providers**: DashScope (embedding), DeepSeek (OpenAI compatible API)

## Architecture

### AI Agent Layer (`agents/`)
- 4 specialized agents (`DailyAgent`, `OrderAgent`, `SupportAgent`, `FallbackAgent`) live in `<base>/agents/`
- Classification is not a separate class: `service/impl/ChatServiceImpl.resolveClassification()` runs it inline, with results cached by `service/base/ClassificationCache`
- Memory: **Heap(L1) → Redis(L2) → MySQL(L3)**; tool results in `agentbase/memory/ToolResponseMemory` (Redis + MySQL, two levels); `ESMemory` is an empty placeholder and `MemoryAdvisor` returns null, both without callers
- Tool callbacks in `agentbase/tool/` for function calling
- SSE orchestration and per-turn execution lock: `service/base/ChatRequestRunner.java`, `agents/AgentStreams.java`; prompt contracts in `config/ChatProperties.java`

### API Layer (`controller/`)
- Controllers -> Services -> Mappers pattern
- Token-based auth with email OTP support; auth chain: `interceptor/AuthTokenInterceptor` → `security/UserTokenContext` → `aspect/PermissionAspect` → `@RequirePermission`
- API documentation in `src/main/resources/api/`

### Data Layer
- MySQL: Relational data (users, orders, tickets, chat messages, tool results, RAG mapping tables)
- MongoDB: Order route traces only (`OrderRoute` / `OrderRouteService`), **not conversations**
- Elasticsearch: `qa_knowledge_base` index and hybrid retrieval (`config/chat-es-setting.json` is a legacy N-Gram config, unused in code)

### Configuration & Async Tasks (`config/`)
- `AsyncWorkConfig` isolates `ragExecutor` / `chatExecutor`; `agentbase/rag/QaIndexSynchronizer` owns knowledge-base index eventual consistency
- `IdGeneratorConfig`, `PaymentConfig`, `ChatGuardProperties`, `WebMvcConfig` govern snowflake node leases, the payment gate, guard parameters and interceptors/CORS

## Development Notes

- Use `StringOrEmptyArrayToNullDeserializer` for String return types in `agentbase.amap` POJOs
- Follow existing function patterns when creating new tools
- Use MyBatis-Plus `LambdaQueryWrapper` / `LambdaUpdateWrapper` in services (there is no `LambdaQueryMapper` type)
- Update API docs in `resources/api/` when modifying controllers
- When applying patch, use relative path like `src/main/java/com/fancy/rideagenthub/controller/AuthController.java`
