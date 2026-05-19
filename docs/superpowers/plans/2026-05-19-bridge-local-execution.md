# Bridge 本地执行节点实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 通过 WebSocket Bridge 架构，让服务器部署的 Agent 能透明地在用户本地机器上执行文件操作、命令和 MCP 工具调用。

**架构：** 本地组件（npm 包 `intellimate-local`）作为 WebSocket 客户端主动连接到 gateway 的 `/api/bridge/connect` 端点。gateway 新增 `BridgeToolProvider` SPI（agent 模块）和实现（gateway 模块），在 `ToolsEngine` 中将匹配的内置工具回调替换为 Bridge 转发版本。Agent 完全透明——工具名不变，仅执行位置不同。

**技术栈：** Java 21 / Spring WebFlux / R2DBC (gateway) + TypeScript / Node.js / ws (local)

**规格文档：** `docs/superpowers/specs/2026-05-19-bridge-local-execution-design.md`

---

## 文件结构

### Gateway 新增文件

| 文件 | 职责 |
|------|------|
| `intellimate-gateway/src/main/resources/db/migration/V26__bridge_node.sql` | 新表 `bridge_node` + `agent.bridge_node` 字段 |
| `intellimate-agent/src/main/java/.../agent/tools/bridge/BridgeToolProvider.java` | SPI 接口：桥接工具提供者 |
| `intellimate-gateway/src/main/java/.../gateway/bridge/BridgeProtocol.java` | WebSocket JSON 消息协议定义 |
| `intellimate-gateway/src/main/java/.../gateway/bridge/BridgeNodeSession.java` | 单个 Bridge 节点的 WebSocket 会话封装 |
| `intellimate-gateway/src/main/java/.../gateway/bridge/BridgeNodeRegistry.java` | 已连接节点的内存注册表 |
| `intellimate-gateway/src/main/java/.../gateway/bridge/BridgeWebSocketHandler.java` | `/api/bridge/connect` WebSocket 端点处理器 |
| `intellimate-gateway/src/main/java/.../gateway/bridge/BridgeToolCallback.java` | 通过 Bridge 转发工具调用的 ToolCallback 实现 |
| `intellimate-gateway/src/main/java/.../gateway/bridge/BridgeToolProviderImpl.java` | BridgeToolProvider SPI 的 gateway 实现 |
| `intellimate-gateway/src/main/java/.../gateway/bridge/BridgeController.java` | Bridge 节点管理 REST API |
| `intellimate-gateway/src/main/java/.../gateway/entity/BridgeNodeEntity.java` | bridge_node 表的 R2DBC 实体 |
| `intellimate-gateway/src/main/java/.../gateway/repository/BridgeNodeRepository.java` | bridge_node 的 R2DBC 仓库 |

### Gateway 修改文件

| 文件 | 修改内容 |
|------|----------|
| `intellimate-agent/.../agent/tools/ToolsEngine.java` | 新增 `getToolCallbacksFor(spec, mcpSpec, bridgeNode)` 三参数方法 |
| `intellimate-agent/.../agent/runtime/AgentRunRequest.java` | 新增 `bridgeNode` 字段（第 15 个参数） |
| `intellimate-agent/.../agent/runtime/AgentRuntime.java:194` | 调用新的三参数 `getToolCallbacksFor` |
| `intellimate-gateway/.../gateway/config/ResolvedAgentConfig.java` | 新增 `bridgeNode` 字段 |
| `intellimate-gateway/.../gateway/config/AgentConfigService.java` | 解析 `entity.getBridgeNode()` |
| `intellimate-gateway/.../gateway/entity/AgentEntity.java` | 新增 `bridgeNode` 属性 |
| `intellimate-gateway/.../gateway/pipeline/MessagePipeline.java:176,924` | 传递 `bridgeNode` 到 AgentRunRequest |
| `intellimate-gateway/.../gateway/scheduler/jobs/AgentPromptJob.java` | 传递 `bridgeNode` 到 AgentRunRequest |
| `intellimate-gateway/.../gateway/websocket/WebSocketRouterConfig.java` | 注册 `/api/bridge/connect` 路由 |

### 本地组件新建文件

| 文件 | 职责 |
|------|------|
| `intellimate-local/package.json` | npm 包定义，bin 入口 |
| `intellimate-local/tsconfig.json` | TypeScript 编译配置 |
| `intellimate-local/src/index.ts` | CLI 入口：参数解析、启动 |
| `intellimate-local/src/bridge-client.ts` | WebSocket 客户端：连接、收发、自动重连 |
| `intellimate-local/src/tool-executor.ts` | 工具分发器：根据 tool 名路由到执行器 |
| `intellimate-local/src/tools/file-ops.ts` | readFile / writeFile / editFile / listFiles |
| `intellimate-local/src/tools/exec.ts` | 命令执行，支持流式输出 |
| `intellimate-local/src/config.ts` | 配置加载（CLI 参数 + YAML 文件） |
| `intellimate-local/config.example.yml` | 示例配置文件 |

---

### 任务 1：数据库迁移 V26

**文件：**
- 创建：`intellimate-gateway/src/main/resources/db/migration/V26__bridge_node.sql`

- [ ] **步骤 1：创建迁移文件**

```sql
-- Bridge execution nodes: lightweight remote endpoints that can execute tools
CREATE TABLE bridge_node (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(64) NOT NULL UNIQUE COMMENT '节点名称，如 my-laptop',
    token_hash VARCHAR(128) NOT NULL COMMENT '认证 token 的 SHA-256 散列',
    status VARCHAR(16) NOT NULL DEFAULT 'DISCONNECTED' COMMENT 'CONNECTED / DISCONNECTED',
    registered_tools JSON DEFAULT NULL COMMENT '节点注册时上报的可用工具列表',
    last_connected_at DATETIME DEFAULT NULL,
    last_heartbeat_at DATETIME DEFAULT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE agent ADD COLUMN bridge_node VARCHAR(64) DEFAULT NULL COMMENT '绑定的 Bridge 节点名称';
```

- [ ] **步骤 2：启动 gateway 验证迁移**

运行：`mvn spring-boot:run -pl intellimate-gateway`
预期：启动日志中看到 `Successfully applied 1 migration to schema ... (execution time ... V26__bridge_node.sql)`

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/resources/db/migration/V26__bridge_node.sql
git commit -m "feat(bridge): add V26 migration for bridge_node table and agent.bridge_node column"
```

---

### 任务 2：BridgeNodeEntity + Repository

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/BridgeNodeEntity.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/BridgeNodeRepository.java`

- [ ] **步骤 1：创建 BridgeNodeEntity**

```java
package com.atm.intellimate.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("bridge_node")
public class BridgeNodeEntity {

    @Id
    private Long id;
    private String name;
    private String tokenHash;
    private String status;
    private String registeredTools;
    private LocalDateTime lastConnectedAt;
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // getters and setters for all fields
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRegisteredTools() { return registeredTools; }
    public void setRegisteredTools(String registeredTools) { this.registeredTools = registeredTools; }
    public LocalDateTime getLastConnectedAt() { return lastConnectedAt; }
    public void setLastConnectedAt(LocalDateTime lastConnectedAt) { this.lastConnectedAt = lastConnectedAt; }
    public LocalDateTime getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(LocalDateTime lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
```

- [ ] **步骤 2：创建 BridgeNodeRepository**

```java
package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.BridgeNodeEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface BridgeNodeRepository extends ReactiveCrudRepository<BridgeNodeEntity, Long> {
    Mono<BridgeNodeEntity> findByName(String name);

    @Query("UPDATE bridge_node SET status = :status, last_connected_at = NOW() WHERE name = :name")
    Mono<Void> updateStatus(String name, String status);

    @Query("UPDATE bridge_node SET last_heartbeat_at = NOW() WHERE name = :name")
    Mono<Void> updateHeartbeat(String name);

    @Query("UPDATE bridge_node SET registered_tools = :tools WHERE name = :name")
    Mono<Void> updateRegisteredTools(String name, String tools);
}
```

- [ ] **步骤 3：AgentEntity 新增 bridgeNode 属性**

在 `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/AgentEntity.java` 中，`goal` 字段后新增：

```java
private String bridgeNode;

public String getBridgeNode() { return bridgeNode; }
public void setBridgeNode(String bridgeNode) { this.bridgeNode = bridgeNode; }
```

- [ ] **步骤 4：编译验证**

运行：`mvn compile -pl intellimate-gateway -q`
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/BridgeNodeEntity.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/BridgeNodeRepository.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/AgentEntity.java
git commit -m "feat(bridge): add BridgeNodeEntity, repository, and agent.bridgeNode field"
```

---

### 任务 3：BridgeToolProvider SPI（agent 模块）

**文件：**
- 创建：`intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/bridge/BridgeToolProvider.java`

- [ ] **步骤 1：定义 SPI 接口**

```java
package com.atm.intellimate.agent.tools.bridge;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolDefinition;

import java.util.Set;

/**
 * SPI for routing tool execution to a remote Bridge node.
 * Agent module defines the interface; gateway module provides the implementation.
 */
public interface BridgeToolProvider {

    /**
     * Whether the named bridge node is currently connected and ready.
     */
    boolean isConnected(String bridgeNodeName);

    /**
     * Tool names that the bridge node has registered (e.g. "readFile", "exec").
     * Returns empty set if not connected.
     */
    Set<String> getRegisteredTools(String bridgeNodeName);

    /**
     * Create a ToolCallback that forwards execution to the bridge node
     * while preserving the original tool definition (name, description, params).
     */
    ToolCallback createBridgeCallback(String bridgeNodeName, ToolDefinition originalDefinition);
}
```

- [ ] **步骤 2：编译验证**

运行：`mvn compile -pl intellimate-agent -q`
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/bridge/BridgeToolProvider.java
git commit -m "feat(bridge): add BridgeToolProvider SPI in agent module"
```

---

### 任务 4：ToolsEngine 集成 Bridge 路由

**文件：**
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/ToolsEngine.java`

- [ ] **步骤 1：注入 BridgeToolProvider**

在 `ToolsEngine` 类中添加字段和修改构造函数：

```java
// 新增 import
import com.atm.intellimate.agent.tools.bridge.BridgeToolProvider;

// 新增字段
private final BridgeToolProvider bridgeToolProvider;

// 修改构造函数签名
public ToolsEngine(List<ToolCallbackProvider> providers,
                   @Autowired(required = false) DynamicToolProvider dynamicToolProvider,
                   @Autowired(required = false) McpToolProvider mcpToolProvider,
                   @Autowired(required = false) BridgeToolProvider bridgeToolProvider) {
    // ... 现有初始化代码不变 ...
    this.bridgeToolProvider = bridgeToolProvider;
    refresh();
}
```

- [ ] **步骤 2：新增三参数 getToolCallbacksFor 方法**

在 `ToolsEngine` 中 `getToolCallbacksFor(String, String)` 方法后添加：

```java
/**
 * Filter tools and optionally replace bridgeable tools with remote-forwarding callbacks.
 *
 * @param toolsEnabledSpec    builtin+custom filter
 * @param mcpToolsEnabledSpec MCP filter
 * @param bridgeNodeName      bridge node name; null = no bridge routing
 */
public ToolCallback[] getToolCallbacksFor(String toolsEnabledSpec, String mcpToolsEnabledSpec,
                                          String bridgeNodeName) {
    ToolCallback[] callbacks = getToolCallbacksFor(toolsEnabledSpec, mcpToolsEnabledSpec);

    if (bridgeNodeName == null || bridgeNodeName.isBlank()
            || bridgeToolProvider == null
            || !bridgeToolProvider.isConnected(bridgeNodeName)) {
        return callbacks;
    }

    Set<String> bridgeTools = bridgeToolProvider.getRegisteredTools(bridgeNodeName);
    if (bridgeTools.isEmpty()) {
        return callbacks;
    }

    log.info("Bridge node '{}' active, routing tools: {}", bridgeNodeName, bridgeTools);

    return Arrays.stream(callbacks)
            .map(cb -> {
                String toolName = cb.getToolDefinition().name();
                if (bridgeTools.contains(toolName)) {
                    return bridgeToolProvider.createBridgeCallback(bridgeNodeName, cb.getToolDefinition());
                }
                return cb;
            })
            .toArray(ToolCallback[]::new);
}
```

- [ ] **步骤 3：编译验证**

运行：`mvn compile -pl intellimate-agent -q`
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
git add intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/ToolsEngine.java
git commit -m "feat(bridge): add bridge-aware tool routing in ToolsEngine"
```

---

### 任务 5：AgentRunRequest + ResolvedAgentConfig + 调用链更新

**文件：**
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRunRequest.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/ResolvedAgentConfig.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/AgentConfigService.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java:176,924`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/scheduler/jobs/AgentPromptJob.java`
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java:194,918,1025`

此任务涉及多个文件，是修改量最大的一步。核心是让 `bridgeNode` 从 DB entity → ResolvedAgentConfig → AgentRunRequest → AgentRuntime → ToolsEngine 完整传递。

- [ ] **步骤 1：修改 AgentRunRequest 添加 bridgeNode 字段**

在 `AgentRunRequest.java` record 的 `delegationContext` 参数后新增 `String bridgeNode`（第 15 个参数）。同时更新两个便利构造函数，尾部追加 `null`：

```java
public record AgentRunRequest(
        Long sessionId,
        String userId,
        IntelliMateProperties.Agent agent,
        String userMessage,
        List<org.springframework.ai.chat.messages.Message> history,
        String toolsEnabled,
        String mcpToolsEnabled,
        String skillsEnabled,
        String skillGroupsEnabled,
        String planContext,
        boolean forcePlan,
        Long activePlanId,
        PlanExecutionAssessment planExecutionAssessment,
        DelegationContext delegationContext,
        String bridgeNode
) {
    public AgentRunRequest(Long sessionId, IntelliMateProperties.Agent agent,
                           String userMessage, List<org.springframework.ai.chat.messages.Message> history) {
        this(sessionId, null, agent, userMessage, history, null, null, null, null, null, false, null, null, null, null);
    }

    public AgentRunRequest(Long sessionId, IntelliMateProperties.Agent agent,
                           String userMessage, List<org.springframework.ai.chat.messages.Message> history,
                           String toolsEnabled, String mcpToolsEnabled, String skillsEnabled) {
        this(sessionId, null, agent, userMessage, history, toolsEnabled, mcpToolsEnabled, skillsEnabled, null, null, false, null, null, null, null);
    }
}
```

- [ ] **步骤 2：修改 ResolvedAgentConfig 添加 bridgeNode 字段**

```java
public record ResolvedAgentConfig(
        IntelliMateProperties.Agent agent,
        String toolsEnabled,
        String mcpToolsEnabled,
        String skillsEnabled,
        String skillGroupsEnabled,
        boolean canDelegate,
        String delegateAgents,
        String goal,
        String bridgeNode
) {
    public ResolvedAgentConfig(IntelliMateProperties.Agent agent,
                               String toolsEnabled, String mcpToolsEnabled,
                               String skillsEnabled, String skillGroupsEnabled) {
        this(agent, toolsEnabled, mcpToolsEnabled, skillsEnabled, skillGroupsEnabled,
                false, null, null, null);
    }
}
```

- [ ] **步骤 3：修改 AgentConfigService 解析 bridgeNode**

在 `AgentConfigService.resolve()` 中，两处构造 `ResolvedAgentConfig` 的地方都追加 `entity.getBridgeNode()`：

```java
// 第一处（有 delegation 时）约第 62 行
return new ResolvedAgentConfig(
        agentConfig,
        entity.getToolsEnabled(),
        entity.getMcpToolsEnabled(),
        entity.getSkillsEnabled(),
        entity.getSkillGroupsEnabled(),
        true, entity.getDelegateAgents(), entity.getGoal(),
        entity.getBridgeNode());

// 第二处（无 delegation 时）约第 70 行
return Mono.just(new ResolvedAgentConfig(
        agentConfig,
        entity.getToolsEnabled(),
        entity.getMcpToolsEnabled(),
        entity.getSkillsEnabled(),
        entity.getSkillGroupsEnabled(),
        canDelegate, entity.getDelegateAgents(), entity.getGoal(),
        entity.getBridgeNode()));
```

- [ ] **步骤 4：修改 MessagePipeline 传递 bridgeNode**

在 `MessagePipeline.java` 第 176 行的 AgentRunRequest 构造中，`null`（delegationContext）后追加 `resolved.bridgeNode()`：

```java
AgentRunRequest runRequest = new AgentRunRequest(
        session.getId(),
        effectiveUserId,
        resolved.agent(),
        userText,
        messages,
        resolved.toolsEnabled(),
        resolved.mcpToolsEnabled(),
        resolved.skillsEnabled(),
        resolved.skillGroupsEnabled(),
        planContext,
        forcePlan,
        effectivePlanId,
        planPayload.assessment(),
        null,
        resolved.bridgeNode()
);
```

第 924 行的 handoff 构造同理：

```java
AgentRunRequest handoffRequest = new AgentRunRequest(
        session.getId(),
        effectiveUserId,
        resolved.agent(),
        handoffMessage,
        List.of(),
        resolved.toolsEnabled(),
        resolved.mcpToolsEnabled(),
        resolved.skillsEnabled(),
        resolved.skillGroupsEnabled(),
        null, false, null, null, null,
        resolved.bridgeNode());
```

- [ ] **步骤 5：修改 AgentRuntime 的委托构造**

在 `AgentRuntime.java` 第 918 行（单委托）和第 1025 行（并行委托）的 AgentRunRequest 构造中，`childCtx` 后追加 `null`（委托 Agent 不继承 bridgeNode，各自解析）：

```java
// 第 918 行
AgentRunRequest workerRequest = new AgentRunRequest(
        workerSessionId, null, workerAgent, workerMessage,
        List.of(),
        workerCfg.toolsEnabled(), workerCfg.mcpToolsEnabled(),
        workerCfg.skillsEnabled(), workerCfg.skillGroupsEnabled(),
        null, false, null, null, childCtx,
        workerCfg.bridgeNode());

// 第 1025 行
AgentRunRequest wr = new AgentRunRequest(
        sid, null, wa, buildWorkerPrompt(pt.task(), ""),
        List.of(), cfg.toolsEnabled(), cfg.mcpToolsEnabled(),
        cfg.skillsEnabled(), cfg.skillGroupsEnabled(),
        null, false, null, null, childCtx,
        cfg.bridgeNode());
```

- [ ] **步骤 6：修改 AgentPromptJob**

在 `AgentPromptJob.java` 的 AgentRunRequest 构造中追加 `resolved.bridgeNode()`。

- [ ] **步骤 7：修改 AgentRuntime.executeAgentLoop 使用三参数方法**

在 `AgentRuntime.java` 第 194 行，将：

```java
ToolCallback[] allTools = toolsEngine.getToolCallbacksFor(request.toolsEnabled(), request.mcpToolsEnabled());
```

改为：

```java
ToolCallback[] allTools = toolsEngine.getToolCallbacksFor(
        request.toolsEnabled(), request.mcpToolsEnabled(), request.bridgeNode());
```

- [ ] **步骤 8：编译验证**

运行：`mvn compile -q`
预期：BUILD SUCCESS（所有模块编译通过）

- [ ] **步骤 9：Commit**

```bash
git add -A
git commit -m "feat(bridge): thread bridgeNode through AgentRunRequest, ResolvedAgentConfig, and all call sites"
```

---

### 任务 6：Bridge 协议 + 节点会话 + 注册表

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/bridge/BridgeProtocol.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/bridge/BridgeNodeSession.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/bridge/BridgeNodeRegistry.java`

- [ ] **步骤 1：创建 BridgeProtocol**

```java
package com.atm.intellimate.gateway.bridge;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;
import java.util.Map;

public final class BridgeProtocol {

    private BridgeProtocol() {}

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = Register.class, name = "register"),
            @JsonSubTypes.Type(value = Registered.class, name = "registered"),
            @JsonSubTypes.Type(value = ToolCall.class, name = "tool_call"),
            @JsonSubTypes.Type(value = ToolResult.class, name = "tool_result"),
            @JsonSubTypes.Type(value = ToolStream.class, name = "tool_stream"),
            @JsonSubTypes.Type(value = Ping.class, name = "ping"),
            @JsonSubTypes.Type(value = Pong.class, name = "pong"),
            @JsonSubTypes.Type(value = ErrorMsg.class, name = "error")
    })
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public sealed interface Message permits Register, Registered, ToolCall, ToolResult,
            ToolStream, Ping, Pong, ErrorMsg {}

    public record Register(String name, List<String> tools,
                           List<McpToolGroup> mcpTools) implements Message {}

    public record McpToolGroup(String server, List<String> tools) {}

    public record Registered(String nodeId) implements Message {}

    public record ToolCall(String id, String tool, Map<String, Object> args) implements Message {}

    public record ToolResult(String id, boolean success, String result,
                             String error, Integer exitCode) implements Message {}

    public record ToolStream(String id, String chunk) implements Message {}

    public record Ping() implements Message {}

    public record Pong() implements Message {}

    public record ErrorMsg(String message) implements Message {}
}
```

- [ ] **步骤 2：创建 BridgeNodeSession**

```java
package com.atm.intellimate.gateway.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class BridgeNodeSession {

    private static final Logger log = LoggerFactory.getLogger(BridgeNodeSession.class);
    private static final long DEFAULT_TOOL_TIMEOUT_SECONDS = 120;

    private final String nodeName;
    private final WebSocketSession wsSession;
    private final Sinks.Many<String> outSink;
    private final ObjectMapper objectMapper;
    private volatile Set<String> registeredTools = Set.of();
    private volatile Instant connectedAt = Instant.now();
    private volatile Instant lastHeartbeat = Instant.now();

    private final Map<String, CompletableFuture<BridgeProtocol.ToolResult>> pendingCalls = new ConcurrentHashMap<>();

    public BridgeNodeSession(String nodeName, WebSocketSession wsSession,
                             Sinks.Many<String> outSink, ObjectMapper objectMapper) {
        this.nodeName = nodeName;
        this.wsSession = wsSession;
        this.outSink = outSink;
        this.objectMapper = objectMapper;
    }

    public String getNodeName() { return nodeName; }
    public Set<String> getRegisteredTools() { return registeredTools; }
    public void setRegisteredTools(Set<String> tools) { this.registeredTools = tools; }
    public Instant getConnectedAt() { return connectedAt; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Instant lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }

    /**
     * Send a tool call to the bridge node and wait for the result.
     * Blocks the calling thread (acceptable on boundedElastic).
     */
    public BridgeProtocol.ToolResult callTool(String toolName, String argsJson) {
        String requestId = java.util.UUID.randomUUID().toString();
        CompletableFuture<BridgeProtocol.ToolResult> future = new CompletableFuture<>();
        pendingCalls.put(requestId, future);

        try {
            Map<String, Object> args = objectMapper.readValue(argsJson, Map.class);
            BridgeProtocol.ToolCall call = new BridgeProtocol.ToolCall(requestId, toolName, args);
            String json = objectMapper.writeValueAsString(call);
            outSink.tryEmitNext(json);

            return future.get(DEFAULT_TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingCalls.remove(requestId);
            log.error("Bridge tool call failed: node={}, tool={}", nodeName, toolName, e);
            return new BridgeProtocol.ToolResult(requestId, false, null,
                    "Bridge call error: " + e.getMessage(), null);
        }
    }

    /**
     * Called when a ToolResult message arrives from the bridge node.
     */
    public void onToolResult(BridgeProtocol.ToolResult result) {
        CompletableFuture<BridgeProtocol.ToolResult> future = pendingCalls.remove(result.id());
        if (future != null) {
            future.complete(result);
        } else {
            log.warn("Received tool result for unknown request: id={}", result.id());
        }
    }

    public void sendPing() {
        try {
            String json = objectMapper.writeValueAsString(new BridgeProtocol.Ping());
            outSink.tryEmitNext(json);
        } catch (Exception e) {
            log.error("Failed to send ping to bridge node: {}", nodeName, e);
        }
    }

    public void close() {
        pendingCalls.values().forEach(f -> f.completeExceptionally(
                new RuntimeException("Bridge node disconnected")));
        pendingCalls.clear();
    }
}
```

- [ ] **步骤 3：创建 BridgeNodeRegistry**

```java
package com.atm.intellimate.gateway.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BridgeNodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(BridgeNodeRegistry.class);

    private final Map<String, BridgeNodeSession> sessions = new ConcurrentHashMap<>();

    public void register(String nodeName, BridgeNodeSession session) {
        BridgeNodeSession prev = sessions.put(nodeName, session);
        if (prev != null) {
            log.warn("Replacing existing bridge session for node: {}", nodeName);
            prev.close();
        }
        log.info("Bridge node registered: {}, tools: {}", nodeName, session.getRegisteredTools());
    }

    public void unregister(String nodeName) {
        BridgeNodeSession session = sessions.remove(nodeName);
        if (session != null) {
            session.close();
            log.info("Bridge node unregistered: {}", nodeName);
        }
    }

    public BridgeNodeSession getSession(String nodeName) {
        return sessions.get(nodeName);
    }

    public boolean isConnected(String nodeName) {
        return sessions.containsKey(nodeName);
    }

    public Set<String> getRegisteredTools(String nodeName) {
        BridgeNodeSession session = sessions.get(nodeName);
        return session != null ? session.getRegisteredTools() : Set.of();
    }

    public Collection<BridgeNodeSession> getAllSessions() {
        return sessions.values();
    }
}
```

- [ ] **步骤 4：编译验证**

运行：`mvn compile -pl intellimate-gateway -q`
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/bridge/
git commit -m "feat(bridge): add BridgeProtocol, BridgeNodeSession, and BridgeNodeRegistry"
```

---

### 任务 7：BridgeWebSocketHandler

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/bridge/BridgeWebSocketHandler.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/websocket/WebSocketRouterConfig.java`

- [ ] **步骤 1：创建 BridgeWebSocketHandler**

```java
package com.atm.intellimate.gateway.bridge;

import com.atm.intellimate.gateway.repository.BridgeNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

@Component
public class BridgeWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BridgeWebSocketHandler.class);
    private static final Duration PING_INTERVAL = Duration.ofSeconds(30);

    private final BridgeNodeRegistry registry;
    private final BridgeNodeRepository repository;
    private final ObjectMapper objectMapper;

    public BridgeWebSocketHandler(BridgeNodeRegistry registry,
                                  BridgeNodeRepository repository,
                                  ObjectMapper objectMapper) {
        this.registry = registry;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @NonNull
    public Mono<Void> handle(@NonNull WebSocketSession session) {
        String token = extractToken(session);
        if (token == null || token.isBlank()) {
            log.warn("Bridge connection rejected: no token provided");
            return session.close();
        }

        String tokenHash = sha256(token);
        log.info("Bridge connection attempt: wsSessionId={}", session.getId());

        Sinks.Many<String> outSink = Sinks.many().unicast()
                .onBackpressureBuffer(Queues.<String>get(256).get());

        // Validate token against DB and complete registration via message exchange
        return session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .publish(incoming -> {
                    Mono<Void> inbound = incoming
                            .concatMap(text -> handleIncoming(text, session, outSink, tokenHash))
                            .then();

                    Flux<WebSocketMessage> outbound = outSink.asFlux()
                            .map(session::textMessage);

                    Disposable heartbeat = Flux.interval(PING_INTERVAL)
                            .subscribe(tick -> {
                                BridgeNodeSession nodeSession = findSessionByWs(session);
                                if (nodeSession != null) {
                                    nodeSession.sendPing();
                                }
                            });

                    return Mono.when(inbound, session.send(outbound))
                            .doFinally(signal -> {
                                heartbeat.dispose();
                                cleanupSession(session);
                                log.info("Bridge disconnected: wsSessionId={}, signal={}", session.getId(), signal);
                            });
                });
    }

    private Mono<Void> handleIncoming(String text, WebSocketSession wsSession,
                                      Sinks.Many<String> outSink, String tokenHash) {
        try {
            BridgeProtocol.Message msg = objectMapper.readValue(text, BridgeProtocol.Message.class);

            return switch (msg) {
                case BridgeProtocol.Register reg -> handleRegister(reg, wsSession, outSink, tokenHash);
                case BridgeProtocol.ToolResult result -> {
                    BridgeNodeSession nodeSession = findSessionByWs(wsSession);
                    if (nodeSession != null) nodeSession.onToolResult(result);
                    yield Mono.empty();
                }
                case BridgeProtocol.Pong pong -> {
                    BridgeNodeSession nodeSession = findSessionByWs(wsSession);
                    if (nodeSession != null) nodeSession.setLastHeartbeat(Instant.now());
                    yield Mono.empty();
                }
                default -> {
                    log.debug("Unhandled bridge message type: {}", msg.getClass().getSimpleName());
                    yield Mono.empty();
                }
            };
        } catch (Exception e) {
            log.error("Failed to parse bridge message: {}", text, e);
            return Mono.empty();
        }
    }

    private Mono<Void> handleRegister(BridgeProtocol.Register reg, WebSocketSession wsSession,
                                      Sinks.Many<String> outSink, String tokenHash) {
        return repository.findByName(reg.name())
                .switchIfEmpty(Mono.error(new RuntimeException("Unknown bridge node: " + reg.name())))
                .flatMap(entity -> {
                    if (!entity.getTokenHash().equals(tokenHash)) {
                        log.warn("Bridge token mismatch for node: {}", reg.name());
                        return sendError(outSink, "Authentication failed")
                                .then(Mono.fromRunnable(wsSession::close))
                                .then();
                    }

                    BridgeNodeSession nodeSession = new BridgeNodeSession(
                            reg.name(), wsSession, outSink, objectMapper);
                    nodeSession.setRegisteredTools(Set.copyOf(reg.tools()));
                    registry.register(reg.name(), nodeSession);

                    return repository.updateStatus(reg.name(), "CONNECTED")
                            .then(repository.updateRegisteredTools(reg.name(),
                                    toJson(reg.tools())))
                            .then(sendRegistered(outSink, entity.getId().toString()));
                })
                .onErrorResume(e -> {
                    log.error("Bridge registration failed: {}", e.getMessage());
                    return sendError(outSink, e.getMessage());
                });
    }

    private Mono<Void> sendRegistered(Sinks.Many<String> outSink, String nodeId) {
        try {
            outSink.tryEmitNext(objectMapper.writeValueAsString(
                    new BridgeProtocol.Registered(nodeId)));
        } catch (Exception e) {
            log.error("Failed to send registered message", e);
        }
        return Mono.empty();
    }

    private Mono<Void> sendError(Sinks.Many<String> outSink, String message) {
        try {
            outSink.tryEmitNext(objectMapper.writeValueAsString(
                    new BridgeProtocol.ErrorMsg(message)));
        } catch (Exception e) {
            log.error("Failed to send error message", e);
        }
        return Mono.empty();
    }

    private BridgeNodeSession findSessionByWs(WebSocketSession wsSession) {
        return registry.getAllSessions().stream()
                .filter(s -> s.getNodeName() != null)
                .findFirst()
                .orElse(null);
    }

    private void cleanupSession(WebSocketSession wsSession) {
        registry.getAllSessions().stream()
                .filter(s -> s.getNodeName() != null)
                .forEach(s -> {
                    registry.unregister(s.getNodeName());
                    repository.updateStatus(s.getNodeName(), "DISCONNECTED").subscribe();
                });
    }

    private String extractToken(WebSocketSession session) {
        var uri = session.getHandshakeInfo().getUri();
        var query = uri.getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    return param.substring(6);
                }
            }
        }
        return null;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); }
        catch (Exception e) { return "[]"; }
    }
}
```

- [ ] **步骤 2：注册 Bridge WebSocket 路由**

在 `WebSocketRouterConfig.java` 中修改 `webSocketHandlerMapping` 方法：

```java
@Bean
public HandlerMapping webSocketHandlerMapping(GatewayWebSocketHandler handler,
                                              BridgeWebSocketHandler bridgeHandler) {
    SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
    mapping.setUrlMap(Map.of(
            "/ws", handler,
            "/api/bridge/connect", bridgeHandler
    ));
    mapping.setOrder(-1);

    CorsConfiguration cors = new CorsConfiguration();
    cors.addAllowedOriginPattern("*");
    cors.addAllowedHeader("*");
    cors.addAllowedMethod("*");
    cors.setAllowCredentials(true);
    mapping.setCorsConfigurations(Map.of("/ws", cors, "/api/bridge/connect", cors));

    return mapping;
}
```

- [ ] **步骤 3：编译验证**

运行：`mvn compile -pl intellimate-gateway -q`
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/bridge/BridgeWebSocketHandler.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/websocket/WebSocketRouterConfig.java
git commit -m "feat(bridge): add BridgeWebSocketHandler and register /api/bridge/connect route"
```

---

### 任务 8：BridgeToolCallback + BridgeToolProviderImpl

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/bridge/BridgeToolCallback.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/bridge/BridgeToolProviderImpl.java`

- [ ] **步骤 1：创建 BridgeToolCallback**

```java
package com.atm.intellimate.gateway.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolDefinition;

public class BridgeToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(BridgeToolCallback.class);

    private final ToolDefinition definition;
    private final BridgeNodeSession nodeSession;

    public BridgeToolCallback(ToolDefinition definition, BridgeNodeSession nodeSession) {
        this.definition = definition;
        this.nodeSession = nodeSession;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        String toolName = definition.name();
        log.info("Bridge tool call: node={}, tool={}", nodeSession.getNodeName(), toolName);

        BridgeProtocol.ToolResult result = nodeSession.callTool(toolName, toolInput);

        if (result.success()) {
            return result.result() != null ? result.result() : "";
        } else {
            String error = result.error() != null ? result.error() : "Unknown bridge error";
            log.warn("Bridge tool error: node={}, tool={}, error={}",
                    nodeSession.getNodeName(), toolName, error);
            return "Error (bridge): " + error;
        }
    }
}
```

- [ ] **步骤 2：创建 BridgeToolProviderImpl**

```java
package com.atm.intellimate.gateway.bridge;

import com.atm.intellimate.agent.tools.bridge.BridgeToolProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class BridgeToolProviderImpl implements BridgeToolProvider {

    private final BridgeNodeRegistry registry;

    public BridgeToolProviderImpl(BridgeNodeRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean isConnected(String bridgeNodeName) {
        return registry.isConnected(bridgeNodeName);
    }

    @Override
    public Set<String> getRegisteredTools(String bridgeNodeName) {
        return registry.getRegisteredTools(bridgeNodeName);
    }

    @Override
    public ToolCallback createBridgeCallback(String bridgeNodeName, ToolDefinition originalDefinition) {
        BridgeNodeSession session = registry.getSession(bridgeNodeName);
        if (session == null) {
            throw new IllegalStateException("Bridge node not connected: " + bridgeNodeName);
        }
        return new BridgeToolCallback(originalDefinition, session);
    }
}
```

- [ ] **步骤 3：编译验证**

运行：`mvn compile -q`
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/bridge/BridgeToolCallback.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/bridge/BridgeToolProviderImpl.java
git commit -m "feat(bridge): add BridgeToolCallback and BridgeToolProviderImpl"
```

---

### 任务 9：BridgeController REST API

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/bridge/BridgeController.java`

- [ ] **步骤 1：创建 BridgeController**

```java
package com.atm.intellimate.gateway.bridge;

import com.atm.intellimate.gateway.entity.BridgeNodeEntity;
import com.atm.intellimate.gateway.repository.BridgeNodeRepository;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/bridge")
public class BridgeController {

    private final BridgeNodeRepository repository;
    private final BridgeNodeRegistry registry;

    public BridgeController(BridgeNodeRepository repository, BridgeNodeRegistry registry) {
        this.repository = repository;
        this.registry = registry;
    }

    @GetMapping("/nodes")
    public Flux<Map<String, Object>> listNodes() {
        return repository.findAll().map(entity -> Map.<String, Object>of(
                "id", entity.getId(),
                "name", entity.getName(),
                "status", registry.isConnected(entity.getName()) ? "CONNECTED" : "DISCONNECTED",
                "registeredTools", entity.getRegisteredTools() != null ? entity.getRegisteredTools() : "[]",
                "lastConnectedAt", entity.getLastConnectedAt() != null ? entity.getLastConnectedAt().toString() : "",
                "lastHeartbeatAt", entity.getLastHeartbeatAt() != null ? entity.getLastHeartbeatAt().toString() : ""
        ));
    }

    @PostMapping("/nodes")
    public Mono<Map<String, Object>> createNode(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return Mono.error(new IllegalArgumentException("name is required"));
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        String tokenHash = sha256(token);

        BridgeNodeEntity entity = new BridgeNodeEntity();
        entity.setName(name);
        entity.setTokenHash(tokenHash);
        entity.setStatus("DISCONNECTED");

        return repository.save(entity)
                .map(saved -> Map.<String, Object>of(
                        "id", saved.getId(),
                        "name", saved.getName(),
                        "token", token,
                        "message", "请保存此 token，它只显示一次。使用方式：npx intellimate-local --server ws://host:3007/api/bridge/connect --token " + token
                ));
    }

    @DeleteMapping("/nodes/{name}")
    public Mono<Void> deleteNode(@PathVariable String name) {
        registry.unregister(name);
        return repository.findByName(name)
                .flatMap(entity -> repository.delete(entity));
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
```

- [ ] **步骤 2：编译验证**

运行：`mvn compile -pl intellimate-gateway -q`
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/bridge/BridgeController.java
git commit -m "feat(bridge): add BridgeController REST API for node management"
```

---

### 任务 10：本地组件项目初始化

**文件：**
- 创建：`intellimate-local/package.json`
- 创建：`intellimate-local/tsconfig.json`

- [ ] **步骤 1：创建 package.json**

```json
{
  "name": "intellimate-local",
  "version": "0.1.0",
  "description": "Lightweight local execution bridge for IntelliMate agents",
  "type": "module",
  "bin": {
    "intellimate-local": "./dist/index.js"
  },
  "scripts": {
    "build": "tsc",
    "start": "node dist/index.js",
    "dev": "tsc --watch"
  },
  "dependencies": {
    "ws": "^8.18.0",
    "yaml": "^2.6.0",
    "commander": "^12.1.0"
  },
  "devDependencies": {
    "@types/node": "^22.0.0",
    "@types/ws": "^8.5.0",
    "typescript": "^5.7.0"
  },
  "engines": {
    "node": ">=18"
  },
  "files": ["dist"],
  "keywords": ["intellimate", "bridge", "agent", "local"],
  "license": "MIT"
}
```

- [ ] **步骤 2：创建 tsconfig.json**

```json
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "Node16",
    "moduleResolution": "Node16",
    "outDir": "./dist",
    "rootDir": "./src",
    "strict": true,
    "esModuleInterop": true,
    "declaration": true,
    "sourceMap": true,
    "skipLibCheck": true
  },
  "include": ["src/**/*"]
}
```

- [ ] **步骤 3：安装依赖**

运行：`cd intellimate-local && npm install`
预期：`node_modules` 创建成功，无错误

- [ ] **步骤 4：Commit**

```bash
git add intellimate-local/package.json intellimate-local/tsconfig.json intellimate-local/package-lock.json
git commit -m "feat(bridge-local): initialize intellimate-local npm project"
```

注意：将 `intellimate-local/node_modules` 加入 `.gitignore`。

---

### 任务 11：本地组件 — 配置与 CLI

**文件：**
- 创建：`intellimate-local/src/config.ts`
- 创建：`intellimate-local/src/index.ts`
- 创建：`intellimate-local/config.example.yml`

- [ ] **步骤 1：创建 config.ts**

```typescript
import { readFileSync } from 'fs';
import { parse } from 'yaml';

export interface SecurityConfig {
  allowedPaths?: string[];
  blockedCommands?: string[];
}

export interface McpServerConfig {
  name: string;
  command: string;
  args?: string[];
}

export interface AppConfig {
  server: string;
  token: string;
  name: string;
  security?: SecurityConfig;
  mcpServers?: McpServerConfig[];
}

export function loadConfig(options: {
  server?: string;
  token?: string;
  name?: string;
  config?: string;
}): AppConfig {
  let fileConfig: Partial<AppConfig> = {};

  if (options.config) {
    const raw = readFileSync(options.config, 'utf-8');
    fileConfig = parse(raw) as Partial<AppConfig>;
  }

  const config: AppConfig = {
    server: options.server || fileConfig.server || '',
    token: options.token || fileConfig.token || '',
    name: options.name || fileConfig.name || `node-${process.pid}`,
    security: fileConfig.security,
    mcpServers: fileConfig.mcpServers,
  };

  if (!config.server) throw new Error('--server is required');
  if (!config.token) throw new Error('--token is required');

  return config;
}
```

- [ ] **步骤 2：创建 index.ts（CLI 入口）**

```typescript
#!/usr/bin/env node
import { Command } from 'commander';
import { loadConfig } from './config.js';
import { BridgeClient } from './bridge-client.js';
import { ToolExecutor } from './tool-executor.js';

const program = new Command();

program
  .name('intellimate-local')
  .description('Local execution bridge for IntelliMate agents')
  .option('-s, --server <url>', 'Gateway WebSocket URL')
  .option('-t, --token <token>', 'Authentication token')
  .option('-n, --name <name>', 'Node name')
  .option('-c, --config <path>', 'Config file path')
  .parse();

const opts = program.opts();

try {
  const config = loadConfig(opts);
  const executor = new ToolExecutor(config);
  const client = new BridgeClient(config, executor);
  client.connect();

  console.log(`[intellimate-local] Node "${config.name}" connecting to ${config.server}`);

  process.on('SIGINT', () => {
    console.log('\n[intellimate-local] Shutting down...');
    client.disconnect();
    process.exit(0);
  });
} catch (e: any) {
  console.error(`[intellimate-local] Error: ${e.message}`);
  process.exit(1);
}
```

- [ ] **步骤 3：创建 config.example.yml**

```yaml
server: "ws://your-server:3007/api/bridge/connect"
token: "your-token-here"
name: "my-laptop"

security:
  allowedPaths:
    - "/Users/user/projects"
  blockedCommands:
    - "rm -rf /"

# mcpServers:
#   - name: browser
#     command: npx
#     args: ["-y", "@anthropic/mcp-browser"]
```

- [ ] **步骤 4：Commit**

```bash
git add intellimate-local/src/config.ts intellimate-local/src/index.ts intellimate-local/config.example.yml
git commit -m "feat(bridge-local): add config loader and CLI entry point"
```

---

### 任务 12：本地组件 — Bridge 客户端

**文件：**
- 创建：`intellimate-local/src/bridge-client.ts`

- [ ] **步骤 1：创建 bridge-client.ts**

```typescript
import WebSocket from 'ws';
import { AppConfig } from './config.js';
import { ToolExecutor } from './tool-executor.js';

interface ToolCallMessage {
  type: 'tool_call';
  id: string;
  tool: string;
  args: Record<string, any>;
}

interface PingMessage {
  type: 'ping';
}

interface RegisteredMessage {
  type: 'registered';
  nodeId: string;
}

interface ErrorMessage {
  type: 'error';
  message: string;
}

type IncomingMessage = ToolCallMessage | PingMessage | RegisteredMessage | ErrorMessage;

export class BridgeClient {
  private ws: WebSocket | null = null;
  private config: AppConfig;
  private executor: ToolExecutor;
  private reconnectDelay = 1000;
  private maxReconnectDelay = 30000;
  private shouldReconnect = true;

  constructor(config: AppConfig, executor: ToolExecutor) {
    this.config = config;
    this.executor = executor;
  }

  connect(): void {
    const url = `${this.config.server}?token=${encodeURIComponent(this.config.token)}`;
    this.ws = new WebSocket(url);

    this.ws.on('open', () => {
      console.log(`[bridge] Connected to gateway`);
      this.reconnectDelay = 1000;
      this.sendRegister();
    });

    this.ws.on('message', (data: WebSocket.Data) => {
      this.handleMessage(data.toString());
    });

    this.ws.on('close', () => {
      console.log('[bridge] Disconnected');
      if (this.shouldReconnect) {
        console.log(`[bridge] Reconnecting in ${this.reconnectDelay}ms...`);
        setTimeout(() => this.connect(), this.reconnectDelay);
        this.reconnectDelay = Math.min(this.reconnectDelay * 2, this.maxReconnectDelay);
      }
    });

    this.ws.on('error', (err: Error) => {
      console.error(`[bridge] WebSocket error: ${err.message}`);
    });
  }

  disconnect(): void {
    this.shouldReconnect = false;
    this.ws?.close();
  }

  private sendRegister(): void {
    const msg = {
      type: 'register',
      name: this.config.name,
      tools: this.executor.getToolNames(),
      mcpTools: [],
    };
    this.send(msg);
  }

  private async handleMessage(raw: string): Promise<void> {
    let msg: IncomingMessage;
    try {
      msg = JSON.parse(raw);
    } catch {
      console.error('[bridge] Failed to parse message:', raw);
      return;
    }

    switch (msg.type) {
      case 'registered':
        console.log(`[bridge] Registered as node: ${msg.nodeId}`);
        break;

      case 'tool_call':
        await this.handleToolCall(msg);
        break;

      case 'ping':
        this.send({ type: 'pong' });
        break;

      case 'error':
        console.error(`[bridge] Server error: ${msg.message}`);
        break;

      default:
        console.warn(`[bridge] Unknown message type: ${(msg as any).type}`);
    }
  }

  private async handleToolCall(msg: ToolCallMessage): Promise<void> {
    console.log(`[bridge] Tool call: ${msg.tool} (id=${msg.id})`);
    try {
      const result = await this.executor.execute(msg.tool, msg.args,
        (chunk: string) => {
          this.send({ type: 'tool_stream', id: msg.id, chunk });
        }
      );
      this.send({
        type: 'tool_result',
        id: msg.id,
        success: true,
        result: result.output,
        exitCode: result.exitCode ?? null,
      });
    } catch (err: any) {
      this.send({
        type: 'tool_result',
        id: msg.id,
        success: false,
        error: err.message,
      });
    }
  }

  private send(msg: object): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    }
  }
}
```

- [ ] **步骤 2：编译验证**

运行（在 intellimate-local 目录）：`npx tsc --noEmit`
预期：仅报告 `tool-executor.ts` 文件缺失的错误（下个任务创建），其余无误

- [ ] **步骤 3：Commit**

```bash
git add intellimate-local/src/bridge-client.ts
git commit -m "feat(bridge-local): add BridgeClient WebSocket client with auto-reconnect"
```

---

### 任务 13：本地组件 — 工具实现

**文件：**
- 创建：`intellimate-local/src/tools/file-ops.ts`
- 创建：`intellimate-local/src/tools/exec.ts`

- [ ] **步骤 1：创建 file-ops.ts**

```typescript
import { readFileSync, writeFileSync, existsSync, readdirSync, statSync, mkdirSync } from 'fs';
import { join, resolve, dirname } from 'path';

export function readFile(args: { path: string; startLine?: number; lineCount?: number }): string {
  const filePath = resolve(args.path);
  if (!existsSync(filePath)) return `Error: File not found: ${args.path}`;

  const content = readFileSync(filePath, 'utf-8');
  const lines = content.split('\n');

  const start = (args.startLine && args.startLine > 0) ? args.startLine - 1 : 0;
  const end = (args.lineCount && args.lineCount > 0)
    ? Math.min(start + args.lineCount, lines.length)
    : lines.length;

  const result = lines.slice(start, end)
    .map((line, i) => `${start + i + 1}|${line}`)
    .join('\n');

  if (start > 0 || end < lines.length) {
    return result + `\n--- [Showing lines ${start + 1}-${end} of ${lines.length} total lines.] ---`;
  }
  return result;
}

export function writeFile(args: { path: string; content: string }): string {
  const filePath = resolve(args.path);
  const dir = dirname(filePath);
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  writeFileSync(filePath, args.content, 'utf-8');
  return `File written: ${filePath} (${args.content.length} chars)`;
}

export function editFile(args: { path: string; oldText: string; newText: string }): string {
  const filePath = resolve(args.path);
  if (!existsSync(filePath)) return `Error: File not found: ${args.path}`;

  const content = readFileSync(filePath, 'utf-8');
  if (!content.includes(args.oldText)) {
    return `Error: oldText not found in file. Make sure it matches exactly.`;
  }

  const occurrences = content.split(args.oldText).length - 1;
  if (occurrences > 1) {
    return `Error: oldText found ${occurrences} times. Provide more context to uniquely identify the replacement.`;
  }

  const newContent = content.replace(args.oldText, args.newText);
  writeFileSync(filePath, newContent, 'utf-8');
  return `File edited: ${filePath}`;
}

export function listFiles(args: { path: string; pattern?: string; recursive?: boolean }): string {
  const dirPath = resolve(args.path);
  if (!existsSync(dirPath)) return `Error: Directory not found: ${args.path}`;

  const entries: string[] = [];
  function walk(dir: string, depth: number) {
    for (const entry of readdirSync(dir)) {
      if (entry.startsWith('.')) continue;
      const fullPath = join(dir, entry);
      const stat = statSync(fullPath);
      const relative = fullPath.replace(dirPath + '/', '');

      if (args.pattern && !relative.includes(args.pattern)) continue;

      entries.push(stat.isDirectory() ? `${relative}/` : relative);

      if (stat.isDirectory() && args.recursive && depth < 5) {
        walk(fullPath, depth + 1);
      }
    }
  }

  walk(dirPath, 0);
  return entries.join('\n') || '(empty directory)';
}
```

- [ ] **步骤 2：创建 exec.ts**

```typescript
import { spawn } from 'child_process';
import { resolve } from 'path';
import { existsSync } from 'fs';

export interface ExecResult {
  output: string;
  exitCode: number;
}

export async function exec(
  args: { command: string; workingDirectory?: string; timeoutSeconds?: number },
  onChunk?: (chunk: string) => void
): Promise<ExecResult> {
  const timeout = (args.timeoutSeconds && args.timeoutSeconds > 0)
    ? args.timeoutSeconds * 1000
    : 30000;

  const cwd = args.workingDirectory ? resolve(args.workingDirectory) : process.cwd();
  if (!existsSync(cwd)) {
    throw new Error(`Working directory not found: ${cwd}`);
  }

  return new Promise((resolvePromise, reject) => {
    const child = spawn('sh', ['-c', args.command], {
      cwd,
      env: process.env,
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    let output = '';
    const maxOutput = 1024 * 1024; // 1MB limit
    let truncated = false;

    const collectOutput = (data: Buffer) => {
      const text = data.toString();
      if (output.length < maxOutput) {
        output += text;
        if (output.length > maxOutput) {
          truncated = true;
          output = output.substring(0, maxOutput);
        }
      }
      if (onChunk) onChunk(text);
    };

    child.stdout.on('data', collectOutput);
    child.stderr.on('data', collectOutput);

    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error(`Command timed out after ${timeout / 1000} seconds`));
    }, timeout);

    child.on('close', (code) => {
      clearTimeout(timer);
      if (truncated) output += '\n--- [Output truncated at 1MB] ---';
      resolvePromise({
        output: `Exit code: ${code ?? -1}\n${output}`,
        exitCode: code ?? -1,
      });
    });

    child.on('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}
```

- [ ] **步骤 3：Commit**

```bash
git add intellimate-local/src/tools/
git commit -m "feat(bridge-local): add file-ops and exec tool implementations"
```

---

### 任务 14：本地组件 — ToolExecutor（分发器）

**文件：**
- 创建：`intellimate-local/src/tool-executor.ts`

- [ ] **步骤 1：创建 tool-executor.ts**

```typescript
import { readFile, writeFile, editFile, listFiles } from './tools/file-ops.js';
import { exec } from './tools/exec.js';
import { AppConfig } from './config.js';
import { resolve } from 'path';

export interface ToolResult {
  output: string;
  exitCode?: number;
}

type StreamCallback = (chunk: string) => void;

export class ToolExecutor {
  private config: AppConfig;

  constructor(config: AppConfig) {
    this.config = config;
  }

  getToolNames(): string[] {
    return ['readFile', 'writeFile', 'editFile', 'exec', 'listFiles'];
  }

  async execute(
    toolName: string,
    args: Record<string, any>,
    onStream?: StreamCallback
  ): Promise<ToolResult> {
    this.validateSecurity(toolName, args);

    switch (toolName) {
      case 'readFile':
        return { output: readFile(args as any) };
      case 'writeFile':
        return { output: writeFile(args as any) };
      case 'editFile':
        return { output: editFile(args as any) };
      case 'listFiles':
        return { output: listFiles(args as any) };
      case 'exec': {
        const result = await exec(args as any, onStream);
        return { output: result.output, exitCode: result.exitCode };
      }
      default:
        throw new Error(`Unknown tool: ${toolName}`);
    }
  }

  private validateSecurity(toolName: string, args: Record<string, any>): void {
    const security = this.config.security;
    if (!security) return;

    if (security.allowedPaths?.length && (toolName !== 'exec')) {
      const path = args.path as string;
      if (path) {
        const resolved = resolve(path);
        const allowed = security.allowedPaths.some(p => resolved.startsWith(resolve(p)));
        if (!allowed) {
          throw new Error(`Access denied: ${path} is not in allowed paths`);
        }
      }
    }

    if (security.blockedCommands?.length && toolName === 'exec') {
      const command = args.command as string;
      if (command) {
        for (const pattern of security.blockedCommands) {
          const regex = new RegExp(pattern.replace('*', '.*'));
          if (regex.test(command)) {
            throw new Error(`Command blocked by security policy: ${command}`);
          }
        }
      }
    }
  }
}
```

- [ ] **步骤 2：完整编译验证**

运行（在 intellimate-local 目录）：`npm run build`
预期：`dist/` 目录生成，无编译错误

- [ ] **步骤 3：Commit**

```bash
git add intellimate-local/src/tool-executor.ts
git commit -m "feat(bridge-local): add ToolExecutor dispatcher with security validation"
```

---

### 任务 15：端到端冒烟测试

此任务不写自动化测试，而是手动验证完整的端到端流程。

- [ ] **步骤 1：确保 gateway 编译通过**

运行：`mvn compile -q`
预期：BUILD SUCCESS

- [ ] **步骤 2：启动 gateway**

运行：`mvn spring-boot:run -pl intellimate-gateway`
预期：启动日志包含 V26 迁移成功

- [ ] **步骤 3：通过 REST API 创建 Bridge 节点**

```bash
curl -X POST http://localhost:3007/api/bridge/nodes \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <your-auth-token>' \
  -d '{"name": "test-laptop"}'
```

预期：返回 JSON，包含 `name`、`token`、`message`

- [ ] **步骤 4：构建并启动本地组件**

```bash
cd intellimate-local && npm run build
node dist/index.js --server ws://localhost:3007/api/bridge/connect --token <步骤3返回的token> --name test-laptop
```

预期：控制台输出 `[bridge] Connected to gateway` 和 `[bridge] Registered as node: ...`

- [ ] **步骤 5：在 Agent 配置中绑定 Bridge 节点**

通过 REST API 或数据库直接设置 agent 的 `bridge_node = 'test-laptop'`。

- [ ] **步骤 6：通过 Web UI 测试文件读取**

在 Web UI 中向绑定了 Bridge 的 Agent 发送："请读取 /tmp/test.txt 的内容"

预期：Agent 通过 Bridge 读取本地文件并返回内容

- [ ] **步骤 7：验证 Bridge 断线恢复**

1. 停止本地组件（Ctrl+C）
2. 向 Agent 发送文件读取请求 → 预期：返回"本地执行节点未连接"错误
3. 重启本地组件 → 预期：自动重连成功
4. 再次发送请求 → 预期：正常工作

- [ ] **步骤 8：Commit 所有剩余改动**

```bash
git add -A
git commit -m "feat(bridge): complete MVP of Bridge local execution node"
```
