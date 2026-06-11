# Plan 模式重构设计规格

> 日期：2026-06-11
> 状态：草案
> 范围：Plan 模式全面重构——从独立面板模式迁移到内联对话流模式

## 1. 目标

将 Plan 模式从「侧边面板 + 独立数据表 + 复杂协议」重构为「内联对话流 + 消息内存储 + 精简协议」，对标 Cursor Plan Mode 的交互体验。

### 核心原则

- Plan 是对话的一部分，不是独立实体
- 以 checklist 形式内联展示，步骤完成后自动打勾
- 审批通过内联按钮完成，不打断对话节奏
- 只保留「取消」「暂停/继续」控制，复杂修改通过自然语言对话
- Agent 自动判断 + `/plan` 命令双触发
- 工具调用在对话中正常流式输出，与 checklist 独立展示

## 2. 数据模型

### 2.1 存储方式

Plan 作为一种特殊类型的消息存储在 `transcript_message` 表中，利用已有的 `metadata_json` 列存储 plan 结构化数据。

**transcript_message 中的 plan 消息**：

| 字段 | 值 | 说明 |
|------|-----|------|
| `role` | `assistant` | Plan 由 Agent 创建 |
| `content` | plan 标题 | 如「重构用户模块」|
| `metadata_json` | plan 结构化数据 | 见下方 JSON 结构 |
| `tool_call_id` | null | |
| `tool_name` | null | |
| `plan_id` | null | 废弃字段 |

**metadata_json 结构**：

```json
{
  "type": "plan",
  "plan": {
    "status": "draft",
    "steps": [
      {
        "index": 0,
        "title": "分析现有代码结构",
        "description": "阅读 src/models/User.java 和 src/services/UserService.java，梳理现有字段和方法依赖",
        "verification": "输出字段清单和依赖关系图，确认无遗漏",
        "status": "pending",
        "resultSummary": null
      },
      {
        "index": 1,
        "title": "设计新数据模型",
        "description": "根据需求在 src/models/ 下创建 UserProfile.java，添加 avatar、bio、preferences 字段，编写 Flyway 迁移脚本",
        "verification": "运行 mvn compile 编译通过，Flyway 迁移脚本语法正确",
        "status": "pending",
        "resultSummary": null
      }
    ],
    "completionSummary": null
  }
}
```

**Plan Status 状态机**：

```
draft → approved → executing ⇄ paused
                ↘ completed
从任意非终态 → cancelled
```

- `draft`：Plan 已创建，等待用户审批
- `approved`：用户已批准，等待开始执行
- `executing`：正在执行中
- `paused`：用户暂停
- `completed`：所有步骤完成
- `cancelled`：用户取消

**Step Status**：`pending` | `in_progress` | `completed` | `failed` | `skipped`

### 2.2 约束

- 一个会话中同时只能有一个活跃 plan（status 不是终态的 plan）
- Plan 消息的 `id`（transcript_message.id）即 plan 的唯一标识（下文称 `messageId`）
- Plan 最大步骤数：20（沿用现有配置 `plan-max-steps`）

### 2.3 数据库迁移（V39）

```sql
-- 1. 确认 transcript_message.metadata_json 列已存在（V1 已创建，无需变更）

-- 2. 迁移现有 plan 数据到 transcript_message
--    将 plan + plan_step 数据转换为 plan 类型的 transcript_message
INSERT INTO transcript_message (session_id, role, content, metadata_json, created_at)
SELECT
    p.session_id,
    'assistant',
    p.title,
    JSON_OBJECT(
        'type', 'plan',
        'plan', JSON_OBJECT(
            'status', p.status,
            'completionSummary', p.completion_summary,
            'steps', (
                SELECT JSON_ARRAYAGG(
                    JSON_OBJECT(
                        'index', ps.step_index,
                        'title', ps.title,
                        'description', ps.description,
                        'status', ps.status,
                        'resultSummary', ps.result_summary
                    ) ORDER BY ps.step_index
                )
                FROM plan_step ps WHERE ps.plan_id = p.id
            )
        )
    ),
    p.created_at
FROM plan p;

-- 3. 删除 transcript_message.plan_id 索引和列
DROP INDEX idx_transcript_plan ON transcript_message;
ALTER TABLE transcript_message DROP COLUMN plan_id;

-- 4. 标记旧表为废弃（暂不删除，保留回退能力）
--    先重命名子表（有外键引用 plan），再重命名父表
RENAME TABLE plan_step TO _deprecated_plan_step;
RENAME TABLE plan TO _deprecated_plan;
```

## 3. Agent 工具层

### 3.1 新工具：`plan`

替换现有的 `WritePlanTool` + `UpdatePlanTool`，合并为一个工具，支持 3 个 action。

**工具定义**：

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `action` | string | 是 | `create` / `step_done` / `complete` |
| `title` | string | create 时必填 | Plan 标题 |
| `steps` | array | create 时必填 | 步骤列表 `[{title, description, verification}]` |
| `stepIndex` | int | step_done 时必填 | 完成的步骤索引 |
| `resultSummary` | string | step_done 时可选 | 步骤完成摘要 |
| `summary` | string | complete 时可选 | Plan 完成总结 |

**调用示例**：

```json
// 创建 plan
{ "action": "create", "title": "重构用户模块", "steps": [
  { "title": "分析现有代码结构", "description": "阅读 src/models/User.java 和 src/services/UserService.java，梳理现有字段和方法依赖", "verification": "输出字段清单和依赖关系图，确认无遗漏" },
  { "title": "设计新数据模型", "description": "根据需求创建 UserProfile.java，添加 avatar、bio 等字段，编写 Flyway 迁移", "verification": "运行 mvn compile 编译通过" }
]}

// 标记步骤完成
{ "action": "step_done", "stepIndex": 0, "resultSummary": "已完成需求分析" }

// 完成 plan
{ "action": "complete", "summary": "所有步骤已完成，模块重构成功" }
```

### 3.2 PlanOperations SPI

简化 SPI 接口，只保留 3 个方法：

```java
public interface PlanOperations {
    Mono<PlanResult> createPlan(long sessionId, String title, List<PlanStep> steps);
    Mono<PlanResult> updateStep(long messageId, int stepIndex, String status, String resultSummary);
    Mono<PlanResult> completePlan(long messageId, String summary);
}
```

`PlanResult` 包含 `messageId` 和操作是否成功的信息。

### 3.3 PlanTool 实现

```java
public class PlanTool implements AgentTool {
    private final PlanOperations planOperations;

    // 工具名称
    public String name() { return "plan"; }

    // 执行逻辑根据 action 分发
    public Mono<String> execute(Map<String, Object> params, ToolContext context) {
        String action = (String) params.get("action");
        return switch (action) {
            case "create" -> handleCreate(params, context);
            case "step_done" -> handleStepDone(params, context);
            case "complete" -> handleComplete(params, context);
            default -> Mono.error(...);
        };
    }
}
```

### 3.4 Agent Prompt

简化 `plan-system.md`，核心指令：

```markdown
## 计划模式

当任务涉及 3 个或以上步骤时，你应该先创建计划：

1. 调用 `plan` 工具创建计划：`plan({ action: "create", title: "...", steps: [...] })`
2. 创建后**停止执行并等待用户审批**。不要调用任何其他工具。
3. 用户批准后，按顺序执行每个步骤。
4. 完成每个步骤的实施后，**必须执行该步骤的验证**。验证通过后才能调用 `plan({ action: "step_done", stepIndex: N })`。
5. 如果验证失败，修复问题后重新验证，不要跳过继续执行。
6. 所有步骤完成后调用：`plan({ action: "complete" })`

### 步骤内容要求

每个步骤必须包含完整的执行细节，同时对核心部分着重描述：
- **标题**：简洁的任务名称（如「配置数据库连接」）
- **描述**：列出这一步涉及的所有操作（文件、命令、配置等），对核心操作和关键决策进行详细说明，次要操作可以简要提及。

好的描述（完整 + 核心突出）：
「修改 src/config/database.ts，**核心：配置连接池参数（minPool=5, maxPool=20）并启用读写分离**，同时添加健康检查端点；更新 .env.example 添加 DB_REPLICA_HOST 变量」

差的描述（无主次，平铺直叙）：
「打开 database.ts，导入 pg 库，配置 host，配置 port，配置 username...」

差的描述（过于模糊）：
「配置数据库」

每个步骤必须包含验证方式——完成后如何确认这一步是成功的。

步骤数量建议 3-10 个，每个步骤应该是一个可独立验证的工作单元。
执行时，每完成一个步骤必须执行验证，验证通过后才能继续下一步。
如果验证失败，应当修复问题后重新验证，而不是跳过继续执行。
```

`forcePlan` 注入保持不变——当用户使用 `/plan` 命令时，额外注入：
```
**你必须先调用 `plan` 工具创建计划，等待用户审批后再执行。**
```

### 3.5 删除的组件

| 组件 | 原因 |
|------|------|
| `WritePlanTool.java` | 被 `PlanTool` 替代 |
| `UpdatePlanTool.java` | 被 `PlanTool` 替代 |
| `PlanStepTracker.java` | 不再需要自动推断步骤，prompt 要求 agent 显式标记 |
| `PlanEventExtractor.java` | 不再需要从工具结果中解析事件 |
| `PlanExecutionAssessment.java` | 简化为 prompt 注入 |
| `write-plan-description.md` | 被新的 prompt 替代 |

## 4. Gateway 编排层

### 4.1 新增：InlinePlanService

直接操作 `TranscriptMessageRepository`，管理 plan 消息的生命周期。

```java
@Service
public class InlinePlanService {

    // 创建 plan 消息
    public Mono<TranscriptMessageEntity> createPlanMessage(
            long sessionId, String title, List<PlanStepData> steps) {
        // 1. 检查会话中没有活跃 plan
        // 2. 创建 transcript_message，role=assistant, content=title
        // 3. 构建 metadata_json（type=plan, status=draft, steps）
        // 4. 保存并返回
    }

    // 更新步骤状态
    public Mono<Void> updateStepStatus(
            long messageId, int stepIndex, String status, String resultSummary) {
        // 1. 读取消息的 metadata_json
        // 2. 更新指定步骤的 status 和 resultSummary
        // 3. 如果是第一个步骤变为 in_progress，同时更新 plan status 为 executing
        // 4. 保存更新后的 metadata_json
    }

    // 更新 plan 状态
    public Mono<Void> updatePlanStatus(long messageId, String newStatus) {
        // 1. 读取当前状态
        // 2. 验证状态转换合法性
        // 3. 更新 metadata_json 中的 status
    }

    // 完成 plan
    public Mono<Void> completePlan(long messageId, String summary) {
        // 1. 更新 plan status 为 completed
        // 2. 设置 completionSummary
        // 3. 将所有未完成步骤标记为 skipped
    }

    // 查找活跃 plan
    public Mono<TranscriptMessageEntity> getActivePlan(long sessionId) {
        // 查询 session 中 metadata_json->>'$.type' = 'plan'
        // 且 status 不是终态的消息
    }

    // 自动修复：agent 执行完毕后同步 plan 状态
    public Mono<Void> syncPlanAfterExecution(long sessionId) {
        // 如果有活跃 plan 且所有步骤已完成，自动标记 plan 为 completed
        // 如果有 in_progress 步骤但 agent 已停止，标记为 failed
    }
}
```

### 4.2 修改：MessagePipeline

简化 plan 请求路由，只处理 4 个方法：

```java
// 替换现有的 plan.* 路由逻辑
if (request.method() != null && request.method().startsWith("plan.")) {
    return handlePlanAction(request, wsSessionId);
}

private Mono<Void> handlePlanAction(RequestFrame request, String wsSessionId) {
    long messageId = request.params().get("messageId");
    return switch (request.method()) {
        case "plan.approve" -> {
            boolean approved = request.params().get("approved");
            if (approved) {
                inlinePlanService.updatePlanStatus(messageId, "approved")
                    .then(triggerPlanExecution(messageId, wsSessionId));
            } else {
                inlinePlanService.updatePlanStatus(messageId, "cancelled");
            }
        }
        case "plan.pause" -> inlinePlanService.updatePlanStatus(messageId, "paused")
                .then(signalAgentPause(wsSessionId));
        case "plan.resume" -> inlinePlanService.updatePlanStatus(messageId, "executing")
                .then(triggerPlanExecution(messageId, wsSessionId));
        case "plan.cancel" -> inlinePlanService.updatePlanStatus(messageId, "cancelled")
                .then(signalAgentCancel(wsSessionId));
        default -> Mono.error(...);
    };
}
```

### 4.3 修改：AgentEventMapper

PlanTool 执行后产生 AgentEvent，映射为 WebSocket 事件：

| AgentEvent | WebSocket 事件 | 数据 |
|------------|---------------|------|
| `PlanCreated` | `plan.created` | 完整 plan message 数据 |
| `PlanStepUpdated` | `plan.step_updated` | `messageId`, `stepIndex`, `status`, `resultSummary` |
| `PlanStatusChanged` | `plan.status_changed` | `messageId`, `status` |
| `PlanCompleted` | `plan.completed` | `messageId`, `summary` |

### 4.4 Plan 执行上下文注入

替代 `PlanExecutionOrchestrator`，在 `AgentPromptBuilder` 中注入简化的上下文：

```markdown
## 当前计划

标题：{title}
进度：第 {completedCount}/{totalCount} 步
下一步：{nextStepTitle} - {nextStepDescription}
验证条件：{nextStepVerification}

请继续执行下一步。完成实施后，执行验证条件确认结果正确，验证通过后调用 plan({ action: "step_done", stepIndex: {nextStepIndex} })。
```

### 4.5 删除的组件

| 组件 | 原因 |
|------|------|
| `PlanService.java` | 被 `InlinePlanService` 替代 |
| `PlanRequestHandler.java` | 逻辑合并到 `MessagePipeline` |
| `PlanExecutionOrchestrator.java` | 简化为 prompt 注入 |
| `PlanController.java` | 不再需要独立 REST API |
| `PlanEntity.java` | 不再有独立表 |
| `PlanStepEntity.java` | 不再有独立表 |
| `PlanDTO.java` / `PlanStepDTO.java` | 不再需要独立 DTO |
| `PlanRepository.java` / `PlanStepRepository.java` | 不再需要独立 Repository |
| `PlanOperationsImpl.java` | 被新的 `InlinePlanOperationsImpl` 替代 |

## 5. WebSocket 协议

### 5.1 客户端 → 服务端（Request）

| 方法 | 参数 | 说明 |
|------|------|------|
| `plan.approve` | `messageId: number`, `approved: boolean` | 审批或拒绝 plan |
| `plan.pause` | `messageId: number` | 暂停执行 |
| `plan.resume` | `messageId: number` | 恢复执行 |
| `plan.cancel` | `messageId: number` | 取消 plan |

**删除的方法**（9 → 4）：
- `plan.approve_and_execute` — 不再需要复合操作
- `plan.skip_step` — 通过自然语言对话实现
- `plan.modify_step` — 通过自然语言对话实现
- `plan.add_step` — 通过自然语言对话实现
- `plan.reorder_steps` — 通过自然语言对话实现

### 5.2 服务端 → 客户端（Event）

| 事件 | 数据 | 说明 |
|------|------|------|
| `plan.created` | `{ messageId, title, status, steps }` | Plan 创建，前端渲染 checklist |
| `plan.step_updated` | `{ messageId, stepIndex, status, resultSummary }` | 步骤状态变更 |
| `plan.status_changed` | `{ messageId, status }` | Plan 整体状态变更 |
| `plan.completed` | `{ messageId, summary }` | Plan 完成 |

**删除的事件**（7 → 4）：
- `plan.awaiting_approval` — 由 `plan.created`（status=draft）隐含
- `plan.step_start` — 合并到 `plan.step_updated`（status=in_progress）
- `plan.step_done` — 合并到 `plan.step_updated`（status=completed）
- `plan.adjusted` — 不再支持客户端直接调整

### 5.3 标识符变更

所有方法/事件使用 `messageId`（transcript_message.id）而非 `planId`。

## 6. 前端架构

### 6.1 新增：PlanMessage 组件

内联 plan 消息组件，替代侧边面板。

**视觉设计**：

```
┌──────────────────────────────────────────────────────────┐
│ 📋 重构用户模块                                          │
│                                                          │
│ ✅ 1. 分析现有代码结构                                    │
│      阅读 src/models/User.java 和 UserService.java，     │
│      梳理现有字段和方法依赖                               │
│                                                          │
│ ⏳ 2. 设计新数据模型  ← 当前步骤（脉冲动画）              │
│      在 src/models/ 下创建 UserProfile.java，             │
│      添加 avatar、bio、preferences 字段                   │
│                                                          │
│ ○  3. 实现 API 接口                                      │
│      创建 UserProfileController，添加 GET/PUT 端点        │
│                                                          │
│ ○  4. 编写单元测试                                       │
│      为 UserProfileService 编写测试，覆盖 CRUD 场景       │
│                                                          │
│         [执行]  [拒绝]     ← draft 状态                   │
│ ── 或 ──                                                 │
│         [暂停]  [取消]     ← executing                    │
│ ── 或 ──                                                 │
│         [继续]  [取消]     ← paused                       │
│ ── 或 ──                                                 │
│ ✅ 计划已完成              ← completed                    │
└──────────────────────────────────────────────────────────┘
```

**步骤描述展示**：
- draft/executing 状态下，默认展开所有步骤的描述
- completed/cancelled 状态下，描述默认折叠（点击步骤可展开）
- 描述文字使用较小的灰色字体，与标题形成层次区分

**状态与按钮映射**：

| Plan Status | 显示按钮 | 视觉特征 |
|-------------|---------|----------|
| `draft` | 「执行」（主按钮）+「拒绝」 | 步骤列表 + 等待审批提示 |
| `approved` | 无（过渡状态） | |
| `executing` | 「暂停」+「取消」 | 当前步骤有加载动画 |
| `paused` | 「继续」+「取消」 | 暂停标识 |
| `completed` | 无 | 所有步骤打勾 + 完成摘要 |
| `cancelled` | 无 | 取消标识 + 已完成步骤保留 |

**步骤状态图标**：

| Step Status | 图标 |
|-------------|------|
| `pending` | ○ 空心圆 |
| `in_progress` | ⏳ 加载动画（spinner） |
| `completed` | ✅ 绿色勾 |
| `failed` | ❌ 红色叉 |
| `skipped` | ⊘ 跳过标记 |

### 6.2 简化：planStore

```typescript
interface PlanState {
  // 当前会话的活跃 plan message ID
  activePlanMessageId: number | null;
  // 活跃 plan 的状态（用于按钮渲染判断）
  activePlanStatus: PlanStatus | null;

  // 事件处理
  handlePlanCreated(data: { messageId: number; title: string; status: string; steps: PlanStepData[] }): void;
  handleStepUpdated(data: { messageId: number; stepIndex: number; status: string; resultSummary?: string }): void;
  handleStatusChanged(data: { messageId: number; status: string }): void;
  handlePlanCompleted(data: { messageId: number; summary?: string }): void;

  clearActivePlan(): void;
}
```

Plan 步骤的实际数据存储在 `chatStore` 的消息列表中（作为消息的 metadata），`planStore` 只维护活跃状态的引用。

### 6.3 修改：MessageBubble / ChatMessageList

在消息渲染逻辑中，识别 plan 类型消息：

```typescript
function renderMessage(message: TranscriptMessage) {
  if (message.metadata?.type === 'plan') {
    return <PlanMessage message={message} />;
  }
  return <MessageBubble message={message} />;
}
```

### 6.4 修改：useWebSocket

```typescript
// 简化的 plan 事件处理
case 'plan.created':
  planStore.handlePlanCreated(event.data);
  chatStore.addMessage(toPlanMessage(event.data));
  break;
case 'plan.step_updated':
  planStore.handleStepUpdated(event.data);
  chatStore.updateMessageMetadata(event.data.messageId, updateStep(event.data));
  break;
case 'plan.status_changed':
  planStore.handleStatusChanged(event.data);
  chatStore.updateMessageMetadata(event.data.messageId, updateStatus(event.data));
  break;
case 'plan.completed':
  planStore.handlePlanCompleted(event.data);
  chatStore.updateMessageMetadata(event.data.messageId, completePlan(event.data));
  break;
```

提供简化的 action 发送方法：

```typescript
function sendPlanAction(method: string, messageId: number) {
  const frame = createRequest(method, { messageId });
  ws.send(JSON.stringify(frame));
}
```

### 6.5 修改：protocol.ts

精简 plan 相关协议方法：

```typescript
export function createPlanApprove(messageId: number, approved: boolean): RequestFrame {
  return createRequest('plan.approve', { messageId, approved });
}

export function createPlanPause(messageId: number): RequestFrame {
  return createRequest('plan.pause', { messageId });
}

export function createPlanResume(messageId: number): RequestFrame {
  return createRequest('plan.resume', { messageId });
}

export function createPlanCancel(messageId: number): RequestFrame {
  return createRequest('plan.cancel', { messageId });
}
```

删除：`createPlanApproveAndExecute`、`createPlanSkipStep`、`createPlanModifyStep`、`createPlanAddStep`、`createPlanReorderSteps`。

### 6.6 修改：App.tsx

- 移除 `showPlanPanel`、`planPanelCollapsed` 状态
- 移除 PlanPanel 的条件渲染和侧边栏挂载
- 聊天区域恢复全宽显示

### 6.7 修改：HistoryPage.tsx

- 「任务」tab 简化或移除
- Plan 历史不再需要独立查询——它们是对话历史中的特殊消息
- 如果保留「任务」tab，改为根据 `metadata_json->>'$.type' = 'plan'` 过滤消息

### 6.8 删除的组件

| 组件 | 原因 |
|------|------|
| `PlanPanel.tsx` | 被 `PlanMessage.tsx` 内联组件替代 |
| `PlanStepCard.tsx` | 步骤渲染内联到 `PlanMessage` 中 |
| `PlanView.tsx` | 未使用，直接删除 |
| `PlanHistoryTab.tsx` | 未使用，直接删除 |

## 7. REST API 变更

### 7.1 删除的 API

| 方法 | 路径 | 原因 |
|------|------|------|
| GET | `/api/plans` | Plan 不再是独立实体 |
| GET | `/api/plans/{planId}` | 通过消息 ID 查询 |
| GET | `/api/plans/{planId}/steps` | 步骤在消息 metadata 中 |
| DELETE | `/api/plans/{planId}` | 不再需要 |
| DELETE | `/api/plans/batch` | 不再需要 |

### 7.2 新增 API（可选）

如果需要查询 plan 历史，可通过现有消息查询 API 添加 `type=plan` 过滤参数：

```
GET /api/sessions/{sessionId}/messages?type=plan
```

## 8. 配置变更

保留的配置项：
- `plan-max-steps: 20` — 最大步骤数

删除的配置项：
- `plan-step-timeout-seconds: 120` — 不再需要单步超时
- `plan-approval-timeout-seconds: 600` — 不再需要审批超时

## 9. 内存系统适配

### 9.1 ImportanceAssessor

保留 plan 上下文的记忆重要性评估，但简化输入：
- 从 `InlinePlanService.getActivePlan()` 获取当前 plan 上下文
- 不再依赖 `PlanExecutionAssessment` 类

### 9.2 Plan 完成时的记忆提取

Plan 完成后，仍然提取 procedural + episodic 记忆。触发点从 `PlanExecutionOrchestrator` 移到 `InlinePlanService.completePlan()` 中。

## 10. 迁移策略

### 10.1 实施顺序

1. **数据库迁移**：V39 脚本，迁移数据 + 废弃旧表
2. **后端核心**：`InlinePlanService` + `PlanTool` + `PlanOperations` SPI
3. **后端集成**：`MessagePipeline` + `AgentEventMapper` + `AgentPromptBuilder` 修改
4. **前端核心**：`PlanMessage` 组件 + `planStore` 简化
5. **前端集成**：`useWebSocket` + `protocol.ts` + `App.tsx` 修改
6. **清理**：删除旧代码，更新文档
7. **测试**：单元测试 + 端到端测试

### 10.2 回退方案

- 旧表以 `_deprecated_` 前缀保留 30 天
- 如果新模式有严重问题，可通过逆向迁移恢复

### 10.3 测试要点

| 场景 | 验证内容 |
|------|---------|
| Plan 创建 | Agent 调用 plan(create) → 消息保存 → 前端渲染 checklist |
| 用户审批 | 点击「执行」→ plan status 变为 approved → agent 继续 |
| 用户拒绝 | 点击「拒绝」→ plan status 变为 cancelled |
| 步骤执行 | Agent 调用 plan(step_done) → 步骤打勾 → 前端实时更新 |
| 暂停/恢复 | 点击暂停 → agent 停止 → 点击继续 → agent 恢复 |
| 取消 | 执行中取消 → agent 停止 → plan 标记 cancelled |
| Plan 完成 | 所有步骤完成 → plan 自动 completed → 记忆提取 |
| 自然语言修改 | 执行中用户说「修改第 3 步」→ agent 理解并调整 |
| 对话历史恢复 | 刷新页面 → plan 消息从历史中恢复 → 显示正确状态 |
| `/plan` 命令 | 用户使用命令 → forcePlan 注入 → agent 必须创建 plan |
| 并发安全 | 同一会话不能创建多个活跃 plan |

## 11. 影响范围总结

### 后端文件变更

| 操作 | 文件 |
|------|------|
| 新增 | `InlinePlanService.java`, `PlanTool.java`, `InlinePlanOperationsImpl.java`, `V39 迁移脚本` |
| 修改 | `MessagePipeline.java`, `AgentEventMapper.java`, `AgentPromptBuilder.java`, `AgentEvent.java`, `AgentRunRequest.java`, `AgentLoopExecutor.java`, `AgentRuntime.java`, `ToolsEngine.java`, `CommandHandler.java`, `ImportanceAssessor.java` |
| 删除 | `WritePlanTool.java`, `UpdatePlanTool.java`, `PlanStepTracker.java`, `PlanEventExtractor.java`, `PlanExecutionAssessment.java`, `PlanService.java`, `PlanRequestHandler.java`, `PlanExecutionOrchestrator.java`, `PlanController.java`, `PlanEntity.java`, `PlanStepEntity.java`, `PlanDTO.java`, `PlanStepDTO.java`, `PlanRepository.java`, `PlanStepRepository.java`, `PlanOperationsImpl.java` |

### 前端文件变更

| 操作 | 文件 |
|------|------|
| 新增 | `PlanMessage.tsx` |
| 修改 | `planStore.ts`, `useWebSocket.ts`, `protocol.ts`, `App.tsx`, `MessageBubble.tsx`, `HistoryPage.tsx`, `chatStore.ts` |
| 删除 | `PlanPanel.tsx`, `PlanStepCard.tsx`, `PlanView.tsx`, `PlanHistoryTab.tsx` |

### Prompt 文件变更

| 操作 | 文件 |
|------|------|
| 重写 | `plan-system.md` |
| 删除 | `write-plan-description.md`, `plan-execution-context.md` |

### 测试文件变更

| 操作 | 文件 |
|------|------|
| 新增 | `InlinePlanServiceTest.java` |
| 修改 | `MessagePipelineApproveExecuteTest.java`, `ImportanceAssessorTest.java` |
| 删除 | `PlanServiceTest.java` |

### 文档更新

| 文件 | 变更 |
|------|------|
| `docs/project/plan-system.md` | 重写，反映新架构 |
| `docs/project/frontend-client.md` | 更新 plan 相关部分 |
| `docs/project/gateway-system.md` | 更新 plan 路由部分 |
| `docs/project/tool-system.md` | 更新工具列表 |
