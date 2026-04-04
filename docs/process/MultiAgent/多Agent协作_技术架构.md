# 多 Agent 协作 — 技术架构

> 日期: 2026-03-12  
> 状态: Draft  
> 前置文档: [多Agent协作_设计方案.md](多Agent协作_设计方案.md)  
> 模块: javaclaw-agent, javaclaw-gateway, javaclaw-web

---

## 1. 架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│                          javaclaw-web (React)                       │
│                                                                     │
│   ChatPanel ──→ WorkflowVisualization (新增) ──→ ApprovalDialog     │
│       │              ├── DelegationCard                              │
│       │              ├── ParallelProgressBar                         │
│       │              └── HandoffIndicator                            │
│       │                                                              │
│       └── WebSocket EventFrame 订阅                                  │
└─────────────────────────────┬───────────────────────────────────────┘
                              │ WebSocket
┌─────────────────────────────▼───────────────────────────────────────┐
│                       javaclaw-gateway                               │
│                                                                      │
│   MessagePipeline ──→ AgentRuntime.dispatch()                        │
│       │                    │                                         │
│       │              ┌─────▼──────┐                                  │
│       │              │ ToolsEngine│──→ DelegateAgentTool (新增)       │
│       │              └────────────┘         │                        │
│       │                                    ▼                         │
│       │                            WorkflowEngine (新增)             │
│       │                              ├── DelegationExecutor          │
│       │                              ├── HandoffExecutor (P1)        │
│       │                              └── FanOutExecutor (P1)         │
│       │                                    │                         │
│       │                              AgentRuntime (复用)              │
│       │                                    │                         │
│   SessionManager                     WorkflowRepository (新增)       │
│       │                                                              │
│   MySQL R2DBC ←──────────────────────────────────────────────────────│
│       ├── agent           (扩展 role, goal, parent_agent_id)         │
│       ├── session         (现有)                                     │
│       ├── agent_workflow   (新增)                                     │
│       └── workflow_step    (新增)                                     │
└──────────────────────────────────────────────────────────────────────┘
```

---

## 2. 数据库 Schema

### 2.1 agent 表扩展 (V11)

```sql
-- V11__multi_agent_collaboration.sql

ALTER TABLE `agent`
    ADD COLUMN `role` VARCHAR(32) NULL
        COMMENT 'supervisor | worker | null(默认 user agent)'
        AFTER `skills_enabled`,
    ADD COLUMN `goal` TEXT NULL
        COMMENT '角色优化目标描述 (参考 CrewAI Goal)'
        AFTER `role`,
    ADD COLUMN `parent_agent_id` BIGINT NULL
        COMMENT '父 Agent ID (worker 从属于哪个 supervisor)'
        AFTER `goal`,
    ADD COLUMN `worker_agents` TEXT NULL
        COMMENT 'JSON 数组, supervisor 可调度的 worker agent 名称列表'
        AFTER `parent_agent_id`,
    ADD INDEX `idx_agent_role` (`role`),
    ADD INDEX `idx_agent_parent` (`parent_agent_id`);
```

### 2.2 agent_workflow 表 (新增)

```sql
CREATE TABLE IF NOT EXISTS `agent_workflow` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`         BIGINT       NOT NULL COMMENT '触发此工作流的用户 session',
    `supervisor_agent`   VARCHAR(128) NOT NULL COMMENT 'Supervisor agent 名称',
    `status`             VARCHAR(32)  NOT NULL DEFAULT 'running'
                         COMMENT 'running | completed | failed | cancelled',
    `trigger_message`    TEXT         NULL COMMENT '触发工作流的原始用户消息',
    `result_summary`     TEXT         NULL COMMENT '工作流最终结果摘要',
    `total_steps`        INT          NOT NULL DEFAULT 0,
    `completed_steps`    INT          NOT NULL DEFAULT 0,
    `config_json`        JSON         NULL COMMENT '工作流运行时配置 (maxParallel, nestingDepth 等)',
    `started_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `finished_at`        DATETIME     NULL,
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_workflow_session` (`session_id`),
    INDEX `idx_workflow_supervisor` (`supervisor_agent`),
    INDEX `idx_workflow_status` (`status`),
    CONSTRAINT `fk_workflow_session` FOREIGN KEY (`session_id`)
        REFERENCES `session` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='多 Agent 工作流实例';
```

### 2.3 workflow_step 表 (新增)

```sql
CREATE TABLE IF NOT EXISTS `workflow_step` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `workflow_id`        BIGINT       NOT NULL COMMENT '所属 agent_workflow.id',
    `step_type`          VARCHAR(32)  NOT NULL
                         COMMENT 'delegation | handoff | parallel_fanout',
    `agent_name`         VARCHAR(128) NOT NULL COMMENT '执行此步骤的 worker agent',
    `task_description`   TEXT         NOT NULL COMMENT 'Supervisor 下发的任务描述',
    `context_data`       TEXT         NULL COMMENT '传递给 worker 的额外上下文',
    `status`             VARCHAR(32)  NOT NULL DEFAULT 'pending'
                         COMMENT 'pending | running | completed | failed | cancelled | awaiting_approval',
    `result_text`        MEDIUMTEXT   NULL COMMENT 'Worker 执行结果',
    `result_success`     TINYINT      NULL COMMENT '1=成功, 0=失败',
    `error_message`      TEXT         NULL COMMENT '失败原因',
    `worker_session_id`  BIGINT       NULL COMMENT 'Worker 使用的临时 session.id',
    `turns_used`         INT          NULL COMMENT 'Worker 实际使用的 turn 数',
    `parallel_group_id`  VARCHAR(64)  NULL COMMENT '并行分组标识 (Fan-out 共享同一 group)',
    `nesting_depth`      INT          NOT NULL DEFAULT 1 COMMENT '委派嵌套深度',
    `duration_ms`        BIGINT       NULL COMMENT '执行耗时 (毫秒)',
    `started_at`         DATETIME     NULL,
    `finished_at`        DATETIME     NULL,
    `created_at`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_step_workflow` (`workflow_id`),
    INDEX `idx_step_status` (`status`),
    INDEX `idx_step_parallel` (`parallel_group_id`),
    CONSTRAINT `fk_step_workflow` FOREIGN KEY (`workflow_id`)
        REFERENCES `agent_workflow` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='工作流步骤 (每次委派/移交/并行产生一条记录)';
```

### 2.4 ER 图

```
agent (扩展)                    agent_workflow
  ├── id ◄─────────────┐         ├── id
  ├── name              │         ├── session_id ──→ session.id
  ├── role (新增)       │         ├── supervisor_agent
  ├── goal (新增)       │         ├── status
  ├── parent_agent_id ──┘         └── ...
  ├── worker_agents (新增)
  └── ...                       workflow_step
                                  ├── id
session                           ├── workflow_id ──→ agent_workflow.id
  ├── id ◄──────────────────────  ├── worker_session_id ──→ session.id
  ├── channel_id                  ├── agent_name
  ├── context_id                  ├── status
  └── ...                         └── ...
```

---

## 3. 核心组件

### 3.1 DelegateAgentTool

**位置**: `javaclaw-agent/src/main/java/com/atm/javaclaw/agent/tools/DelegateAgentTool.java`

Supervisor Agent 通过此工具触发委派。作为标准的 `@Tool` 注册到 ToolsEngine。

```java
@Component
public class DelegateAgentTool {

    private final WorkflowEngine workflowEngine;

    @Tool(description = """
        Delegate a sub-task to a specialist worker agent.
        The worker will execute independently and return results.
        Use this when a task requires specific expertise that another agent has.
        """)
    public String delegateAgent(
            @ToolParam(description = "Name of the worker agent to delegate to")
            String agentName,
            @ToolParam(description = "Clear description of the task for the worker")
            String task,
            @ToolParam(description = "Optional context/background for the worker", required = false)
            String context
    ) {
        // 由 WorkflowEngine 同步执行 Worker 并返回结果
        return workflowEngine.executeDelegation(agentName, task, context);
    }
}
```

**注入策略**: 在 `ToolAutoConfiguration` 中，仅当 Agent 的 `role=supervisor` 时注入。

```java
// ToolAutoConfiguration.java 片段
if ("supervisor".equals(agentRole)) {
    callbacks.add(delegateAgentTool);
    // P1: callbacks.add(handoffTool);
    // P1: callbacks.add(delegateParallelTool);
}
```

### 3.2 WorkflowEngine

**位置**: `javaclaw-agent/src/main/java/com/atm/javaclaw/agent/workflow/WorkflowEngine.java`

编排引擎，管理 Agent 间的执行流。

```java
@Service
public class WorkflowEngine {

    private final AgentRuntime agentRuntime;
    private final WorkflowRepository workflowRepository;
    private final WorkflowStepRepository stepRepository;
    private final AgentConfigService agentConfigService;
    private final SessionManager sessionManager;

    /**
     * 当前执行上下文 (ThreadLocal)，由 AgentRuntime 在执行 Supervisor 时设置。
     * DelegateAgentTool 通过此上下文获取 workflowId、sessionId 等信息。
     */
    private static final ThreadLocal<WorkflowContext> CURRENT_CONTEXT = new ThreadLocal<>();

    public static void setCurrentContext(WorkflowContext ctx) {
        CURRENT_CONTEXT.set(ctx);
    }

    public static WorkflowContext getCurrentContext() {
        return CURRENT_CONTEXT.get();
    }

    /**
     * P0: 同步委派 — Supervisor tool call 时阻塞等待 Worker 完成。
     */
    public String executeDelegation(String workerAgentName, String task, String context) {
        WorkflowContext ctx = getCurrentContext();
        validateDelegation(ctx, workerAgentName);

        // 1. 创建 workflow_step 记录
        WorkflowStepEntity step = createStep(ctx.workflowId(), "delegation",
                workerAgentName, task, context, ctx.nestingDepth());

        // 2. 为 Worker 创建临时 Session
        SessionEntity workerSession = createWorkerSession(ctx, workerAgentName);

        // 3. 解析 Worker Agent 配置
        ResolvedAgentConfig workerConfig = agentConfigService
                .resolve(workerAgentName).block();

        // 4. 构建 AgentRunRequest
        String workerPrompt = buildWorkerPrompt(task, context);
        AgentRunRequest request = new AgentRunRequest(
                workerSession.getId(),
                workerConfig.agent(),
                workerPrompt,
                List.of(),  // Worker 无历史
                workerConfig.toolsEnabled(),
                workerConfig.mcpToolsEnabled(),
                workerConfig.skillsEnabled()
        );

        // 5. 执行 Worker (阻塞等待完成)
        long startMs = System.currentTimeMillis();
        StringBuilder result = new StringBuilder();
        int[] turnsUsed = {0};
        boolean[] success = {true};

        agentRuntime.dispatch(request)
                .doOnNext(event -> {
                    if (event instanceof AgentEvent.Done done) {
                        result.append(done.fullText());
                        turnsUsed[0] = done.totalTurns();
                    } else if (event instanceof AgentEvent.Error err) {
                        result.append("Error: ").append(err.message());
                        success[0] = false;
                    }
                    // 向 Supervisor 的 WebSocket 发送嵌套事件 (DelegationProgress)
                    emitDelegationProgress(ctx, workerAgentName, event);
                })
                .blockLast();

        long durationMs = System.currentTimeMillis() - startMs;

        // 6. 更新 workflow_step
        updateStepResult(step.getId(), result.toString(),
                success[0], turnsUsed[0], durationMs, workerSession.getId());

        // 7. 结果过长则摘要
        String finalResult = truncateIfNeeded(result.toString(), 32_000);

        return finalResult;
    }

    /**
     * P1: 并行委派 — Fan-out 多个 Worker，Fan-in 汇聚结果。
     */
    public String executeParallelDelegation(List<DelegationTask> tasks) {
        WorkflowContext ctx = getCurrentContext();
        String parallelGroupId = UUID.randomUUID().toString();
        int maxParallel = ctx.maxParallel();

        List<Mono<DelegationResult>> monos = tasks.stream()
                .map(t -> Mono.fromCallable(() ->
                    executeSingleDelegation(ctx, t, parallelGroupId))
                    .subscribeOn(Schedulers.boundedElastic())
                )
                .toList();

        // 限制并发数
        List<DelegationResult> results = Flux.merge(
                Flux.fromIterable(monos), maxParallel)
                .collectList()
                .block();

        return formatParallelResults(results);
    }

    private void validateDelegation(WorkflowContext ctx, String workerAgentName) {
        if (ctx == null) {
            throw new IllegalStateException("DelegateAgentTool called outside workflow context");
        }
        if (ctx.nestingDepth() >= ctx.maxNestingDepth()) {
            throw new IllegalStateException(
                "Delegation nesting depth exceeded (max=" + ctx.maxNestingDepth() + ")");
        }
        // 检查 workerAgentName 是否在 supervisor 的 workerAgents 列表中
    }
}
```

### 3.3 WorkflowContext

**位置**: `javaclaw-agent/src/main/java/com/atm/javaclaw/agent/workflow/WorkflowContext.java`

```java
/**
 * 工作流执行上下文，在 Supervisor 执行过程中通过 ThreadLocal 传递。
 */
public record WorkflowContext(
    Long workflowId,
    Long supervisorSessionId,
    String supervisorAgentName,
    String wsSessionId,
    int nestingDepth,
    int maxNestingDepth,        // 默认 2
    int maxParallel,            // 默认 4
    int maxWorkerInvocations    // 默认 10
) {
    public WorkflowContext withIncrementedDepth() {
        return new WorkflowContext(workflowId, supervisorSessionId,
                supervisorAgentName, wsSessionId,
                nestingDepth + 1, maxNestingDepth,
                maxParallel, maxWorkerInvocations);
    }
}
```

### 3.4 AgentEvent 扩展

**位置**: `javaclaw-agent/src/main/java/com/atm/javaclaw/agent/runtime/AgentEvent.java`

```java
public sealed interface AgentEvent {

    // ... 现有事件不变 ...

    /** Supervisor 发起委派。 */
    record DelegationStart(
            String workerAgentName,
            String task,
            Long workflowStepId
    ) implements AgentEvent {}

    /** Worker 执行过程中的嵌套事件。 */
    record DelegationProgress(
            String workerAgentName,
            AgentEvent nestedEvent
    ) implements AgentEvent {}

    /** Worker 完成或失败。 */
    record DelegationResult(
            String workerAgentName,
            String result,
            boolean success,
            int turnsUsed,
            long durationMs
    ) implements AgentEvent {}

    /** Agent 发起移交 (P1)。 */
    record HandoffStart(
            String fromAgent,
            String toAgent,
            String reason
    ) implements AgentEvent {}

    /** 并行执行开始 (P1)。 */
    record ParallelStart(
            List<String> agentNames,
            String parallelGroupId
    ) implements AgentEvent {}
}
```

### 3.5 AgentRuntime 改造

在 `AgentRuntime.executeAgentLoop` 中，Supervisor Agent 执行前需设置 WorkflowContext：

```java
// AgentRuntime.java — executeAgentLoop 方法中，在 skillsMono.flatMapMany 内部

if ("supervisor".equals(request.agent().getRole())) {
    // 创建 agent_workflow 记录
    Long workflowId = workflowEngine.createWorkflow(
            request.sessionId(), request.agent().getName(),
            request.userMessage()).block();

    WorkflowContext ctx = new WorkflowContext(
            workflowId, request.sessionId(),
            request.agent().getName(), wsSessionId,
            0, 2, 4, 10);

    // 在 processToolCalls 中，执行 DelegateAgentTool 前设置 ThreadLocal
    // Mono.fromCallable(() -> { WorkflowEngine.setCurrentContext(ctx); ... })
}
```

**关键改造点**:

1. `processToolCalls` 中检测到 `delegate_agent` tool call 时，在 `Mono.fromCallable` 之前设置 `WorkflowContext`
2. 发射 `DelegationStart` 事件，然后执行工具，最后发射 `DelegationResult` 事件
3. 嵌套的 Worker 事件通过 `DelegationProgress` 包装后冒泡到 Supervisor 的事件流

### 3.6 MessagePipeline 改造

在 `mapAgentEvent` 方法中增加新事件的映射：

```java
// MessagePipeline.java — mapAgentEvent 方法

case AgentEvent.DelegationStart ds -> Flux.just(new EventFrame(
        "workflow.delegation_start",
        Map.of("workerAgent", ds.workerAgentName(),
               "task", ds.task(),
               "stepId", ds.workflowStepId(),
               "requestId", requestId),
        seqGenerator.incrementAndGet()
));

case AgentEvent.DelegationProgress dp -> Flux.just(new EventFrame(
        "workflow.delegation_progress",
        Map.of("workerAgent", dp.workerAgentName(),
               "event", serializeNestedEvent(dp.nestedEvent()),
               "requestId", requestId),
        seqGenerator.incrementAndGet()
));

case AgentEvent.DelegationResult dr -> Flux.just(new EventFrame(
        "workflow.delegation_result",
        Map.of("workerAgent", dr.workerAgentName(),
               "result", truncate(dr.result()),
               "success", dr.success(),
               "turnsUsed", dr.turnsUsed(),
               "durationMs", dr.durationMs(),
               "requestId", requestId),
        seqGenerator.incrementAndGet()
));
```

---

## 4. AgentConfigService 扩展

### 4.1 角色感知

`AgentConfigService` 需要解析新增的 role/goal 字段，并注入对应工具：

```java
// AgentConfigService.java

public Mono<ResolvedAgentConfig> resolve(String agentName) {
    return agentRepository.findByNameAndDeleted(agentName, 0)
            .map(entity -> {
                JavaClawProperties.Agent agent = mapToAgent(entity);
                String toolsEnabled = entity.getToolsEnabled();

                // Supervisor 自动注入委派工具
                if ("supervisor".equals(entity.getRole())) {
                    toolsEnabled = injectDelegationTools(toolsEnabled);
                    // 将 Worker 列表注入到 system prompt
                    String workerInfo = buildWorkerInfo(entity.getWorkerAgents());
                    agent.setAgentsMd(
                        agent.getAgentsMd() + "\n\n" + workerInfo);
                }

                return new ResolvedAgentConfig(agent, toolsEnabled,
                        entity.getMcpToolsEnabled(), entity.getSkillsEnabled());
            });
}

private String buildWorkerInfo(String workerAgentsJson) {
    List<String> workers = parseWorkerNames(workerAgentsJson);
    if (workers.isEmpty()) return "";

    StringBuilder sb = new StringBuilder();
    sb.append("## Available Worker Agents\n\n");
    sb.append("You can delegate tasks to the following specialist agents:\n\n");

    for (String workerName : workers) {
        AgentEntity worker = agentRepository
                .findByNameAndDeleted(workerName, 0).block();
        if (worker != null) {
            sb.append("- **").append(workerName).append("**");
            if (worker.getGoal() != null) {
                sb.append(": ").append(worker.getGoal());
            }
            sb.append('\n');
        }
    }

    sb.append("\nUse the `delegateAgent` tool to assign sub-tasks. ");
    sb.append("Provide a clear task description. ");
    sb.append("The worker will execute independently and return results.\n");

    return sb.toString();
}
```

### 4.2 JavaClawProperties.Agent 扩展

```java
// JavaClawProperties.Agent 新增字段

private String role;           // supervisor | worker | null
private String goal;           // 角色优化目标
private Long parentAgentId;    // 父 Agent ID
private String workerAgents;   // JSON array of worker names

public String getRole() { return role; }
public void setRole(String role) { this.role = role; }
// ... 其他 getter/setter
```

---

## 5. 实体与 Repository

### 5.1 AgentWorkflowEntity

```java
@Table("agent_workflow")
public class AgentWorkflowEntity {
    @Id private Long id;
    private Long sessionId;
    private String supervisorAgent;
    private String status;            // running, completed, failed, cancelled
    private String triggerMessage;
    private String resultSummary;
    private Integer totalSteps;
    private Integer completedSteps;
    private String configJson;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    // ... getters/setters
}
```

### 5.2 WorkflowStepEntity

```java
@Table("workflow_step")
public class WorkflowStepEntity {
    @Id private Long id;
    private Long workflowId;
    private String stepType;           // delegation, handoff, parallel_fanout
    private String agentName;
    private String taskDescription;
    private String contextData;
    private String status;             // pending, running, completed, failed, cancelled, awaiting_approval
    private String resultText;
    private Boolean resultSuccess;
    private String errorMessage;
    private Long workerSessionId;
    private Integer turnsUsed;
    private String parallelGroupId;
    private Integer nestingDepth;
    private Long durationMs;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    // ... getters/setters
}
```

### 5.3 Repository 接口

```java
public interface AgentWorkflowRepository extends ReactiveCrudRepository<AgentWorkflowEntity, Long> {
    Flux<AgentWorkflowEntity> findBySessionIdOrderByCreatedAtDesc(Long sessionId);
    Flux<AgentWorkflowEntity> findBySupervisorAgentOrderByCreatedAtDesc(String supervisorAgent);
    Flux<AgentWorkflowEntity> findByStatus(String status);
}

public interface WorkflowStepRepository extends ReactiveCrudRepository<WorkflowStepEntity, Long> {
    Flux<WorkflowStepEntity> findByWorkflowIdOrderByCreatedAtAsc(Long workflowId);
    Flux<WorkflowStepEntity> findByParallelGroupId(String parallelGroupId);
    Mono<Long> countByWorkflowIdAndStatus(Long workflowId, String status);
}
```

---

## 6. REST API

### 6.1 工作流管理 API

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/workflows` | 列出工作流（支持 ?sessionId, ?status 过滤） |
| GET | `/api/workflows/{id}` | 获取工作流详情 |
| GET | `/api/workflows/{id}/steps` | 获取工作流所有步骤 |
| POST | `/api/workflows/{id}/cancel` | 取消正在执行的工作流 |

### 6.2 Agent 扩展 API

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/agents?role=supervisor` | 按角色过滤 Agent |
| GET | `/api/agents/{id}/workers` | 获取 Supervisor 的 Worker 列表 |
| PUT | `/api/agents/{id}/workers` | 更新 Supervisor 的 Worker 列表 |

### 6.3 人工审批 API (P1)

| 方法 | 路径 | 描述 |
|------|------|------|
| POST | `/api/workflows/steps/{stepId}/approve` | 批准委派 |
| POST | `/api/workflows/steps/{stepId}/reject` | 拒绝委派 |

---

## 7. WebSocket 事件协议

### 7.1 新增 EventFrame 类型

```typescript
// 前端 EventFrame 类型定义

interface WorkflowDelegationStart {
    type: "workflow.delegation_start";
    data: {
        workerAgent: string;
        task: string;
        stepId: number;
        requestId: string;
    };
}

interface WorkflowDelegationProgress {
    type: "workflow.delegation_progress";
    data: {
        workerAgent: string;
        event: {
            type: string;  // agent.chunk | agent.tool_call | agent.tool_result | ...
            data: Record<string, unknown>;
        };
        requestId: string;
    };
}

interface WorkflowDelegationResult {
    type: "workflow.delegation_result";
    data: {
        workerAgent: string;
        result: string;
        success: boolean;
        turnsUsed: number;
        durationMs: number;
        requestId: string;
    };
}

interface WorkflowApprovalRequired {
    type: "workflow.approval_required";
    data: {
        stepId: number;
        workerAgent: string;
        task: string;
        requestId: string;
    };
}
```

### 7.2 审批请求协议 (P1)

```typescript
// 用户通过 WebSocket 发送审批决策
interface ApprovalRequest {
    method: "workflow.approve";
    params: {
        stepId: number;
        approved: boolean;
        modifiedTask?: string;  // 修改后的任务描述
    };
}
```

---

## 8. 前端组件

### 8.1 新增组件清单

| 组件 | 位置 | 描述 |
|------|------|------|
| `DelegationCard` | `src/components/workflow/DelegationCard.tsx` | 单个委派步骤的展示卡片 |
| `ParallelGroup` | `src/components/workflow/ParallelGroup.tsx` | 并行执行组的展示 |
| `WorkflowTimeline` | `src/components/workflow/WorkflowTimeline.tsx` | 工作流时间线 |
| `ApprovalDialog` | `src/components/workflow/ApprovalDialog.tsx` | 人工审批对话框 |
| `AgentRoleConfig` | `src/components/AgentRoleConfig.tsx` | Agent 角色配置面板 |
| `WorkerSelector` | `src/components/WorkerSelector.tsx` | Worker Agent 选择器 |

### 8.2 DelegationCard 设计

```tsx
interface DelegationCardProps {
    workerAgent: string;
    task: string;
    status: "running" | "completed" | "failed";
    result?: string;
    turnsUsed?: number;
    durationMs?: number;
    nestedEvents: AgentEvent[];  // Worker 执行过程的嵌套事件
}

// 展示逻辑:
// - running: 显示 Worker 名称 + 任务描述 + 实时嵌套事件流
// - completed: 显示结果摘要 + turns + 耗时
// - failed: 显示错误信息
```

### 8.3 ChatPanel 集成

在 `ChatPanel` 中，根据 EventFrame 类型插入 DelegationCard：

```tsx
// ChatPanel.tsx 中的事件处理

case "workflow.delegation_start":
    // 插入一个新的 DelegationCard (status=running)
    addDelegationCard(event.data.workerAgent, event.data.task, event.data.stepId);
    break;

case "workflow.delegation_progress":
    // 更新对应 DelegationCard 的嵌套事件流
    updateDelegationCard(event.data.workerAgent, event.data.event);
    break;

case "workflow.delegation_result":
    // 标记 DelegationCard 为 completed/failed
    completeDelegationCard(event.data.workerAgent, event.data);
    break;
```

---

## 9. 分阶段实施

### 9.1 P0 — 基础委派框架 (2-3 周)

#### Week 1: 数据层 + 核心引擎

| 任务 | 涉及文件 | 工期 |
|------|----------|------|
| DB Migration V11 | `V11__multi_agent_collaboration.sql` | 0.5d |
| AgentEntity 扩展 | `AgentEntity.java` | 0.5d |
| Workflow Entity + Repository | `AgentWorkflowEntity.java`, `WorkflowStepEntity.java`, `*Repository.java` | 1d |
| WorkflowContext record | `WorkflowContext.java` | 0.5d |
| WorkflowEngine (委派部分) | `WorkflowEngine.java` | 2d |

#### Week 2: 工具 + Runtime 集成

| 任务 | 涉及文件 | 工期 |
|------|----------|------|
| DelegateAgentTool | `DelegateAgentTool.java` | 1d |
| ToolAutoConfiguration 改造 | `ToolAutoConfiguration.java`, `ToolGroup.java` | 0.5d |
| AgentRuntime 改造 (WorkflowContext 注入) | `AgentRuntime.java` | 1d |
| AgentEvent 扩展 | `AgentEvent.java` | 0.5d |
| MessagePipeline 事件映射 | `MessagePipeline.java` | 1d |
| AgentConfigService role 感知 | `AgentConfigService.java` | 0.5d |

#### Week 3: 前端 + API + 测试

| 任务 | 涉及文件 | 工期 |
|------|----------|------|
| REST API (Workflow 管理) | `WorkflowController.java` | 1d |
| Agent 配置扩展 (角色/Worker 设置) | `AgentEditor.tsx`, `AgentRoleConfig.tsx` | 1d |
| DelegationCard 组件 | `DelegationCard.tsx` | 1d |
| ChatPanel 集成 | `ChatPanel.tsx` | 0.5d |
| 集成测试 | — | 1.5d |

#### P0 交付物

- Supervisor Agent 可以通过 `delegateAgent` tool call 委派任务给 Worker
- Worker 使用独立的 AgentRuntime loop 执行
- 前端展示委派过程（DelegationCard）
- 工作流记录持久化到 DB

---

### 9.2 P1 — 增强协作 (2-3 周)

#### Week 4: Handoff + 并行

| 任务 | 涉及文件 | 工期 |
|------|----------|------|
| HandoffTool | `HandoffTool.java` | 1d |
| HandoffExecutor (MessagePipeline 改造) | `WorkflowEngine.java`, `MessagePipeline.java` | 2d |
| FanOutExecutor (并行委派) | `DelegateParallelTool.java`, `WorkflowEngine.java` | 2d |

#### Week 5: 模板 + 审批

| 任务 | 涉及文件 | 工期 |
|------|----------|------|
| 工作流模板系统 | `WorkflowTemplate.java`, `builtin-workflows/*.json` | 1.5d |
| 人工审批节点 | `WorkflowEngine.java`, `ApprovalDialog.tsx` | 1.5d |
| 审批 WebSocket 协议 | `MessagePipeline.java`, `CommandHandler.java` | 1d |

#### Week 6: 前端可视化

| 任务 | 涉及文件 | 工期 |
|------|----------|------|
| WorkflowTimeline 组件 | `WorkflowTimeline.tsx` | 1.5d |
| ParallelGroup 组件 | `ParallelGroup.tsx` | 1d |
| WorkerSelector 组件 | `WorkerSelector.tsx` | 0.5d |
| 集成测试 + 优化 | — | 2d |

#### P1 交付物

- Agent 间 Handoff 移交
- 并行委派 (Fan-out/Fan-in) 支持
- 预置工作流模板（代码审查、研究报告、TDD）
- 人工审批节点
- 完整的工作流可视化

---

### 9.3 P2 — 高级功能 (2 周)

#### Week 7-8

| 任务 | 涉及文件 | 工期 |
|------|----------|------|
| 动态 Agent 创建 | `WorkflowEngine.java`, `AgentRepository.java` | 2d |
| 执行统计面板 | `WorkflowStatsController.java`, `WorkflowStats.tsx` | 2d |
| 工作流版本管理 | `WorkflowTemplateVersion.java` | 1d |
| 调试面板 (Step 重放) | `WorkflowDebugPanel.tsx` | 2d |
| 端到端测试 + 文档 | — | 3d |

#### P2 交付物

- Supervisor 可动态创建临时 Worker Agent
- 工作流执行统计与分析
- 工作流模板版本管理
- 调试面板支持 Step 级别重放

---

## 10. 风险与缓解

| 风险 | 严重度 | 缓解措施 |
|------|--------|----------|
| Worker 执行超时导致 Supervisor 阻塞 | 高 | Worker 有独立超时，超时后返回 error ToolResponse |
| 递归委派导致无限循环 | 高 | `nestingDepth` 限制，Worker 默认不注入 DelegateAgentTool |
| 并行 Worker 的文件写冲突 | 中 | P0 阶段串行执行，P1 阶段文档约束每个 Worker 操作不同文件 |
| token 消耗膨胀 | 中 | 结果摘要 + 上下文压缩 + 独立对话历史 |
| ThreadLocal 在 Reactor 线程切换中丢失 | 高 | 使用 `Schedulers.boundedElastic()` + 在 `Mono.fromCallable` 内设置 |
| 工作流状态不一致 (Worker 执行但 Step 未更新) | 中 | 异常时的 finally 清理 + 定时扫描 hanging workflow |

---

## 11. 监控与可观测性

### 11.1 关键指标

| 指标 | 类型 | 描述 |
|------|------|------|
| `workflow.total` | Counter | 工作流总数 |
| `workflow.active` | Gauge | 当前活跃工作流数 |
| `workflow.duration` | Histogram | 工作流总耗时 |
| `delegation.count` | Counter | 委派总次数 (按 agent 分) |
| `delegation.duration` | Histogram | 单次委派耗时 |
| `delegation.failure_rate` | Rate | 委派失败率 |
| `parallel.fanout_size` | Histogram | 并行度分布 |

### 11.2 日志规范

```
[WorkflowEngine] Workflow #{id} started: supervisor={name}, session={id}, trigger="{message}"
[WorkflowEngine] Step #{id} delegation: workflow={id}, worker={name}, task="{task}"
[WorkflowEngine] Step #{id} completed: worker={name}, success={bool}, turns={n}, duration={ms}ms
[WorkflowEngine] Workflow #{id} finished: status={status}, steps={completed}/{total}, duration={ms}ms
```

---

## 附录 A: 文件变更清单 (P0)

### 新增文件

| 文件 | 模块 | 描述 |
|------|------|------|
| `V11__multi_agent_collaboration.sql` | gateway/resources/db/migration | DB 迁移 |
| `AgentWorkflowEntity.java` | gateway/entity | 工作流实体 |
| `WorkflowStepEntity.java` | gateway/entity | 步骤实体 |
| `AgentWorkflowRepository.java` | gateway/repository | 工作流 Repository |
| `WorkflowStepRepository.java` | gateway/repository | 步骤 Repository |
| `WorkflowEngine.java` | agent/workflow | 编排引擎 |
| `WorkflowContext.java` | agent/workflow | 执行上下文 |
| `DelegateAgentTool.java` | agent/tools | 委派工具 |
| `WorkflowController.java` | gateway/http | REST 控制器 |
| `DelegationCard.tsx` | web/components/workflow | 前端组件 |
| `AgentRoleConfig.tsx` | web/components | 角色配置 |

### 修改文件

| 文件 | 模块 | 改动 |
|------|------|------|
| `AgentEntity.java` | gateway/entity | 新增 role, goal, parentAgentId, workerAgents 字段 |
| `AgentEvent.java` | agent/runtime | 新增 DelegationStart/Progress/Result 事件 |
| `AgentRuntime.java` | agent/runtime | Supervisor 执行时注入 WorkflowContext |
| `AgentRunRequest.java` | agent/runtime | 可选: 新增 role 字段 |
| `ToolAutoConfiguration.java` | agent/tools | 条件注入 DelegateAgentTool |
| `ToolGroup.java` | agent/tools | 新增 WORKFLOW group |
| `MessagePipeline.java` | gateway/pipeline | 映射新事件类型 |
| `AgentConfigService.java` | gateway/config | 角色感知、Worker 列表注入 |
| `ChatPanel.tsx` | web/components | 集成 DelegationCard |
| `AgentEditor.tsx` | web/components | 角色配置 UI |
| `api.ts` | web/lib | 新增 Workflow API 调用 |

---

## 附录 B: 与现有 Session 隔离策略的关系

当前 Session 隔离使用 `contextId = baseContextId + "::" + agentName`。

多 Agent 协作中，Worker 使用**临时子 Session**：

```
Supervisor Session:  contextId = "ws123::supervisor-agent"
Worker Session:      contextId = "ws123::supervisor-agent::workflow-{id}::code-reviewer"
```

- Worker Session 的 `channel_id` = `"workflow"`（区分于用户直接对话）
- Worker Session 的 `context_type` = `"delegation"`
- 工作流完成后，Worker Session 可被归档（设置 `deleted=1`）
- 查询历史时，默认排除 `channel_id="workflow"` 的 Session
