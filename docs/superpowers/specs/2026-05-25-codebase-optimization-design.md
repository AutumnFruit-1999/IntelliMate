# IntelliMate 代码结构优化设计方案

> 日期：2026-05-25
> 状态：待审核
> 范围：代码结构重构 + API 规范化 + 可观测性

## 概述

本文档描述 IntelliMate 项目的三阶段优化路线图，采用"核心先行"策略：先拆分最大的技术债（God Class），在重构过程中引入统一 DTO 和错误处理，最后在清晰的代码结构上添加可观测性。

### 背景

项目当前整体质量评分约 3/5。核心问题：

- `AgentRuntime`（1888 行）承载 7+ 个独立职责
- `MessagePipeline`（1060 行）承载 5+ 个独立职责
- 15 个 Controller 无统一响应格式和 DTO 层
- 零可观测性（无 Metrics、无链路追踪）

### 约束

- 仅内部使用，不需要向后兼容
- 长期路线图，不急于一次性完成
- 不引入重量级外部基础设施

---

## Phase 0: 统一异常处理 + Response DTO 骨架

**目标：** 建立规范，后续重构自动遵循。

### 0.1 全局异常处理器

新增 `GlobalExceptionHandler`（`@ControllerAdvice`），按异常类型分级处理：

| 异常类型 | HTTP 状态码 | 行为 |
|---------|------------|------|
| `IntelliMateException` 子类 | 由错误码决定 | 返回业务错误码 + 脱敏消息 |
| `IllegalArgumentException` | 400 | 返回参数校验错误 |
| `ResponseStatusException` | 原始状态码 | 透传（兼容现有代码） |
| 其他 `Exception` | 500 | 脱敏消息 + ERROR 日志 |

### 0.2 统一响应结构

```java
public record ApiResponse<T>(
    boolean success,
    T data,
    ApiError error
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }
    public static <T> ApiResponse<T> fail(ApiError error) {
        return new ApiResponse<>(false, null, error);
    }
}

public record ApiError(
    String code,
    String message,
    Map<String, Object> details
) {}
```

### 0.3 错误码体系

```java
public enum ErrorCode {
    AGENT_NOT_FOUND("AGENT_001", 404),
    AGENT_NAME_CONFLICT("AGENT_002", 409),
    SESSION_NOT_FOUND("SESSION_001", 404),
    PLAN_INVALID_STATE("PLAN_001", 409),
    PLAN_NOT_FOUND("PLAN_002", 404),
    TOOL_EXECUTION_FAILED("TOOL_001", 500),
    TOOL_NOT_FOUND("TOOL_002", 404),
    MCP_CONNECTION_FAILED("MCP_001", 502),
    MODEL_NOT_FOUND("MODEL_001", 404),
    BRIDGE_TIMEOUT("BRIDGE_001", 504),
    AUTH_INVALID_TOKEN("AUTH_001", 401),
    VALIDATION_FAILED("VALIDATION_001", 400),
    INTERNAL_ERROR("INTERNAL_001", 500);

    private final String code;
    private final int httpStatus;
    // constructor, getters
}
```

### 0.4 Bean Validation

为请求体引入 `@Valid` + `@NotBlank` / `@NotNull` / `@Size` 等约束，替代各 Controller 中手动的 `if-null-throw` 检查。

---

## Phase 1: 拆分 AgentRuntime（1888 行 → 7 个协作类）

**目标：** 消除最大单点技术债，每个类职责单一、可独立测试。

### 1.1 职责拆分

| 新类名 | 包路径 | 职责 | 预估行数 |
|--------|--------|------|----------|
| `AgentRuntime` | `agent.runtime` | 瘦入口：dispatch + WS 生命周期管理 | ~120 |
| `AgentLoopExecutor` | `agent.runtime` | LLM 对话循环：流式调用、响应解析、轮次递归 | ~250 |
| `ToolExecutionPipeline` | `agent.runtime` | 工具执行中间件链：循环检测→缓存→审批→执行→重试 | ~200 |
| `DelegationExecutor` | `agent.runtime` | 多 Agent 委托：单一/并行委托 + Handoff | ~250 |
| `AgentPromptBuilder` | `agent.runtime` | 系统提示词构建：soul/agents/skills/plan/tool guidelines | ~180 |
| `PlanEventExtractor` | `agent.runtime` | Plan 事件提取 + PlanStepTracker 自动跟踪 | ~200 |
| `AgentMemoryLifecycle` | `agent.runtime` | 记忆初始化/压缩/情景记忆存储 | ~180 |

### 1.2 类间协作关系

```
AgentRuntime (瘦入口, @Service)
  │
  ├── AgentLoopExecutor (@Component)
  │     ├── ToolExecutionPipeline (@Component)
  │     ├── DelegationExecutor (@Component)
  │     └── PlanEventExtractor (@Component)
  │
  ├── AgentPromptBuilder (@Component)
  │
  └── AgentMemoryLifecycle (@Component)
```

所有拆分类均为 Spring `@Component`，通过构造器注入协作。不使用 Spring 事件机制（避免流式延迟和调试复杂度）。

### 1.3 AgentLoopContext

解决 `executeLoopTurn` 22 个参数的问题：

```java
public record AgentLoopContext(
    ChatModel chatModel,
    List<Message> history,
    ChatOptions options,
    int maxTurns,
    Duration timeout,
    String agentName,
    Long sessionId,
    String skillsBasePath,
    ToolCallLoopDetector loopDetector,
    WorkingMemory workingMemory,
    ImportanceAssessor importanceAssessor,
    TokenEstimator tokenEstimator,
    ToolResultCache cache,
    ToolApprovalGate approvalGate,
    Duration toolTimeout,
    int maxParallel,
    Set<String> nonRetryableTools,
    Long activePlanId,
    PlanStepTracker planStepTracker,
    PlanExecutionAssessment planAssessment,
    AgentRunRequest originalRequest,
    Map<String, ToolCallback> toolCallbackMap
) {}
```

### 1.4 静态状态清理

将以下 `static ConcurrentMap` 移至 `AgentRuntime` 实例字段：

| 原字段 | 管理方 | 访问方式 |
|--------|--------|---------|
| `latestSnapshots` | `AgentRuntime` | 提供 `getLatestSnapshot()` 公共方法 |
| `deferredEpisodicStores` | `AgentMemoryLifecycle` | 注入到 AgentRuntime |
| `sessionApprovalGates` | `AgentRuntime` | `resolveApproval()` 保留在 AgentRuntime |
| `sessionSkillGroups` | `AgentPromptBuilder` | 内部管理 |

### 1.5 PlanStepTracker 提升

从 `AgentRuntime` 的 inner class 提升为独立类 `com.atm.intellimate.agent.runtime.PlanStepTracker`，被 `PlanEventExtractor` 使用。

### 1.6 来源方法 → 目标类映射

| 原方法 | 目标类 |
|--------|--------|
| `dispatch()` | AgentRuntime |
| `registerWsRun()`, `cancelByWsSession()` | AgentRuntime |
| `flushDeferredEpisodicMemory()` | AgentRuntime → 委托给 AgentMemoryLifecycle |
| `resolveApproval()`, `signalPlanPaused()` | AgentRuntime |
| `executeAgentLoop()` | AgentLoopExecutor |
| `executeLoopTurn()` | AgentLoopExecutor |
| `extractTextDelta()`, `mergeToolCallsFromChunks()` | AgentLoopExecutor |
| `processToolCalls()` | AgentLoopExecutor（协调 ToolExecutionPipeline + DelegationExecutor） |
| `executeSingleTool()`, `doExecuteTool()`, `isRetryableError()` | ToolExecutionPipeline |
| `executeSingleDelegation()`, `executeParallelDelegation()`, `executeHandoffToolCall()` | DelegationExecutor |
| `buildSystemPrompt()`, `buildSkillsDiscovery()`, `setupSkillGroupContext()` | AgentPromptBuilder |
| `extractPlanEvents()`, `extractWritePlanEvents()`, `extractUpdatePlanEvents()`, `filterDuplicatePlanEvents()` | PlanEventExtractor |
| `loadMemoryInitReactive()`, `createMemoryConsolidator()`, `storeSessionEpisodicMemory()`, `deferEpisodicStore()` | AgentMemoryLifecycle |
| `resolveModel()`, `buildChatOptions()` | AgentLoopExecutor（或直接用 ChatModelRegistry） |
| `recordSkillActivationIfApplicable()` | ToolExecutionPipeline |
| `logRequestParams()` | AgentLoopExecutor |

---

## Phase 2: 拆分 MessagePipeline + API 全面规范化

**目标：** HTTP/WS 层焕然一新，响应格式统一，API 文档自动生成。

### 2.1 MessagePipeline 拆分

| 新类名 | 包路径 | 职责 | 预估行数 |
|--------|--------|------|----------|
| `MessagePipeline` | `gateway.pipeline` | 瘦路由器：请求分发 + cancel/approval + WS 管理 | ~100 |
| `PlanRequestHandler` | `gateway.pipeline` | Plan 子协议：approve/pause/resume/cancel/skip/modify/add/reorder | ~200 |
| `AgentEventMapper` | `gateway.pipeline` | AgentEvent → GatewayFrame 映射（20+ case） | ~280 |
| `PlanExecutionOrchestrator` | `gateway.pipeline` | Plan 执行上下文构建 + 后同步 + 记忆提取 | ~200 |
| `MessageConverter` | `gateway.pipeline` | Transcript → Spring AI Message + 历史加载 | ~80 |

### 2.2 协作关系

```
MessagePipeline (瘦路由器, @Component)
  ├── PlanRequestHandler (@Component)
  ├── AgentEventMapper (@Component)
  ├── PlanExecutionOrchestrator (@Component)
  ├── MessageConverter (@Component)
  └── AgentRuntime
```

### 2.3 来源方法 → 目标类映射

| 原方法 | 目标类 |
|--------|--------|
| `processRequest()` | MessagePipeline |
| `processCancelRequest()`, `processApprovalResponse()` | MessagePipeline |
| `onWebSocketDisconnect()` | MessagePipeline |
| `processMessageStreaming()` | MessagePipeline（调用其他组件组装） |
| `processPlanRequest()` | PlanRequestHandler |
| `mapAgentEvent()` | AgentEventMapper |
| `handleHandoff()` | AgentEventMapper |
| `buildPlanExecutionPayload()` | PlanExecutionOrchestrator |
| `syncPlanAfterExecution()`, `checkAndCompletePlan()` | PlanExecutionOrchestrator |
| `schedulePlanCompletionMemoryExtraction()` | PlanExecutionOrchestrator |
| `convertToAiMessages()` | MessageConverter |
| `loadHistory()` | MessageConverter |

### 2.4 DTO 层

为核心实体定义 Java record DTO，使用静态工厂方法 `fromEntity()` 映射：

| DTO | 对应实体 | 使用场景 |
|-----|---------|---------|
| `AgentDTO` / `AgentSummaryDTO` | AgentConfig 数据库表 | 详情 / 列表 |
| `ModelDTO` | ModelDefinition | 模型管理 |
| `ToolDTO` | DynamicTool | 工具管理 |
| `McpServerDTO` | McpServerEntity | MCP 管理 |
| `SkillDTO` / `SkillGroupDTO` | Skill / SkillGroup | 技能管理 |
| `PlanDTO` / `PlanStepDTO` | PlanEntity / PlanStepEntity | Plan 管理 |
| `SessionDTO` | SessionEntity | 会话管理 |
| `ScheduledJobDTO` | ScheduledJobEntity | 调度管理 |

DTO 放置在 `gateway.dto` 包下。

### 2.5 OpenAPI 文档

引入 `springdoc-openapi-starter-webflux-ui`：

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
    <version>2.8.0</version>
</dependency>
```

- Controller 方法添加 `@Operation(summary = "...")` 注解
- DTO 字段添加 `@Schema(description = "...")` 注解
- 访问 `/swagger-ui.html` 查看自动生成的文档
- WebSocket 协议手动补充文档

---

## Phase 3: 可观测性

**目标：** 在清晰边界上建立运行时可视化能力。

### 3.1 技术选型

- **Micrometer**（Spring Boot 原生 Metrics 门面）+ **micrometer-registry-prometheus**
- **Prometheus**（Docker 容器，定时拉取 `/actuator/prometheus`）
- **Grafana**（可选，可视化仪表盘）
- **MDC traceId**（轻量级链路追踪，贯穿日志）

### 3.2 Metrics 埋点

#### LLM 层

| Metric | 类型 | 维度 | 埋点位置 |
|--------|------|------|----------|
| `agent.llm.requests` | Counter | model, agent, status | AgentLoopExecutor |
| `agent.llm.latency` | Timer | model, agent | AgentLoopExecutor |
| `agent.llm.tokens.prompt` | Counter | model, agent | AgentLoopExecutor |
| `agent.llm.tokens.completion` | Counter | model, agent | AgentLoopExecutor |
| `agent.loop.turns` | Histogram | agent | AgentLoopExecutor |

#### 工具层

| Metric | 类型 | 维度 | 埋点位置 |
|--------|------|------|----------|
| `agent.tool.executions` | Counter | tool, status | ToolExecutionPipeline |
| `agent.tool.latency` | Timer | tool | ToolExecutionPipeline |
| `agent.tool.cache.hits` | Counter | tool | ToolExecutionPipeline |
| `agent.tool.retries` | Counter | tool | ToolExecutionPipeline |
| `agent.tool.loop_detected` | Counter | tool | ToolExecutionPipeline |

#### 记忆层

| Metric | 类型 | 维度 | 埋点位置 |
|--------|------|------|----------|
| `memory.working.usage_ratio` | Gauge | agent | AgentMemoryLifecycle |
| `memory.consolidation.triggered` | Counter | agent | AgentMemoryLifecycle |
| `memory.longterm.retrieval.latency` | Timer | agent | AgentMemoryLifecycle |
| `memory.longterm.store.count` | Counter | type | AgentMemoryLifecycle |

#### API / WebSocket 层

| Metric | 类型 | 维度 | 埋点位置 |
|--------|------|------|----------|
| `http.requests` | Timer | method, path, status | WebFilter（自动/Actuator） |
| `ws.connections.active` | Gauge | — | SessionRegistry |
| `ws.messages.received` | Counter | method | MessagePipeline |

#### Plan 层

| Metric | 类型 | 维度 | 埋点位置 |
|--------|------|------|----------|
| `plan.created` | Counter | agent | PlanEventExtractor |
| `plan.completed` | Counter | agent, status | PlanExecutionOrchestrator |
| `plan.step.duration` | Timer | agent | PlanExecutionOrchestrator |

### 3.3 链路追踪（MDC 方案）

每个 WebSocket/HTTP 请求生成 `traceId`，贯穿整个调用链：

```
WebSocket Handler → MessagePipeline → AgentRuntime → AgentLoopExecutor
    → ToolExecutionPipeline → DelegationExecutor → AgentMemoryLifecycle
```

实现方式：
- WebSocket 层：收到请求时 `MDC.put("traceId", UUID.randomUUID())`
- Reactor 上下文：通过 `Context.of("traceId", ...)` 传递
- 日志格式：`%d{HH:mm:ss.SSS} [%thread] [traceId=%X{traceId}] %-5level %logger - %msg%n`

### 3.4 基础设施配置

```xml
<!-- intellimate-gateway/pom.xml -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```yaml
# application.yml
management:
  endpoints:
    web:
      exposure:
        include: health, info, prometheus, metrics
  metrics:
    tags:
      application: intellimate
    distribution:
      percentiles-histogram:
        agent.llm.latency: true
        agent.tool.latency: true
```

---

## 阶段依赖与执行顺序

```
Phase 0 (统一异常 + DTO 骨架)
    │
    ├──▶ Phase 1 (拆分 AgentRuntime)
    │       可独立执行，拆分过程中遵循 Phase 0 规范
    │
    ├──▶ Phase 2 (拆分 MessagePipeline + API 规范化)
    │       依赖 Phase 0（使用统一响应格式）
    │       与 Phase 1 无硬依赖，可并行
    │
    └──▶ Phase 3 (可观测性)
            依赖 Phase 1/2 完成后的清晰边界
            Metrics 埋点位置基于拆分后的类
```

推荐执行顺序：Phase 0 → Phase 1 → Phase 2 → Phase 3

Phase 1 和 Phase 2 理论上可并行，但建议串行以减少合并冲突。

---

## 风险与缓解

| 风险 | 缓解措施 |
|------|---------|
| AgentRuntime 拆分引入 bug | 拆分前补充关键路径单元测试；拆分后全量回归 |
| API 响应格式变更影响前端 | 前端同步适配；在 `ApiResponse` 中保持 `data` 结构不变 |
| Metrics 埋点影响性能 | Micrometer 设计为纳秒级开销；先限定关键路径 |
| 拆分后类数增加影响可读性 | 每个类命名清晰、职责单一；包结构不变 |

## 验证标准

| 阶段 | 验证 |
|------|------|
| Phase 0 | 所有 Controller 返回 `ApiResponse`；全局异常处理器生效 |
| Phase 1 | `AgentRuntime` < 150 行；现有测试全部通过；端到端对话功能正常 |
| Phase 2 | `MessagePipeline` < 120 行；OpenAPI 文档可访问；前端功能正常 |
| Phase 3 | `/actuator/prometheus` 返回所有定义的 Metrics；日志包含 traceId |
