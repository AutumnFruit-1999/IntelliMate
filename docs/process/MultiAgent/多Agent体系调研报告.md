# 多 Agent 体系调研报告

> 日期: 2026-03-12  
> 状态: Final  
> 目标: 分析主流开源项目的多 Agent 协作体系，为 JavaClaw 提供设计参考

---

## 1. 调研背景

JavaClaw 当前已实现「多 Agent 管理」——用户可创建多个独立 Agent 并在前端自由切换。但每条消息仍由单个 Agent 独立处理，Agent 之间无法：
- 委派子任务给其他 Agent
- 在执行过程中移交控制权
- 并行协作后汇聚结果
- 共享中间状态和工作上下文

本报告调研 6 个代表性开源项目，分析其多 Agent 协作机制，提炼可借鉴的模式。

---

## 2. 项目概览

| 项目 | 语言 | 定位 | 核心模式 | 开源协议 |
|------|------|------|----------|----------|
| **OpenHands** (All-Hands-AI) | Python | 通用 AI 编程助手 | 层级委派 (Supervisor → Subordinate) | MIT |
| **DeerFlow 2.0** (ByteDance) | Python | 研究/内容 SuperAgent | 顺序流水线 + 并行研究团队 | Apache 2.0 |
| **Cursor 2.0** | TypeScript (闭源) | AI 代码编辑器 | Planner/Worker/Judge | 商业 |
| **LangGraph** (LangChain) | Python/JS | 多 Agent 编排框架 | 图编排 (StateGraph) | MIT |
| **CrewAI** | Python | 角色扮演协作框架 | Flow + Crew 双层编排 | MIT |
| **AutoGen** (Microsoft) | Python | 对话式多 Agent 框架 | GroupChat 群聊 | MIT |

---

## 3. 逐项分析

### 3.1 OpenHands (原 OpenDevin)

**仓库**: github.com/All-Hands-AI/OpenHands

#### 架构模式

层级式 Supervisor/Subordinate。主 Agent 拥有 `DelegateTool`，可 spawn 并管理多个子 Agent。

```
Supervisor Agent
  ├── spawn("research", description="...")
  ├── spawn("coding", description="...")
  └── delegate(task="实现用户注册功能", agents=["coding"])
         └── Sub-Agent "coding" 独立执行
               └── 返回 AgentDelegateObservation → Supervisor
```

#### Agent 定义与角色

- `Agent(llm, tools, agent_context)` — 每个 Agent 持有独立 LLM 配置和工具集
- `AgentContext(skills, system_message_suffix)` — 角色通过 system message 和 skills 定义
- 内置角色：`explore`（只读探索）、`bash`（命令行）、自定义 Agent
- `register_agent(name, factory, description)` 动态注册

#### 通信与委派

- **事件驱动**: `MessageAction` → `AgentDelegateObservation` / `AgentFinishAction`
- **DelegateTool** 提供两个命令：
  - `spawn(id, description)` — 创建子 Agent（不立即执行）
  - `delegate(task, agents)` — 分发任务，**阻塞**直到所有子 Agent 完成
- 子 Agent 并行运行（每个一个线程），Supervisor 等待所有结果

#### 共享上下文

- **共享工作区**: 所有 Agent 操作同一文件系统（Docker 容器）
- **独立对话**: 每个子 Agent 维护自己的对话历史
- **结果回传**: 子 Agent 仅返回最终结果给 Supervisor，中间推理不可见
- `max_children` 限制并发数

#### 工具分配

- 子 Agent 继承父 Agent 的 LLM 配置
- 工具独立配置：Supervisor 有 DelegateTool，子 Agent 有各自的工具集
- 通过 `register_tool` 按需注册

#### 对 JavaClaw 的启示

- **DelegateTool 模式非常适合 JavaClaw**：Agent 通过 tool call 触发委派，无需修改 LLM 调用链
- 共享文件系统 + 独立对话的隔离策略可直接复用
- spawn/delegate 两阶段设计允许灵活的任务编排

---

### 3.2 DeerFlow 2.0 (ByteDance)

**仓库**: github.com/bytedance/deer-flow

#### 架构模式

基于 LangGraph 的**扁平调度架构**（Flat Dispatcher），采用固定流水线 + 可选并行：

```
Coordinator → Planner → [Human Feedback]
  → Research Team (并行)
    ├── Researcher A
    ├── Researcher B
    └── Coder
  → Reporter → [Human Review] → Final Output
```

不同于 LangGraph 的通用 Supervisor 模式，DeerFlow 选择了**结构化流水线**，每个阶段有明确职责。

#### Agent 定义与角色

- **Coordinator**: 分析任务，决定是否需要研究（可直接回答简单问题）
- **Planner**: 将复杂任务分解为子步骤，生成研究计划
- **Researcher**: 使用搜索和爬虫收集信息
- **Coder**: 在沙箱中执行代码（数据分析、可视化）
- **Reporter**: 综合所有研究结果，生成最终报告/PPT/播客

#### 通信与委派

- LangGraph State 作为数据总线，各节点通过 State 传递信息
- Planner 将任务分解为步骤列表，Research Team 按步骤并行执行
- 人工反馈节点允许用户修改计划后再继续

#### 共享上下文

- **LangGraph State**: 全局状态在节点间流转（query → plan → research_results → report）
- **长期记忆**: 跨会话持久化
- **上下文隔离**: 每个 Researcher 只看到自己的子任务描述，不看其他 Researcher 的结果
- **文件系统**: Docker 沙箱中的共享文件系统

#### 工具分配

- **渐进式加载**: 工具按需分配，不是一次全给
- Researcher 拥有搜索和爬虫工具
- Coder 拥有代码执行和文件操作工具
- Reporter 拥有报告生成和格式化工具
- 支持 MCP Server 和自定义 Python 函数作为工具

#### 对 JavaClaw 的启示

- 固定流水线适合**特定场景**（如研究报告），但通用性不如 Supervisor
- Human Feedback 节点的设计值得借鉴——用户可以在关键环节介入
- 上下文隔离策略（子 Agent 只看子任务）能有效控制 token 消耗

---

### 3.3 Cursor 2.0

**来源**: cursor.com/blog (闭源，根据公开信息分析)

#### 架构模式

Planner/Worker/Judge 三层模型，支持高并发：

```
Planner (持续探索代码库，生成任务图)
  ├── Worker A (独立 worktree)
  ├── Worker B (独立 worktree)
  ├── ...最多 8 个并行
  └── Worker H (独立 worktree)
Judge (评估每轮结果，决定是否继续)
```

#### Agent 定义与角色

- **Planner**: 分析代码库，创建任务图（含依赖关系、元数据、约束条件），可递归 spawn 子 Planner
- **Worker**: 纯执行角色，专注完成分配的任务，不与其他 Worker 协调
- **Judge**: 评估执行结果，决定继续/停止/重试

#### 通信与委派

- Planner 创建 Task Graph，Worker 从中领取任务
- Worker 之间**不直接通信**，通过 Planner 间接协调
- 每轮 Worker 最多 25 个 tool call

#### 共享上下文

- **Shadow Virtual File System (SVFS)**: 每个 Worker 写入独立虚拟文件树，逻辑合并后呈现给用户审批
- **Git Worktree 隔离**: 每个 Worker 在独立 worktree 中操作，避免文件冲突
- 共享代码库（只读），各 Worker 的写操作隔离

#### 工具分配

- 所有 Worker 共享相同工具集（文件读写、终端、搜索等）
- 每轮最多 25 个 tool call
- Planner 有代码搜索和探索专用工具

#### 对 JavaClaw 的启示

- **并行隔离策略**是亮点：Git worktree + SVFS 解决了并发写冲突
- Planner/Worker 分离适合大规模重构场景
- 但成本高（并行执行 25-35% token 开销），适合高端场景
- Judge 角色的引入可提升结果质量

---

### 3.4 LangGraph

**仓库**: github.com/langchain-ai/langgraph

#### 架构模式

**图编排 (StateGraph)** — 最通用的多 Agent 框架：

```
StateGraph:
  Node_A (Agent/Function)
    ↓ conditional_edge
  Node_B (Agent/Function)
    ↓ normal_edge
  Node_C (Agent/Function)
    ↓
  END

Supervisor 模式:
  Supervisor ──→ Worker_A ──→ Supervisor
      │                           ↑
      └──→ Worker_B ──────────────┘
```

#### Agent 定义与角色

- **Node**: 图中的节点，可以是 Agent、函数、或子图
- **Supervisor**: 中央路由节点，分析意图后将任务路由到合适的 Worker
- **Worker**: 专业化子 Agent，执行具体任务后将结果返回 Supervisor
- 通过 `create_supervisor(agents, model, tools, prompts)` 快速创建

#### 通信与委派

- **Typed State**: 强类型状态对象在节点间传递（TypedDict / Pydantic）
- **边 (Edge)**: 
  - Normal Edge — 固定路由
  - Conditional Edge — 基于状态的条件路由
  - Entry/Exit Edge — 子图入口/出口
- **Handoff Tool**: `create_handoff_tool("transfer_to_X")` 实现 Agent 间直接移交
- **Supervisor 路由**: Supervisor 通过 LLM 决策将任务路由到哪个 Worker

#### 共享上下文

- **Shared State**: 全局状态在所有节点间共享，可包含 query、intermediate_results、final_answer 等
- Worker 的内部推理不污染全局状态，仅回写最终结果
- 支持状态持久化（Checkpointing）用于恢复和调试

#### 工具分配

- 每个 Agent/Node 独立配置工具
- Supervisor 可以没有工具（纯路由）或有自己的工具
- Worker 按专业领域配置工具

#### 对 JavaClaw 的启示

- **StateGraph 模型是最灵活的编排方案**，但实现复杂度高
- **Supervisor + Handoff 混合模式**是生产级推荐方案
- **LangGraph4j** 提供了 Java 实现，可直接参考（Spring AI 集成）
- Conditional Edge 的概念可简化为 Supervisor 的路由逻辑

---

### 3.5 CrewAI

**仓库**: github.com/joaomdmoura/crewAI

#### 架构模式

**Flow + Crew 双层编排**:

```
Flow (控制流)
  ├── Step 1: 接收输入
  ├── Step 2: 触发 Crew A
  │     └── Crew A:
  │           ├── Agent "Researcher" (role/goal/backstory)
  │           ├── Agent "Analyst" (role/goal/backstory)
  │           └── Task 链: research → analyze → report
  ├── Step 3: 条件分支
  └── Step 4: 触发 Crew B (可选)
```

#### Agent 定义与角色

CrewAI 的 Agent 定义最具表达力：

- **role**: 职位名（如 "Senior Data Researcher"）
- **goal**: 优化目标（如 "Find accurate and up-to-date data"）
- **backstory**: 背景故事（如 "Expert with 10 years experience in..."）
- **tools**: 可用工具列表
- **allow_delegation**: 是否允许委派给其他 Agent

这种 Role-Playing 模式让 LLM 更好地进入角色。

#### 通信与委派

- **Flow → Crew**: Flow 触发 Crew 执行，Crew 返回结果
- **Crew 内部**: 按 Task 链顺序执行，或并行执行
- **Agent 间委派**: 如果 `allow_delegation=True`，Agent 可以请求其他 Agent 帮助
- Flow 层管理全局状态、条件分支、循环

#### 共享上下文

- **Flow State**: 跨 Step 的全局状态
- **Crew Shared Thread**: Crew 内部 Agent 共享消息线程
- **Memory 系统**: 短期（当前任务）、长期（跨任务持久化）、实体记忆（知识图谱）

#### 工具分配

- 每个 Agent 独立配置 `tools` 列表
- 支持 API、数据库、本地工具
- Crew 级别可配置 `max_rpm`（速率限制）

#### 对 JavaClaw 的启示

- **Role/Goal/Backstory 三元组**是优秀的角色定义模型，比纯 system prompt 更结构化
- Flow + Crew 双层设计值得借鉴：Flow 管理业务逻辑，Crew 管理 Agent 协作
- `allow_delegation` 标志简洁有效
- Memory 系统对长期运行的工作流很重要

---

### 3.6 AutoGen (Microsoft)

**仓库**: github.com/microsoft/autogen

#### 架构模式

**GroupChat 群聊模式**:

```
GroupChatManager
  ├── Agent A (ConversableAgent)
  ├── Agent B (ConversableAgent)
  ├── Agent C (ConversableAgent)
  └── Speaker Selection:
        - round_robin: 轮流发言
        - random: 随机选择
        - auto: LLM 决定下一个发言者
        - manual: 人工选择
```

#### Agent 定义与角色

- **ConversableAgent**: 统一抽象，可以是 LLM Agent、工具 Agent、人类 Agent 或混合
- 角色通过 `system_message` 定义
- 无显式的 role/goal 结构

#### 通信与委派

- **共享消息线程**: 所有 Agent 看到同一个对话
- **发言选择**: `GroupChatManager` 决定谁下一个发言
- **SelectorGroupChat**: 使用 LLM 基于上下文选择最合适的发言者
- v0.4 引入事件驱动的 Actor 模型（异步）

#### 共享上下文

- **完全共享**: 所有 Agent 看到完整的对话历史
- 优点：上下文丰富，Agent 可以互相补充
- 缺点：对话越长 token 消耗越大，容易跑题

#### 工具分配

- 每个 Agent 独立配置工具
- `AgentTools` 接口

#### 对 JavaClaw 的启示

- GroupChat 模式适合**头脑风暴/讨论**场景，但不适合**任务执行**
- 完全共享上下文的方式 token 消耗过大，不推荐用于生产
- SelectorGroupChat 的 LLM 发言选择可作为 Supervisor 路由的参考
- v0.4 的 Actor 模型更适合分布式场景

---

## 4. 横向对比

### 4.1 编排模式对比

| 项目 | 编排模式 | 控制方式 | 灵活性 | 可预测性 |
|------|----------|----------|--------|----------|
| OpenHands | 层级委派 | Supervisor 通过 DelegateTool | 中 | 高 |
| DeerFlow | 固定流水线 | 预定义节点顺序 | 低 | 很高 |
| Cursor | Planner/Worker | Planner 创建 Task Graph | 高 | 中 |
| LangGraph | 图编排 | StateGraph + 条件路由 | 很高 | 取决于设计 |
| CrewAI | Flow + Crew | 双层控制 | 高 | 高 |
| AutoGen | 群聊 | Speaker Selection | 中 | 低 |

### 4.2 Agent 粒度对比

| 项目 | Agent 定义 | 角色区分 | 动态创建 |
|------|------------|----------|----------|
| OpenHands | LLM + Tools + Context | spawn 时指定 | 支持 |
| DeerFlow | 预定义节点 | 固定角色 | 不支持 |
| Cursor | Planner/Worker/Judge | 固定三类 | Worker 可动态 |
| LangGraph | Node (Agent/Function) | 开发者定义 | 支持 (子图) |
| CrewAI | Role + Goal + Backstory | 结构化定义 | 支持 |
| AutoGen | ConversableAgent | system_message | 支持 |

### 4.3 上下文管理对比

| 项目 | 共享范围 | 隔离策略 | 结果回传 |
|------|----------|----------|----------|
| OpenHands | 共享文件系统 | 独立对话历史 | 仅最终结果 |
| DeerFlow | LangGraph State | 子 Agent 只看子任务 | State 更新 |
| Cursor | 共享代码库(只读) | Git worktree 写隔离 | SVFS 合并 |
| LangGraph | Typed Shared State | Worker 内部隔离 | State 写回 |
| CrewAI | Flow State + 共享线程 | Crew 间隔离 | 结果回传 |
| AutoGen | 完全共享对话 | 无隔离 | 直接在线程中 |

### 4.4 并行支持对比

| 项目 | 并行支持 | 最大并发 | 并发隔离 |
|------|----------|----------|----------|
| OpenHands | 支持 (线程) | max_children 可配 | 共享工作区 |
| DeerFlow | Research Team 并行 | 按子任务数 | 子任务隔离 |
| Cursor | 强并行 | 8 个 Worker | Git worktree |
| LangGraph | Fan-out / Map-Reduce | 无限制 | State 分片 |
| CrewAI | Crew 内可并行 | 无限制 | Task 隔离 |
| AutoGen | 不支持 (顺序发言) | 1 | N/A |

### 4.5 适用场景对比

| 项目 | 最佳场景 | 不适合场景 |
|------|----------|------------|
| OpenHands | 复杂编程任务，需要子任务分解 | 简单对话 |
| DeerFlow | 研究报告，内容生产 | 需要灵活路由的场景 |
| Cursor | 大规模代码重构 | 非编程任务 |
| LangGraph | 任何复杂的 Agent 编排 | 简单场景(过度工程化) |
| CrewAI | 角色明确的团队协作 | 动态、不确定的任务 |
| AutoGen | 讨论、头脑风暴 | 需要严格流程控制 |

---

## 5. 关键设计模式总结

### 5.1 三大编排模式

```
模式一: Supervisor (Hub-and-Spoke)
─────────────────────────────────
      Supervisor
      /    |    \
  Worker  Worker  Worker

适用: 任务可明确分解的场景
代表: LangGraph Supervisor, OpenHands


模式二: Pipeline (Sequential)
─────────────────────────────
  Step A → Step B → Step C → Output

适用: 流程固定的场景 (研究报告、代码审查流水线)
代表: DeerFlow


模式三: GroupChat (Peer-to-Peer)
─────────────────────────────────
  Agent A ↔ Agent B ↔ Agent C
  (共享消息线程, Manager 选择发言者)

适用: 开放式讨论、头脑风暴
代表: AutoGen
```

### 5.2 通信模式

| 模式 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **Tool-based 委派** | Agent 通过 tool call 触发子 Agent | 无需改造 LLM 调用链 | 委派粒度受限于 tool schema |
| **State-based 传递** | 通过全局 State 对象传递上下文 | 类型安全，可追踪 | 需要预定义 State schema |
| **Message-based 对话** | 共享消息线程 | 简单直接 | token 消耗大，难以控制 |
| **Event-based 事件** | 异步事件驱动 | 解耦，可扩展 | 调试困难 |

### 5.3 上下文隔离策略

推荐策略（综合各项目最佳实践）：

1. **共享工作区**: 所有 Agent 可访问相同的文件系统/代码库
2. **独立对话历史**: 每个 Agent 维护自己的对话上下文
3. **仅结果回传**: 子 Agent 只返回最终结果给父 Agent，中间推理不暴露
4. **上下文压缩**: 长任务的中间结果进行摘要后再传递

---

## 6. 对 JavaClaw 的综合建议

### 6.1 推荐模式

**Supervisor + Handoff 混合模式**（结合 LangGraph 和 OpenHands 的优势）：

- 使用 **Tool-based 委派**（参考 OpenHands DelegateTool），Agent 通过 tool call 触发委派
- 支持 **Handoff**（参考 LangGraph），允许 Agent 间直接移交控制权
- 可选 **并行执行**（参考 Cursor），多个 Worker 同时工作

不推荐 GroupChat 模式（AutoGen），因为完全共享上下文的 token 消耗过高，且不适合任务执行场景。

### 6.2 实现策略

- **复用现有 AgentRuntime**: 不引入新的执行引擎，子 Agent 通过 AgentRuntime 执行
- **DelegateAgentTool 作为桥梁**: Supervisor 通过标准 tool call 触发子 Agent，与现有 ToolsEngine 集成
- **渐进式实施**: P0 先实现单向委派，P1 加 Handoff 和并行，P2 做动态 Agent 和统计

### 6.3 角色定义

参考 CrewAI 的 Role/Goal/Backstory 三元组，为 Agent 增加结构化的角色描述，而非仅依赖 system prompt 文本。

### 6.4 上下文管理

采用 OpenHands 的策略：
- 共享文件系统（已有的 Skills 目录、工作区文件）
- 独立对话历史（已有的 Session 隔离）
- 子 Agent 仅返回最终结果

---

## 7. 参考链接

| 项目 | 链接 |
|------|------|
| OpenHands | https://github.com/All-Hands-AI/OpenHands |
| OpenHands Delegation Docs | https://docs.openhands.dev/sdk/guides/agent-delegation |
| DeerFlow | https://github.com/bytedance/deer-flow |
| Cursor Agent Blog | https://cursor.com/blog/scaling-agents |
| LangGraph | https://github.com/langchain-ai/langgraph |
| LangGraph4j (Java) | https://bsorrentino.github.io/langgraph4j/ |
| LangGraph Supervisor Ref | https://reference.langchain.com/python/langgraph/supervisor/ |
| CrewAI | https://github.com/joaomdmoura/crewAI |
| CrewAI Flows Docs | https://docs.crewai.com/en/concepts/flows |
| AutoGen | https://github.com/microsoft/autogen |
