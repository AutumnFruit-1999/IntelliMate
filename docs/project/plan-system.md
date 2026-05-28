# 计划模式系统

## 概述

计划模式是 IntelliMate 的核心交互范式之一。当用户提出复杂的多步骤任务时，Agent 不会直接开始执行，而是先创建一份结构化的执行计划，经用户审批后再逐步执行。整个过程中用户可以暂停、恢复、修改步骤、跳过步骤或取消计划，保持对执行过程的完全控制。

计划模式横跨三个层：Agent 层提供创建和更新计划的工具、运行时自动追踪步骤进度；Gateway 层持久化计划数据、驱动执行生命周期、自动补全遗漏状态；前端实时呈现计划面板并提供全部操作入口。

## 数据模型

### plan 表

每一份计划对应一行记录，绑定到一个会话（session）。同一个会话中同一时间只能有一个活跃计划。

主要字段：

- id：主键
- session_id：所属会话
- title：计划标题
- status：当前状态（draft / approved / executing / paused / completed / cancelled）
- completion_summary：完成摘要，计划结束时由 Agent 或系统写入
- created_at / updated_at：时间戳

### plan_step 表

每个步骤是计划的子记录，通过 plan_id 关联。步骤按 step_index（从 1 开始）排列，支持动态增删和重排序。

主要字段：

- id：主键
- plan_id：所属计划
- step_index：顺序号
- title：步骤标题
- description：步骤描述
- status：步骤状态（pending / in_progress / completed / failed / skipped）
- result_summary：执行结果摘要
- started_at / completed_at：时间戳

### transcript_message 关联

消息表中的 plan_id 字段将聊天消息关联到计划，实现计划执行期间的历史隔离——加载历史时只取计划相关的上下文，避免无关对话干扰 Agent 的执行。

## 计划的生命周期

### 状态流转

```
                    用户审批
[新建] → draft ──────────────→ approved
            │                      │
            │ 用户拒绝               │ 开始执行 / Agent 标记步骤
            ↓                      ↓
         cancelled            executing
                                  │
                        用户暂停 / 步骤失败
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

任何非终态都可以被用户取消（cancelled）。取消时所有待执行和进行中的步骤标记为 skipped。

### 关键状态转换规则

- draft → approved：用户审批通过，可同时提交步骤修改
- draft/approved → executing：当 Agent 标记某步骤为 in_progress 时自动提升
- executing → paused：用户主动暂停，或某步骤标记为 failed 时自动暂停
- paused → executing：用户恢复执行
- 任意状态 → cancelled：用户取消，所有未完成步骤标记为 skipped
- executing → completed：Agent 调用 completePlan 或系统自动检测所有步骤已完成

## 计划创建

### WritePlanTool

这是 Agent 侧的计划创建工具，直接实现 ToolCallback 接口。接受 title 和 steps 数组作为输入，每个步骤包含 title 和 description。

工具内部做了大量的 JSON 容错处理，包括修复未加引号的值、提取 Markdown 代码块中的 JSON、正则回退解析等，确保 LLM 各种格式的输出都能被正确解析。

创建流程：

1. 从当前线程上下文获取 sessionId
2. 解析并修复 LLM 返回的 JSON
3. 调用 PlanOperations.createPlan 持久化
4. 返回包含 planId、status、message 的结果

创建完成后 AgentRuntime 会自动提取事件：PlanCreated（包含计划内容）和 PlanAwaitingApproval（等待用户审批）。这两个事件通过 WebSocket 推送到前端。

### 创建后的行为

Agent 在创建计划后必须停止执行并等待用户审批，这在系统提示词中有明确约束。计划面板会自动弹出，用户可以查看、修改步骤、批准或拒绝。

## 计划执行

### 执行触发

用户在前端点击"执行"按钮后：

1. 发送 plan.approve 审批计划
2. 等待审批成功
3. 自动发送"开始执行计划"消息触发 Agent 运行

### MessagePipeline 的角色

MessagePipeline 是计划执行的驱动中心。处理每条消息时会检查当前会话是否有活跃计划：

1. 查询活跃计划（状态为 executing 或 approved）
2. 如果有，构建计划执行载荷（plan execution payload）
3. 将计划上下文注入到 Agent 请求中
4. Agent 执行完毕后调用同步逻辑检查并自动补全

### 计划上下文注入

执行期间，Agent 的系统提示词中会注入两层计划信息：

第一层是通用计划系统规则（plan_system），始终包含，说明何时应该创建计划、计划质量要求、创建后必须等待审批等。

第二层是当前执行上下文（plan_execution），仅在计划处于 executing 或 approved 状态时注入，包含：

- 计划 ID（用于 updatePlan 调用）
- 已完成步骤及其结果摘要
- 当前步骤（in_progress 的步骤，或第一个 pending 步骤）
- 待执行步骤清单
- 严格的三步执行流程：标记开始 → 执行工具 → 标记完成

### 历史隔离

计划执行期间加载的聊天历史会合并两部分：

- 计划前的通用聊天上下文
- 计划执行期间的消息（按 plan_id 过滤）

这确保 Agent 聚焦于计划相关内容，不被其他对话干扰。

## 步骤追踪

### UpdatePlanTool

Agent 在执行过程中通过 UpdatePlanTool 更新步骤状态，支持以下操作：

- markStep：标记步骤状态（in_progress / completed / failed），可附带 resultSummary
- addStep：在指定位置之后添加新步骤
- removeStep：移除步骤并重新排序
- completePlan：完成整个计划，可附带总结摘要

每个操作都会生成对应的事件（PlanStepStart、PlanStepDone、PlanAdjusted、PlanCompleted），通过 WebSocket 实时推送到前端。

### PlanStepTracker 自动追踪

PlanStepTracker 是 AgentRuntime 中的内部组件，解决 LLM 忘记调用 updatePlan 就直接执行工具的问题。

当 Agent 调用非计划工具（不是 writePlan 或 updatePlan）且当前没有活跃步骤时，PlanStepTracker 会自动找到第一个 pending 步骤并标记为 in_progress，同时发出相应事件。这样即使 LLM 不规范地跳过了步骤状态管理，前端面板仍然能正确反映进度。

### 执行后自动同步

每次 Agent 运行结束后，MessagePipeline 会执行 syncPlanAfterExecution：

1. 查找处于 in_progress 或 pending 的步骤
2. 将遗留的 in_progress 步骤自动标记为 completed
3. 发出 plan.step_done 事件
4. 检查是否所有步骤都已完成（completed / failed / skipped），如果是则自动完成计划
5. 触发计划完成的记忆提取

## 步骤管理

### 添加步骤

addStep 在指定位置之后插入新步骤。为避免唯一约束冲突，先以逆序更新后续步骤的索引号，再插入新步骤。

### 移除步骤

removeStep 删除指定步骤后，按顺序从 1 开始重新编排剩余步骤的索引号。

### 重排序

用户可以在前端拖拽调整步骤顺序（仅限 draft 或 paused 状态），通过 plan.reorder_steps 事件发送新的排列顺序。

### 跳过步骤

用户可以跳过某个 failed 步骤，步骤状态变为 skipped，结果摘要标记为"用户跳过"。

### 修改步骤

用户可以在审批前修改步骤的标题和描述。审批时也可以附带批量修改（edit / add / remove）。

## 暂停与恢复

### 暂停

用户暂停计划或步骤失败时，PlanService 将计划状态设为 paused，同时调用 AgentRuntime.signalPlanPaused 通知运行中的 Agent。Agent 在每个回合开始时检查暂停信号，如果当前计划已暂停则优雅退出，输出"计划已暂停，当前步骤完成后停止执行"。

### 恢复

用户恢复执行后计划状态回到 executing，下一条消息触发 Agent 继续从中断的步骤执行。

## 前端交互

### PlanPanel

主侧边栏面板（420px 宽），是计划模式的核心交互组件，包含：

- 进度条：显示已完成 / 总步骤数
- 步骤列表：每个步骤显示状态图标、标题、描述
- 操作按钮：审批/拒绝、执行、暂停/恢复、取消
- 拖拽排序：draft 和 paused 状态下可拖拽调整步骤顺序
- 步骤编辑：可修改步骤标题和描述
- 步骤工具调用列表：展开可查看每个步骤执行的工具调用
- 历史分页：可浏览之前完成的计划

### PlanStepCard

步骤卡片组件，显示步骤详情和状态，支持展开查看关联的工具调用列表。工具调用在执行过程中实时追加。

### PlanHistoryTab

全局计划历史视图，展示所有历史计划的表格，支持按 Agent、状态筛选，可展开查看步骤详情和完成摘要，支持批量删除。

### planStore

Zustand 状态管理，维护当前计划、步骤工具调用映射、当前步骤索引、待分配工具调用缓冲、计划历史等状态。处理所有 WebSocket 计划事件并更新 UI。

### 工具调用关联

前端负责将 Agent 执行的工具调用关联到对应步骤：

1. 收到 agent.tool_call 事件时，如果当前有活跃步骤，将工具调用追加到该步骤
2. 如果没有活跃步骤但计划在执行中，乐观地分配给第一个 pending 步骤
3. 如果都不匹配，缓冲在 pendingToolCalls 中，等 plan.step_start 事件到达后刷入
4. 收到 agent.tool_result 时更新对应工具调用的结果

## WebSocket 事件

### 客户端请求方法

| 方法 | 参数 | 说明 |
|------|------|------|
| plan.approve | planId, approved, modifications | 审批或拒绝计划 |
| plan.approve_and_execute | planId | 审批并立即开始执行 |
| plan.pause | planId | 暂停计划 |
| plan.resume | planId | 恢复执行 |
| plan.cancel | planId | 取消计划 |
| plan.skip_step | planId, stepIndex | 跳过步骤 |
| plan.modify_step | planId, stepIndex, title, description | 修改步骤 |
| plan.add_step | planId, afterIndex, title, description | 添加步骤 |
| plan.reorder_steps | planId, newOrder | 重排步骤顺序 |

### 服务端推送事件

| 事件 | 载荷 | 说明 |
|------|------|------|
| plan.created | planId, title, steps | 计划已创建 |
| plan.awaiting_approval | planId | 等待用户审批 |
| plan.status_changed | planId, status | 计划状态变更 |
| plan.step_start | planId, stepIndex, title | 步骤开始执行 |
| plan.step_done | planId, stepIndex, status, resultSummary | 步骤执行完成 |
| plan.adjusted | planId, adjustType, currentSteps | 步骤列表调整 |
| plan.completed | planId, status | 计划已完成 |

## REST API

| 端点 | 说明 |
|------|------|
| GET /api/plans | 分页查询计划列表，支持 agentName / status / sessionId / includeSteps 过滤 |
| GET /api/plans/{planId} | 获取计划详情及所有步骤 |
| GET /api/plans/{planId}/steps | 获取计划的步骤列表 |
| DELETE /api/plans/{planId} | 删除计划及其步骤 |
| DELETE /api/plans/batch | 批量删除计划 |

## PlanOperations SPI

Agent 模块定义了 PlanOperations 接口，Gateway 模块提供 PlanOperationsImpl 实现，避免循环依赖。

接口定义：

- createPlan(sessionId, title, steps)：创建计划
- markStep(planId, stepIndex, status, summary)：更新步骤状态
- addStep(planId, afterIndex, title, description)：添加步骤
- removeStep(planId, stepIndex, reason)：移除步骤
- completePlan(planId, summary)：完成计划
- getSteps(planId)：获取步骤快照
- isPausedOrCancelled(planId)：检查计划是否已暂停或取消

PlanOperationsImpl 是响应式 PlanService 之上的同步包装，使用 .block() 调用。由于运行在响应式管道中，必须切换到 boundedElastic 调度器。

## 记忆提取

计划完成后（无论是 Agent 主动完成还是系统自动补全），MessagePipeline 会触发记忆提取：

- 过程记忆：提取"问题 + 解决步骤 + 结果"形式的经验
- 情景记忆：生成简短的执行摘要

这些记忆写入长期记忆存储，帮助 Agent 在未来遇到类似任务时借鉴历史经验。

## forcePlan 模式

当用户发送 /plan 命令或消息带有 forcePlan 参数时，Agent 的系统提示词中会注入强制要求："你必须先调用 writePlan 创建计划，等待用户审批后再执行"。这确保即使是简单任务也走计划流程。

## 设计要点

活跃计划唯一性：每个会话同一时间只能有一个活跃计划（状态为 draft / approved / executing / paused）。新计划创建时如有前序计划，前序计划进入历史。

乐观 UI 更新：前端在收到确认前不会预设状态，所有状态更新都基于 WebSocket 事件驱动。

自动容错：PlanStepTracker 处理 LLM 忘记标记步骤的情况，syncPlanAfterExecution 处理 LLM 忘记完成计划的情况，多层兜底确保计划状态最终一致。

工具关联前端化：工具调用与步骤的关联在前端完成而非后端，减少了后端复杂度，同时保持了实时性。

历史隔离保证聚焦：计划执行期间的 Agent 上下文仅包含计划相关消息和必要的前置聊天，避免无关内容稀释上下文。
