# Agent 规划执行模式业界调研报告

> 调研时间：2026-04-02
> 调研范围：DeerFlow (ByteDance)、OpenClaw、Cursor、Claude Code

---

## 一、调研背景与目标

当前主流 AI Agent 框架普遍采用 ReAct（Reason + Act）循环作为核心执行范式——LLM 推理后直接调用工具、观察结果、再推理。这种模式在简单任务中表现良好，但面对复杂、多步骤任务时存在明显问题：

- **黑盒执行**：用户无法预知 Agent 的行动计划，只能被动等待最终结果
- **方向偏离**：Agent 可能在早期步骤中走偏，导致后续大量无效工作
- **不可控性**：缺乏对关键步骤的人工审批和干预机制
- **资源浪费**：无规划的试探性执行消耗大量 token 和时间

本报告深入调研四个业界主流 Agent 框架在"规划-执行"模式上的设计，提炼共性模式和最佳实践，为 JavaClaw 引入 Plan 模式提供设计依据。

---

## 二、框架逐一分析

### 2.1 DeerFlow (ByteDance)

**版本**：DeerFlow 2.0（2026 年 2 月发布，完全重写）
**技术栈**：Python / LangGraph / FastAPI / Next.js

#### 2.1.1 整体架构

DeerFlow 采用"Harness + App"分层架构，核心 Agent 运行时（Harness 层）作为可发布的独立包，与上层应用（Gateway API / IM 渠道）严格隔离。

```
┌─────────────────────────────────────────┐
│              Nginx (Port 2026)          │
│            统一反向代理入口              │
├──────────┬──────────┬───────────────────┤
│ Frontend │ Gateway  │  LangGraph Server │
│ (3000)   │ API(8001)│     (2024)        │
│ Next.js  │ FastAPI  │  Agent Runtime    │
└──────────┴──────────┴───────────────────┘
```

#### 2.1.2 规划机制：TodoListMiddleware + Plan Mode

DeerFlow 的规划通过两个协作机制实现：

**1. TodoListMiddleware**

当 `is_plan_mode=True` 时，Lead Agent 创建过程中注入 `TodoListMiddleware`，为 Agent 提供 `write_todos` 工具：

- Agent 可以将复杂任务分解为结构化的 Todo 列表
- 每个 Todo 项有 `pending`、`in_progress`、`completed` 三种状态
- Todo 状态通过中间件持久化到线程状态中
- 适用于 3+ 步骤的复杂任务、需要仔细规划的非平凡任务

**2. Lead Agent 的 SubAgent 编排**

Lead Agent 作为核心编排器，通过系统提示词中的 `<subagent_system>` 段落，按照 **COUNT → PLAN → EXECUTE → SYNTHESIZE** 四步工作流运行：

1. **COUNT**：评估子任务数量，确定是否超过并发限制
2. **PLAN**：制定任务分解策略，决定哪些子任务可以并行
3. **EXECUTE**：通过 `task` 工具委派给专门的 SubAgent（最多 3 个并行）
4. **SYNTHESIZE**：汇总所有子任务结果，生成最终输出

**3. SubAgent 系统**

- `task_tool` 工具接口，参数包括 `description`、`prompt`、`subagent_type`
- SubAgent 类型通过注册表管理，包括 `general-purpose` 和 `bash` 等
- 双线程池架构：Scheduler Pool（4 workers）负责编排，Execution Pool（8 workers）负责执行
- 后端轮询模式：`task_tool` 每 5 秒轮询子任务状态，避免 LLM 的浪费性轮询
- 多级超时：执行超时（300s） + 轮询超时 + LangGraph 递归限制

#### 2.1.3 中间件管线

DeerFlow 采用 14 层中间件链，严格排序：

| 位置 | 中间件 | 职责 |
|------|--------|------|
| 0-2 | ThreadDataMiddleware, SandboxMiddleware | 基础设施 |
| 4-5 | GuardrailMiddleware, ToolErrorHandlingMiddleware | 安全防护 |
| 6-9 | SummarizationMiddleware, TodoMiddleware, MemoryMiddleware | 业务逻辑 |
| 11-12 | SubagentLimitMiddleware, LoopDetectionMiddleware | 约束控制 |
| 13 | ClarificationMiddleware | 交互澄清 |

#### 2.1.4 关键设计特点

- **Harness 与 App 分离**：Agent 核心逻辑（harness）禁止导入 App 层代码，确保可移植性
- **沙箱隔离**：每个线程拥有独立的 Docker 容器、持久化工作区
- **持久化记忆**：跨会话记忆存储，支持学习型知识积累
- **Plan Mode 可选启用**：通过 `RunnableConfig` 中的 `is_plan_mode` 参数按请求开启

---

### 2.2 OpenClaw

**技术栈**：TypeScript / Node.js
**GitHub Stars**：200,000+

#### 2.2.1 整体架构

OpenClaw 将每个 Agent 建模为**有限状态机（FSM）**，架构围绕三大支柱构建：

| 支柱 | 职责 |
|------|------|
| **Kernel** | 事件总线，路由请求和事件到正确的 Agent |
| **Agent Registry** | 持有 Agent 定义和状态转移逻辑 |
| **State Manager** | 持久化 Agent 状态，支持跨重启和故障恢复 |

#### 2.2.2 推理循环（Agent Loop）

OpenClaw 的核心是一个迭代推理循环，将 Agent 从简单聊天机器人提升为自主代理：

```
Load Context → Call LLM → Parse Response → Execute Tools → Append Results → Loop
```

每次迭代的具体步骤：

1. **解读目标**：理解用户意图
2. **生成子任务计划**：将复杂目标分解为子任务
3. **选择工具**：为当前子任务挑选合适的工具
4. **执行动作**：调用工具并获取结果
5. **观察输出**：分析工具返回
6. **评估成功**：判断子任务是否完成
7. **决定是否继续**：循环或返回最终响应

#### 2.2.3 规划机制

OpenClaw 的规划是**隐式的**——规划能力嵌入在推理循环中，而非独立的 Plan 阶段：

- **上下文累积**：每次迭代的结果追加到上下文中，Agent 可以回顾之前步骤并调整计划
- **多步推理**：复杂任务通过多个循环迭代完成（如搜索 → 读取 → 总结 → 响应）
- **错误恢复**：如果工具失败，Agent 看到错误信息后可以用不同参数重试或尝试替代方案
- **会话隔离**：使用 session lanes 串行化同一 session 的运行，防止并发冲突

#### 2.2.4 多 Agent 协作

- 父 Agent 通过 `sessions_spawn` 生成多个子 Agent 并行执行
- 子 Agent 在隔离环境中运行，仅接收必要上下文
- 结果自动推回父 Agent

#### 2.2.5 关键设计特点

- **FSM 建模**：Agent 行为通过状态转移图定义，而非简单的 if-else
- **上下文窗口管理**：面对 200K token 限制，通过策略化总结或丢弃过期信息
- **无独立 Plan 模式**：规划能力完全嵌入在 Agent Loop 的推理过程中

---

### 2.3 Cursor

**技术栈**：TypeScript / Electron (IDE)
**核心特点**：编码 Agent，深度集成 IDE

#### 2.3.1 整体架构

Cursor 采用分层编排模型（Layered Orchestration Model），架构特点：

- **ReAct 循环**：推理时优化，维持 250 tokens/s 的推理速度
- **感知-行动桥接**：能感知整个项目（百万行代码），同时精准操作单个文件
- **多 Agent 协作**：多个 Agent 协同处理复杂任务

#### 2.3.2 四种操作模式

Cursor 定义了四种明确分离的操作模式：

| 模式 | 特点 | 能力 |
|------|------|------|
| **Plan Mode** | 只读，设计实现方案 | 搜索代码、阅读文件、提问澄清、生成 Markdown 计划 |
| **Agent Mode** | 自主执行，多文件操作 | 编辑代码、运行终端命令、创建文件 |
| **Ask Mode** | 只读，代码探索 | 回答问题、解释代码 |
| **Debug Mode** | 系统化排错 | 假设生成、日志分析、根因定位 |

#### 2.3.3 Plan Mode 详解

Plan Mode 是 Cursor 的核心创新之一，其工作流程：

**规划阶段：**
1. Cursor 分析代码库，搜索相关文件
2. 生成澄清问题（clarifying questions），通过交互式 UI 收集用户回答
3. 创建 Markdown 格式的计划文件，包含文件路径和代码引用
4. 用户可以直接在计划中编辑 todo 项
5. 支持计划内搜索（⌘+F）

**执行阶段：**
1. 用户审核/编辑计划后批准
2. Agent 自主执行多文件变更
3. 内循环自动捕获构建失败并修复
4. 通过验证循环（测试、lint、构建）校验变更

#### 2.3.4 Long-Running Agents

Cursor 的长时间运行 Agent（2025-2026）：
- 支持 25-36+ 小时连续运行
- 先提出计划并等待批准，而非立即执行
- 多个 Agent 互相检查工作，确保复杂任务的执行质量

#### 2.3.5 关键设计特点

- **Plan-before-Execute 原则**：先对齐方向，减少错误方案
- **计划可编辑**：用户对计划有完全控制权，可增删修改 todo
- **计划可保存**：计划以 Markdown 文件形式存入仓库，可复用
- **Spec-first 工作流**：消除约 80% 的幻觉问题
- **沙箱执行**：变更在沙箱中执行，不直接提交到主分支

---

### 2.4 Claude Code

**开发者**：Anthropic
**技术栈**：TypeScript / CLI
**核心特点**：流式优先的 Agentic Loop

#### 2.4.1 整体架构

Claude Code 的核心是一个**流式优先**的 Agent 循环：

```
接收提示 → 评估并响应 → 执行工具 → 重复（直到无工具调用） → 返回结果
```

关键技术特征：
- **SSE 流式响应**：API 响应通过 Server-Sent Events 增量到达
- **中流工具检测**：工具调用在完整响应到达前即被检测并触发执行
- **约 26 个内置工具**：Bash、Read、Write、Edit、Glob、Grep 等 + Task 元工具
- **五级权限模式**：从"所有操作都问"到完全自主

#### 2.4.2 双层规划机制

Claude Code 实现了两个互补的规划方法：

**1. Plan Mode（行为状态）**

通过 `/plan` 或 `Shift+Tab` 激活：
- 主 Agent 进入**只读模式**，限制写操作
- 探索代码库，设计实现方案
- 方案写入 Markdown 文件
- 用户可编辑后批准执行

**2. TodoWrite 工具（执行期任务管理）**

即使在非 Plan Mode 下，Agent 也可以主动使用 TodoWrite 工具：
- 维护结构化任务列表，每项有 `pending`、`in_progress`、`completed`、`cancelled` 状态
- 约束：最多 20 项，同时只能有 1 项处于 `in_progress`
- 复杂任务（3+ 步骤）时自动触发
- 实时更新状态，完成即标记

#### 2.4.3 Plan-Execute-Verify 三阶段模式

推荐的工作流遵循三阶段：

| 阶段 | 活动 |
|------|------|
| **Plan** | 分析需求 → 分解任务 → 识别依赖 → 创建 TodoWrite 列表 |
| **Execute** | 按序执行任务 → 标记进度 → 必要时调整计划 |
| **Verify** | 审查变更 → 运行测试 → 确认完整性 |

#### 2.4.4 子 Agent 系统（Task 工具）

Claude Code 通过 `Task` 工具生成子 Agent：
- Plan Mode 下可并行启动最多 3 个 Explore Agent
- 子 Agent 有独立的 subagent_type：generalPurpose、explore、shell、browser-use
- 子 Agent 运行在 depth=1，不能再生成子 Agent
- 文件系统作为唯一的协调基质（Agent Teams 模式）

#### 2.4.5 Agent Teams（多 Agent 协作）

v2.1.47 引入的分布式多 Agent 系统：
- Lead-and-Teammates 拓扑结构
- 文件系统作为唯一协调媒介
- 文件锁实现任务认领，防止竞态
- JSON 格式的 inbox 消息通信
- 每个 teammate 作为独立 CLI 进程运行

#### 2.4.6 关键设计特点

- **流式优先**：所有操作都通过 SSE 流式传输
- **工具即规划**：TodoWrite 作为 Agent 可感知的工具，而非外部框架
- **规划可见性**：计划对用户可见、可编辑
- **渐进式规划**：执行过程中可以动态调整计划

---

## 三、横向对比分析

### 3.1 规划机制对比

| 维度 | DeerFlow | OpenClaw | Cursor | Claude Code |
|------|----------|----------|--------|-------------|
| **规划方式** | TodoListMiddleware + SubAgent 编排 | 隐式（嵌入推理循环） | 独立 Plan Mode | Plan Mode + TodoWrite 工具 |
| **规划产物** | Todo 列表（状态化） | 无显式产物 | Markdown 计划文件 | Markdown 文件 + Todo 列表 |
| **规划持久化** | 线程状态存储 | 上下文内累积 | 文件系统 | 文件系统 + 内存 |
| **用户可编辑** | 否（Agent 自管理） | 否 | 是（直接编辑） | 是（审批后执行） |
| **规划与执行分离** | 是（Plan Mode 可选） | 否 | 是（模式切换） | 是（模式切换） |
| **动态调整** | 是（多批次执行） | 是（迭代推理） | 有限 | 是（TodoWrite 更新） |

### 3.2 执行模式对比

| 维度 | DeerFlow | OpenClaw | Cursor | Claude Code |
|------|----------|----------|--------|-------------|
| **执行引擎** | LangGraph + 中间件管线 | FSM + Kernel 事件总线 | ReAct Loop | 流式 Agent Loop |
| **并行执行** | SubAgent 并行（最多 3） | sessions_spawn | 多 Agent 协作 | Task 子 Agent |
| **循环控制** | max_turns + 递归限制 | 上下文窗口限制 | 自动修复循环 | max_turns + 循环检测 |
| **错误恢复** | ToolErrorHandlingMiddleware | 迭代重试 | 构建失败自动修复 | 重试 + 替代方案 |
| **超时机制** | 三级超时 | 无详细信息 | 25-36h 长运行 | 工具级超时 |
| **上下文管理** | SummarizationMiddleware | 策略化总结/丢弃 | .cursorignore | ContextCondenser |

### 3.3 可观测性对比

| 维度 | DeerFlow | OpenClaw | Cursor | Claude Code |
|------|----------|----------|--------|-------------|
| **执行进度可视** | SSE 事件流 + 子任务追踪 | 有限 | IDE 内实时显示 | 终端流式输出 |
| **步骤状态** | Todo 状态（pending/in_progress/done） | 无 | 计划中 todo 状态 | TodoWrite 状态 |
| **用户干预点** | Plan Mode 审批 | 无 | 计划审批 + 编辑 | Plan 审批 + 工具审批 |
| **子任务追踪** | task_running SSE 事件 | 无详细信息 | IDE 中展示 | Task 结果返回 |

### 3.4 动态调整能力对比

| 维度 | DeerFlow | OpenClaw | Cursor | Claude Code |
|------|----------|----------|--------|-------------|
| **执行中增删步骤** | 是（write_todos 可重写整个列表） | 无显式步骤 | 有限（依赖手动编辑） | 是（TodoWrite merge=true 可增删） |
| **Agent 自主调整** | 是（Agent 可随时调用 write_todos） | 是（隐式在推理中调整方向） | 否（需要人工编辑计划） | 是（Agent 可随时调用 TodoWrite） |
| **提前完成** | 是（Agent 可标记所有 Todo 完成） | N/A（无显式计划） | 有限（Agent 完成后自然终止） | 是（Agent 可标记所有 Todo completed） |
| **动态调整可见性** | Todo 列表实时更新（前端渲染） | 不可见 | 计划文件需手动刷新 | 终端实时输出 TodoWrite 更新 |

### 3.5 失败处理策略对比

| 维度 | DeerFlow | OpenClaw | Cursor | Claude Code |
|------|----------|----------|--------|-------------|
| **工具失败重试** | ToolErrorHandlingMiddleware 自动重试 | Agent 在循环中看到错误，可用不同参数重试 | 自动捕获构建失败并尝试修复 | Agent 看到错误后可重试（含工具级 retry） |
| **步骤级失败恢复** | Agent 可通过 write_todos 重新规划 | N/A | Agent 在循环内自动修复 | Agent 可修改 Todo 列表重新规划 |
| **人工介入机制** | SubAgent 失败后结果返回主 Agent，主 Agent 决策 | 无 | 用户审查 Agent 输出 | 权限系统：工具调用可配置为需要人工批准 |
| **不可恢复处理** | SubAgent 超时（300s）后强制终止 | 上下文窗口耗尽后终止 | 无详细信息 | 工具超时 + max_turns 终止 |

### 3.6 暂停/恢复与会话隔离对比

| 维度 | DeerFlow | OpenClaw | Cursor | Claude Code |
|------|----------|----------|--------|-------------|
| **暂停/恢复** | 不支持显式暂停（SubAgent 异步执行，主 Agent 可继续） | 不支持 | 用户可停止 Agent 运行后重新启动 | 用户可 Ctrl+C 中断，新消息重新启动循环 |
| **中途对话** | 不支持（Agent 等待 SubAgent 完成） | 无 Plan，天然支持任意对话 | Plan 是文件，用户可在其他聊天中对话 | 中断后可对话，但上下文在同一会话中 |
| **会话隔离** | SubAgent 运行在隔离上下文（不污染主对话） | session lanes 串行化 | 不同 Agent Mode 之间无显式隔离 | SubAgent (Task) 有独立上下文，主循环连续 |
| **断点续传** | LangGraph checkpointing 支持状态恢复 | 不支持 | 计划文件持久化，可从文件恢复 | 无原生断点续传 |

**关键发现**：

- **暂停/恢复是行业空白**：四个框架均未实现真正意义上的"暂停执行→中途自由对话→恢复执行且对话不污染上下文"能力。DeerFlow 的 SubAgent 异步模式最接近，但主 Agent 在等待期间无法与用户交互。
- **会话隔离已有雏形**：DeerFlow 的 SubAgent 上下文隔离和 Claude Code 的 Task 子 Agent 独立上下文提供了会话隔离的参考实现，但都是针对并行执行场景，而非"暂停-对话-恢复"场景。
- **这为 JavaClaw 提供了差异化机会**：如果 JavaClaw 能实现双轨会话隔离（Plan 轨道 + 对话轨道），将是超越业界现有方案的创新。

---

## 四、关键设计模式提炼

通过横向对比，可以提炼出以下在业界已被验证的核心设计模式：

### 模式一：Plan-Execute 双模式分离

**描述**：将 Agent 的工作流程分为"规划"和"执行"两个显式阶段，规划阶段限制为只读（不执行副作用操作），执行阶段按计划逐步推进。

**被采用**：Cursor（Plan Mode vs Agent Mode）、Claude Code（Plan Mode vs Agent Mode）、DeerFlow（is_plan_mode 参数）

**核心价值**：
- 减少 Agent 在错误方向上的大量浪费
- 给用户提供干预和纠正的机会
- Cursor 数据表明消除约 80% 的幻觉问题

### 模式二：工具化的规划（Planning as Tool）

**描述**：将规划能力作为 Agent 可调用的工具暴露，而非外部框架强制的流程。Agent 自行判断何时需要规划、何时直接执行。

**被采用**：DeerFlow（write_todos 工具）、Claude Code（TodoWrite 工具）

**核心价值**：
- Agent 对自身行为有更强的自主性
- 简单任务可以跳过规划，直接执行
- 规划本身成为上下文的一部分，LLM 可以推理和修改

### 模式三：SubAgent 并行委派

**描述**：主 Agent 作为编排器，将复杂任务分解后委派给专门的子 Agent 并行执行，子 Agent 在隔离上下文中运行。

**被采用**：DeerFlow（task_tool + SubagentExecutor）、Claude Code（Task 工具）、Cursor（多 Agent）、OpenClaw（sessions_spawn）

**核心价值**：
- 并行化加速复杂任务
- 上下文隔离防止信息污染
- 子 Agent 可以有不同的工具集和权限

### 模式四：中间件管线（Middleware Pipeline）

**描述**：将 Agent 运行时的各种横切关注点（安全、日志、限流、上下文管理等）抽象为有序的中间件链。

**被采用**：DeerFlow（14 层中间件）、JavaClaw 已有部分实现（ToolCallLoopDetector、ContextCondenser 等）

**核心价值**：
- 关注点分离，每个中间件职责单一
- 新功能通过添加中间件引入，不修改核心循环
- 中间件顺序决定执行语义

### 模式五：结构化状态追踪

**描述**：将规划和执行的状态以结构化数据（而非纯文本）管理，支持状态查询、更新和持久化。

**被采用**：DeerFlow（Todo 状态机）、Claude Code（TodoWrite with status）、Cursor（Markdown 中的 checkbox）

**核心价值**：
- 前端可以精确渲染每个步骤的状态
- 支持断点续传（从上次中断的步骤继续）
- 提供可量化的进度指标

---

## 五、结论与建议

### 5.1 核心结论

1. **Plan Mode 已成为标配**：DeerFlow、Cursor、Claude Code 三个框架均实现了独立的 Plan 模式，表明"先规划再执行"已成为复杂 Agent 任务的行业共识。

2. **工具化规划优于强制规划**：DeerFlow 和 Claude Code 将规划能力作为 Agent 工具暴露的方式，比 Cursor 的模式切换更灵活——Agent 可以自行判断是否需要规划。

3. **结构化 Todo 是最佳平衡点**：相比纯文本计划（Cursor 的 Markdown），结构化 Todo 列表（DeerFlow / Claude Code）在保持灵活性的同时提供了更好的可追踪性。

4. **SubAgent 是处理复杂性的关键**：所有四个框架都支持某种形式的子 Agent / 子任务机制，用于任务分解和并行执行。

5. **可观测性是 Plan Mode 的核心价值**：Plan Mode 的根本目的不仅是提高质量，更是让 Agent 的决策过程对用户透明可控。

6. **动态调整是成熟度的标志**：DeerFlow 和 Claude Code 均支持 Agent 在执行过程中自主增删步骤，这使得计划不再是僵硬的路线图，而是可以随执行进展自适应的活文档。

7. **失败处理普遍采用 Agent 自主优先**：所有框架都倾向于让 Agent 先自行尝试解决失败（重试、换方法、调整计划），只有在 Agent 确实无法处理时才升级到人工介入。

8. **暂停/恢复与会话隔离是行业空白**：四个框架均未实现"暂停计划→中途自由对话→恢复执行且对话不污染上下文"的能力，这为 JavaClaw 提供了差异化创新机会。

### 5.2 对 JavaClaw 的建议

基于调研结论，建议 JavaClaw 采用以下组合策略：

- **模式二（工具化规划）为核心**：`writePlan` / `updatePlan` 作为 Agent 工具始终可用，Agent 自主决定是否创建计划，不需要外部框架强制模式切换
- **模式五（结构化追踪）**：Plan 和 Step 使用结构化数据模型持久化到数据库，支持前端精确渲染
- **增强的 updatePlan**：支持 markStep / addStep / removeStep / completePlan 四种操作，覆盖动态调整和提前完成场景
- **渐进式失败降级**：Agent 自主重试 → Agent 自主调整计划 → 暂停等待用户介入
- **双轨会话隔离**（行业创新）：Plan 执行上下文与临时对话通过 `planId` 标记隔离，支持真正的暂停-对话-恢复
- WebSocket 事件流扩展 Plan 相关事件，前端实现 Plan 视图

详细技术方案见 `javaclaw-plan-mode-design.md`。

---

## 附录：参考资料

| 框架 | 主要参考源 |
|------|-----------|
| DeerFlow | [GitHub](https://github.com/bytedance/deer-flow)、[DeepWiki Architecture](https://deepwiki.com/bytedance/deer-flow/3-architecture)、[Plan Mode Usage](https://github.com/bytedance/deer-flow/blob/main/backend/docs/plan_mode_usage.md) |
| OpenClaw | [Agent Loop Docs](https://openclaws.io/docs/concepts/agent-loop)、[Reasoning Loop](https://openclawconsult.com/lab/openclaw-reasoning-loop)、[Multi-Agent Architecture](https://www.openclawplaybook.ai/guides/openclaw-multi-agent-architecture-explained/) |
| Cursor | [Introducing Plan Mode](https://cursor.com/blog/plan-mode)、[Agent Modes Docs](https://www.cursor.com/docs/agent/modes)、[Long-Running Agents](https://www.cursor.com/blog/long-running-agents) |
| Claude Code | [Agent Loop Docs](https://console.anthropic.com/docs/en/agent-sdk/agent-loop)、[Architecture Deep Dive](https://gist.github.com/yanchuk/0c47dd351c2805236e44ec3935e9095d)、[TodoWrite Planning](https://claude-world.com/tutorials/s03-planning-with-todowrite) |
