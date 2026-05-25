# IntelliMate 定时任务系统

## 概述

IntelliMate 定时任务系统是一套完整的响应式任务调度框架，负责管理和执行周期性任务、心跳检测、数据清理等后台操作。系统基于 Spring WebFlux 构建，采用 R2DBC 实现全链路非阻塞，支持 Cron 表达式和固定间隔两种触发方式。

定时任务产生的结果（如 AI Agent 回复）通过 WebSocket 实时推送到前端对话框，即使用户离线也不会丢失消息——系统会在用户重新连接时自动投递离线期间产生的消息。

---

## 系统架构

定时任务系统由三个核心层组成：调度引擎层、任务实现层、消息投递层。

### 调度引擎层

调度引擎层是整个系统的核心，负责任务的调度、执行、重试和生命周期管理。

**ReactiveScheduleEngine** 是调度引擎的主控组件，实现了 Spring SmartLifecycle 接口，在应用启动时自动加载任务配置并启动调度循环。引擎每隔 10 秒（可配置）扫描一次待执行任务，找到到期的任务后分发执行。引擎支持最大并发任务数限制（默认 8 个），超出时排队等待。

引擎包含以下子组件：

- **TaskRegistry**：任务注册表，维护任务配置的内存缓存和 Spring Bean 的映射关系。启动时从数据库加载所有任务配置，并将 ScheduledJob 实现类注册到注册表中。提供 getDueJobs 方法查找到期任务，advanceFireTime 方法计算并更新下次触发时间。

- **ExecutionTracker**：执行追踪器，负责记录每次任务执行的完整生命周期。任务开始时创建状态为 RUNNING 的执行日志，任务结束后根据结果更新为 SUCCESS、FAILED 或 TIMEOUT，同时记录执行时长、结果消息和指标数据。

- **RetryHandler**：重试处理器，实现指数退避重试策略。当任务执行失败时，根据配置的最大重试次数决定是否重试。重试延迟采用指数增长（基础延迟乘以 2 的重试次数次方），上限为 10 分钟。同时维护连续失败计数，任务成功后重置。

- **CronCalculator**：时间计算器，支持三种触发类型。CRON 类型使用标准 Spring Cron 表达式计算下次触发时间，支持时区配置。FIXED_RATE 和 FIXED_DELAY 类型将配置的毫秒数加到当前时间作为下次触发时间。

### 任务实现层

系统内置了五种任务类型，所有任务都实现了 ScheduledJob 接口，该接口定义了 getJobName（任务名称）和 execute（执行逻辑）两个核心方法，以及 getJobGroup（任务分组）、getDefaultTimeout（默认超时时间）和 allowConcurrent（是否允许并发）三个可选方法。

### 消息投递层

消息投递层负责将任务产生的结果实时推送到前端，包括 ChatInjectionService（消息注入服务）、SessionRegistry（会话注册表）和前端的事件处理逻辑。

---

## 内置任务类型

### 心跳任务（heartbeat-tick）

心跳任务是系统的核心任务之一，每 60 秒执行一次，负责检测各个 Agent 的活动状态并在适当时机主动与用户互动。

HeartbeatJob 是心跳任务的入口，它加载所有已启用的心跳配置，然后将实际处理逻辑委托给 HeartbeatEngine。

HeartbeatEngine 包含完整的心跳处理流程：

1. 根据当前时间和配置的作息时间（默认 06:00 起床、23:00 睡觉），计算 Agent 当前处于哪个生命周期状态。系统定义了四种状态：SLEEPING（睡眠中，不触发心跳）、WAKING（刚醒来阶段）、ACTIVE（活跃阶段）、WINDING_DOWN（即将休息阶段）。

2. 如果 Agent 处于 SLEEPING 状态，直接跳过本次心跳。否则调用 shouldTrigger 方法判断是否应该触发。触发判断的优先级为：首先检查是否有到期的任务提醒需要推送，其次对 WAKING 和 WINDING_DOWN 状态执行每日去重（每天每种状态只触发一次），最后对 ACTIVE 状态检查是否到达心跳间隔时间（默认 60 分钟）。

3. 触发后，HeartbeatEngine 根据当前状态构建提示词，调用 AgentRuntime 让 AI Agent 生成回复。如果 Agent 回复中包含 [SILENT] 标记，表示 Agent 判断当前不需要打扰用户，只记录日志不推送消息。否则调用 ChatInjectionService 将消息注入到对话中。

心跳任务不允许并发执行（allowConcurrent 返回 false），避免多个心跳实例同时运行导致消息重复。

### Agent 提示任务（agent-prompt）

Agent 提示任务是用户可自定义的定时 AI 对话任务。用户可以通过聊天中的工具调用或 REST API 创建此类任务，指定一个 Agent 和一段提示词，系统会按照设定的时间计划自动执行。

AgentPromptJob 的执行流程：

1. 从任务参数（params_json）中解析出 agentName（目标 Agent 名称）、prompt 或 promptTemplate（提示词内容）、userId（用户标识，默认 "scheduler"）等配置。

2. 如果使用了 promptTemplate，通过 PromptTemplateRenderer 进行模板渲染。模板支持多种内置变量：time（当前时间）、date（当前日期）、dayOfWeek（星期几）、lastResponse（上次回复内容）等。

3. 如果启用了记忆召回功能（enableMemoryRecall，默认开启），从 MemorySystem 中检索与提示词相关的记忆片段，作为额外上下文注入到对话中，最大 Token 数由 maxRecallTokens 控制（默认 500）。

4. 通过 AgentConfigService 解析目标 Agent 的完整配置（模型、人设、工具列表等），然后调用 AgentRuntime.dispatch 执行 AI 对话。

5. 从 Agent 事件流中收集完整回复文本（通过 AgentEvent.Done 事件），然后调用 ChatInjectionService.injectAgentMessage 将回复注入到对话中，标记来源为 SCHEDULED_JOB。

Agent 提示任务允许并发执行，因为不同的任务可能面向不同的 Agent，互不影响。默认超时时间为 2 分钟。

典型使用场景：每天 9 点提醒用户复盘今日工作、每小时检查一次待办事项进度、定期生成数据分析报告等。

### 数据清理任务（data-cleanup）

DataCleanupJob 每天凌晨 4:30 执行，负责清理过期的系统数据：

- 清理超过保留天数（默认 30 天）的任务执行日志（scheduled_job_log 表）
- 清理超过保留天数的心跳日志（heartbeat_log 表）
- 清理超过消息 TTL（默认 24 小时）的 proactive 会话中的 transcript 消息（context_id 以 "proactive::" 开头的消息记录）

### 记忆维护任务（memory-nightly-maintenance）

MemoryMaintenanceJob 每天凌晨 3 点执行，委托给记忆系统的遗忘调度器进行记忆维护。包括过期记忆的清理、记忆权重的衰减等操作。

### HTTP 回调任务（http-callback）

HttpCallbackJob 支持用户创建自定义的 HTTP 回调任务，可以在指定时间向外部 URL 发送 GET 或 POST 请求。参数包括目标 URL（必填）、HTTP 方法（默认 GET）和可选的请求体。系统内置了 SSRF 防护，阻止对内网地址的请求。允许并发执行。

---

## 消息投递机制

定时任务产生的 AI 回复通过以下流程到达前端用户界面。

### 消息持久化

ChatInjectionService 是消息投递的核心服务。当 AgentPromptJob 或 HeartbeatEngine 调用 injectAgentMessage 方法时，服务首先通过 SessionManager.findOrCreateProactiveSession 获取或创建一个专用的 proactive 会话。每个 Agent 有独立的 proactive 会话，其 context_id 格式为 "proactive::{agentName}"，与用户的正常聊天会话隔离。

消息以 TranscriptMessageEntity 的形式持久化到 transcript_message 表中，包含以下关键字段：session_id（关联的 proactive 会话 ID）、role（固定为 "assistant"）、content（AI 回复的完整文本）、metadata_json（JSON 格式的元数据，包含消息来源标记 "heartbeat" 或 "scheduled_job"）、created_at（创建时间戳）。

### 实时推送

消息持久化完成后，ChatInjectionService 立即尝试通过 WebSocket 实时推送。调用 SessionRegistry.pushToAllAgentSessions 方法，将消息推送到所有已绑定该 Agent 的活跃 WebSocket 会话。

推送的事件类型为 "agent.proactive"，载荷包含：agentName（Agent 名称）、requestId（唯一请求标识，格式 "bg-" 加 8 位 UUID）、text（消息文本）、source（来源标识："heartbeat" 或 "scheduled_job"）、timestamp（时间戳）。

如果当前没有活跃的 WebSocket 会话绑定到该 Agent（即用户不在线），推送会返回 0 并记录警告日志。消息不会丢失，因为已经持久化到数据库。

### 离线消息投递

当用户打开页面或刷新页面时，前端会建立 WebSocket 连接。连接成功后，服务端发送 session.welcome 事件。前端随后发送 agent.bind 事件，将当前 WebSocket 会话绑定到用户正在查看的 Agent。

GatewayWebSocketHandler 在收到 agent.bind 事件时，除了在 SessionRegistry 中注册绑定关系外，还会调用 ChatInjectionService.deliverPendingMessages 方法。

deliverPendingMessages 的投递策略：

- 服务器启动后首次 bind 某个 Agent 时，只记录当前时间作为检查点（lastDeliveredTimestamp），不投递任何历史消息。这避免了每次启动服务器后用户看到大量旧消息。

- 后续 bind 时（例如用户刷新页面或断线重连），系统查找从上次投递时间到当前时间之间产生的新 proactive 消息，通过 SessionRegistry.pushToSession 逐条推送给当前 WebSocket 会话。推送数量上限为 replay-limit（默认 20 条），消息时间范围受 message-ttl-hours（默认 24 小时）限制。

- 投递完成后更新 lastDeliveredTimestamp 为当前时间，确保同一批消息不会被重复投递。

### 前端消息处理

前端通过 useWebSocket Hook 处理 agent.proactive 事件。如果用户当前正在等待 Agent 回复（isWaiting 状态为 true），proactive 消息会被缓冲到队列中，待当前回复完成后再刷新显示，避免消息在界面上交错出现。如果用户不在等待状态，消息立即通过 chatStore.addProactiveMessage 添加到消息列表中显示。

消息在界面上以普通助手消息气泡的形式呈现，与用户主动发起对话得到的回复在视觉上保持一致。

---

## 任务配置

### 全局配置

全局调度器配置位于 application.yml 的 intellimate.scheduler 节点下：

- **enabled**：是否启用调度引擎，默认 true
- **tick-period-seconds**：调度循环的扫描间隔，默认 10 秒。值越小任务触发越精确，但 CPU 开销越大
- **pool-size**：调度线程池大小，默认 4
- **shutdown-await-seconds**：应用关闭时等待运行中任务完成的最大秒数，默认 30 秒
- **log-retention-days**：执行日志保留天数，默认 30 天
- **default-timeout-ms**：任务默认超时时间（毫秒），默认 300000（5 分钟）
- **max-concurrent-jobs**：最大并发任务数，默认 8
- **config-reload-debounce-ms**：配置变更检测的防抖时间，默认 500 毫秒

消息投递相关配置位于 intellimate.proactive 节点下：

- **message-ttl-hours**：proactive 消息的保留时间（小时），默认 24。超过此时间的消息不会在用户上线时投递，也会被数据清理任务删除
- **replay-limit**：单次投递的最大消息条数，默认 20

### 每任务配置

每个定时任务在 scheduled_job_config 数据库表中有一条配置记录，包含以下关键字段：

- **job_name**：任务的唯一标识符。内置任务使用固定名称（heartbeat-tick、memory-nightly-maintenance、data-cleanup），用户创建的任务使用 "chat-" 前缀加随机后缀的格式
- **job_class**：任务实现类的 Bean 名称。对于用户创建的任务，使用 "agent-prompt" 或 "http-callback"
- **job_group**：任务分组，用于分类管理。内置任务使用 "agent" 或 "data"，用户创建的任务使用 "user-chat" 或 "custom"
- **trigger_type**：触发类型，可选 CRON、FIXED_RATE、FIXED_DELAY
- **trigger_value**：触发值。CRON 类型为 Cron 表达式（如 "0 0 9 * * ?"），FIXED_RATE/FIXED_DELAY 为毫秒数
- **timezone**：时区，默认 Asia/Shanghai
- **enabled**：是否启用，1 为启用，0 为暂停
- **timeout_ms**：任务执行超时时间（毫秒），为空时使用全局默认值
- **max_retry_count**：最大重试次数，默认 0（不重试）
- **retry_backoff_ms**：重试基础延迟（毫秒），默认 5000
- **params_json**：任务参数，JSON 格式。不同任务类型需要不同的参数
- **concurrent_allowed**：是否允许并发执行，0 为不允许
- **next_fire_time**：下次触发时间，由引擎自动维护
- **last_fire_time**：上次触发时间
- **last_status**：上次执行状态（SUCCESS、FAILED、TIMEOUT）
- **consecutive_failures**：连续失败次数

### 心跳每 Agent 配置

心跳功能有独立的 heartbeat_config 表，每个 Agent 一条记录：

- **agent_id**：关联的 Agent ID
- **enabled**：是否启用心跳，默认关闭
- **timezone**：时区，默认 Asia/Shanghai
- **wake_time**：起床时间，默认 06:00
- **sleep_time**：睡觉时间，默认 23:00
- **heartbeat_interval_minutes**：ACTIVE 状态下的心跳间隔（分钟），默认 60

---

## REST API

### 定时任务管理接口

所有接口前缀为 /api/scheduled-jobs。

**任务 CRUD：**

- GET /api/scheduled-jobs：获取所有任务列表
- POST /api/scheduled-jobs：创建新任务
- GET /api/scheduled-jobs/{jobName}：获取指定任务详情及最近执行日志
- PUT /api/scheduled-jobs/{jobName}：更新任务配置
- DELETE /api/scheduled-jobs/{jobName}：删除任务（内置任务禁止删除）

**任务控制：**

- POST /api/scheduled-jobs/{jobName}/trigger：手动触发任务执行，有 10 秒冷却时间，不影响正常调度计划
- POST /api/scheduled-jobs/{jobName}/pause：暂停任务（设置 enabled=0）
- POST /api/scheduled-jobs/{jobName}/resume：恢复任务（设置 enabled=1，重新计算下次触发时间）

**执行日志：**

- GET /api/scheduled-jobs/{jobName}/logs：获取指定任务的执行日志（分页）
- GET /api/scheduled-jobs/{jobName}/logs/{logId}：获取单条执行日志详情
- GET /api/scheduled-jobs/logs/recent：获取所有任务的最近执行日志

**统计信息：**

- GET /api/scheduled-jobs/stats/overview：调度器总览统计（任务总数、活跃数、成功率等）
- GET /api/scheduled-jobs/stats/{jobName}：指定任务的执行统计
- GET /api/scheduled-jobs/stats/timeline：执行时间线数据

### 心跳管理接口

所有接口前缀为 /api/heartbeat。

- GET /api/heartbeat/{agentId}：获取指定 Agent 的心跳配置
- PUT /api/heartbeat/{agentId}：更新心跳配置
- GET /api/heartbeat/{agentId}/state：获取当前生命周期状态
- GET /api/heartbeat/{agentId}/logs：获取心跳日志
- POST /api/heartbeat/{agentId}/trigger：手动触发一次心跳（跳过 shouldTrigger 判断）

### Agent 工具接口

Agent 在对话中可以通过 ScheduledJobManagementTool 工具直接创建和管理定时任务。工具提供 createScheduledJob、listScheduledJobs、updateScheduledJob、deleteScheduledJob 四个方法。系统保护内置任务不被删除或覆盖。

---

## WebSocket 事件

定时任务系统通过 WebSocket 推送以下事件类型：

### agent.proactive（服务端推送）

定时任务或心跳产生的 AI 回复消息。载荷包含 agentName、requestId、text、source 和 timestamp 字段。前端收到后将消息添加到对应 Agent 的消息列表中。

### scheduler.job.started（服务端广播）

任务开始执行时广播给所有已连接的客户端。载荷包含 jobName、triggerSource（AUTO、MANUAL 或 RETRY）等信息。前端调度器面板据此更新任务运行状态。

### scheduler.job.completed（服务端广播）

任务执行完成时广播。载荷包含 jobName、status（SUCCESS、FAILED、TIMEOUT）、duration（执行时长）、message（结果描述）等。前端据此更新任务状态和最近执行结果。

### agent.bind（客户端发送）

前端发送给服务端，将当前 WebSocket 会话绑定到指定 Agent。绑定后该会话才能接收该 Agent 的 proactive 消息。同时触发离线消息的投递检查。

---

## 数据库表结构

### scheduled_job_config

存储所有定时任务的配置信息。job_name 列有唯一索引，enabled 和 next_fire_time 列有联合索引用于加速到期任务查询。系统启动时从该表加载所有配置到内存缓存。

### scheduled_job_log

记录每次任务执行的完整信息，包括执行状态、开始时间、结束时间、持续时长、触发来源、结果消息、指标数据和错误堆栈。由 DataCleanupJob 定期清理超过保留天数的记录。

### heartbeat_config

存储每个 Agent 的心跳配置，包括启用状态、时区、作息时间和心跳间隔。

### heartbeat_log

记录每次心跳执行的详细信息，包括生命周期状态、触发原因、是否发送了消息、AI 回复内容等。

### session 和 transcript_message

proactive 消息使用独立的 session 记录（context_id 格式为 "proactive::{agentName}"），消息存储在 transcript_message 表中。这种设计将定时任务消息与用户主动对话消息在存储层面隔离，同时复用已有的会话和消息基础设施。

---

## 执行生命周期

一个定时任务从触发到完成经历以下阶段：

1. **调度发现**：ReactiveScheduleEngine 的 tick 循环扫描 TaskRegistry，找到 enabled 且 next_fire_time 小于等于当前时间的任务配置。

2. **前置检查**：检查任务是否有对应的 Spring Bean 实现，检查是否已有相同任务在运行（对于不允许并发的任务），检查并发任务总数是否超过上限。

3. **执行准备**：将任务加入 runningJobs 映射表，在 TaskRegistry 中标记为 RUNNING，通过 ExecutionTracker 在 scheduled_job_log 表中创建一条 RUNNING 状态的记录。通过 WebSocket 广播 scheduler.job.started 事件。

4. **任务执行**：调用任务实现的 execute 方法，传入 JobExecutionContext（包含任务名称、参数、触发来源等信息）。执行过程受超时时间限制。

5. **结果处理**：
   - 成功：ExecutionTracker 更新日志为 SUCCESS，RetryHandler 重置连续失败计数，TaskRegistry 根据触发类型计算并更新 next_fire_time（仅 AUTO 触发才推进，MANUAL 触发不影响调度计划）。
   - 失败/超时：RetryHandler 检查是否允许重试。如果允许，计算指数退避延迟后重新调度执行。如果不允许或已达最大重试次数，ExecutionTracker 更新日志为 FAILED/TIMEOUT，RetryHandler 递增连续失败计数。

6. **清理**：从 runningJobs 映射表中移除任务。通过 WebSocket 广播 scheduler.job.completed 事件。

手动触发（通过 REST API 的 trigger 端点）的任务经历相同的执行生命周期，但有两个区别：触发来源标记为 MANUAL 而非 AUTO，且不推进 next_fire_time，保持原有调度计划不变。手动触发有 10 秒冷却时间防止频繁触发。

---

## 设计要点

### 消息不丢失

所有 proactive 消息在推送前先持久化到数据库。即使推送时用户不在线（pushToAllAgentSessions 返回 0），消息也安全地存储在 proactive session 中。用户下次上线时通过 agent.bind 触发的 deliverPendingMessages 机制投递离线期间的消息。

### 消息不重复

deliverPendingMessages 通过内存中的 lastDeliveredTimestamp 跟踪每个 Agent 的最后投递时间。只有在该时间戳之后产生的消息才会被投递。服务器首次启动时将当前时间设为检查点，避免历史消息全量重放。

### 隔离性

proactive 消息使用独立的 session（context_id = "proactive::{agentName}"），与用户的正常聊天 session 完全隔离。数据清理任务只清理 proactive session 中的过期消息，不影响正常聊天记录。

### 并发安全

调度引擎使用 ConcurrentHashMap 管理运行中的任务，防止不允许并发的任务被重复执行。lastDeliveredTimestamp 同样使用 ConcurrentHashMap，支持多 WebSocket 会话并发 bind 同一 Agent。

### 前端缓冲

当用户正在与 Agent 对话（等待回复中）时，到达的 proactive 消息不会立即插入消息列表，而是缓冲到队列中。待当前对话回复完成后再一次性刷新显示，避免消息在界面上交错造成混乱。
