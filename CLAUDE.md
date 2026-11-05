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
- **Database**: MySQL (users, orders, tickets), MongoDB (order routes, conversations)
- **Search**: Elasticsearch (full-text chat search), Redis (cache)
- **ORM**: MyBatis-Plus 3.5.9
- **LLM Providers**: DashScope (embedding), DeepSeek (OpenAI compatible API)

## Architecture

### AI Agent Layer (`agentbase/agents/`)
- 4 specialized agents handle different domains
- Memory system spans Redis, MongoDB, MySQL, and Elasticsearch
- Tool callbacks in `agentbase/tool/` for function calling

### API Layer (`controller/`)
- Controllers -> Services -> Mappers pattern
- Token-based auth with email OTP support
- API documentation in `src/main/resources/api/`

### Data Layer
- MySQL: Relational data (users, orders, tickets, chat logs)
- MongoDB: Order routes and conversation history
- Elasticsearch: N-Gram full-text chat retrieval

## Development Notes

- Use `StringOrEmptyArrayToNullDeserializer` for String return types in `agentbase.amap` POJOs
- Follow existing function patterns when creating new tools
- Use MyBatis-Plus LambdaQueryMapper in services
- Update API docs in `resources/api/` when modifying controllers
- When applying patch, use relative path like `src/main/java/com/fancy/rideagenthub/controller/AuthController.java`
