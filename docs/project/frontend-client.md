# IntelliMate 前端客户端

## 概述

IntelliMate 前端是一个基于 React 19 的单页应用（SPA），使用 Vite 6 构建，Tailwind CSS 4 提供样式支持，Zustand 5 管理全局状态。前端通过 WebSocket 与后端进行实时双向通信，同时通过 REST API 进行配置管理和数据查询。

前端的核心功能是一个 AI 对话界面，围绕它扩展了计划管理、Agent 配置、技能管理、模型管理、记忆系统、定时任务看板等功能模块。整个应用没有使用路由框架，而是通过 App.tsx 中的视图模式状态（ViewMode）来切换不同页面。

---

## 技术栈

前端使用的主要技术和库：

- React 19 和 TypeScript 5.7 作为核心框架和类型系统
- Vite 6 作为开发服务器和构建工具，开发时将 /api 和 /ws 请求代理到后端的 3007 端口
- Tailwind CSS 4 提供原子化的样式能力，通过 @tailwindcss/vite 插件集成
- Zustand 5 作为轻量级状态管理方案，替代了 Redux
- lucide-react 提供统一的图标库
- react-markdown 配合 remark-gfm 实现 Markdown 渲染，支持表格、删除线和任务列表
- react-syntax-highlighter 提供代码块的语法高亮
- recharts 为调度器统计页面提供图表组件

---

## 整体布局

应用的页面布局分为三个区域：

左侧是侧边栏（Sidebar），展示 Agent 列表和功能导航入口。在桌面端侧边栏始终可见，在移动端隐藏为抽屉式导航，通过顶部的汉堡按钮打开。

中间是主内容区，根据当前视图模式显示不同的内容。默认是聊天面板（ChatPanel），也可以切换到 Agent 管理、计划历史、工具管理、技能管理、模型管理、记忆管理或调度器看板。

右侧是可折叠的计划面板（PlanPanel），宽度固定为 420 像素，可以收起为 40 像素的窄条。只有在当前有活跃计划或计划历史时才显示。

顶部有一个工具栏（TopBar），显示 Logo、当前活跃的 Agent 名称、连接状态指示灯和深色模式切换按钮。

---

## 状态管理

前端使用七个 Zustand Store 管理不同领域的状态。

### chatStore

聊天相关的核心状态。最重要的数据结构是 messagesByAgent，一个以 Agent 名称为键、消息数组为值的映射表。这意味着每个 Agent 有独立的消息历史，切换 Agent 时界面显示对应 Agent 的消息列表。

消息（ChatMessage）包含 id、角色（user/assistant/system）、内容文本、是否正在流式输出、关联的工具调用列表、当前轮次信息和工作流条目等字段。

关键方法包括：addUserMessage（添加用户消息并创建空的助手消息占位）、appendChunk（流式追加文本片段）、finishStreaming（结束流式输出并刷新缓冲的 proactive 消息）、addToolCall 和 updateToolResult（管理工具调用的显示）、addProactiveMessage 和 bufferProactiveMessage（处理定时任务和心跳推送的消息）。

isWaiting 状态用于控制输入区域的禁用状态——当 Agent 正在回复时，用户不能发送新消息。

proactiveBuffer 数组用于缓冲在 Agent 回复期间到达的 proactive 消息，等回复完成后一次性刷新显示。

### agentStore

Agent 管理相关状态。核心数据包括 agents 列表和 activeAgent（当前选中的 Agent，持久化在 localStorage 中）。还维护了 Agent 配置的各种草稿状态：soul_md 和 agents_md 的编辑内容、工具选择、MCP 工具选择、技能选择等。

提供了完整的 Agent CRUD 方法和各配置项的独立保存方法（saveToolsEnabled、saveMcpToolsEnabled、saveSkillsEnabled 等），以及配置重置功能。

### planStore

计划模式的状态。包含当前活跃计划（plan）及其步骤列表、每个步骤关联的工具调用（stepToolCalls）、当前执行到的步骤索引、计划历史列表（最多 5 个）等。

提供了丰富的 WebSocket 事件处理方法：handlePlanCreated（新计划创建）、handleStepStart/Done（步骤开始/完成）、handlePlanAdjusted（计划调整）、handlePlanStatusChanged（计划状态变更）、handlePlanCompleted（计划完成）等。还支持从 REST API 同步计划状态作为 WebSocket 事件的兜底。

### toolStore

管理自定义工具和 MCP 服务器的状态。提供 CRUD 操作和测试功能。

### memoryStore

记忆系统的状态。包含工作记忆快照（通过 WebSocket 实时更新）、巩固日志、长期记忆列表、统计数据和配置信息。

### schedulerStore

调度器看板的状态。包含任务列表、最近执行日志、总览统计数据。提供触发、暂停、恢复等操作方法，以及 WebSocket 事件的实时状态更新。

### modelStore

模型提供者和模型定义的管理状态。提供 CRUD 和测试功能。

---

## WebSocket 通信

### 连接管理

WebSocket 客户端（wsClient.ts）负责连接的建立、维持和重连。连接地址默认为当前主机的 /ws 路径，可通过 VITE_WS_URL 环境变量配置。支持通过 VITE_AUTH_TOKEN 设置认证令牌，以查询参数的形式附加到 WebSocket URL 上。

客户端实现了自动重连机制，使用指数退避策略（从 1 秒开始，最长 30 秒）。连接状态有四种：connecting（正在连接）、connected（已连接）、disconnected（已断开）、reconnecting（正在重连）。

客户端还处理服务端的 ping 事件，自动回复 pong 以维持连接活跃。

### 帧协议

WebSocket 通信使用三种帧类型：

Event 帧由服务端主动推送，包含事件类型（event）、载荷（payload）和序列号（seq）。这是最常用的帧类型，用于流式输出、工具调用通知、计划状态更新等。

Request 帧由客户端发送，包含请求 ID（id）、方法名（method）和参数（params）。用于发送聊天消息和计划操作。

Response 帧由服务端返回，对应某个 Request，包含请求 ID、是否成功（ok）、载荷或错误信息。

### 事件处理

useWebSocket Hook 是 WebSocket 通信的中枢，负责监听所有事件并分发到对应的 Store。

连接建立后，服务端发送 session.welcome 事件，客户端收到后记录 WebSocket 会话 ID，同步计划状态，加载历史数据，并发送 agent.bind 事件将当前会话绑定到活跃的 Agent。

聊天相关事件包括：agent.chunk（流式文本片段，追加到当前助手消息）、agent.done（回复完成，结束流式输出并处理后续操作）、agent.turn_start（新一轮推理开始）、agent.tool_call（工具调用开始，在消息中显示工具卡片）、agent.tool_result（工具调用结果返回，更新工具卡片状态）。

计划相关事件包括：plan.created（新计划创建）、plan.awaiting_approval（等待用户审批）、plan.status_changed（计划状态变更）、plan.step_start/done（步骤生命周期）、plan.adjusted（计划内容调整）、plan.completed（计划执行完成）。

工作流事件用于多 Agent 协作的进度展示：workflow.delegation_start/progress/result（委派的开始、进度和结果）、workflow.handoff（对话移交）、workflow.parallel_start/progress（并行委派）。

记忆事件：memory.snapshot（工作记忆快照更新）、memory.consolidation（记忆巩固日志）。

调度器事件：scheduler.job.started/completed（任务执行状态实时更新）。

### 发送消息

用户发送消息时，useWebSocket 构建一个 conversation.message 请求，包含用户输入文本、channelId（固定为 webchat）、contextType（固定为 dm）和当前的 agentName。如果用户通过 / 命令触发了强制计划模式，还会附加 forcePlan 参数。

发送后，chatStore 立即在界面上添加用户消息气泡和一个空的助手消息占位，进入等待状态。如果 300 秒内没有收到回复，会触发超时处理。

---

## 聊天界面

### ChatPanel

ChatPanel 是聊天界面的容器组件，包裹了 MessageList 和 ComposeArea。它根据连接状态和 isWaiting 状态控制输入区域的可用性——断开连接或等待回复时禁止输入。

### MessageList

MessageList 渲染所有消息气泡的滚动列表。支持自动滚动到底部（新消息到达时），并在用户向上翻阅历史消息时暂停自动滚动，同时显示一个"回到底部"的按钮。

### MessageBubble

MessageBubble 是单条消息的渲染组件，处理三种角色的消息显示：

用户消息显示在右侧，纯文本渲染（不做 Markdown 解析），浅蓝色背景。

助手消息显示在左侧，根据流式状态选择不同的渲染方式。流式输出中使用 StreamingText 组件以纯文本 pre 标签渲染（避免不完整的 Markdown 导致闪烁），并显示闪烁的光标。流式完成后切换到 react-markdown 进行完整的 Markdown 渲染。

如果助手消息包含工具调用，会在文本下方显示 ToolCallGroup 组件。如果处于计划执行模式，工具调用还会按计划步骤分组显示（StepGroupedTools）。

工作流相关的条目（委派、移交、并行执行）以时间线的形式嵌入在助手消息中（WorkflowTimeline 组件）。

系统消息居中显示，通常用于连接状态通知。

### ComposeArea

ComposeArea 是输入区域，包含一个可自动调整高度的 textarea 和发送按钮。支持 Enter 键发送（Shift+Enter 换行）。当用户输入 / 开头的内容时，会弹出 CommandPopup 显示可用的命令列表。

### StreamingText

StreamingText 组件根据消息的流式状态选择渲染策略。流式输出中使用简单的 pre 标签加闪烁光标，性能更好且避免了 Markdown 解析不完整内容时的视觉异常。输出完成后切换到完整的 react-markdown 渲染，支持 GFM 扩展（表格、删除线、任务列表），代码块使用懒加载的 react-syntax-highlighter 显示语法高亮，并附带复制按钮。

---

## 计划模式

计划模式允许 Agent 创建一个分步骤的执行计划，用户可以审查和批准后让 Agent 按计划执行。

### PlanPanel

PlanPanel 是右侧的计划面板，显示当前计划的步骤列表和操作按钮。步骤可以拖拽重排序。面板底部有执行/暂停/取消按钮。如果有多个历史计划，可以通过分页切换查看。

### 计划生命周期

创建：Agent 在对话中调用 writePlan 工具时，服务端发送 plan.created 事件，前端在 planStore 中创建计划对象并展开计划面板。

审批：计划创建后处于草稿状态，等待用户确认。用户点击"执行"按钮时，前端发送 plan.approve 请求，同时向聊天发送一条"开始执行计划"的消息触发 Agent 开始执行。

执行：Agent 按步骤执行计划。每个步骤开始时收到 plan.step_start 事件，结束时收到 plan.step_done 事件。执行过程中的工具调用会被关联到对应的步骤，在聊天气泡中按步骤分组显示。

完成：收到 plan.completed 事件时，计划被标记为已完成。如果 Agent 回复完成（agent.done）时计划仍有未完成的步骤，系统会自动将它们标记为完成。

历史：已完成的计划保存在 planHistory 中（最多 5 个），可以在面板中回顾。完整的计划历史可以通过"计划历史"视图查看，支持按 Agent 和状态筛选。

### PlanStepCard

单个步骤的卡片组件，显示步骤编号、标题、状态图标（待执行/执行中/已完成/失败）。支持展开查看步骤相关的工具调用详情。提供编辑步骤内容和跳过步骤的操作。

---

## 工具调用显示

### ToolCallCard

ToolCallCard 是单个工具调用的显示卡片。显示工具名称（带有根据工具类型区分的图标）、执行状态（loading/成功/失败）。可以展开查看输入参数和返回结果。

对于特定类型的工具调用，ToolCallCard 会渲染专用的卡片组件而非通用的 JSON 展示：

- createTodoTask、listTodoTasks 等待办事项工具渲染为 TaskCard，显示任务标题、描述和状态，支持完成/取消操作。
- createScheduledJob、listScheduledJobs 等定时任务工具渲染为 ScheduledJobCard，显示任务名称、调度信息和状态，支持暂停/恢复操作。
- writePlan 和 updatePlan 工具在计划模式激活时不显示在普通工具列表中（避免重复显示）。

### ToolCallGroup

ToolCallGroup 将同一轮对话中的多个工具调用组合为可折叠的分组。标题显示工具数量和整体状态。

---

## 记忆系统界面

MemoryManagerPage 提供四个选项卡：

记忆总览选项卡显示工作记忆的实时状态——Token 使用量进度条、记忆块列表和记忆巩固日志。工作记忆数据来自 WebSocket 的 memory.snapshot 事件实时更新。

长期记忆选项卡按类型分为三个子页签：情景记忆（episodic）、语义记忆（semantic）和程序性记忆（procedural）。支持搜索和删除操作。

记忆配置选项卡允许调整工作记忆、巩固策略和长期记忆的各项参数，每个配置项附有解释性的工具提示。

遗忘日志选项卡显示已被遗忘调度器归档或过期的记忆条目。

界面顶部有 Agent 选择器，与侧边栏的活跃 Agent 同步，用于查看特定 Agent 的记忆数据。

---

## 调度器看板

SchedulerDashboard 提供三个选项卡：

任务总览按分组（agent、data、monitor、system、custom）展示所有定时任务卡片。每个 JobCard 显示任务名称、触发规则、运行状态和最近执行结果，支持手动触发、暂停和恢复操作。

执行历史展示最近的任务执行日志，包括执行状态、耗时和结果。

统计选项卡使用 recharts 图表展示任务执行的时间分布、成功率等统计数据。

看板数据来自两个来源：REST API 定期轮询（每 15 秒）和 WebSocket 事件（scheduler.job.started/completed）实时更新。

---

## Agent 管理界面

### Agent 列表

侧边栏的 AgentList 组件展示所有 Agent，点击切换活跃 Agent。非默认 Agent 支持删除。切换 Agent 时，chatStore 切换到对应 Agent 的消息历史，planStore 清除当前计划，WebSocket 发送 agent.bind 事件。

### Agent 配置弹窗

AgentConfigModal 是一个多选项卡的配置弹窗，包含以下选项卡：

SOUL 和 AGENTS 选项卡使用 AgentContextEditor 组件——一个等宽字体的文本编辑器，支持 Tab 缩进，字符限制 20000。分别编辑 soul_md（Agent 人格）和 agents_md（操作指南）。

工具选择选项卡（ToolsTab）让用户选择该 Agent 可用的内置工具。支持三种模式：全部工具、预设配置文件和自定义选择。

MCP 工具选项卡（McpToolsTab）配置 MCP 工具的访问权限。展示所有已连接 MCP 服务器的工具列表，支持勾选。

Skills 选项卡配置技能和技能分组的使用权限。

模型选项卡选择该 Agent 使用的 AI 模型。

委派协作选项卡（AgentDelegationConfig）配置多 Agent 委派能力：是否允许委派（can_delegate 开关）、可委派的 Agent 列表选择、Agent 能力描述（goal）。

心跳选项卡（HeartbeatConfigPanel）配置心跳检测的时间表参数。

任务选项卡（TaskManager）管理该 Agent 关联的待办任务。

---

## API 层

### 主 API 客户端

api.ts 是主要的 REST 客户端，封装了一个通用的 request 函数，自动处理 Content-Type 头部、HTTP 错误状态和 JSON 解析。基础 URL 默认为当前主机的 3007 端口，可通过 VITE_API_URL 环境变量配置。

主 API 客户端覆盖了以下接口组：

- Agent 管理：列表、详情、创建、更新、删除、上下文更新
- 工具管理：工具元数据、自定义工具 CRUD、MCP 服务器 CRUD 和测试
- 技能管理：技能 CRUD、分组管理、版本管理、文件上传、统计
- 模型管理：提供者和模型定义的 CRUD 和测试
- 计划管理：获取计划详情
- 记忆管理：配置、统计、长期记忆 CRUD、归档

### 专用 API 客户端

schedulerApi.ts 封装了定时任务相关的接口：任务 CRUD、触发/暂停/恢复、执行日志查询和统计。

heartbeatApi.ts 封装了心跳配置和待办任务的接口。

部分组件（如 PlanHistoryTab）直接使用原生 fetch 调用 API（通过 Vite 代理的相对路径），而不经过 api.ts 封装。

---

## 主题和样式

### 深色模式

应用支持深色和浅色两种主题，可通过顶部工具栏的切换按钮手动切换。主题偏好持久化在 localStorage 的 intellimate-theme 键中。如果没有保存的偏好，默认跟随系统的 prefers-color-scheme 设置。

切换时在 document.documentElement 上添加或移除 dark 类名，Tailwind CSS 的 dark variant 据此应用深色样式。

### 设计风格

应用使用 Tailwind CSS 的 slate 色系作为中性色基础。浅色模式下背景为白色，深色模式下为 slate-900。主色调为 blue-500/600。

消息气泡使用圆角 2xl 设计。用户消息为浅蓝色背景，助手消息为浅灰色（深色模式下为深灰色）。

状态颜色遵循常见约定：emerald 表示成功，red 表示错误，amber 表示警告，orange 表示暂停。

Logo 区域使用 blue-600 到 cyan-500 的渐变色。

滚动条定制为 6 像素宽度，使用 slate 色系。

Markdown 渲染使用 Tailwind 的 prose 排版类，配合 dark:prose-invert 适配深色模式。

---

## 响应式设计

应用在桌面端和移动端有不同的布局行为：

桌面端（md 断点及以上）侧边栏始终可见，计划面板在有计划时显示为固定宽度的右栏。Agent 管理卡片使用 2-3 列网格布局。

移动端（md 断点以下）侧边栏隐藏，通过汉堡按钮打开为覆盖层。导航操作后自动关闭覆盖层。消息气泡的最大宽度为容器的 75%，内边距适度缩小。顶部工具栏中的 Agent 名称在小屏幕上隐藏。

应用没有专门的移动端聊天布局——使用相同的组件结构，仅通过侧边栏抽屉化和间距调整适配移动端。

---

## 认证机制

当前的认证模型非常简单。WebSocket 连接可以通过 VITE_AUTH_TOKEN 环境变量配置一个认证令牌，以查询参数的形式附加到连接 URL 上。REST API 请求不携带任何认证头部。

没有登录界面、会话管理或 JWT 刷新机制。应用假设运行在可信的网络环境中，后端与前端在同一主机或通过代理访问。

客户端仅在 localStorage 中持久化两项数据：主题偏好（intellimate-theme）和当前活跃的 Agent 名称（intellimate-active-agent）。
