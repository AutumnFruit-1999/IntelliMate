# IntelliMate 技能（Skill）系统

## 什么是技能

在 IntelliMate 中，"技能"和"工具"是两个不同的概念，理解这个区别是理解整个系统的关键。

**工具（Tool）** 是 AI Agent 可以直接调用的函数，比如读文件、写文件、执行命令、搜索网页。工具是代码层面的实现，Agent 在对话中通过 function calling 机制调用它们，每个工具有明确的输入参数和输出结果。

**技能（Skill）** 则是一套 Markdown 格式的指令文档，告诉 Agent "遇到某种场景应该怎么做"。技能本身不包含可执行代码，它更像是一份操作手册。Agent 读取技能内容后，会按照手册中的指引，使用现有的工具来完成任务。

举个例子：假设有一个"代码审查"技能，它的 SKILL.md 里会写明"第一步用 readFile 读取被审查的代码，第二步分析代码质量，第三步用 writeFile 生成审查报告"。技能定义了流程和策略，工具提供了执行能力。

---

## 技能的存储方式

技能采用双重存储策略：数据库存储元数据，文件系统存储内容。

### 数据库存储

每个技能在 skill_definition 表中有一条记录，包含技能的唯一标识名称（name）、显示名称（display_name）、触发描述（description）以及技能正文内容（content）。触发描述是一段简短的文字，用于告诉 Agent 在什么情况下应该使用这个技能。

除了主定义表之外，还有三张辅助表。skill_version 表记录每次修改的版本历史，支持回滚。skill_usage_log 表记录每次技能被激活的情况，用于使用统计。skill_group 和 skill_group_member 表实现了技能分组功能，可以将相关技能归类管理，比如将所有编码相关的技能归入"coding"组。

### 文件系统存储

技能在文件系统中以目录的形式存在，根目录通过配置项 intellimate.skills.dir 指定（默认为项目根目录下的 skills 文件夹）。每个技能一个子目录，目录名就是技能名称。目录内的标准结构为：

- SKILL.md：技能的核心文档，包含完整的指令内容。这是必须的文件。
- scripts/：可选目录，存放技能可能需要执行的脚本文件。
- references/：可选目录，存放参考资料。
- assets/：可选目录，存放相关资源文件。

当 Agent 需要读取技能内容时，系统优先从文件系统读取 SKILL.md，如果文件不存在则回退到数据库中的 content 字段。这种设计让技能内容可以在文件系统中直接编辑，方便开发调试。

技能文档中可以使用 {baseDir} 占位符来引用自身所在目录的路径，系统在读取时会自动替换为实际的绝对路径。

---

## 技能如何与 Agent 关联

每个 Agent 在数据库的 agent 表中有两个与技能相关的配置字段：skills_enabled 和 skill_groups_enabled。

### skills_enabled 字段

控制哪些技能会出现在 Agent 的系统提示词中。支持三种配置方式：

- 留空或不设置：Agent 不使用任何技能。
- 设为 "full"：Agent 可以使用所有已启用的技能。
- 设为 JSON 数组：指定具体的技能名称列表，例如 ["code-review", "tdd"]，Agent 只能使用列表中的技能。

### skill_groups_enabled 字段

控制 Agent 基于分组的技能发现能力。同样支持三种配置：

- 留空或不设置：不启用分组发现功能。
- 设为 "full"：Agent 可以浏览和使用所有分组中的技能。
- 设为 JSON 数组：只允许访问指定分组中的技能。

这两个字段构成了一个两层的访问控制体系。skills_enabled 控制的是"系统提示词中直接展示哪些技能"，skill_groups_enabled 控制的是"Agent 调用 listSkillsByGroup 工具时能看到哪些分组"。

---

## 技能在对话中的工作流程

当用户发送消息时，技能的参与过程分为三个阶段：发现、加载和执行。

### 第一阶段：发现

AgentRuntime 在构建系统提示词时，根据 Agent 的 skills_enabled 配置，从数据库查询对应的技能摘要信息（名称和触发描述）。这些摘要被组装成系统提示词的 skills 段落，告诉 Agent "你有这些技能可用"，并附上每个技能的 SKILL.md 文件路径。

如果 Agent 配置了 skill_groups_enabled 但没有配置 skills_enabled，系统提示词中只会包含分组概览和使用说明，引导 Agent 先调用 listSkillsByGroup 工具查看分组内的技能列表，再决定使用哪个。

技能发现的提示词模板定义在 prompts/skills-discovery.md 文件中，内容大致是："你已安装技能系统。技能按分组组织，使用时先识别需求匹配的分组，调用 listSkillsByGroup 获取列表，选择最匹配的技能，调用 getSkillContent 获取完整说明，按照 SKILL.md 指引执行。"

### 第二阶段：加载

当 Agent 的 LLM 决定使用某个技能时，它会调用两个专用工具之一来获取技能内容：

getSkillContent 工具：接受技能名称作为参数，从文件系统（优先）或数据库读取 SKILL.md 的完整内容，将其中的 {baseDir} 替换为实际路径后返回。该工具支持模糊名称匹配，即使 Agent 提供的名称不完全精确也能找到对应技能。

listSkillsByGroup 工具：接受分组名称，返回该分组下所有技能的名称和描述列表。这个工具受 skill_groups_enabled 的访问控制限制——系统在每次对话开始时通过 SkillGroupContext（一个 ThreadLocal 变量）设置当前 Agent 可访问的分组列表，listSkillsByGroup 在返回结果前会过滤掉未授权的分组。

Agent 也可以跳过这两个工具，直接用 readFile 工具读取 SKILL.md 文件。系统会检测这种行为并同样记录为技能使用。

### 第三阶段：执行

Agent 读取到 SKILL.md 的内容后，按照文档中的指引，使用其他工具（如 readFile、writeFile、exec、webSearch 等）完成具体任务。技能本身不执行任何代码，所有的实际操作都通过 Agent 已有的工具完成。

这个设计的好处是：技能可以编排任意复杂的工作流程，而不需要编写任何代码。只要 Agent 拥有必要的工具权限，就能按照技能文档的指引完成任务。

---

## 工具系统

虽然技能是知识层面的能力，但它们依赖工具系统来实际执行操作。理解工具系统有助于完整理解技能的运作方式。

### 工具注册

所有工具通过 ToolsEngine 统一管理。ToolsEngine 聚合了系统中所有的 ToolCallbackProvider，在启动时和动态刷新时收集所有可用的工具回调。

工具来源有四种：

**内置工具**：使用 Spring AI 的 @Tool 注解在 Java 方法上定义。编译后自动注册为 ToolCallback。这是最常见的工具定义方式，包括文件操作工具（readFile、writeFile、editFile）、命令执行工具（exec）、网络工具（webSearch、webFetch）、Agent 委托工具（delegateAgent、handoffToAgent、delegateAgentsParallel）、技能工具（getSkillContent、listSkillsByGroup）以及计划工具（writePlan、updatePlan）。

**任务管理工具**：在 gateway 模块中定义，包括待办任务管理（createTodoTask、listTodoTasks、updateTodoTask、deleteTodoTask）和定时任务管理（createScheduledJob、listScheduledJobs、updateScheduledJob、deleteScheduledJob）。

**自定义 HTTP 工具**：通过 tool_definition 数据库表定义。每条记录描述一个 HTTP API 调用，包括 URL 模板、请求方法、参数模式和响应提取规则。系统在启动和 CRUD 操作后自动刷新工具注册表。

**MCP 工具**：通过 MCP（Model Context Protocol）协议从外部 MCP 服务器动态发现的工具。工具名称以 mcp_{serverName}_{toolName} 的格式注册。

### 工具过滤

不是所有工具都会暴露给每个 Agent。每个 Agent 的 tools_enabled 字段控制可用的内置工具和自定义工具，mcp_tools_enabled 字段独立控制 MCP 工具的可用范围。

tools_enabled 支持四种配置：留空或 "full" 表示所有内置和自定义工具可用；设为预定义的工具配置文件名（如 "coding"、"messaging"、"minimal"）使用预设子集；设为 JSON 数组则精确指定允许的工具名称列表。

### 工具执行

当 LLM 在对话中决定调用某个工具时，AgentRuntime 的 processToolCalls 方法处理执行流程。每个工具调用经过一个中间件链：

1. 循环检测器（ToolCallLoopDetector）检查 Agent 是否陷入了重复调用同一工具的死循环。
2. 审批门控（ToolApprovalGate）对配置了需要人工审批的工具，暂停执行等待用户确认。
3. 结果缓存（ToolResultCache）检查是否有相同参数的历史调用结果可以复用。
4. 超时和重试控制，确保工具调用不会无限阻塞。

对于支持并行的工具调用，系统可以同时执行多个独立的工具请求，最大并行数由配置控制。

工具执行的结果会作为 ToolResponseMessage 追加到对话历史中，LLM 在下一轮对话中可以看到工具返回的信息并继续推理。

### Bridge 工具

当 Agent 配置了 bridge_node 且对应的 Bridge 节点在线时，部分工具的执行会被透明地代理到用户本地机器上。这意味着 Agent 可以在用户的本地环境中执行命令、读写文件，而不是在服务器上。Bridge 工具替换是自动的——同名的工具回调会被 BridgeToolCallback 包装，将参数转发到本地节点执行，再将结果返回。

---

## 技能管理

### REST API

系统提供了完整的 REST API 来管理技能，所有接口的前缀为 /api/skills。

基础 CRUD 操作包括：获取所有技能列表（GET /api/skills）、获取单个技能详情（GET /api/skills/{id}）、创建技能（POST /api/skills）、更新技能（PUT /api/skills/{id}）、删除技能（DELETE /api/skills/{id}）。

创建和更新操作会同时修改数据库记录和文件系统中的 SKILL.md。更新操作还会自动创建一个版本快照，保留修改前的内容。

版本管理接口包括：查看版本历史（GET /api/skills/{id}/versions）和回滚到指定版本（POST /api/skills/{id}/rollback/{version}）。

文件管理接口允许管理技能目录下的 scripts、references 和 assets 子目录中的文件。

导出功能支持单文件导出（GET /api/skills/{id}/export，返回 SKILL.md 内容）和整目录打包导出（GET /api/skills/{id}/export/zip）。

使用统计接口提供全局统计（GET /api/skills/stats）和单技能统计（GET /api/skills/{id}/stats）。

分组管理接口前缀为 /api/skill-groups，支持分组的 CRUD、排序和成员管理。

### 前端管理界面

前端侧边栏中有"Skills 管理"入口，打开后显示技能管理页面。页面支持技能的完整 CRUD 操作、从 SKILL.md 文件导入、版本历史查看和回滚、分组管理，以及使用统计的可视化展示。

在 Agent 配置弹窗中，有独立的"Skills"选项卡，用于配置该 Agent 可以使用的技能和技能分组。支持三种模式切换：不使用技能、使用全部技能、自定义选择特定技能。工具选择在另一个"工具选择"选项卡中，与技能配置分开管理。

### Agent 对话中创建

除了通过管理界面和 API 之外，Agent 本身也可以在对话中通过工具调用的方式创建定时任务（这些任务可以包含对技能的引用），但 Agent 目前不能直接创建新的技能定义——技能的创建和维护主要通过管理界面或 API 完成。

---

## 使用追踪

系统会自动记录技能的使用情况。有两种激活方式会被追踪：

1. Agent 调用 getSkillContent 工具读取技能内容时，记录为 tool_call 类型的激活。
2. Agent 直接使用 readFile 工具读取某个 SKILL.md 文件时，系统检测到路径中包含 "/SKILL.md" 后缀，也会记录为 file_read 类型的激活。

每次激活记录包含技能名称、Agent 名称、激活类型和时间戳。这些数据可以通过统计 API 查询，帮助了解哪些技能被使用最多、哪些 Agent 最频繁使用技能等信息。

---

## 模块分工

技能和工具系统的代码分布在三个 Maven 模块中：

**intellimate-core**：共享配置模块。包含 IntelliMateProperties（配置属性绑定）和 ToolExecutionException（工具执行异常）。不包含任何技能或工具的实现。

**intellimate-agent**：Agent 运行时模块。定义了技能相关的 SPI 接口（SkillContentProvider、SkillUsageRecorder、SkillGroupContext），实现了所有内置工具（@Tool 注解的 Java 类），包含 AgentRuntime（Agent 执行引擎）和 ToolsEngine（工具注册引擎），以及技能发现的提示词模板。

**intellimate-gateway**：网关模块。实现了 SPI 接口（SkillContentProviderImpl、SkillUsageRecorderImpl），包含所有数据库实体和 Repository、REST Controller、任务管理工具（ScheduledJobManagementTool、TaskManagementTool），以及文件系统操作服务（SkillFileService）。

这种分层设计使得 Agent 运行时不直接依赖数据库和 Web 框架，便于独立测试和部署。

---

## 设计理念

### 知识与能力分离

技能是知识，工具是能力。技能告诉 Agent "做什么"和"怎么做"，工具给 Agent "用什么来做"。这种分离让非程序员也能通过编写 Markdown 文档来扩展 Agent 的能力边界，无需编写代码。

### 渐进式发现

Agent 不会在每次对话开始时加载所有技能的完整内容（那样会占用大量的上下文窗口）。系统只在提示词中放入技能摘要（名称和触发描述），Agent 在判断需要使用某个技能时再通过工具调用获取完整内容。分组机制进一步优化了发现效率——Agent 可以先浏览分组概览，再深入特定分组查找技能。

### 双存储互补

数据库提供结构化的元数据管理和 API 访问，文件系统提供直接编辑的便利性和脚本执行的支持。两者通过"文件优先、数据库兜底"的读取策略协调工作。

### 访问控制分层

skills_enabled 控制"Agent 看到什么"，skill_groups_enabled 控制"Agent 能查什么"。一个 Agent 可以在提示词中看到几个常用技能的快捷入口，同时通过分组发现访问更多技能。两层控制可以独立配置，灵活组合。
