# IntelliMate 网关系统

## 概述

intellimate-gateway 是 IntelliMate 的核心网关模块，也是整个应用的主入口。它是一个 Spring WebFlux 应用，将前端 WebSocket 客户端、AI Agent 运行时、数据库持久化、REST 管理 API、Bridge 本地执行节点和定时调度引擎整合在一个进程中。

网关依赖四个兄弟模块：intellimate-core（共享配置和协议定义）、intellimate-agent（AI 运行时和工具引擎）、intellimate-memory（记忆系统）和 intellimate-channel-api（外部渠道适配器接口）。

应用默认在 3007 端口启动，通过 INTELLIMATE_PORT 环境变量可修改。

---

## WebSocket 通信层

### 连接管理

GatewayWebSocketHandler 处理所有来自前端的 WebSocket 连接。连接的完整生命周期如下：

**认证**：连接建立时，从 URL 查询参数（?token=）或 HTTP 头部（Authorization: Bearer）中提取认证令牌。如果系统配置了 intellimate.security.auth-token，则令牌不匹配时立即关闭连接。如果未配置令牌（开发模式），所有连接都被允许。

**注册**：认证通过后，系统为连接分配一个 WebSocket 会话 ID，在 SessionRegistry 中注册一个带背压缓冲区（1024 帧上限）的单播 Sink 作为该连接的出站通道。

**欢迎事件**：注册完成后立即发送 session.welcome 事件，携带 wsSessionId，前端据此识别自己的会话身份。

**心跳保活**：每 30 秒发送一次 ping 事件。客户端必须回复 pong 事件。连续 3 次没有收到 pong 回复后，系统判定连接已死，关闭出站通道并断开连接。

**入站消息路由**：所有入站 JSON 消息通过 ProtocolCodec 解码为三种帧类型之一。RequestFrame（客户端请求）路由到 MessagePipeline 处理。EventFrame 中的 agent.bind 事件用于绑定 Agent，pong 事件重置心跳计数器。ResponseFrame 被忽略（客户端不应发送响应帧）。

**断开清理**：连接断开时，释放消息接收器和心跳定时器，从 SessionRegistry 中注销会话，通知 MessagePipeline 执行断开清理（包括延迟的情景记忆刷新）。

### SessionRegistry

SessionRegistry 维护两个核心映射关系：一个是 WebSocket 会话 ID 到出站 Sink 的映射（用于向特定连接发送消息），另一个是 Agent 名称到 WebSocket 会话 ID 集合的映射（用于向绑定了特定 Agent 的所有连接推送消息）。

主要功能包括：bindAgent 将 WebSocket 会话绑定到指定 Agent、pushToAllAgentSessions 向 Agent 的所有绑定会话推送事件（用于心跳和定时任务）、pushToSession 向单个会话推送事件（用于离线消息投递）、broadcast 向所有已连接客户端广播事件（用于调度器状态通知）、isAgentOnline 检查某个 Agent 是否有活跃的绑定会话。

### 帧协议

WebSocket 通信使用 JSON 格式的三种帧类型，在 intellimate-core 模块中定义：

- RequestFrame：包含请求 ID、方法名和参数。由客户端发送，用于聊天消息和计划操作。
- ResponseFrame：包含请求 ID、是否成功、载荷或错误信息。由服务端返回，对应特定请求。
- EventFrame：包含事件名称、载荷和序列号。由服务端主动推送，用于流式输出、状态通知等。

---

## 消息处理管道

MessagePipeline 是网关的核心处理组件，负责将 WebSocket 请求路由到正确的处理逻辑。

### 请求路由

所有 WebSocket 请求进入 processRequest 方法后，根据方法名分发：

- conversation.message 路由到主聊天流程
- conversation.approve_tool 路由到工具审批处理
- plan.* 系列方法路由到计划操作处理
- 未知方法返回失败响应

### 聊天消息处理流程

conversation.message 是最核心的处理路径，完整流程如下：

1. 解析消息参数：用户输入文本（text）、渠道标识（channelId，默认 webchat）、上下文类型（contextType，默认 dm）、Agent 名称（agentName）、是否强制计划模式（forcePlan）等。

2. 在 SessionRegistry 中将当前 WebSocket 会话绑定到指定 Agent。

3. 构建上下文 ID：在基础 contextId 后追加 Agent 名称作为后缀（格式为 "baseContextId::agentName"），确保每个 Agent 有独立的数据库会话。

4. 通过 SessionManager.getOrCreate 查找或创建数据库会话。会话的唯一性由 channelId、contextType 和 contextId 三元组保证。

5. 如果消息以 / 开头，交给 CommandHandler 处理（支持 /clear、/reset、/status、/model、/approve、/plan、/help 等命令）。

6. 否则进入流式 Agent 运行：
   - 通过 InlinePlanService.getActivePlan 检查是否有活跃计划
   - 将用户消息持久化到 transcript_message 表
   - 加载对话历史（计划执行模式下通过 MessageConverter 加载含计划消息的上下文）
   - 通过 AgentConfigService.resolve 加载 Agent 的完整配置（模型、工具、MCP、技能、委派、Bridge 等）
   - 构建 AgentRunRequest，包含 activePlanMessageId 和 InlinePlanService.buildPlanContext 生成的计划上下文
   - 调用 AgentRuntime.dispatch 开始 AI 对话循环
   - 将 AgentEvent 流映射为 WebSocket EventFrame 实时发送给前端

7. 对话完成后：
   - 持久化助手的完整回复到 transcript_message
   - 发送成功的 ResponseFrame，包含完整回复文本

### Agent 事件映射

AgentRuntime 产生的事件被映射为不同类型的 WebSocket 事件：

- 文本和对话事件（TurnStart、TextChunk、Done、Error、ToolCall、ToolResult、ApprovalRequired）映射为 agent.* 系列事件
- 计划事件（PlanCreated、PlanStepUpdated、PlanStatusChanged、PlanCompleted）映射为 plan.* 系列事件
- 工作流事件（Delegation*、Parallel*、HandoffStart）映射为 workflow.* 系列事件
- 记忆事件（MemorySnapshot、ConsolidationTriggered）映射为 memory.* 系列事件

### 移交处理

当 Agent 发出 HandoffStart 事件时，MessagePipeline 在发送 workflow.handoff 事件给前端后，构建一条合成的用户消息发送给接管 Agent，在同一个用户会话上启动新的 Agent 对话循环。

### 计划相关的 WebSocket 方法

`MessagePipeline.handlePlanAction` 直接处理计划操作，不再经过 `PlanRequestHandler` 或 `PlanExecutionOrchestrator`。前端通过以下四个方法与计划系统交互（均使用 `messageId` 标识）：

- plan.approve：审批或拒绝计划（`messageId`, `approved`）。审批通过后自动触发 Agent 执行
- plan.pause：暂停执行，同时通知 AgentRuntime 停止当前回合
- plan.resume：恢复执行，自动触发 Agent 继续
- plan.cancel：取消计划，未完成步骤标记为 skipped

---

## 会话管理

### 会话标识

每个会话由 SessionKey 唯一标识，包含三个维度：channelId（渠道标识，如 webchat）、contextType（上下文类型，如 dm）和 contextId（上下文标识）。数据库中 channel_id、context_type、context_id 三列加上 deleted=0 构成唯一约束。

### 上下文 ID 约定

不同场景使用不同的 contextId 格式：

- 网页聊天：客户端提供的基础 ID 或 WebSocket 会话 ID，后追加 Agent 名称后缀（如 "wsSessionId::agentName"）
- Proactive 消息（心跳/定时任务）：使用 "proactive::agentName" 格式
- 委派工作会话：使用 "delegation::parentSessionId::workerAgentName::delegationId" 格式

### 会话与 Agent 的双层绑定

会话和 Agent 的绑定存在两个独立的层次。数据库层面，session 表的 agent_name 字段在会话创建时设定。WebSocket 层面，SessionRegistry 的 agentSessions 映射表维护实时的推送目标关系。

由于 contextId 中已包含 Agent 名称，切换 Agent 实际上会创建或使用不同的数据库会话。这保证了不同 Agent 的对话历史天然隔离。

---

## 数据库层

### 存储配置

系统使用 MySQL 数据库，运行时查询通过 R2DBC 进行全异步操作（连接池初始 5 个、最大 20 个、空闲超时 30 分钟）。数据库迁移使用 Flyway 通过传统的 JDBC 连接执行（DDL 操作不走异步）。

### 数据库迁移历史

系统从 V1 到 V30 共经历了 28 次迁移（V13 和 V29 缺失），逐步完善了数据库结构：

V1 建立了核心表结构，包括 agent、session、transcript_message、channel_config、allowlist_entry、pairing_request 和 audit_log。

V2 为 agent 表添加了 soul_md、user_md 和 agents_md 字段，引入了 Agent 上下文配置。

V4 创建了 tool_definition 表，支持自定义动态工具。V5 创建了 mcp_server 表。V6 为 agent 添加了 mcp_tools_enabled 字段。

V7-V8 创建了 model_provider 和 model_definition 表，建立了模型注册体系，并植入了默认的 DashScope 模型提供者。

V9-V10 创建了 skill_definition、skill_version 和 skill_usage_log 表，V19-V20 添加了技能分组支持。

V12 创建了 plan 和 plan_step 表（V39 已将数据迁移到 transcript_message.metadata_json，旧表重命名为 _deprecated_plan / _deprecated_plan_step）。

V14-V17 逐步建立了记忆系统的表结构（memory_config、agent_memory、agent_memory_archive），V17 添加了全文索引。

V21 为 agent 添加了委派协作字段（can_delegate、delegate_agents、goal）。

V22 将 agent.model 从模型名称字符串迁移为 model_definition.id 的引用。

V23 创建了心跳相关表（heartbeat_config、heartbeat_log、agent_task、offline_message）。V24 创建了调度器表（scheduled_job_config、scheduled_job_log）。

V26 创建了 bridge_node 表并为 agent 添加了 bridge_node 字段。V30 删除了已废弃的 offline_message 表。

### 实体和仓库

网关模块定义了 27 个数据库实体（R2DBC @Table 注解的类）和 27 个响应式仓库接口（继承 ReactiveCrudRepository）。实体涵盖了 Agent 配置、会话管理、消息存储、计划系统、模型管理、工具定义、MCP 服务器、技能系统、Bridge 节点、记忆系统、心跳配置、调度器和安全相关的所有数据表。

---

## REST API

所有 /api/ 前缀的接口在配置了认证令牌时受 ApiAuthFilter 保护。/ws 和 /webhook/ 路径不经过此过滤器。

### 各控制器及其职责

**AgentController**（/api/agents, /api/agent/{name}）：Agent 的 CRUD、上下文更新（soul_md/agents_md）和软删除。

**ModelProviderController**（/api/model-providers）：模型提供者的 CRUD 和连接测试。

**ModelDefinitionController**（/api/model-definitions）：模型定义的 CRUD，按提供者分组返回。

**ToolController**（/api/tools）：返回所有内置工具的元数据，包括工具配置文件和分组定义。

**ToolDefinitionController**（/api/tool-definitions）：自定义 HTTP 工具的 CRUD 和测试。

**McpServerController**（/api/mcp-servers）：MCP 服务器的 CRUD、连接测试和重连。

**SkillDefinitionController**（/api/skills）：技能的 CRUD、文件管理、版本管理、导出和统计。

**SkillGroupController**（/api/skill-groups）：技能分组的 CRUD、排序和成员管理。

**MemoryController**（/api/memory）：记忆配置 CRUD、长期记忆管理、归档和统计。

**TaskController**（/api/tasks/{agentId}）：每 Agent 待办任务的 CRUD。

**ScheduledJobController**（/api/scheduled-jobs）：定时任务的完整 CRUD、触发/暂停/恢复、执行日志和统计。

**HeartbeatController**（/api/heartbeat/{agentId}）：心跳配置 CRUD、状态查询、日志和手动触发。

**BridgeController**（/api/bridge/nodes）：Bridge 节点的创建（生成连接令牌）和删除。

**WebhookController**（/webhook/{channelId}）：外部渠道的回调入口（验证和消息接收）。

系统还暴露了 Actuator 端点（/actuator/health 和 /actuator/info）用于健康检查。

---

## 安全机制

### 令牌认证

系统使用单一的静态认证令牌模型，通过 intellimate.security.auth-token 配置（或 INTELLIMATE_AUTH_TOKEN 环境变量）。

REST API 认证通过 ApiAuthFilter 实现，检查 Authorization Bearer 头部或 token 查询参数。WebSocket 认证在 GatewayWebSocketHandler 中内联实现，使用相同的令牌验证逻辑。SecurityService 封装了共享的验证方法。

开发模式下（未配置令牌），所有请求被允许，但启动时会记录警告日志。

### 白名单和 DM 配对

支持配置文件级别的白名单（intellimate.security.allowlist）和数据库级别的白名单（allowlist_entry 表）。空白名单等同于开放访问。

DM 配对功能（可通过 dm-pairing-enabled 配置启用）允许通过 6 位数字验证码将新用户添加到白名单。

### API 密钥加密

模型提供者的 API 密钥通过 CryptoService 使用 AES-256-GCM 加密存储。加密密钥通过 intellimate.security.crypto-key 配置。如果未配置加密密钥，API 密钥以明文存储，启动时记录警告。

### Bridge 认证

Bridge 节点使用独立的认证机制。创建节点时生成一次性令牌，存储其 SHA-256 哈希值。Bridge 客户端连接时通过令牌哈希验证身份。

---

## 模型管理

### 数据模型

model_provider 表存储模型提供者的信息：名称、类型（DASHSCOPE、OPENAI_COMPATIBLE、ANTHROPIC）、基础 URL、加密的 API 密钥、思维模式设置、启用状态和排序权重。

model_definition 表存储具体的模型定义：关联的提供者 ID、模型 API 标识（如 qwen-plus）、显示名称、能力描述、启用状态。

### 运行时流程

ModelRegistryService 在应用启动时从数据库加载所有已启用的提供者和模型定义，解密 API 密钥后注册到 ChatModelRegistry。当提供者或模型定义发生 CRUD 操作时，触发全量重新加载。

AgentRuntime 在处理每次对话请求时，从 Agent 配置中获取 model 字段（model_definition 的 ID），通过 ChatModelRegistry 解析出对应的 ChatModel 实例、模型 API 标识和提供者类型。

### DashScope 迁移

系统启动时检查 application.yml 中的 DashScope 配置。如果配置了有效的 API 密钥（非占位符），会将其迁移到 model_provider 数据库表中，实现从配置文件到数据库管理的平滑过渡。

---

## 计划系统后端

### 分层架构

计划系统由三层组成：

- Agent 工具层：`PlanTool`（单一 `plan` 工具，支持 create / step_done / complete）通过 `PlanOperations` SPI 调用网关
- 网关实现层：`InlinePlanOperationsImpl` 包装 `InlinePlanService`
- 接入层：`MessagePipeline` 处理 `plan.*` WebSocket 请求和聊天消息中的计划上下文注入

已删除的组件：`PlanService`、`PlanRequestHandler`、`PlanExecutionOrchestrator`、`PlanController`、独立的 `plan` / `plan_step` 数据库表。

### InlinePlanService

核心服务，直接操作 `transcript_message` 表的 `metadata_json` 字段：

- `createPlanMessage`：创建 plan 类型消息
- `updateStepStatus` / `updatePlanStatus` / `completePlan`：状态和步骤管理
- `getActivePlan`：查询会话活跃计划
- `buildPlanContext`：构建注入 Agent 的执行上下文（含 verification 指引）

### 状态生命周期

计划状态：draft → approved → executing ⇄ paused → completed，任意非终态可转为 cancelled。

步骤状态：pending | in_progress | completed | failed | skipped。步骤包含 `verification` 字段，Agent 须在验证通过后才标记 step_done。

### 数据存储

计划作为 `transcript_message` 中的一条消息存储，`metadata_json` 包含 `type: "plan"` 和完整的步骤数据。计划的唯一标识是 `transcript_message.id`（messageId）。

---

## Bridge 系统

### 注册流程

管理员通过 POST /api/bridge/nodes 创建节点记录，获取一次性连接令牌和建议的本地启动命令（npx intellimate-local ...）。

用户在本地运行启动命令后，Bridge 客户端通过 WebSocket 连接到 /api/bridge/connect 端点。客户端发送 register 消息（包含节点名称和支持的工具列表），服务端验证令牌后创建 BridgeNodeSession 并回复 registered 确认。

连接建立后，服务端定期发送 ping 消息，客户端回复 pong 更新心跳时间。

### 工具路由

当 Agent 配置了 bridge_node 且节点在线时，ToolsEngine 在构建工具回调时检查每个工具是否在节点注册的工具列表中。匹配的工具使用 BridgeToolCallback 包装，执行时通过 WebSocket 发送 tool_call 消息到本地节点，阻塞等待最多 120 秒直到收到 tool_result 回复。

### 会话管理

每个节点名称只允许一个活跃会话。如果同名节点重新连接，旧会话被替换并关闭。节点断开时自动注销并更新数据库状态为 DISCONNECTED。

---

## 应用配置

application.yml 的主要配置节点：

- **server.port**：服务端口，默认 3007
- **spring.r2dbc**：MySQL 响应式连接池配置
- **spring.flyway**：数据库迁移配置（使用独立的 JDBC URL）
- **spring.ai.dashscope**：默认 DashScope API 密钥和模型（启动时迁移到数据库）
- **spring.ai.mcp.client.enabled**：设为 false，MCP 由网关手动管理
- **intellimate.security**：认证令牌、加密密钥、白名单和 DM 配对
- **intellimate.agent**：默认 Agent 名称和模型、对话轮次上限、超时、历史记录条数、循环检测、工具并行度、计划限制
- **intellimate.skills.dir**：技能文件存储目录
- **intellimate.proactive**：proactive 消息的 TTL 和回放限制
- **intellimate.scheduler**：调度引擎的扫描间隔、线程池、并发上限等

---

## 启动配置

IntelliMateApplication 使用 @SpringBootApplication 注解，但显式排除了大部分 Spring AI 的自动配置类（DashScope、OpenAI、Anthropic、DeepSeek 等）——模型的加载和管理由数据库驱动的 ChatModelRegistry 完成，不走 Spring AI 的自动配置。

@ComponentScan 扫描整个 com.atm.intellimate 包，拉入 agent、memory、channel 等兄弟模块的 Bean。@EnableR2dbcRepositories 启用响应式仓库。@EnableScheduling 支持 Spring 的 @Scheduled 注解。

应用启动时允许循环引用（spring.main.allow-circular-references=true），这是因为 agent、gateway 和 memory 模块之间存在相互依赖的 Bean 注入。
