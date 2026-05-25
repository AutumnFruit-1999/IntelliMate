# IntelliMate 多 Agent 协作系统

## 概述

IntelliMate 的多 Agent 协作系统让多个 AI Agent 能够在一次对话中分工合作完成复杂任务。系统采用"主管-工人"（Supervisor-Worker）模式：一个主管 Agent 分析用户需求后，将子任务委派给专门的工人 Agent 执行，最终将各工人的结果汇总并回复用户。

系统支持三种协作方式：委派（delegateAgent）让主管将一个子任务分配给一个工人，并等待结果返回后继续推理；并行委派（delegateAgentsParallel）让多个工人同时执行不同任务；移交（handoffToAgent）让主管将整个对话控制权转交给另一个 Agent。

所有多 Agent 协作都运行在同一个 AgentRuntime 中，通过递归调用 dispatch 方法实现嵌套执行——不涉及多进程或多服务器，工人 Agent 的执行就是主管 Agent 对话循环中的一次"函数调用"。

---

## Agent 定义

### 数据库配置

每个 Agent 在数据库的 agent 表中有一条记录，包含以下核心字段：

**name**：Agent 的唯一标识名称，同时也是用户界面中的显示名称。

**model**：使用的 AI 模型标识，关联到 model_definition 表。不同的 Agent 可以使用不同的模型。

**soul_md**：Agent 的"灵魂"，用 Markdown 格式定义 Agent 的人格、语气和行为边界。例如一个编码助手的 soul_md 可能包含"你是一个专业的 Java 开发者，擅长 Spring Boot 和微服务架构"。

**agents_md**：Agent 的操作指南，定义具体的行为规则和工作流程。与 soul_md 的区别在于：soul_md 定义"你是谁"，agents_md 定义"你应该怎么做"。

**tools_enabled 和 mcp_tools_enabled**：分别控制内置工具和 MCP 工具的可用范围。

**skills_enabled 和 skill_groups_enabled**：控制技能系统的使用权限。

**can_delegate**：是否允许该 Agent 委派任务给其他 Agent。设为 1 时 Agent 拥有委派能力，设为 0 时委派工具会被从工具列表中移除。

**delegate_agents**：JSON 数组格式，列出推荐的被委派 Agent 名称。例如 ["coder", "researcher", "reviewer"]。这个字段用于在系统提示词中引导 Agent 委派给合适的工人，但系统不会在代码层面强制限制——Agent 技术上可以委派给任何已存在的 Agent。

**goal**：Agent 的能力描述，用一句话说明该 Agent 擅长什么。当其他 Agent 的委派配置中包含该 Agent 时，这个描述会出现在主管 Agent 的提示词中，帮助主管选择合适的工人。

**bridge_node**：绑定的 Bridge 节点名称。绑定后，该 Agent 的部分工具（如文件读写、命令执行）会在用户本地机器上执行而不是服务器上。

### 配置解析

AgentConfigService 负责从数据库加载 Agent 配置并与 application.yml 中的默认值合并。当 Agent 启用了委派功能（can_delegate=1）且配置了推荐的工人列表（delegate_agents）时，AgentConfigService 会做一件额外的事情：自动在 agents_md 中注入一段委派指南。

这段注入的内容列出了所有可用的工人 Agent 及其能力描述（goal 字段），并说明了三种委派工具的使用方法。这样主管 Agent 就知道有哪些工人可以委派、每个工人擅长什么、以及如何使用委派工具。

---

## 委派机制

### 委派工具

系统定义了三个委派工具，它们都是"标记工具"——不会被直接执行，而是在 AgentRuntime 中被特殊拦截和处理。

**delegateAgent**（委派）：主管 Agent 将一个子任务分配给指定的工人 Agent。参数包括 agentName（工人名称）、task（任务描述）和可选的 context（上下文信息）。主管会等待工人完成任务后继续自己的推理循环。

**delegateAgentsParallel**（并行委派）：主管 Agent 同时将多个子任务分配给不同的工人。参数是一个 JSON 数组，每个元素包含 agentName 和 task。多个工人会并发执行，全部完成后结果汇总返回给主管。

**handoffToAgent**（移交）：主管 Agent 将整个对话的控制权转交给另一个 Agent。参数包括 agentName（接管者名称）、reason（移交原因）和 contextSummary（上下文摘要）。移交后主管的对话循环立即结束。

当 Agent 的 can_delegate 配置为 false 时，这三个工具会被从该 Agent 可用的工具列表中自动移除，Agent 在对话中就不会看到也无法调用它们。

### 委派执行流程

当 AgentRuntime 检测到 LLM 返回了 delegateAgent 的工具调用时，执行以下步骤：

1. 解析参数中的 agentName、task 和 context。

2. 检查 DelegationContext 的限制条件：当前嵌套深度是否超过上限（默认最多 2 层嵌套），总委派次数是否超过上限（默认最多 10 次）。如果超过任一限制，返回错误信息给主管 Agent。

3. 发射 DelegationStart 事件，前端据此显示委派进度 UI。

4. 通过 DelegationResolver 为工人创建一个独立的数据库会话。工人会话使用特殊的 context_id 格式（"delegation::父会话ID::工人名称::委派ID"），与正常聊天会话和其他工人会话完全隔离。

5. 通过 AgentConfigService 加载工人 Agent 的完整配置。如果工人 Agent 本身不允许委派（can_delegate=0），则在本次运行中禁用其委派能力，防止无限递归。

6. 构建工人的提示词。系统使用 buildWorkerPrompt 方法将 task 和 context 组装成一条结构化的用户消息。关键点：工人不会收到主管的对话历史，只收到这条任务描述消息。

7. 递归调用 AgentRuntime.dispatch，传入工人的配置和一个深度加 1 的 DelegationContext。工人在自己的执行循环中运行，可以使用自己配置的工具和技能。

8. 工人的执行事件流被包装为 DelegationProgress 事件发送给前端，前端实时显示工人的工作进度。

9. 工人完成后（Done 或 Error 事件），系统发射 DelegationResult 事件。工人的完整回复被截断到 32000 字符后作为工具调用结果返回给主管 Agent。

10. 主管 Agent 在下一轮推理中看到工人的结果，决定是否需要进一步处理或继续委派其他任务。

### 并行委派

并行委派的流程与单次委派类似，但有几个关键区别：

- 多个工人同时启动，并发执行各自的任务。并发数受 DelegationContext 的 maxParallel 限制（默认 4 个）。

- 系统使用 Reactor 的 flatMap 操作符实现受控并发，超出并发上限的任务会排队等待。

- 所有工人完成后，主管收到一个汇总的工具调用结果（描述性文字），各工人的详细输出通过 ParallelProgress 事件流实时发送给前端。

### 移交

移交与委派有本质区别：委派是"借用"工人的能力然后拿回结果，移交是"交出"对话的控制权。

当主管 Agent 调用 handoffToAgent 时：

1. 主管的对话循环立即终止（不再继续推理）。

2. AgentRuntime 发射 HandoffStart 事件，包含移交目标、原因和上下文摘要。

3. MessagePipeline 在网关层接管后续处理。它构建一条合成的用户消息发送给接管 Agent，内容包含原始 Agent 的名称、移交原因和上下文摘要。

4. 接管 Agent 在同一个用户会话（session）上启动新的对话循环，开始直接与用户互动。

5. 接管 Agent 不会收到之前的对话历史——它只通过 LLM 提供的 contextSummary 了解之前发生了什么。

需要注意的是，移交目前不会自动更新前端的 activeAgent 状态或数据库中的会话绑定。后续用户发送的消息仍然会路由到原始 Agent（因为前端发送的 agentName 没变），除非用户手动切换 Agent。

---

## Agent 间的上下文传递

多 Agent 协作中一个重要的设计决策是：工人 Agent 不会收到主管 Agent 的对话历史。

这意味着每个工人都是从一张"白纸"开始的——它只知道被分配的任务描述（task）和可选的上下文信息（context），不知道用户之前说了什么、主管之前做了什么。

这种设计的考量是：

- 避免上下文窗口溢出。将主管的完整历史传给每个工人会快速消耗 Token 配额，尤其在多层嵌套委派的场景下。

- 强制任务描述自包含。主管必须在 task 参数中提供工人完成任务所需的所有信息，不能依赖隐式的上下文传递。

- 隔离关注点。工人只需要关注自己的任务，不需要理解整个对话的来龙去脉。

上下文共享的唯一渠道：

- 主管通过 task 和 context 参数显式传递必要信息。
- 工人可以访问自己的长期记忆（通过 MemorySystem），但记忆是按 userId 和 agentName 隔离的。
- 工人的完整回复作为工具调用结果返回给主管，主管可以从中提取信息。

---

## 安全限制

### 嵌套深度

DelegationContext 维护当前的嵌套深度（nestingDepth）。每次委派时深度加 1，默认最大深度为 2。这意味着最多支持"主管 → 工人 → 工人的工人"三层嵌套。超过深度限制时，委派调用返回错误信息。

### 总委派次数

DelegationContext 还跟踪整次对话中的总委派次数（delegationCount），默认上限为 10 次。注意这个计数是在整个调用树中共享的——所有嵌套层级的委派共用一个计数器。

### 并发限制

并行委派的最大并发数默认为 4。超出限制的任务会排队等待，不会被拒绝。

### 工具循环检测

ToolCallLoopDetector 监控每次运行中的工具调用模式。如果检测到同一工具以相同参数被调用了 3 次，会在结果中附加警告信息。调用 5 次后直接阻止执行。这个机制对所有工具（包括委派工具）统一适用。

### 委派目标控制

delegate_agents 字段虽然列出了推荐的委派目标，但这只是提示词级别的引导，不是代码级别的强制限制。Agent 技术上可以委派给任何已存在的 Agent。如果需要严格的白名单控制，需要在 AgentRuntime 中添加额外的校验逻辑。

### 对话轮次

每个 Agent（无论是主管还是工人）都受 maxTurns 限制（默认 128 轮）。一个轮次包括一次 LLM 调用和可能的工具执行。

---

## 消息路由

### 前端路由

前端通过 useAgentStore 管理当前活跃的 Agent（activeAgent），存储在 localStorage 中。用户发送消息时，前端将 activeAgent 的名称作为 agentName 参数包含在 WebSocket 消息中。

每个 Agent 在前端有独立的消息列表（chatStore 中的 messagesByAgent 映射表）。切换 Agent 时，界面切换到对应 Agent 的消息历史。

### 后端路由

MessagePipeline 从消息参数中提取 agentName，如果为空则使用配置文件中的默认 Agent。消息路由到对应 Agent 后，系统通过 AgentConfigService 加载该 Agent 的配置，创建 AgentRunRequest 并调用 AgentRuntime.dispatch。

每个 (WebSocket 会话, agentName) 组合对应一个独立的数据库会话（session），通过复合 contextId（格式为 "baseContextId::agentName"）实现隔离。这意味着同一个用户同一个浏览器与不同 Agent 的对话分别存储在不同的会话和消息记录中。

### Agent 绑定

前端在 WebSocket 连接建立后和切换 Agent 时，会发送 agent.bind 事件将当前 WebSocket 会话绑定到活跃 Agent。这个绑定关系存储在 SessionRegistry 中，用于：实时推送消息时找到正确的 WebSocket 会话、离线消息投递时确定投递目标。

---

## 系统提示词组装

AgentRuntime 在每次对话循环开始时构建系统提示词。提示词由多个标签化段落按固定顺序拼接而成：

**soul 段**：Agent 的人格定义，来自 soul_md 配置。

**agents 段**：Agent 的操作指南，来自 agents_md 配置。对于启用了委派的 Agent，这个段落已经被 AgentConfigService 注入了委派指南，列出了可用的工人 Agent 和使用说明。

**skills 段**：技能发现提示，根据 skills_enabled 和 skill_groups_enabled 配置生成。列出可用技能的名称和描述，或技能分组的概览。

**plan_system 段**：计划模式的指引（如果当前处于计划模式）。

**plan_execution 段**：当前正在执行的计划内容（如果有）。

**tool_guidelines 段**：工具使用的通用指南，包含并行调用的说明。

各段落使用类似 XML 的标签包裹，如 <soul>...</soul>、<agents>...</agents>。系统提示词总长度上限为 150000 字符。

值得注意的是，工具定义（Tool definitions）不在系统提示词中——它们通过 Spring AI 的 ChatOptions 机制以结构化格式传递给 LLM，由 LLM 的原生 function calling 能力处理。

---

## Bridge 本地执行

Bridge 功能让 Agent 能够在用户的本地机器上执行工具调用。

### 工作原理

1. 管理员通过 REST API 创建一个 Bridge 节点记录，获得一次性连接令牌和连接命令（npx intellimate-local ...）。

2. 用户在本地机器运行连接命令，本地 Bridge 客户端通过 WebSocket 连接到服务器，注册自己支持的工具列表。

3. 在 Agent 配置中将 bridge_node 设为对应的节点名称。

4. 当该 Agent 执行工具调用时，ToolsEngine 检查被调用的工具是否在 Bridge 节点注册的工具列表中。如果匹配，工具调用被透明地代理到本地 Bridge 节点执行——参数通过 WebSocket 发送到本地，本地执行后结果返回。

5. 不在 Bridge 工具列表中的工具仍然在服务器端执行。

### 委派中的 Bridge

在多 Agent 委派场景中，每个工人 Agent 使用自己配置的 bridge_node。这意味着一个绑定了本地 Bridge 的编码 Agent，即使被云端的主管 Agent 委派调用，它的文件读写和命令执行仍然在用户的本地机器上进行。这是通过在构建工人的 AgentRunRequest 时传入工人自己的 bridgeNode 配置实现的。

---

## 前端界面

### Agent 列表

侧边栏展示所有 Agent 列表，每个 Agent 显示名称。点击切换当前活跃的 Agent，界面同步切换到对应 Agent 的对话历史。支持创建新 Agent。

### Agent 配置

Agent 配置弹窗包含多个选项卡：

- SOUL：编辑 Agent 的人格定义（soul_md）
- AGENTS：编辑 Agent 的操作指南（agents_md）
- 工具选择：配置可用的内置工具
- MCP 工具：配置可用的 MCP 工具
- Skills：配置可用的技能
- 模型：选择 AI 模型
- 委派协作：配置委派能力（can_delegate 开关、选择可委派的 Agent 列表、设置能力描述）
- 心跳：配置心跳检测参数
- 任务：配置关联的定时任务

### 委派进度显示

当 Agent 在对话中执行委派时，前端通过 WebSocket 事件实时显示进度：

- workflow.delegation_start：显示委派开始，包括工人名称和任务描述
- workflow.delegation_progress：显示工人的执行过程，包括文本输出和工具调用
- workflow.delegation_result：显示工人的最终结果

并行委派有类似的事件流（workflow.parallel_start、workflow.parallel_progress）。

---

## REST API

### Agent 管理

- GET /api/agents：获取所有活跃 Agent 列表
- GET /api/agent/{name}：获取指定 Agent 的完整配置
- POST /api/agent：创建新 Agent，需要提供 name 和 model
- PUT /api/agent/{name}：更新 Agent 配置（模型、工具、技能、委派、Bridge 等）
- PUT /api/agent/{name}/context：只更新 soul_md 和 agents_md
- DELETE /api/agent/{name}：软删除 Agent（默认 Agent 不可删除）

### Bridge 管理

- GET /api/bridge/nodes：获取所有 Bridge 节点列表
- POST /api/bridge/nodes：创建节点并获取连接令牌
- DELETE /api/bridge/nodes/{id}：删除节点

---

## WebSocket 事件

多 Agent 协作相关的 WebSocket 事件类型：

| 事件 | 方向 | 说明 |
|------|------|------|
| workflow.delegation_start | 服务端推送 | 单次委派开始，包含工人名称和任务 |
| workflow.delegation_progress | 服务端推送 | 工人执行中的实时进度 |
| workflow.delegation_result | 服务端推送 | 工人完成，包含结果文本 |
| workflow.parallel_start | 服务端推送 | 并行委派开始，包含所有任务列表 |
| workflow.parallel_progress | 服务端推送 | 各工人的实时进度 |
| workflow.handoff | 服务端推送 | 对话控制权移交通知 |

---

## 设计理念

### 工具调用语义

委派在运行时本质上是一次"工具调用"。工人 Agent 的完整执行就像调用了一个非常强大的工具——输入是任务描述，输出是自然语言的执行结果。这种设计让委派能够无缝融入 Agent 的推理循环，主管可以像使用其他工具一样使用委派，也可以在委派前后做其他工具调用。

### 递归复用

所有 Agent 共享同一个 AgentRuntime 实例。委派通过递归调用 dispatch 方法实现，不需要额外的进程间通信或服务发现。每个工人 Agent 的执行享有完整的运行时能力——自己的工具、技能、记忆和配置。

### 会话隔离

每层委派创建独立的数据库会话，对话历史互不干扰。主管的对话记录和工人的工作记录分别保存，工人的结果只通过工具调用返回值传递给主管。

### 提示词引导而非代码强制

委派目标的选择通过提示词引导而非代码校验。delegate_agents 列表和 goal 描述帮助 LLM 做出合理的委派决策，但不阻止 LLM 委派给列表外的 Agent。这种方式更灵活，LLM 可以根据实际需要做出最佳判断。
