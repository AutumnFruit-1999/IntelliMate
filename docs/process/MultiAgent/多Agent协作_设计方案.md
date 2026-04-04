# 多 Agent 协作 — 设计方案

> 日期: 2026-03-12  
> 状态: Draft  
> 前置文档: [多Agent体系调研报告.md](多Agent体系调研报告.md)  
> 关联文档: [多Agent协作_技术架构.md](多Agent协作_技术架构.md)

---

## 1. 设计目标

| 目标 | 描述 |
|------|------|
| **Agent 间协作** | Supervisor Agent 可以将子任务委派给 Worker Agent，并汇聚结果 |
| **灵活编排** | 支持委派 (Delegation)、移交 (Handoff)、并行 (Fan-out/Fan-in) 三种协作模式 |
| **最小侵入** | 复用现有 `AgentRuntime`、`ToolsEngine`、`MessagePipeline`，不引入独立的编排引擎 |
| **可观测** | 多 Agent 执行过程对用户可见，支持人工审批节点 |
| **渐进演进** | P0 单向委派 → P1 Handoff + 并行 → P2 动态 Agent + 调试统计 |

---

## 2. 核心理念

### 2.1 Tool-based 委派（核心机制）

**不修改 LLM 调用链，Agent 通过标准 tool call 触发协作。**

```
用户消息 → Supervisor Agent
  ├── LLM 推理 → tool_call: delegate_agent(task="...", agents=["coder"])
  │     └── AgentRuntime 拦截 → spawn Worker "coder" → Worker 执行完成
  │           └── 返回结果作为 ToolResponse → Supervisor 继续推理
  └── LLM 最终输出 → 返回用户
```

这个设计参考 OpenHands 的 DelegateTool，优点：

- Agent 是否委派由 LLM 自主决定，不需要硬编码流程
- 委派通过标准 ToolCall/ToolResponse 完成，复用现有 `ToolsEngine` 管道
- 子 Agent 的执行复用现有 `AgentRuntime.executeAgentLoop`

### 2.2 与现有架构的关系

```
┌─────────────────────────────────────────────────────────┐
│ 当前架构 (保持不变)                                       │
│                                                         │
│   WebSocket ──→ MessagePipeline ──→ AgentRuntime        │
│                      │                    │              │
│                 SessionManager        ToolsEngine        │
│                      │                    │              │
│                   MySQL R2DBC     ToolCallback[]         │
└─────────────────────────────────────────────────────────┘
                        │
                        ▼ 新增
┌─────────────────────────────────────────────────────────┐
│ 多 Agent 协作层                                          │
│                                                         │
│   DelegateAgentTool (内置工具)                            │
│       │                                                 │
│   WorkflowEngine (编排引擎)                               │
│       ├── DelegationExecutor   — 单向委派                 │
│       ├── HandoffExecutor      — 控制权移交 (P1)          │
│       └── FanOutExecutor       — 并行执行 (P1)            │
│                                                         │
│   WorkflowContext (共享上下文)                             │
│       ├── SharedWorkspace      — 文件系统共享              │
│       ├── ResultStore          — 中间结果存储              │
│       └── ExecutionGraph       — 执行依赖图               │
└─────────────────────────────────────────────────────────┘
```

---

## 3. Agent 角色体系

### 3.1 角色分类

| 角色 | 描述 | 能力 | 创建方式 |
|------|------|------|----------|
| **Supervisor** | 协调者，分析任务并委派 | DelegateAgentTool + HandoffTool | 用户在 Agent 配置中设置 `role=supervisor` |
| **Worker** | 执行者，专注完成具体任务 | 各自的专业工具集 | 用户预先创建，或 Supervisor 动态创建 (P2) |
| **User Agent** | 普通 Agent，无协作能力 | 所有可用工具 | 默认角色（现有行为） |

### 3.2 角色定义模型

参考 CrewAI 的结构化角色定义，在 AgentEntity 上扩展：

```
AgentEntity (现有)
  ├── name: String           ← 已有
  ├── model: String          ← 已有
  ├── soulMd: String         ← 已有 (system prompt 一部分)
  ├── toolsEnabled: String   ← 已有
  │
  └── 新增字段:
      ├── role: String       ← "supervisor" | "worker" | null(默认 user)
      ├── goal: String       ← 优化目标描述 (参考 CrewAI)
      ├── parentAgentId: Long ← 父 Agent ID (worker 从属于哪个 supervisor)
      └── workerAgents: String ← JSON 数组，supervisor 可用的 worker 名单
```

### 3.3 角色行为差异

**Supervisor Agent**:

- 自动注入 `DelegateAgentTool`（委派工具）和 `HandoffTool`（移交工具，P1）
- System Prompt 中追加 Worker 列表和委派指南
- 可访问所有 Worker 的执行结果摘要
- 自身也可以执行工具（不只是调度）

**Worker Agent**:

- 拥有各自独立的工具集和 Skills
- 收到的任务描述来自 Supervisor 的 tool call 参数
- 执行完成后返回结果给调用方
- 不能反向委派给 Supervisor（P0），P1 支持 Handoff 给其他 Worker

**User Agent (默认)**:

- 与当前行为完全一致，无协作能力
- 不注入 DelegateAgentTool

---

## 4. 协作模式

### 4.1 模式一：委派 (Delegation) — P0

最基础的协作模式，Supervisor 单向分发任务。

```
时序图:

User ──[消息]──→ Supervisor
                    │
                    ├── LLM 推理
                    │     "这个任务需要代码审查和文档编写"
                    │
                    ├── tool_call: delegate_agent
                    │   {
                    │     "task": "审查 src/main/java/... 的代码质量",
                    │     "agent": "code-reviewer",
                    │     "context": "用户想优化这个模块的性能"
                    │   }
                    │     │
                    │     └── [Worker: code-reviewer 执行]
                    │           ├── 读取代码文件
                    │           ├── 分析代码质量
                    │           └── 返回审查报告
                    │     │
                    │     └── ToolResponse: "审查报告: ..."
                    │
                    ├── tool_call: delegate_agent
                    │   { "task": "为模块编写 README", "agent": "readme-writer" }
                    │     │
                    │     └── [Worker: readme-writer 执行]
                    │           └── 返回 README 内容
                    │
                    ├── LLM 综合两个结果
                    └── 最终回复用户
```

**关键设计**:

- `delegate_agent` 是**同步阻塞**的 tool call：Supervisor 发起委派后等待 Worker 完成
- Worker 的执行结果作为 ToolResponse 返回给 Supervisor 的对话历史
- Worker 使用独立的 `AgentRuntime.executeAgentLoop`，有自己的对话历史和 turn 限制
- 多个 delegate_agent 调用**默认串行**（按 tool call 顺序执行）

### 4.2 模式二：移交 (Handoff) — P1

Agent 间直接转交控制权，当前 Agent 退出，目标 Agent 接管。

```
时序图:

User ──[消息]──→ Agent A (前端开发)
                    │
                    ├── LLM 推理
                    │     "这个任务涉及后端 API，我移交给后端专家"
                    │
                    ├── tool_call: handoff_to_agent
                    │   {
                    │     "agent": "backend-dev",
                    │     "reason": "需要修改 API 端点",
                    │     "context_summary": "用户想在 /api/users 增加分页参数"
                    │   }
                    │
                    └── Agent A 执行结束
                              │
                              ▼
                    Agent B (后端开发) 接管
                    │
                    ├── 收到上下文摘要
                    ├── 独立执行
                    └── 最终回复用户
```

**与委派的区别**:

| | 委派 (Delegation) | 移交 (Handoff) |
|---|---|---|
| 控制权 | Supervisor 保留控制权 | 当前 Agent 放弃控制权 |
| 结果流向 | 返回给 Supervisor | 直接返回给用户 |
| 适用场景 | 子任务分解 | 跨专业领域转交 |
| 调用方式 | `delegate_agent` tool call | `handoff_to_agent` tool call |

### 4.3 模式三：并行 (Fan-out / Fan-in) — P1

多个 Worker 同时执行不同子任务，结果汇聚后返回。

```
时序图:

User ──[消息]──→ Supervisor
                    │
                    ├── tool_call: delegate_agents_parallel
                    │   {
                    │     "tasks": [
                    │       { "agent": "researcher", "task": "调研 Redis 缓存方案" },
                    │       { "agent": "coder",      "task": "实现基础缓存接口" },
                    │       { "agent": "tester",     "task": "编写缓存测试用例" }
                    │     ]
                    │   }
                    │         │
                    │    ┌────┼────────┐
                    │    ▼    ▼        ▼
                    │  [researcher] [coder] [tester]   ← 并行执行
                    │    │    │        │
                    │    └────┼────────┘
                    │         ▼
                    │   ToolResponse: { results: [...] }
                    │
                    ├── LLM 综合三个结果
                    └── 最终回复用户
```

**并行执行策略**:

- 使用 `Flux.merge` 并行调度多个 `AgentRuntime.executeAgentLoop`
- 每个 Worker 在独立的 `boundedElastic` 线程上运行
- 所有 Worker 完成后汇聚为一个 ToolResponse 返回 Supervisor
- 支持配置 `maxParallel`（默认 4，参考 Cursor 的 8 上限）
- 任一 Worker 超时/失败，其他 Worker 继续执行，失败结果标记为 error

---

## 5. 上下文传递设计

### 5.1 上下文分层

```
┌─────────────────────────────────────────┐
│ Layer 1: 共享工作区 (Shared Workspace)     │
│   所有 Agent 访问相同的文件系统              │
│   Skills 目录、项目代码、配置文件             │
│   → 通过 readFile/writeFile 工具访问        │
└────────────────────┬────────────────────┘
                     │
┌────────────────────▼────────────────────┐
│ Layer 2: 工作流上下文 (Workflow Context)    │
│   当前工作流的全局信息                       │
│   原始用户消息、任务分解计划、中间结果         │
│   → WorkflowContext 对象在 Agent 间传递     │
└────────────────────┬────────────────────┘
                     │
┌────────────────────▼────────────────────┐
│ Layer 3: Agent 私有对话 (Private History)   │
│   每个 Agent 维护独立的对话历史               │
│   不暴露给其他 Agent                        │
│   → 隔离在各自的 executeAgentLoop 中        │
└─────────────────────────────────────────┘
```

### 5.2 上下文传递规则

| 场景 | Supervisor → Worker | Worker → Supervisor |
|------|---------------------|---------------------|
| 任务描述 | `task` 参数 (必须) | N/A |
| 额外上下文 | `context` 参数 (可选，摘要) | N/A |
| 执行结果 | N/A | Worker 的 `Done.fullText` |
| 中间推理 | 不可见 | 不可见 |
| 文件修改 | 通过共享文件系统可见 | 通过共享文件系统可见 |

### 5.3 Token 控制策略

| 策略 | 描述 |
|------|------|
| **结果摘要** | Worker 返回结果超过 4000 tokens 时自动摘要 |
| **上下文压缩** | Supervisor 传给 Worker 的 context 有上限 (8000 tokens) |
| **历史隔离** | Worker 不继承 Supervisor 的对话历史 |
| **按需传递** | 只传递 Worker 需要的最小上下文 |

---

## 6. 用户交互设计

### 6.1 执行过程可视化

多 Agent 执行需要在前端清晰展示：

```
┌──────────────────────────────────────────────────┐
│  💬 用户: "帮我优化这个模块的性能并写文档"             │
├──────────────────────────────────────────────────┤
│  🤖 Supervisor (planner-agent):                   │
│  "好的，我将把这个任务分为两个子任务..."                │
│                                                    │
│  ┌─ 委派: code-reviewer ─────────────────────┐    │
│  │  📋 任务: 审查 src/service/ 的代码质量       │    │
│  │  ⏳ 执行中...                               │    │
│  │  ├── 🔧 tool: readFile("Service.java")     │    │
│  │  ├── 🔧 tool: readFile("Controller.java")  │    │
│  │  └── ✅ 完成 (3 turns, 12s)                 │    │
│  │  📄 结果: 发现 3 个性能问题...               │    │
│  └─────────────────────────────────────────────┘    │
│                                                    │
│  ┌─ 委派: readme-writer ─────────────────────┐    │
│  │  📋 任务: 为模块编写 README                  │    │
│  │  ⏳ 执行中...                               │    │
│  │  └── ✅ 完成 (2 turns, 8s)                  │    │
│  │  📄 结果: # Module README...                │    │
│  └─────────────────────────────────────────────┘    │
│                                                    │
│  🤖 Supervisor:                                    │
│  "综合审查和文档结果，建议如下..."                      │
└──────────────────────────────────────────────────┘
```

### 6.2 新增 AgentEvent 类型

为支持前端展示，扩展 `AgentEvent`：

| 事件 | 触发时机 | 携带数据 |
|------|----------|----------|
| `DelegationStart` | Supervisor 发起委派 | agentName, task, workflowStepId |
| `DelegationProgress` | Worker 执行中的进度 | agentName, turn, event (嵌套) |
| `DelegationResult` | Worker 完成或失败 | agentName, result, success, duration |
| `HandoffStart` | Agent 发起移交 | fromAgent, toAgent, reason |
| `ParallelStart` | 并行执行开始 | agentNames[], tasks[] |
| `ParallelProgress` | 某个并行 Worker 有进展 | agentName, event (嵌套) |

### 6.3 人工审批节点

关键决策点允许用户介入：

```
Supervisor 推理: "这个操作会修改生产数据库 schema..."

┌────────────────────────────────────────┐
│  ⚠️ 需要审批                            │
│  Supervisor 想要委派以下任务:              │
│                                         │
│  Agent: db-migrator                     │
│  任务: 执行数据库 migration V12           │
│                                         │
│  [✅ 批准]  [❌ 拒绝]  [✏️ 修改后批准]    │
└────────────────────────────────────────┘
```

- 通过 Agent 配置中的 `requireApproval: boolean` 控制
- 审批请求通过 WebSocket 的 `EventFrame("workflow.approval_required", ...)` 发送
- 用户通过 `RequestFrame("workflow.approve", { stepId, approved })` 响应
- 超时未审批默认拒绝

---

## 7. 预设工作流模板

### 7.1 内置 Supervisor 模板

提供开箱即用的 Supervisor Agent 配置：

| 模板名 | 描述 | 包含 Worker |
|--------|------|-------------|
| **代码审查流水线** | PR 审查 → 问题修复建议 → 文档更新 | code-reviewer, readme-writer |
| **研究报告** | 调研 → 分析 → 报告生成 | researcher, analyst, reporter |
| **测试驱动开发** | 写测试 → 实现代码 → 审查 | unit-test-gen, coder, code-reviewer |

### 7.2 模板结构

```json
{
  "name": "code-review-pipeline",
  "displayName": "代码审查流水线",
  "description": "自动化代码审查、问题修复和文档更新",
  "supervisor": {
    "role": "supervisor",
    "soulMd": "你是一个代码审查协调者...",
    "goal": "确保代码质量和文档完整性",
    "model": "claude-sonnet-4-20250514",
    "workerAgents": ["code-reviewer", "readme-writer"]
  },
  "workers": [
    {
      "name": "code-reviewer",
      "role": "worker",
      "soulMd": "你是一个专业的代码审查专家...",
      "goal": "发现代码中的质量和性能问题",
      "skillsEnabled": "[\"code-reviewer\"]",
      "toolsEnabled": "[\"readFile\", \"listDirectory\", \"searchFiles\"]"
    },
    {
      "name": "readme-writer",
      "role": "worker",
      "soulMd": "你是一个技术文档专家...",
      "goal": "编写清晰准确的技术文档",
      "skillsEnabled": "[\"readme-writer\"]",
      "toolsEnabled": "[\"readFile\", \"writeFile\", \"listDirectory\"]"
    }
  ]
}
```

---

## 8. 与现有功能的兼容性

### 8.1 不破坏现有行为

| 现有功能 | 影响 | 保证 |
|----------|------|------|
| 单 Agent 对话 | 无影响 | `role=null` 的 Agent 行为完全不变 |
| 多 Agent 切换 | 兼容 | 前端仍可自由切换 Agent，协作是额外能力 |
| Session 隔离 | 保持 | `contextId = baseContextId + "::" + agentName` 不变 |
| 对话历史 | 保持 | Worker 的对话历史存储在独立的 Workflow Session 中 |
| Skills 系统 | 兼容 | Worker 可以使用自己配置的 Skills |
| 自定义工具 | 兼容 | Worker 可以使用自定义工具和 MCP 工具 |

### 8.2 复用组件清单

| 组件 | 当前职责 | 协作中的新用途 |
|------|----------|----------------|
| `AgentRuntime` | 执行 Agent Loop | Worker 复用相同的执行循环 |
| `ToolsEngine` | 工具注册和过滤 | 为 Worker 按需组装工具集 |
| `MessagePipeline` | 消息路由和流式输出 | 增加 Delegation 事件的映射 |
| `SessionManager` | 会话管理 | 为 Worker 创建临时子 Session |
| `AgentConfigService` | Agent 配置解析 | 支持 role/goal 新字段 |
| `SkillContentProvider` | Skills 内容加载 | Worker 使用各自的 Skills 配置 |
| `RunQueueManager` | 执行队列 | Worker 共享同一队列（同 session 串行） |

---

## 9. 安全与限制

### 9.1 执行限制

| 限制 | 默认值 | 可配置 |
|------|--------|--------|
| Worker 最大 turn 数 | 10 | 是 (per agent) |
| Worker 超时 | 120s | 是 (per agent) |
| 并行 Worker 上限 | 4 | 是 (workflow 级别) |
| 委派嵌套深度 | 2 | 是 (防止无限递归) |
| 单次工作流最大 Worker 调用数 | 10 | 是 |
| Worker 结果最大长度 | 32KB | 是 |

### 9.2 安全策略

- **权限继承**: Worker 的工具权限不能超过 Supervisor 可访问的范围
- **防递归**: Worker 默认不注入 `DelegateAgentTool`，除非显式配置
- **审计追踪**: 所有委派操作记录在 `workflow_step` 表中
- **资源隔离**: Worker 的 token 消耗独立计算

---

## 10. 分阶段实施路线

### P0 — 基础委派框架

- `DelegateAgentTool` 内置工具
- `WorkflowEngine` 基础编排
- AgentEntity 新增 `role` / `goal` / `parentAgentId` 字段
- DB: `agent_workflow` + `workflow_step` 表
- 前端: 委派过程的基础可视化
- 预计工期: 2-3 周

### P1 — 增强协作

- `HandoffTool` 移交工具
- `FanOutExecutor` 并行执行
- 工作流模板系统
- 前端: 完整的工作流可视化面板
- 人工审批节点
- 预计工期: 2-3 周

### P2 — 高级功能

- 动态 Agent 创建（运行时 spawn 临时 Worker）
- 执行统计与调试面板
- 工作流版本管理
- 预计工期: 2 周

---

## 附录 A: 设计决策记录

### 决策 1: Tool-based 委派 vs 独立编排引擎

**选择**: Tool-based 委派  
**原因**: 
- 复用现有 ToolsEngine 管道，无需引入 LangGraph4j 等外部依赖
- Agent 的委派决策由 LLM 自主完成，不需要硬编码流程
- 降低实现复杂度，P0 可快速落地

### 决策 2: Supervisor + Handoff vs GroupChat

**选择**: Supervisor + Handoff  
**原因**:
- Supervisor 模式更可控、可预测，适合生产环境
- GroupChat 的完全共享上下文 token 消耗过高
- Handoff 提供了灵活的 Agent 间切换，补充 Supervisor 的不足

### 决策 3: 共享文件系统 + 独立对话 vs 完全共享

**选择**: 共享文件系统 + 独立对话  
**原因**:
- 参考 OpenHands 和 Cursor 的成功实践
- 文件系统共享允许 Worker 读写相同的代码
- 独立对话避免上下文膨胀，控制 token 消耗
- Worker 仅返回最终结果，中间推理不暴露

### 决策 4: Worker Session 策略

**选择**: 临时子 Session  
**原因**:
- Worker 的对话历史是临时的，任务完成即可归档
- 不污染 Supervisor 的主 Session
- 通过 `parent_session_id` 关联，支持审计追踪
