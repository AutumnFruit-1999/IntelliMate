# 计划模式系统

## 概述

计划模式是 IntelliMate 的核心交互范式之一。当用户提出复杂的多步骤任务时，Agent 不会直接开始执行，而是先创建一份结构化的执行计划，经用户审批后再逐步执行。整个过程中用户可以暂停、恢复或取消计划，保持对执行过程的控制。

重构后的计划模式将计划作为对话流中的一条内联消息展示（checklist 形式），不再使用独立侧边面板或独立数据库表。计划数据存储在 `transcript_message.metadata_json` 中，由 `InlinePlanService` 管理生命周期，前端通过 `PlanMessage` 组件渲染。

## 数据模型

### 存储方式

计划作为一种特殊类型的消息存储在 `transcript_message` 表中，利用 `metadata_json` 列保存结构化数据。

| 字段 | 值 | 说明 |
|------|-----|------|
| `role` | `assistant` | 计划由 Agent 创建 |
| `content` | 计划标题 | 如「重构用户模块」 |
| `metadata_json` | plan 结构化数据 | 见下方 JSON 结构 |
| `tool_call_id` | null | |
| `tool_name` | null | |

计划的唯一标识是 `transcript_message.id`，下文称 `messageId`。所有 WebSocket 操作和 Agent 工具调用均使用 `messageId`，不再使用独立的 `planId`。

### metadata_json 结构

```json
{
  "type": "plan",
  "plan": {
    "status": "draft",
    "steps": [
      {
        "index": 0,
        "title": "分析现有代码结构",
        "description": "阅读 src/models/User.java 和 UserService.java，梳理现有字段和方法依赖",
        "verification": "输出字段清单和依赖关系图，确认无遗漏",
        "status": "pending",
        "resultSummary": null
      }
    ],
    "completionSummary": null
  }
}
```

每个步骤包含 `verification` 字段，描述完成该步骤后如何验证结果正确。Agent 必须在验证通过后才可调用 `plan({ action: "step_done" })` 标记步骤完成。

### 约束

- 一个会话中同时只能有一个活跃计划（status 不是终态）
- 计划最大步骤数：20（配置项 `plan-max-steps`）
- 旧版 `plan` / `plan_step` 表及 `transcript_message.plan_id` 列已废弃（V39 迁移后重命名为 `_deprecated_plan` / `_deprecated_plan_step`）

## 计划的生命周期

### 状态流转

```
                    用户审批
[新建] → draft ──────────────→ approved
            │                      │
            │ 用户拒绝               │ 开始执行
            ↓                      ↓
         cancelled            executing
                                  │
                            用户暂停
                                  ↓
                               paused
                                  │
                              用户恢复
                                  ↓
                             executing
                                  │
                           全部步骤完成
                                  ↓
                             completed
```

任何非终态都可以被用户取消（`cancelled`）。取消时所有 `pending` 和 `in_progress` 步骤标记为 `skipped`。

### 步骤状态

`pending` | `in_progress` | `completed` | `failed` | `skipped`

### 关键状态转换规则

- `draft` → `approved`：用户点击「执行」审批通过
- `draft` → `cancelled`：用户点击「拒绝」
- `approved` → `executing`：审批后自动触发 Agent 执行（发送「开始执行计划」消息）
- `executing` ⇄ `paused`：用户暂停 / 恢复
- 任意非终态 → `cancelled`：用户取消
- `executing` → `completed`：Agent 调用 `plan({ action: "complete" })` 或所有步骤已完成

## Agent 工具：`plan`

单一的 `plan` 工具（`PlanTool`）替代了旧版的 `writePlan` 和 `updatePlan`，支持三个 action：

| action | 必填参数 | 说明 |
|--------|----------|------|
| `create` | `title`, `steps[]` | 创建计划。每步需 `title`、`description`、`verification` |
| `step_done` | `stepIndex` | 标记步骤完成，可选 `resultSummary` |
| `complete` | — | 完成整个计划，可选 `summary` |

工具内置宽松的 JSON 解析（支持未加引号字段名、单引号、Markdown 代码块提取等），应对 LLM 格式不规范的输出。

创建流程：

1. 从 `AgentSessionContext` 获取 `sessionId`
2. 解析并校验步骤（含 verification 字段）
3. 调用 `PlanOperations.createPlan` → `InlinePlanService.createPlanMessage` 持久化
4. 返回 `{ messageId, status, message }`
5. AgentRuntime 发出 `PlanCreated` 事件，映射为 WebSocket `plan.created`

创建完成后 Agent 必须停止执行并等待用户审批。步骤完成前 Agent 应先执行 `verification` 中描述的验证，验证通过后再调用 `step_done`。

## 计划执行

### 执行触发

用户在前端 PlanMessage 组件点击「执行」后：

1. 发送 `plan.approve`（`messageId`, `approved: true`）
2. 服务端将状态更新为 `approved`，推送 `plan.status_changed`
3. 服务端自动发送「开始执行计划」消息，触发 Agent 运行

### MessagePipeline 的角色

`MessagePipeline` 是计划操作的唯一入口，直接处理 `plan.*` WebSocket 请求，不再经过 `PlanRequestHandler` 或 `PlanExecutionOrchestrator`。

处理聊天消息时：

1. 通过 `InlinePlanService.getActivePlan(sessionId)` 查询活跃计划
2. 若计划处于 `executing` 或 `approved` 状态，将 `messageId` 作为 `activePlanMessageId` 传入 Agent 请求
3. 调用 `InlinePlanService.buildPlanContext(messageId)` 构建计划上下文并注入系统提示词
4. 通过 `MessageConverter.loadHistory` 加载对话历史（含计划消息）

### 计划上下文注入

执行期间，Agent 系统提示词中注入当前计划上下文，包含：

- 计划标题和进度（已完成步数 / 总步数）
- 下一步的标题、描述和验证条件
- 操作指引：完成实施后执行验证，验证通过后调用 `plan({ action: "step_done", stepIndex: N })`

通用计划系统规则（`plan_system` 提示词）始终包含，说明何时创建计划、步骤质量要求、verification 字段含义、创建后必须等待审批等。

## 暂停与恢复

### 暂停

用户点击「暂停」时，`MessagePipeline` 调用 `InlinePlanService.updatePlanStatus(messageId, "paused")`，同时调用 `AgentRuntime.signalPlanPaused(messageId)`。Agent 在每个回合开始时检查暂停信号，若当前计划已暂停则优雅退出。

### 恢复

用户点击「继续」时，状态回到 `executing`，服务端自动触发「开始执行计划」消息，Agent 从中断处继续。

### 取消

用户点击「取消」或审批时拒绝，状态变为 `cancelled`，未完成步骤标记为 `skipped`，同时通知 Agent 停止执行。

## 前端交互

### PlanMessage

内联计划消息组件，替代旧版 `PlanPanel` 侧边面板。当消息的 `metadata.type === "plan"` 时，在对话流中渲染 checklist 风格的计划卡片，包含：

- 计划标题和状态徽章
- 步骤列表（状态图标、标题、描述、验证条件、结果摘要）
- 操作按钮：draft 状态显示「执行」「拒绝」；executing 显示「暂停」「取消」；paused 显示「继续」「取消」

步骤修改、添加、跳过等复杂操作通过自然语言对话完成，不再提供独立的步骤编辑 UI。

### planStore

Zustand 状态管理已简化为仅跟踪活跃计划：

- `activePlanMessageId`：当前活跃计划的 messageId
- `activePlanStatus`：当前计划状态

计划的结构化数据（步骤列表、状态等）存储在 `chatStore` 消息的 `metadata` 中。WebSocket 事件处理器更新 `planStore` 的活跃指针，同时通过 `chatStore.updateMessageMetadata` 更新消息内的 plan 数据。

### 工具调用显示

`plan` 工具调用不在普通工具列表中重复显示（与旧版 writePlan/updatePlan 相同处理方式）。Agent 执行的其他工具调用在对话气泡中正常流式展示，与 checklist 独立。

## WebSocket 协议

### 客户端请求（4 个方法）

| 方法 | 参数 | 说明 |
|------|------|------|
| `plan.approve` | `messageId`, `approved` | 审批或拒绝计划 |
| `plan.pause` | `messageId` | 暂停执行 |
| `plan.resume` | `messageId` | 恢复执行 |
| `plan.cancel` | `messageId` | 取消计划 |

协议辅助函数定义在 `protocol.ts`：`createPlanApprove`、`createPlanPause`、`createPlanResume`、`createPlanCancel`。

已删除的方法：`plan.approve_and_execute`、`plan.skip_step`、`plan.modify_step`、`plan.add_step`、`plan.reorder_steps`。

### 服务端推送事件（4 个事件）

| 事件 | 载荷 | 说明 |
|------|------|------|
| `plan.created` | `messageId`, `title`, `status`, `steps` | 计划已创建，前端渲染 checklist |
| `plan.step_updated` | `messageId`, `stepIndex`, `status`, `resultSummary` | 步骤状态变更 |
| `plan.status_changed` | `messageId`, `status` | 计划整体状态变更 |
| `plan.completed` | `messageId`, `summary` | 计划已完成 |

已删除的事件：`plan.awaiting_approval`（由 `plan.created` status=draft 隐含）、`plan.step_start`、`plan.step_done`（合并为 `plan.step_updated`）、`plan.adjusted`。

## PlanOperations SPI

Agent 模块定义 `PlanOperations` 接口，Gateway 模块提供 `InlinePlanOperationsImpl` 实现，避免循环依赖。

```java
public interface PlanOperations {
    Mono<PlanResult> createPlan(long sessionId, String title, List<StepInput> steps);
    Mono<PlanResult> updateStep(long messageId, int stepIndex, String status, String resultSummary);
    Mono<PlanResult> completePlan(long messageId, String summary);
    Mono<Boolean> isPausedOrCancelled(long messageId);
}
```

`PlanResult` 包含 `messageId`、状态和消息文本。`InlinePlanOperationsImpl` 是 `InlinePlanService` 之上的响应式包装。

## InlinePlanService

Gateway 层的计划服务，直接操作 `transcript_message` 表：

- `createPlanMessage`：创建 plan 类型消息，状态为 draft
- `updateStepStatus`：更新步骤状态和结果摘要
- `updatePlanStatus`：更新计划状态（审批、暂停、恢复、取消）
- `completePlan`：标记计划完成并写入摘要
- `getActivePlan`：查询会话中的活跃计划
- `buildPlanContext`：构建注入 Agent 的计划执行上下文

不再有独立的 `PlanService`、`PlanController` REST API 或 `plan` / `plan_step` 数据库表。

## forcePlan 模式

当用户发送 `/plan` 命令或消息带有 `forcePlan` 参数时，Agent 系统提示词中会注入强制要求：「你必须先调用 plan 工具创建计划，等待用户审批后再执行」。

## 设计要点

**计划即消息**：计划是对话的一部分，不是独立实体。历史加载、持久化和 UI 渲染都基于 transcript_message。

**验证驱动完成**：每个步骤必须定义 verification 条件，Agent 完成实施后须验证通过才标记 step_done，避免跳过质量检查。

**精简协议**：WebSocket 方法从 9 个减至 4 个，事件从 7 个减至 4 个，所有标识统一为 messageId。

**活跃计划唯一性**：每个会话同一时间只能有一个活跃计划。新计划创建时若已有活跃计划，创建操作会失败。

**内联交互**：审批和控制按钮内嵌在对话流中，不打断聊天节奏。复杂步骤调整通过自然语言完成。
