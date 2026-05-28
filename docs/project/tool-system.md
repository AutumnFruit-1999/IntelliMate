# IntelliMate 工具系统

## 概述

工具系统是 IntelliMate 中 AI Agent 与外部世界交互的核心能力层。当 Agent 在对话中需要读写文件、执行命令、搜索网页或管理任务时，都是通过调用工具来完成的。

系统基于 Spring AI 的 ToolCallback 机制构建，但接管了工具执行的控制权——AgentRuntime 不依赖 Spring AI 的内置工具执行，而是自行管理工具的调度、并行执行、循环检测、审批门控和重试逻辑。

工具来源有四种：内置工具（通过 @Tool 注解定义的 Java 方法）、自定义 HTTP 工具（通过数据库配置的 HTTP API 调用）、MCP 工具（从外部 MCP 服务器动态发现）和 Bridge 工具（代理到用户本地机器执行）。所有工具通过 ToolsEngine 统一注册和管理。

---

## 工具注册引擎

ToolsEngine 是工具系统的核心枢纽，负责收集、合并和过滤所有可用工具。

### 工具收集

ToolsEngine 在构造时收集所有 Spring 容器中的 ToolCallbackProvider Bean，获取它们提供的 ToolCallback 数组。这些构成了系统的内置工具集。

在 refresh 方法被调用时（应用启动、自定义工具 CRUD、MCP 服务器变更时），ToolsEngine 将三个来源的工具合并为完整的工具列表：内置工具（Spring Bean 提供）加上自定义 HTTP 工具（数据库驱动）加上 MCP 工具（外部服务器提供）。

### 工具过滤

每次对话请求时，ToolsEngine.getToolCallbacksFor 方法根据 Agent 的配置过滤工具。

tools_enabled 参数控制内置和自定义工具的可用范围。留空或设为 "full" 时所有工具可用。设为预定义的配置文件名（coding、messaging、minimal）时使用预设子集。设为 JSON 数组时精确指定允许的工具名称列表。

mcp_tools_enabled 参数独立控制 MCP 工具。留空时不提供任何 MCP 工具。设为 "full" 时所有已连接的 MCP 工具可用。设为 JSON 数组时精确指定。

如果 Agent 配置了 bridge_node 且对应节点在线，过滤后的工具列表中匹配的工具会被透明替换为 Bridge 工具回调，执行时会路由到用户本地机器。

---

## 内置工具

系统内置了约 22 个工具，按功能分为六个工具组。

### 文件系统组

**readFile**（读取文件）：接受文件路径作为必填参数，支持可选的起始行号和行数参数实现部分读取。返回的内容每行以行号加竖线前缀标记（如 "1|第一行内容"），方便 Agent 引用具体行。部分读取时会在结果末尾附加范围说明。

**writeFile**（写入文件）：接受文件路径和内容。自动创建不存在的父目录，将内容完整写入文件。返回写入的字符数。

**editFile**（编辑文件）：接受文件路径、旧文本和新文本。执行精确的单次替换——如果旧文本在文件中出现零次或多于一次，会返回错误要求 Agent 提供更精确的匹配文本。

### 运行时组

**exec**（执行命令）：接受命令字符串，通过 sh -c 执行。支持可选的工作目录和超时时间（默认 30 秒）。将标准输出和标准错误合并返回，附带退出码。超时时强制终止进程。

### 网络组

**webSearch**（网页搜索）：接受搜索查询和可选的最大结果数（默认 5）。如果配置了 SerpAPI 密钥，使用 Google 搜索；否则返回提示信息说明搜索功能未配置。

**webFetch**（获取网页）：接受 URL 和可选的超时时间（默认 15 秒）。发送 HTTP GET 请求，返回响应状态码和正文内容。

### 委派协作组

**delegateAgent**（委派任务）、**handoffToAgent**（移交对话）和 **delegateAgentsParallel**（并行委派）是三个特殊的"标记工具"。它们的 @Tool 方法实际上会抛出异常——真正的执行逻辑在 AgentRuntime 中被特殊拦截和处理。关于这三个工具的详细说明请参见多 Agent 协作系统文档。

当 Agent 的 can_delegate 配置为 false 时，这三个工具会被自动从可用工具列表中移除。

### 技能组

**getSkillContent**（获取技能内容）和 **listSkillsByGroup**（按分组列出技能）是技能系统的接口工具。关于它们的详细说明请参见技能系统文档。

只有在系统中存在 SkillContentProvider Bean 时这两个工具才会被注册。

### 计划工具

**writePlan**（创建计划）：接受标题和步骤列表（每步包含标题和描述），通过 PlanOperations 创建一个分步骤的执行计划。返回 JSON 格式的结果，包含计划 ID、状态和消息。工具内置了健壮的 JSON 解析逻辑，支持宽松模式和修复策略，应对 LLM 可能产生的格式不标准的 JSON。

**updatePlan**（更新计划）：接受计划 ID 和操作类型。支持四种操作：markStep（标记步骤状态）、addStep（添加步骤）、removeStep（移除步骤）和 completePlan（完成计划）。

计划工具不通过 @Tool 注解注册，而是直接实现 ToolCallback 接口，以避免 Spring AI 对 JSON 返回值的双重编码。

### 任务管理组

**createTodoTask**（创建待办任务）：接受标题、可选的描述、截止时间、提醒时间和优先级（0=普通、1=重要、2=紧急）。Agent 在对话中可以为用户创建待办事项。

**listTodoTasks**（列出待办任务）：支持按状态过滤（待处理、已完成、已取消、全部），默认返回最多 20 条。

**updateTodoTask**（更新待办任务）和 **deleteTodoTask**（删除待办任务）：通过任务 ID 操作。

**createScheduledJob**（创建定时任务）：接受显示名称、触发类型（CRON/FIXED_RATE/FIXED_DELAY）、触发值、任务类型（agent-prompt 或 http-callback）和参数。创建 agent-prompt 类型任务时自动注入当前 Agent 名称。

**listScheduledJobs**（列出定时任务）、**updateScheduledJob**（更新定时任务）和 **deleteScheduledJob**（删除定时任务）。更新和删除操作会保护系统内置任务（heartbeat-tick、memory-nightly-maintenance、data-cleanup）不被修改或删除。

任务管理工具在执行时通过 AgentContext（ThreadLocal）获取当前 Agent 的数据库 ID 和名称，将任务关联到正确的 Agent。

---

## 自定义 HTTP 工具

用户可以通过数据库表 tool_definition 定义自定义的 HTTP API 工具，无需编写任何代码。

每个自定义工具的核心配置包含执行配置（execution_config JSON 字段），其中指定了目标 URL、HTTP 方法（GET/POST/PUT/PATCH）、请求头、请求体模板和响应提取路径。

URL 和请求体模板支持两种变量替换：${paramName} 从工具调用的参数中取值，${env:VAR} 从环境变量中取值。例如 URL 模板 "https://api.example.com/search?q=${query}" 中的 ${query} 会被替换为 Agent 调用时传入的 query 参数值。

参数模式（parameters_schema 字段）使用 JSON Schema 格式定义工具的输入参数结构。当 Agent 调用工具时，LLM 会根据这个模式生成符合格式的参数。

响应提取（responseExtract 字段）支持 JsonPath 表达式，从 HTTP 响应中提取特定的数据节点。如果不配置，返回完整的响应内容。

自定义工具可以设置超时时间（默认 30 秒）和启用状态。禁用的工具不会出现在任何 Agent 的工具列表中。

DynamicToolProviderImpl 在应用启动时和每次 CRUD 操作后重新加载所有已启用的自定义工具，并调用 ToolsEngine.refresh() 更新全局工具表。

目前只实现了 HTTP_API 类型的自定义工具。数据库模式中定义了 SHELL_COMMAND 和 BUILTIN_OVERRIDE 类型，但没有对应的运行时实现。

---

## 工具执行流程

当 LLM 在对话中返回工具调用指令时，AgentRuntime 的 processToolCalls 方法接管执行。

### 工具分类

首先，系统将 LLM 返回的工具调用按类型分为四类：

- 直接工具（direct）：普通的工具调用，如 readFile、exec、webSearch 等
- 审批工具（approval）：配置了需要人工审批的工具
- 委派工具（delegation）：delegateAgent 和 delegateAgentsParallel
- 移交工具（handoff）：handoffToAgent（每轮最多一个，且会终止当前循环）

### 执行顺序

分类后按以下顺序执行：

1. 如果当前处于计划执行模式，自动将待执行的计划步骤标记为进行中。

2. 发射所有工具调用的 ToolCall 事件给前端。

3. 执行所有直接工具。独立的工具调用可以并行执行，最大并发数由 max-parallel-tool-calls 配置控制（默认 8）。

4. 执行审批工具。按顺序逐个执行，每个工具等待用户确认后再继续。

5. 执行委派工具。按顺序执行。

6. 如果有移交调用，在所有其他工具完成后终止当前循环。

7. 收集所有工具结果，作为 ToolResponseMessage 追加到对话历史中，进入下一轮 LLM 推理。

### 单个工具的执行

每个工具调用经过以下中间件链：

**循环检测**：ToolCallLoopDetector 维护一个滑动窗口（默认大小 8），记录最近的工具调用签名（工具名+参数哈希）。如果同一签名出现了 3 次（可配置），会在结果中附加警告信息。出现 5 次（可配置）时直接阻止执行，返回循环检测错误。这个机制防止 Agent 陷入"反复调用同一工具但不改变行为"的死循环。可以通过配置排除特定工具不受检测。

**结果缓存**：ToolResultCache 维护一个最近 50 次调用的 LRU 缓存。只读工具（readFile、webFetch、getSkillContent）的结果会被缓存。当写入工具（writeFile、editFile）执行后，相关缓存会被清除。exec 执行后清空所有缓存。缓存命中时在结果中附加 [缓存结果] 标记。

**实际执行**：调用 ToolCallback.call 方法，传入 JSON 格式的参数字符串。执行受超时限制（默认 60 秒），超时时抛出异常。

**重试**：对于可重试的错误（网络超时、连接异常、429 限流），系统最多重试 2 次，每次间隔 1 秒。文件未找到、参数错误、安全异常等不可重试。可以通过配置将特定工具标记为不可重试。

---

## 工具审批

系统支持为敏感工具配置人工审批门控。通过 application.yml 中的 approval-required-tools 列表配置需要审批的工具名称。

当 Agent 调用一个需要审批的工具时：

1. AgentRuntime 发射 ApprovalRequired 事件，通过 WebSocket 发送给前端。
2. 执行暂停，等待用户响应。
3. 用户发送 approve_tool 消息，可以选择批准执行、修改参数后批准、或拒绝执行。
4. 批准后使用原始或修改后的参数执行工具。拒绝后返回"用户拒绝了工具执行"的结果给 Agent。

需要注意的是，工具审批功能的后端实现已完成，但前端目前没有对应的审批 UI 组件——前端没有处理 agent.approval_required 事件的逻辑。计划审批（plan.awaiting_approval）有完整的 UI 支持，但工具级别的审批 UI 尚未实现。

审批配置目前是全局的（YAML 级别），不支持按 Agent 独立配置。

---

## 工具配置文件

系统预定义了四种工具配置文件（ToolProfile），用于快速配置 Agent 的工具子集：

**FULL**：包含 exec、readFile、writeFile、editFile、webSearch、webFetch 以及所有任务管理工具。注意：FULL 配置文件并不包含委派工具、技能工具和计划工具——这些需要通过 "full" 字符串值（即 tools_enabled 留空或设为 "full"）才能全部启用。

**CODING**：仅包含编码相关工具：exec、readFile、writeFile、editFile。

**MESSAGING**：仅包含网络工具：webSearch、webFetch。

**MINIMAL**：不包含任何工具。

这里有一个容易混淆的地方：tools_enabled 设为 "full" 字符串表示"所有已注册的工具"，而选择 FULL 配置文件只包含上述明确列出的工具子集。

---

## 工具组

工具被分为六个逻辑组，用于前端的分组展示和批量选择：

- 文件系统（FS）：readFile、writeFile、editFile
- 运行时（RUNTIME）：exec
- 网络（WEB）：webSearch、webFetch
- Skills（SKILLS）：getSkillContent、listSkillsByGroup
- 委派协作（DELEGATION）：delegateAgent、handoffToAgent、delegateAgentsParallel
- 任务管理（TASK）：4 个待办任务工具 + 4 个定时任务工具

计划工具（writePlan、updatePlan）和自定义工具不属于任何预定义分组。自定义工具在元数据接口中显示为 CUSTOM 分组。

---

## Bridge 本地执行

当 Agent 配置了 bridge_node 且对应的 Bridge 节点在线时，部分工具的执行会被透明地代理到用户的本地机器。

Bridge 客户端连接到服务器后，注册自己支持执行的工具名称列表。在 Agent 对话请求时，ToolsEngine 检查每个工具是否在 Bridge 注册的列表中。匹配的工具回调被替换为 BridgeToolCallback，执行时将参数通过 WebSocket 发送到本地 Bridge 客户端，由本地客户端在用户机器上执行后返回结果。超时时间为 120 秒。

Bridge 替换是透明的——LLM 看到的工具定义（名称、描述、参数模式）完全不变，只是执行位置从服务器转移到了用户本地。不在 Bridge 注册列表中的工具仍然在服务器端执行。

---

## 并行工具执行

当 LLM 在一次回复中返回多个工具调用时，AgentRuntime 可以并行执行它们以提高效率。

并行执行的前提条件：Agent 使用的模型支持并行工具调用（通过 enable-parallel-tool-calls 配置启用，默认开启），且 LLM 在系统提示词中被引导了并行调用的使用方法。

运行时的并行策略：只有直接工具（非审批、非委派、非移交）才会并行执行。使用 Reactor 的 flatMap 操作符实现受控并发，并发上限为 max-parallel-tool-calls（默认 8）。审批工具必须按顺序逐个执行（等待用户确认），委派工具也按顺序执行。

---

## REST API

### 工具元数据

GET /api/tools：返回所有已注册工具的元数据列表、配置文件定义和工具组定义。每个工具的元数据包含名称、描述、来源（builtin/custom/mcp）和所属分组。MCP 工具包含在列表中但不纳入分组。

### 自定义工具管理

所有接口前缀为 /api/tool-definitions。

- GET /api/tool-definitions：列出所有自定义工具
- GET /api/tool-definitions/{id}：获取指定工具详情
- POST /api/tool-definitions：创建新工具。名称必须以字母开头，只允许字母、数字、下划线和连字符，长度 2-64 字符
- PUT /api/tool-definitions/{id}：更新工具配置
- DELETE /api/tool-definitions/{id}：删除工具
- POST /api/tool-definitions/{id}/test：使用提供的测试参数执行工具调用，返回实际结果

每次创建、更新或删除操作后，系统自动重新加载自定义工具并刷新全局工具表。

---

## 前端工具管理

### Agent 工具配置

Agent 配置弹窗中的"工具选择"选项卡（ToolsTab）从 /api/tools 加载工具列表和分组信息。界面提供四个配置文件快捷按钮（全部/编码/消息/最少），也支持按分组勾选具体工具。

选择了所有工具时存储为 null（等同于 "full"），选择为空时存储为 "minimal"，部分选择时存储为 JSON 数组。

### 自定义工具管理

工具管理页面包含"自定义工具"选项卡，提供完整的 CRUD 操作。自定义工具编辑器包括：HTTP 配置的可视化编辑器（URL、方法、请求头、请求体模板、JsonPath 响应提取）、参数模式构建器（生成 JSON Schema）和内联测试功能。

### 对话中的工具显示

前端通过 WebSocket 事件 agent.tool_call 和 agent.tool_result 在对话界面中显示工具调用。ToolCallCard 组件根据工具类型显示不同的图标和内容：文件工具显示文件图标，网络工具显示地球图标，命令执行显示终端图标。

特定的任务管理工具（createTodoTask 等）和定时任务工具（createScheduledJob 等）不使用通用的工具卡片，而是渲染为专用的 TaskCard 和 ScheduledJobCard 组件，提供更丰富的交互功能（如完成任务、暂停定时任务等）。

---

## 运行时配置

以下是工具系统的主要配置项，位于 application.yml 的 intellimate.agent 节点下：

- **tool-execution-timeout-seconds**（默认 60）：单个工具调用的超时时间
- **max-parallel-tool-calls**（默认 8）：直接工具的最大并行执行数
- **enable-parallel-tool-calls**（默认 true）：是否启用 LLM 的并行工具调用能力
- **loop-detector-window-size**（默认 8）：循环检测的滑动窗口大小
- **loop-detector-warn-threshold**（默认 3）：循环检测的警告阈值
- **loop-detector-terminate-threshold**（默认 5）：循环检测的终止阈值
- **loop-detector-excluded-tools**（默认空）：免于循环检测的工具列表
- **non-retryable-tools**（默认空）：不进行重试的工具列表
- **approval-required-tools**（默认空）：需要人工审批的工具列表
- **can-delegate**（默认 false）：是否启用委派工具

网页搜索工具需要额外配置 intellimate.tools.serpapi-key 才能使用 Google 搜索。

---

## 设计要点

### 统一执行模型

所有工具——无论是内置、自定义、MCP 还是 Bridge——都通过相同的 ToolCallback.call 接口执行。中间件链（循环检测、缓存、超时、重试）对所有工具类型统一适用。从 LLM 的角度看，不同来源的工具没有任何区别。

### 主动控制

AgentRuntime 禁用了 Spring AI 的内置工具执行（internalToolExecutionEnabled = false），完全接管工具调用的处理。这使系统能够实现复杂的执行策略：并行调度、审批门控、委派拦截、Bridge 路由等，这些都超出了 Spring AI 默认工具执行的能力范围。

### 分类执行

每轮对话中的工具调用被分为四类后按策略执行，保证了：直接工具可以并行以提高效率，审批工具逐个等待用户确认，委派工具按序处理上下文传递，移交工具在最后执行并终止循环。

### 安全多层

工具安全通过多个层次实现：配置层面的工具过滤（每 Agent 独立的 tools_enabled）、运行时的循环检测、人工审批门控、执行超时、以及特定工具的内置保护（如 HTTP 工具的 SSRF 防护、定时任务的内置任务保护）。
