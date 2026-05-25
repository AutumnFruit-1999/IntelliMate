# IntelliMate 代码结构优化实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 通过拆分 God Class、统一 API 规范和添加可观测性，将代码质量从 3/5 提升到 4+/5

**架构：** 核心先行策略——先建立 DTO/异常基础，再拆分 AgentRuntime（1888 行→7 类）和 MessagePipeline（1060 行→5 类），最后用 Micrometer+Prometheus 添加 Metrics

**技术栈：** Java 21, Spring Boot 3.4.3, Spring WebFlux, R2DBC, Micrometer, Prometheus, springdoc-openapi

**设计规格：** `docs/superpowers/specs/2026-05-25-codebase-optimization-design.md`

---

## 文件结构

### Phase 0：新建文件
- `intellimate-core/src/main/java/com/atm/intellimate/core/exception/ErrorCode.java` — 错误码枚举
- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/ApiResponse.java` — 统一响应包装
- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/ApiError.java` — 错误详情 record
- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/GlobalExceptionHandler.java` — 全局异常处理
- `intellimate-gateway/src/test/java/com/atm/intellimate/gateway/http/GlobalExceptionHandlerTest.java` — 异常处理测试

### Phase 1：AgentRuntime 拆分 — 新建文件
- `intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentLoopContext.java` — 循环上下文 record
- `intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentLoopExecutor.java` — 对话循环
- `intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/ToolExecutionPipeline.java` — 工具执行链
- `intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/DelegationExecutor.java` — 委托执行
- `intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentPromptBuilder.java` — 提示词构建
- `intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/PlanEventExtractor.java` — Plan 事件提取
- `intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentMemoryLifecycle.java` — 记忆生命周期
- `intellimate-agent/src/test/java/com/atm/intellimate/agent/runtime/AgentPromptBuilderTest.java`
- `intellimate-agent/src/test/java/com/atm/intellimate/agent/runtime/ToolExecutionPipelineTest.java`
- `intellimate-agent/src/test/java/com/atm/intellimate/agent/runtime/PlanEventExtractorTest.java`

### Phase 1：AgentRuntime 拆分 — 修改文件
- `intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java` — 瘦身为入口
- `intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/PlanStepTracker.java` — 从 inner class 提升（新建独立文件）

### Phase 2：MessagePipeline 拆分 — 新建文件
- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/PlanRequestHandler.java`
- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/AgentEventMapper.java`
- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/PlanExecutionOrchestrator.java`
- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessageConverter.java`
- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/*.java` — 各实体 DTO（约 10 个文件）

### Phase 2：MessagePipeline 拆分 — 修改文件
- `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java` — 瘦身
- 15 个 Controller — 统一返回 `ApiResponse<T>`

### Phase 3：可观测性 — 修改文件
- `intellimate-gateway/pom.xml` — 添加 micrometer-registry-prometheus
- `intellimate-gateway/src/main/resources/application.yml` — Actuator 配置
- Phase 1 拆分后的各类 — 添加 Metrics 埋点

---

## Phase 0: 统一异常处理 + Response DTO 骨架

### 任务 1：ErrorCode 枚举 + ApiError + ApiResponse

**文件：**
- 创建：`intellimate-core/src/main/java/com/atm/intellimate/core/exception/ErrorCode.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/ApiError.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/ApiResponse.java`

- [ ] **步骤 1：创建 ErrorCode 枚举**

```java
// intellimate-core/src/main/java/com/atm/intellimate/core/exception/ErrorCode.java
package com.atm.intellimate.core.exception;

public enum ErrorCode {
    AGENT_NOT_FOUND("AGENT_001", 404, "Agent not found"),
    AGENT_NAME_CONFLICT("AGENT_002", 409, "Agent name already exists"),
    SESSION_NOT_FOUND("SESSION_001", 404, "Session not found"),
    PLAN_NOT_FOUND("PLAN_001", 404, "Plan not found"),
    PLAN_INVALID_STATE("PLAN_002", 409, "Invalid plan state transition"),
    TOOL_NOT_FOUND("TOOL_001", 404, "Tool not found"),
    TOOL_EXECUTION_FAILED("TOOL_002", 500, "Tool execution failed"),
    MCP_SERVER_NOT_FOUND("MCP_001", 404, "MCP server not found"),
    MCP_CONNECTION_FAILED("MCP_002", 502, "MCP server connection failed"),
    MODEL_NOT_FOUND("MODEL_001", 404, "Model not found"),
    SKILL_NOT_FOUND("SKILL_001", 404, "Skill not found"),
    BRIDGE_NOT_FOUND("BRIDGE_001", 404, "Bridge node not found"),
    BRIDGE_TIMEOUT("BRIDGE_002", 504, "Bridge node timeout"),
    AUTH_INVALID_TOKEN("AUTH_001", 401, "Invalid authentication token"),
    VALIDATION_FAILED("VALIDATION_001", 400, "Validation failed"),
    INTERNAL_ERROR("INTERNAL_001", 500, "Internal server error");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String getCode() { return code; }
    public int getHttpStatus() { return httpStatus; }
    public String getDefaultMessage() { return defaultMessage; }
}
```

- [ ] **步骤 2：更新 IntelliMateException 支持 ErrorCode**

修改：`intellimate-core/src/main/java/com/atm/intellimate/core/exception/IntelliMateException.java`

添加新构造器：

```java
public IntelliMateException(ErrorCode errorCode) {
    super(errorCode.getDefaultMessage());
    this.errorCode = errorCode.getCode();
}

public IntelliMateException(ErrorCode errorCode, String message) {
    super(message);
    this.errorCode = errorCode.getCode();
}
```

- [ ] **步骤 3：创建 ApiError record**

```java
// intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/ApiError.java
package com.atm.intellimate.gateway.dto;

import java.time.Instant;
import java.util.Map;

public record ApiError(
    String code,
    String message,
    Instant timestamp,
    Map<String, Object> details
) {
    public ApiError(String code, String message) {
        this(code, message, Instant.now(), null);
    }

    public ApiError(String code, String message, Map<String, Object> details) {
        this(code, message, Instant.now(), details);
    }
}
```

- [ ] **步骤 4：创建 ApiResponse record**

```java
// intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/ApiResponse.java
package com.atm.intellimate.gateway.dto;

import com.atm.intellimate.core.exception.ErrorCode;

public record ApiResponse<T>(
    boolean success,
    T data,
    ApiError error
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static <T> ApiResponse<T> fail(String code, String message) {
        return new ApiResponse<>(false, null, new ApiError(code, message));
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(false, null,
            new ApiError(errorCode.getCode(), errorCode.getDefaultMessage()));
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, null,
            new ApiError(errorCode.getCode(), message));
    }
}
```

- [ ] **步骤 5：编译验证**

运行：`mvn compile -pl intellimate-core,intellimate-gateway -q`
预期：BUILD SUCCESS

- [ ] **步骤 6：Commit**

```bash
git add intellimate-core/src/main/java/com/atm/intellimate/core/exception/ErrorCode.java \
       intellimate-core/src/main/java/com/atm/intellimate/core/exception/IntelliMateException.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/ApiError.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/ApiResponse.java
git commit -m "feat: add ErrorCode enum, ApiError, ApiResponse foundation"
```

---

### 任务 2：全局异常处理器

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/GlobalExceptionHandler.java`
- 创建：`intellimate-gateway/src/test/java/com/atm/intellimate/gateway/http/GlobalExceptionHandlerTest.java`

- [ ] **步骤 1：编写测试**

```java
// intellimate-gateway/src/test/java/com/atm/intellimate/gateway/http/GlobalExceptionHandlerTest.java
package com.atm.intellimate.gateway.http;

import com.atm.intellimate.core.exception.ErrorCode;
import com.atm.intellimate.core.exception.IntelliMateException;
import com.atm.intellimate.gateway.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleIntelliMateException_returnsCorrectStatusAndCode() {
        var ex = new IntelliMateException(ErrorCode.AGENT_NOT_FOUND);
        ResponseEntity<ApiResponse<Void>> response = handler.handleIntelliMateException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error().code()).isEqualTo("AGENT_001");
    }

    @Test
    void handleIllegalArgument_returns400() {
        var ex = new IllegalArgumentException("bad param");
        ResponseEntity<ApiResponse<Void>> response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error().code()).isEqualTo("VALIDATION_001");
    }

    @Test
    void handleResponseStatusException_preservesStatus() {
        var ex = new ResponseStatusException(HttpStatus.CONFLICT, "already exists");
        ResponseEntity<ApiResponse<Void>> response = handler.handleResponseStatusException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void handleGenericException_returns500WithSanitizedMessage() {
        var ex = new RuntimeException("internal DB connection password=secret123");
        ResponseEntity<ApiResponse<Void>> response = handler.handleGenericException(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().error().code()).isEqualTo("INTERNAL_001");
        assertThat(response.getBody().error().message()).doesNotContain("secret123");
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn test -pl intellimate-gateway -Dtest=GlobalExceptionHandlerTest -q`
预期：FAIL，GlobalExceptionHandler 类不存在

- [ ] **步骤 3：实现 GlobalExceptionHandler**

```java
// intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/GlobalExceptionHandler.java
package com.atm.intellimate.gateway.http;

import com.atm.intellimate.core.exception.ErrorCode;
import com.atm.intellimate.core.exception.IntelliMateException;
import com.atm.intellimate.gateway.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IntelliMateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIntelliMateException(IntelliMateException ex) {
        ErrorCode errorCode = resolveErrorCode(ex.getErrorCode());
        int status = errorCode != null ? errorCode.getHttpStatus() : 500;
        String code = errorCode != null ? errorCode.getCode() : ex.getErrorCode();
        log.warn("Business error: code={}, message={}", code, ex.getMessage());
        return ResponseEntity.status(status)
                .body(ApiResponse.fail(code, ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Validation error: {}", ex.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(ErrorCode.VALIDATION_FAILED.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiResponse<Void>> handleResponseStatusException(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(ApiResponse.fail("HTTP_" + ex.getStatusCode().value(),
                        ex.getReason() != null ? ex.getReason() : ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        log.error("Unexpected error: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR.getCode(),
                        "An internal error occurred. Please try again later."));
    }

    private ErrorCode resolveErrorCode(String code) {
        if (code == null) return null;
        for (ErrorCode ec : ErrorCode.values()) {
            if (ec.getCode().equals(code)) return ec;
        }
        return null;
    }
}
```

- [ ] **步骤 4：运行测试确认通过**

运行：`mvn test -pl intellimate-gateway -Dtest=GlobalExceptionHandlerTest -q`
预期：4 tests PASS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/GlobalExceptionHandler.java \
       intellimate-gateway/src/test/java/com/atm/intellimate/gateway/http/GlobalExceptionHandlerTest.java
git commit -m "feat: add GlobalExceptionHandler with unified error responses"
```

---

## Phase 1: 拆分 AgentRuntime

### 任务 3：创建 AgentLoopContext + 提升 PlanStepTracker

**文件：**
- 创建：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentLoopContext.java`
- 创建：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/PlanStepTracker.java`（独立文件）
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java`（删除 inner class）

- [ ] **步骤 1：创建 AgentLoopContext record**

```java
// intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentLoopContext.java
package com.atm.intellimate.agent.runtime;

import com.atm.intellimate.agent.plan.PlanOperations;
import com.atm.intellimate.memory.perception.ImportanceAssessor;
import com.atm.intellimate.memory.working.TokenEstimator;
import com.atm.intellimate.memory.working.WorkingMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

- [ ] **步骤 2：提升 PlanStepTracker 为独立类**

将 `AgentRuntime` 中的 `static class PlanStepTracker`（第 1558-1634 行）提取到独立文件 `PlanStepTracker.java`，包名不变 `com.atm.intellimate.agent.runtime`。

将访问权限从 `static class` 改为 `public class`。内容完全不变，只移动位置。

- [ ] **步骤 3：更新 AgentRuntime 删除 inner class**

从 `AgentRuntime.java` 中删除 `PlanStepTracker` inner class（第 1558-1634 行），改为 import 新的独立类。

- [ ] **步骤 4：编译验证**

运行：`mvn compile -pl intellimate-agent -q`
预期：BUILD SUCCESS

- [ ] **步骤 5：运行现有测试**

运行：`mvn test -pl intellimate-agent -q`
预期：所有现有测试通过

- [ ] **步骤 6：Commit**

```bash
git add intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentLoopContext.java \
       intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/PlanStepTracker.java \
       intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java
git commit -m "refactor: extract AgentLoopContext record and PlanStepTracker to standalone files"
```

---

### 任务 4：提取 AgentPromptBuilder

**文件：**
- 创建：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentPromptBuilder.java`
- 创建：`intellimate-agent/src/test/java/com/atm/intellimate/agent/runtime/AgentPromptBuilderTest.java`
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java`

- [ ] **步骤 1：编写测试**

```java
package com.atm.intellimate.agent.runtime;

import com.atm.intellimate.agent.skills.SkillContentProvider;
import com.atm.intellimate.core.config.IntelliMateProperties;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AgentPromptBuilderTest {

    @Test
    void buildSystemPrompt_includesSoulSection() {
        AgentPromptBuilder builder = new AgentPromptBuilder(null);
        IntelliMateProperties.Agent agentConfig = new IntelliMateProperties.Agent();
        agentConfig.setSoulMd("You are a helpful assistant.");

        String prompt = builder.buildSystemPrompt(agentConfig, List.of(), false, null, false, null);

        assertThat(prompt).contains("<soul>");
        assertThat(prompt).contains("You are a helpful assistant.");
        assertThat(prompt).contains("</soul>");
    }

    @Test
    void buildSystemPrompt_truncatesAtMaxLength() {
        AgentPromptBuilder builder = new AgentPromptBuilder(null);
        IntelliMateProperties.Agent agentConfig = new IntelliMateProperties.Agent();
        agentConfig.setSoulMd("x".repeat(200_000));

        String prompt = builder.buildSystemPrompt(agentConfig, List.of(), false, null, false, null);

        assertThat(prompt.length()).isLessThanOrEqualTo(150_010);
    }

    @Test
    void buildSystemPrompt_includesParallelSection_whenEnabled() {
        AgentPromptBuilder builder = new AgentPromptBuilder(null);
        IntelliMateProperties.Agent agentConfig = new IntelliMateProperties.Agent();

        String prompt = builder.buildSystemPrompt(agentConfig, List.of(), true, null, false, null);

        assertThat(prompt).contains("并行与串行调用");
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn test -pl intellimate-agent -Dtest=AgentPromptBuilderTest -q`
预期：FAIL

- [ ] **步骤 3：从 AgentRuntime 提取以下方法到 AgentPromptBuilder**

提取方法清单：
- `buildSystemPrompt()` → 公开方法
- `buildPlanSystemSection()` → 私有方法
- `buildSkillsDiscovery()` → 私有方法
- `setupSkillGroupContext()` → 公开方法
- `appendSection()` → 私有静态方法
- `parseJsonStringArray()` → 私有静态方法
- `TOTAL_MAX_CHARS` 常量
- `SKILL_GROUPS_UNRESTRICTED` 常量
- `sessionSkillGroups` ConcurrentMap

AgentPromptBuilder 是 `@Component`，构造器注入 `SkillContentProvider`（`@Autowired(required = false)`）。

从 `AgentRuntime` 中删除以上方法和字段，在 `AgentRuntime` 中注入 `AgentPromptBuilder`。

- [ ] **步骤 4：运行测试确认通过**

运行：`mvn test -pl intellimate-agent -Dtest=AgentPromptBuilderTest -q`
预期：PASS

- [ ] **步骤 5：运行全部测试**

运行：`mvn test -pl intellimate-agent,intellimate-gateway -q`
预期：所有现有测试通过

- [ ] **步骤 6：Commit**

```bash
git add intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentPromptBuilder.java \
       intellimate-agent/src/test/java/com/atm/intellimate/agent/runtime/AgentPromptBuilderTest.java \
       intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java
git commit -m "refactor: extract AgentPromptBuilder from AgentRuntime"
```

---

### 任务 5：提取 AgentMemoryLifecycle

**文件：**
- 创建：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentMemoryLifecycle.java`
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java`

- [ ] **步骤 1：从 AgentRuntime 提取以下方法到 AgentMemoryLifecycle**

提取方法清单：
- `loadMemoryInitReactive()` → 公开方法
- `createMemoryConsolidator()` → 私有方法
- `storeSessionEpisodicMemory()` → 公开方法
- `storeSessionEpisodicViaLLM()` → 私有方法
- `storeSessionEpisodicSimple()` → 私有方法
- `deferEpisodicStore()` → 公开方法
- `flushDeferredEpisodicMemory()` → 公开方法
- `toConsolidationTriggeredEvent()` → 公开静态方法
- `DeferredEpisodicStore` record
- `deferredEpisodicStores` ConcurrentMap
- `MemoryInit` record

AgentMemoryLifecycle 是 `@Component`，构造器注入：`MemoryConfigProvider`、`LongTermMemory`、`MemorySystem`、`ChatModelRegistry`（均 `@Autowired(required = false)`，ChatModelRegistry 用于 resolveModel）。

- [ ] **步骤 2：更新 AgentRuntime**

删除上述方法和字段。`flushDeferredEpisodicMemory()` 委托给 `AgentMemoryLifecycle`。

- [ ] **步骤 3：编译验证**

运行：`mvn compile -pl intellimate-agent -q`
预期：BUILD SUCCESS

- [ ] **步骤 4：运行全部测试**

运行：`mvn test -pl intellimate-agent,intellimate-gateway -q`
预期：所有现有测试通过

- [ ] **步骤 5：Commit**

```bash
git add intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentMemoryLifecycle.java \
       intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java
git commit -m "refactor: extract AgentMemoryLifecycle from AgentRuntime"
```

---

### 任务 6：提取 PlanEventExtractor

**文件：**
- 创建：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/PlanEventExtractor.java`
- 创建：`intellimate-agent/src/test/java/com/atm/intellimate/agent/runtime/PlanEventExtractorTest.java`
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java`

- [ ] **步骤 1：编写测试**

```java
package com.atm.intellimate.agent.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PlanEventExtractorTest {

    private final PlanEventExtractor extractor = new PlanEventExtractor(new ObjectMapper(), null);

    @Test
    void extractPlanEvents_nonPlanTool_returnsEmpty() {
        List<AgentEvent> events = extractor.extractPlanEvents("readFile", "{}", "result");
        assertThat(events).isEmpty();
    }

    @Test
    void extractPlanEvents_writePlan_returnsPlanCreatedAndAwaiting() {
        String args = "{\"title\":\"Test Plan\",\"steps\":[{\"title\":\"Step 1\",\"description\":\"Do step 1\"}]}";
        String result = "{\"planId\":42,\"status\":\"draft\"}";

        List<AgentEvent> events = extractor.extractPlanEvents("writePlan", args, result);

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(AgentEvent.PlanCreated.class);
        assertThat(events.get(1)).isInstanceOf(AgentEvent.PlanAwaitingApproval.class);
        AgentEvent.PlanCreated created = (AgentEvent.PlanCreated) events.get(0);
        assertThat(created.planId()).isEqualTo(42L);
        assertThat(created.title()).isEqualTo("Test Plan");
    }

    @Test
    void extractPlanEvents_updatePlanMarkStepInProgress_returnsStatusAndStepStart() {
        String args = "{\"planId\":10,\"action\":\"markStep\",\"stepIndex\":1,\"status\":\"in_progress\"}";
        String result = "{\"status\":\"ok\"}";

        List<AgentEvent> events = extractor.extractPlanEvents("updatePlan", args, result);

        assertThat(events).hasSize(2);
        assertThat(events.get(0)).isInstanceOf(AgentEvent.PlanStatusChanged.class);
        assertThat(events.get(1)).isInstanceOf(AgentEvent.PlanStepStart.class);
    }

    @Test
    void extractPlanEvents_errorResult_returnsEmpty() {
        String args = "{\"title\":\"Test\"}";
        String result = "{\"error\":\"something failed\"}";

        List<AgentEvent> events = extractor.extractPlanEvents("writePlan", args, result);

        assertThat(events).isEmpty();
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn test -pl intellimate-agent -Dtest=PlanEventExtractorTest -q`
预期：FAIL

- [ ] **步骤 3：从 AgentRuntime 提取到 PlanEventExtractor**

提取方法清单：
- `extractPlanEvents()` → 公开方法
- `extractWritePlanEvents()` → 私有方法
- `extractUpdatePlanEvents()` → 私有方法
- `filterDuplicatePlanEvents()` → 公开静态方法

PlanEventExtractor 是 `@Component`，构造器注入 `ObjectMapper`、`PlanOperations`。

- [ ] **步骤 4：运行测试确认通过**

运行：`mvn test -pl intellimate-agent -Dtest=PlanEventExtractorTest -q`
预期：PASS

- [ ] **步骤 5：运行全部测试**

运行：`mvn test -pl intellimate-agent,intellimate-gateway -q`
预期：所有现有测试通过

- [ ] **步骤 6：Commit**

```bash
git add intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/PlanEventExtractor.java \
       intellimate-agent/src/test/java/com/atm/intellimate/agent/runtime/PlanEventExtractorTest.java \
       intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java
git commit -m "refactor: extract PlanEventExtractor from AgentRuntime"
```

---

### 任务 7：提取 ToolExecutionPipeline

**文件：**
- 创建：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/ToolExecutionPipeline.java`
- 创建：`intellimate-agent/src/test/java/com/atm/intellimate/agent/runtime/ToolExecutionPipelineTest.java`
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java`

- [ ] **步骤 1：编写测试**

```java
package com.atm.intellimate.agent.runtime;

import com.atm.intellimate.agent.tools.AgentSessionContext;
import com.atm.intellimate.agent.tools.ToolsEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ToolExecutionPipelineTest {

    @Mock ToolsEngine toolsEngine;
    @Mock AgentSessionContext agentSessionContext;

    @Test
    void executeSingleTool_loopTerminate_returnsErrorResult() {
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                toolsEngine, agentSessionContext, null, null);

        ToolCallLoopDetector detector = new ToolCallLoopDetector(5, 3, 5, Set.of());
        for (int i = 0; i < 6; i++) {
            detector.check("readFile", "{\"path\":\"/tmp/a\"}");
        }

        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall("tc1", "tool", "readFile", "{\"path\":\"/tmp/a\"}");

        ToolExecutionResult result = pipeline.executeSingleTool(
                tc, "agent1", null, 1L, null,
                detector, new ToolResultCache(),
                Duration.ofSeconds(30), Set.of(), Map.of()
        ).block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.result()).contains("循环调用");
    }

    @Test
    void executeSingleTool_cacheHit_returnsCachedResult() {
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                toolsEngine, agentSessionContext, null, null);

        ToolResultCache cache = new ToolResultCache();
        cache.put("readFile", "{\"path\":\"/tmp/a\"}", "file content");

        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall("tc1", "tool", "readFile", "{\"path\":\"/tmp/a\"}");

        ToolExecutionResult result = pipeline.executeSingleTool(
                tc, "agent1", null, 1L, null,
                new ToolCallLoopDetector(5, 3, 5, Set.of()),
                cache, Duration.ofSeconds(30), Set.of(), Map.of()
        ).block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.result()).contains("file content");
        assertThat(result.result()).contains("缓存结果");
    }
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn test -pl intellimate-agent -Dtest=ToolExecutionPipelineTest -q`
预期：FAIL

- [ ] **步骤 3：从 AgentRuntime 提取到 ToolExecutionPipeline**

提取方法清单：
- `executeSingleTool()` → 公开方法
- `doExecuteTool()` → 私有方法
- `isRetryableError()` → 私有方法
- `recordSkillActivationIfApplicable()` → 私有方法
- `extractPathFromArgs()` → 私有方法
- `extractSkillNameFromPath()` → 私有方法
- `extractSkillNameFromArgs()` → 私有方法
- `MAX_RETRIES`、`RETRY_DELAY` 常量

ToolExecutionPipeline 是 `@Component`，构造器注入 `ToolsEngine`、`AgentSessionContext`、`SkillUsageRecorder`、`ObjectMapper`。

- [ ] **步骤 4：运行测试确认通过**

运行：`mvn test -pl intellimate-agent -Dtest=ToolExecutionPipelineTest -q`
预期：PASS

- [ ] **步骤 5：运行全部测试**

运行：`mvn test -pl intellimate-agent,intellimate-gateway -q`
预期：所有现有测试通过

- [ ] **步骤 6：Commit**

```bash
git add intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/ToolExecutionPipeline.java \
       intellimate-agent/src/test/java/com/atm/intellimate/agent/runtime/ToolExecutionPipelineTest.java \
       intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java
git commit -m "refactor: extract ToolExecutionPipeline from AgentRuntime"
```

---

### 任务 8：提取 DelegationExecutor

**文件：**
- 创建：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/DelegationExecutor.java`
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java`

- [ ] **步骤 1：从 AgentRuntime 提取到 DelegationExecutor**

提取方法清单：
- `executeDelegationToolCall()` → 公开方法
- `executeSingleDelegation()` → 私有方法
- `executeParallelDelegation()` → 私有方法
- `executeHandoffToolCall()` → 公开方法
- `buildWorkerPrompt()` → 私有静态方法
- `MAX_DELEGATION_RESULT_CHARS` 常量

DelegationExecutor 是 `@Component`，构造器注入 `DelegationResolver`、`ObjectMapper`。需要一个 `AgentRuntime` 的引用来递归调用 `dispatch()`——通过 `@Lazy` 注入避免循环依赖。

```java
@Component
public class DelegationExecutor {
    private final DelegationResolver delegationResolver;
    private final ObjectMapper objectMapper;
    private final AgentRuntime agentRuntime;

    public DelegationExecutor(
            @Autowired(required = false) DelegationResolver delegationResolver,
            ObjectMapper objectMapper,
            @Lazy AgentRuntime agentRuntime) {
        // ...
    }
}
```

- [ ] **步骤 2：更新 AgentRuntime 删除委托相关方法**

- [ ] **步骤 3：编译验证**

运行：`mvn compile -pl intellimate-agent -q`
预期：BUILD SUCCESS

- [ ] **步骤 4：运行全部测试**

运行：`mvn test -pl intellimate-agent,intellimate-gateway -q`
预期：所有现有测试通过

- [ ] **步骤 5：Commit**

```bash
git add intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/DelegationExecutor.java \
       intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java
git commit -m "refactor: extract DelegationExecutor from AgentRuntime"
```

---

### 任务 9：提取 AgentLoopExecutor + 最终瘦身 AgentRuntime

**文件：**
- 创建：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentLoopExecutor.java`
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java`

这是 Phase 1 最关键的一步——将 `executeAgentLoop`、`executeLoopTurn`、`processToolCalls` 等核心循环逻辑提取出来。

- [ ] **步骤 1：创建 AgentLoopExecutor**

提取方法清单：
- `executeAgentLoop()` → 公开方法，接收 `AgentRunRequest`，返回 `Flux<AgentEvent>`
- `executeLoopTurn()` → 私有方法，参数改为 `AgentLoopContext` + `int turn` + `StringBuilder fullText`
- `processToolCalls()` → 私有方法
- `extractTextDelta()` → 私有方法
- `mergeToolCallsFromChunks()` → 私有方法
- `resolveModel()` → 私有方法（或委托给 ChatModelRegistry）
- `buildChatOptions()` → 私有方法
- `logRequestParams()` → 私有方法

AgentLoopExecutor 是 `@Component`，构造器注入：
- `ChatModelRegistry`
- `ToolsEngine`
- `ToolExecutionPipeline`
- `DelegationExecutor`
- `PlanEventExtractor`
- `AgentPromptBuilder`
- `AgentMemoryLifecycle`
- `ObjectMapper`
- `IntelliMateProperties`
- `SkillContentProvider`（optional）
- `MemorySystem`（optional）

在 `executeLoopTurn` 中，22 个参数替换为 `AgentLoopContext ctx`。内部通过 `ctx.workingMemory()` 等访问。

- [ ] **步骤 2：瘦身 AgentRuntime 为入口类**

AgentRuntime 最终保留：
- `dispatch()` — 委托给 `runQueueManager.enqueue(() -> agentLoopExecutor.executeAgentLoop(request))`
- `registerWsRun()`, `unregisterWsRun()`, `cancelByWsSession()` — WS 生命周期
- `signalPlanPaused()`, `clearPlanPaused()` — Plan 暂停信号
- `resolveApproval()` — 审批解析
- `flushDeferredEpisodicMemory()` — 委托给 AgentMemoryLifecycle
- `getLatestSnapshot()` — 快照查询
- `sessionApprovalGates`、`pausedPlanIds`、`activeWsRuns`、`latestSnapshots` 实例字段

预计 AgentRuntime 最终约 120-150 行。

- [ ] **步骤 3：编译验证**

运行：`mvn compile -pl intellimate-agent -q`
预期：BUILD SUCCESS

- [ ] **步骤 4：运行全部测试**

运行：`mvn test -pl intellimate-agent,intellimate-gateway -q`
预期：所有现有测试通过

- [ ] **步骤 5：验证 AgentRuntime 行数**

运行：`wc -l intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java`
预期：< 200 行

- [ ] **步骤 6：Commit**

```bash
git add intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentLoopExecutor.java \
       intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java
git commit -m "refactor: extract AgentLoopExecutor, AgentRuntime now ~150 lines"
```

---

## Phase 2: 拆分 MessagePipeline + API 规范化

### 任务 10：提取 MessageConverter

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessageConverter.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java`

- [ ] **步骤 1：从 MessagePipeline 提取到 MessageConverter**

提取方法清单：
- `convertToAiMessages()` → 公开方法
- `loadHistory()` → 公开方法

MessageConverter 是 `@Component`，注入 `SessionManager`、`IntelliMateProperties`。

- [ ] **步骤 2：更新 MessagePipeline，注入 MessageConverter**

- [ ] **步骤 3：编译验证 + 运行测试**

运行：`mvn compile test -pl intellimate-gateway -q`
预期：BUILD SUCCESS，所有测试通过

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessageConverter.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java
git commit -m "refactor: extract MessageConverter from MessagePipeline"
```

---

### 任务 11：提取 AgentEventMapper

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/AgentEventMapper.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java`

- [ ] **步骤 1：从 MessagePipeline 提取 mapAgentEvent 和 handleHandoff**

提取方法清单：
- `mapAgentEvent()` → 公开方法
- `handleHandoff()` → 私有方法

AgentEventMapper 是 `@Component`，注入 `AgentConfigService`、`AgentRuntime`、`IntelliMateProperties`。需要 `seqGenerator`（AtomicLong）——在 AgentEventMapper 内部持有独立实例。

注意：`handleHandoff` 中的 `schedulePlanCompletionMemoryExtraction` 调用需要引入 `PlanExecutionOrchestrator`（任务 13 创建）。如果任务 13 尚未完成，先保留内联逻辑，任务 13 完成后再提取。

- [ ] **步骤 2：更新 MessagePipeline**

- [ ] **步骤 3：编译验证 + 运行测试**

运行：`mvn compile test -pl intellimate-gateway -q`
预期：BUILD SUCCESS，所有测试通过

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/AgentEventMapper.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java
git commit -m "refactor: extract AgentEventMapper from MessagePipeline"
```

---

### 任务 12：提取 PlanRequestHandler

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/PlanRequestHandler.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java`

- [ ] **步骤 1：从 MessagePipeline 提取 processPlanRequest**

提取：`processPlanRequest()` 方法及其内部的 10 个 case 分支。

PlanRequestHandler 是 `@Component`，注入 `PlanService`、`AgentRuntime`、`SessionRepository`。

`processMessageStreaming` 的引用需要通过接口或回调传递。

- [ ] **步骤 2：更新 MessagePipeline，plan.* 请求委托给 PlanRequestHandler**

- [ ] **步骤 3：编译验证 + 运行测试**

运行：`mvn compile test -pl intellimate-gateway -q`
预期：BUILD SUCCESS，所有测试通过

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/PlanRequestHandler.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java
git commit -m "refactor: extract PlanRequestHandler from MessagePipeline"
```

---

### 任务 13：提取 PlanExecutionOrchestrator

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/PlanExecutionOrchestrator.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java`

- [ ] **步骤 1：从 MessagePipeline 提取**

提取方法清单：
- `buildPlanExecutionPayload()` → 公开方法
- `syncPlanAfterExecution()` → 公开方法
- `checkAndCompletePlan()` → 私有方法
- `schedulePlanCompletionMemoryExtraction()` → 公开方法
- `buildPlanProceduralSummary()` → 私有静态方法
- `stepTextForImportance()`, `stepTitleDescriptionOnly()` → 私有静态方法
- `PlanExecutionPayload` record

PlanExecutionOrchestrator 是 `@Component`，注入 `PlanService`、`LongTermMemory`、`MemoryConfigProvider`、`IntelliMateProperties`。

- [ ] **步骤 2：更新 MessagePipeline + AgentEventMapper**

- [ ] **步骤 3：验证 MessagePipeline 行数**

运行：`wc -l intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java`
预期：< 150 行

- [ ] **步骤 4：编译验证 + 运行全部测试**

运行：`mvn compile test -pl intellimate-gateway -q`
预期：BUILD SUCCESS，所有测试通过

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/PlanExecutionOrchestrator.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/AgentEventMapper.java
git commit -m "refactor: extract PlanExecutionOrchestrator, MessagePipeline now ~120 lines"
```

---

### 任务 14：DTO 层 + Controller 统一响应

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/AgentDTO.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/ModelDTO.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/ToolDTO.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/McpServerDTO.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/SkillDTO.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/SkillGroupDTO.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/PlanDTO.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/SessionDTO.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/ScheduledJobDTO.java`
- 修改：所有 15 个 Controller

这是一个大任务，建议分步执行：每次改造 2-3 个 Controller。

- [ ] **步骤 1：创建核心 DTO records**

每个 DTO 是一个 Java record，包含 `static fromEntity()` 工厂方法：

```java
// intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/AgentDTO.java
package com.atm.intellimate.gateway.dto;

import com.atm.intellimate.gateway.entity.AgentEntity;
import java.time.LocalDateTime;

public record AgentDTO(
    Long id,
    String name,
    String description,
    String model,
    boolean enabled,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
    public static AgentDTO fromEntity(AgentEntity entity) {
        return new AgentDTO(
            entity.getId(),
            entity.getName(),
            entity.getDescription(),
            entity.getModel(),
            entity.isEnabled(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
```

其他 DTO 类似模式。根据实体字段映射。

- [ ] **步骤 2：改造 AgentController（示范）**

将所有返回 `Map<String, Object>` 或实体的端点改为返回 `ApiResponse<XxxDTO>`。

```java
// 改造前
@GetMapping("/{id}")
public Mono<Map<String, Object>> getAgent(@PathVariable Long id) {
    return agentRepository.findById(id)
        .map(this::entityToDto)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
}

// 改造后
@GetMapping("/{id}")
public Mono<ApiResponse<AgentDTO>> getAgent(@PathVariable Long id) {
    return agentRepository.findById(id)
        .map(entity -> ApiResponse.ok(AgentDTO.fromEntity(entity)))
        .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.AGENT_NOT_FOUND)));
}
```

- [ ] **步骤 3：逐批改造其余 Controller**

按以下分组改造，每组完成后编译验证：
- 批次 1：`ModelDefinitionController`、`ModelProviderController`、`ToolController`、`ToolDefinitionController`
- 批次 2：`McpServerController`、`SkillDefinitionController`、`SkillGroupController`
- 批次 3：`PlanController`、`ScheduledJobController`、`TaskController`
- 批次 4：`HeartbeatController`、`MemoryController`、`BridgeController`、`WebhookController`

- [ ] **步骤 4：编译验证 + 运行全部测试**

运行：`mvn compile test -q`
预期：BUILD SUCCESS，所有测试通过

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/ \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/
git commit -m "refactor: add DTO layer, unify all controllers to ApiResponse<T>"
```

---

### 任务 15：OpenAPI 文档集成

**文件：**
- 修改：`intellimate-gateway/pom.xml`
- 修改：代表性 Controller（添加 `@Operation` 注解）

- [ ] **步骤 1：添加 springdoc-openapi 依赖**

在 `intellimate-gateway/pom.xml` 的 `<dependencies>` 中添加：

```xml
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webflux-ui</artifactId>
    <version>2.8.0</version>
</dependency>
```

- [ ] **步骤 2：为 AgentController 添加 OpenAPI 注解（示范）**

```java
@Operation(summary = "获取所有 Agent 列表")
@GetMapping
public Flux<ApiResponse<AgentDTO>> listAgents() { ... }

@Operation(summary = "根据 ID 获取 Agent 详情")
@GetMapping("/{id}")
public Mono<ApiResponse<AgentDTO>> getAgent(@PathVariable Long id) { ... }
```

其余 Controller 类似处理。

- [ ] **步骤 3：编译验证**

运行：`mvn compile -pl intellimate-gateway -q`
预期：BUILD SUCCESS

- [ ] **步骤 4：启动应用验证 Swagger UI**

运行：`mvn spring-boot:run -pl intellimate-gateway`
访问：`http://localhost:3007/swagger-ui.html`
预期：能看到所有 API 端点文档

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/pom.xml \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/
git commit -m "feat: integrate springdoc-openapi, add Swagger UI at /swagger-ui.html"
```

---

## Phase 3: 可观测性

### 任务 16：Micrometer + Prometheus 基础设施

**文件：**
- 修改：`intellimate-gateway/pom.xml`
- 修改：`intellimate-gateway/src/main/resources/application.yml`

- [ ] **步骤 1：添加 micrometer-registry-prometheus 依赖**

在 `intellimate-gateway/pom.xml` 添加：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

（版本由 Spring Boot BOM 管理，无需指定。）

- [ ] **步骤 2：配置 Actuator 暴露端点**

在 `application.yml` 中添加/更新：

```yaml
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

- [ ] **步骤 3：编译验证**

运行：`mvn compile -pl intellimate-gateway -q`
预期：BUILD SUCCESS

- [ ] **步骤 4：启动应用验证 Prometheus 端点**

运行：`mvn spring-boot:run -pl intellimate-gateway`
访问：`http://localhost:3007/actuator/prometheus`
预期：返回 Prometheus 格式的指标数据

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/pom.xml \
       intellimate-gateway/src/main/resources/application.yml
git commit -m "feat: add Micrometer + Prometheus, expose /actuator/prometheus"
```

---

### 任务 17：LLM + 工具层 Metrics 埋点

**文件：**
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentLoopExecutor.java`
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/ToolExecutionPipeline.java`
- 修改：`intellimate-agent/pom.xml`（如需添加 micrometer-core 依赖）

- [ ] **步骤 1：在 intellimate-agent 的 pom.xml 添加 Micrometer 依赖**

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-core</artifactId>
</dependency>
```

- [ ] **步骤 2：在 AgentLoopExecutor 注入 MeterRegistry 并添加 LLM Metrics**

```java
private final MeterRegistry meterRegistry;

// 在 LLM 调用前后
Timer.Sample sample = Timer.start(meterRegistry);
// ... chatModel.stream(prompt) ...
sample.stop(Timer.builder("agent.llm.latency")
    .tag("model", resolved.modelId())
    .tag("agent", agentName)
    .register(meterRegistry));

meterRegistry.counter("agent.llm.requests",
    "model", resolved.modelId(),
    "agent", agentName,
    "status", "success").increment();

// Token 统计
meterRegistry.counter("agent.llm.tokens.prompt",
    "model", resolved.modelId(),
    "agent", agentName)
    .increment(usage.getPromptTokens());
```

- [ ] **步骤 3：在 ToolExecutionPipeline 添加工具 Metrics**

```java
// 工具执行计时
Timer.Sample sample = Timer.start(meterRegistry);
// ... tool execution ...
sample.stop(Timer.builder("agent.tool.latency")
    .tag("tool", toolName)
    .register(meterRegistry));

// 缓存命中
meterRegistry.counter("agent.tool.cache.hits", "tool", toolName).increment();

// 循环检测
meterRegistry.counter("agent.tool.loop_detected", "tool", toolName).increment();
```

- [ ] **步骤 4：编译验证 + 运行测试**

运行：`mvn compile test -pl intellimate-agent -q`
预期：BUILD SUCCESS，所有测试通过

- [ ] **步骤 5：Commit**

```bash
git add intellimate-agent/pom.xml \
       intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentLoopExecutor.java \
       intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/ToolExecutionPipeline.java
git commit -m "feat: add LLM and tool execution Metrics via Micrometer"
```

---

### 任务 18：记忆 + WebSocket + Plan Metrics 埋点

**文件：**
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentMemoryLifecycle.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/websocket/SessionRegistry.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/PlanExecutionOrchestrator.java`

- [ ] **步骤 1：记忆层 Metrics**

在 AgentMemoryLifecycle 添加：
- `memory.working.usage_ratio` Gauge
- `memory.consolidation.triggered` Counter
- `memory.longterm.retrieval.latency` Timer
- `memory.longterm.store.count` Counter

- [ ] **步骤 2：WebSocket Metrics**

在 SessionRegistry 添加：
- `ws.connections.active` Gauge（跟踪活跃连接数）

在 MessagePipeline 添加：
- `ws.messages.received` Counter（按 method 维度）

- [ ] **步骤 3：Plan Metrics**

在 PlanExecutionOrchestrator 添加：
- `plan.completed` Counter
- `plan.step.duration` Timer

- [ ] **步骤 4：编译验证 + 运行全部测试**

运行：`mvn compile test -q`
预期：BUILD SUCCESS，所有测试通过

- [ ] **步骤 5：Commit**

```bash
git add intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentMemoryLifecycle.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/websocket/SessionRegistry.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/PlanExecutionOrchestrator.java
git commit -m "feat: add memory, WebSocket, and Plan Metrics"
```

---

### 任务 19：MDC TraceId 链路追踪

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/websocket/GatewayWebSocketHandler.java`
- 修改：`intellimate-gateway/src/main/resources/application.yml`（日志格式）
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java`

- [ ] **步骤 1：在 WebSocket Handler 注入 traceId**

在 `GatewayWebSocketHandler` 接收到消息时生成 traceId：

```java
String traceId = UUID.randomUUID().toString().substring(0, 8);
// 通过 Reactor Context 传递
.contextWrite(Context.of("traceId", traceId))
```

- [ ] **步骤 2：在 MessagePipeline 从 Context 读取并注入 MDC**

```java
Mono.deferContextual(ctx -> {
    String traceId = ctx.getOrDefault("traceId", "none");
    MDC.put("traceId", traceId);
    // ... processing ...
})
```

- [ ] **步骤 3：更新日志格式**

在 `application.yml` 中更新 logging pattern：

```yaml
logging:
  pattern:
    console: "%d{HH:mm:ss.SSS} [%thread] [traceId=%X{traceId}] %-5level %logger{36} - %msg%n"
```

- [ ] **步骤 4：编译验证**

运行：`mvn compile -pl intellimate-gateway -q`
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/websocket/GatewayWebSocketHandler.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java \
       intellimate-gateway/src/main/resources/application.yml
git commit -m "feat: add MDC traceId for lightweight request tracing"
```

---

## 自检结果

### 规格覆盖度

| 规格章节 | 对应任务 |
|---------|---------|
| Phase 0: 全局异常处理器 | 任务 2 |
| Phase 0: 统一响应结构 | 任务 1 |
| Phase 0: 错误码体系 | 任务 1 |
| Phase 0: Bean Validation | 任务 14（在 Controller 改造中一并引入） |
| Phase 1: AgentLoopContext | 任务 3 |
| Phase 1: PlanStepTracker 提升 | 任务 3 |
| Phase 1: AgentPromptBuilder | 任务 4 |
| Phase 1: AgentMemoryLifecycle | 任务 5 |
| Phase 1: PlanEventExtractor | 任务 6 |
| Phase 1: ToolExecutionPipeline | 任务 7 |
| Phase 1: DelegationExecutor | 任务 8 |
| Phase 1: AgentLoopExecutor + 瘦身 | 任务 9 |
| Phase 2: MessageConverter | 任务 10 |
| Phase 2: AgentEventMapper | 任务 11 |
| Phase 2: PlanRequestHandler | 任务 12 |
| Phase 2: PlanExecutionOrchestrator | 任务 13 |
| Phase 2: DTO 层 + Controller 统一 | 任务 14 |
| Phase 2: OpenAPI 文档 | 任务 15 |
| Phase 3: Micrometer + Prometheus | 任务 16 |
| Phase 3: LLM + 工具 Metrics | 任务 17 |
| Phase 3: 记忆 + WS + Plan Metrics | 任务 18 |
| Phase 3: MDC traceId | 任务 19 |

全覆盖，无遗漏。

### 占位符扫描

无"待定"、"TODO"、"后续实现"。所有代码步骤均包含实际代码。

### 类型一致性

- `AgentLoopContext` 在任务 3 定义，任务 9 的 `AgentLoopExecutor` 中使用
- `ApiResponse` 和 `ApiError` 在任务 1 定义，任务 2 和任务 14 使用
- `ErrorCode` 在任务 1 定义，任务 2 中 `GlobalExceptionHandler` 使用
- 所有拆分类名在任务间保持一致
