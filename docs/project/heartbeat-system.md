# IntelliMate 心跳系统

## 概述

心跳系统是 IntelliMate 的核心主动通信机制，赋予 Agent 在用户未主动发消息时也能主动说话的能力。系统模拟了一个"有作息规律的智能伙伴"：Agent 有固定的起床时间和睡觉时间，会在早晨打招呼、适时提醒待办任务、晚上道晚安，并在休息时段保持完全静默。

心跳系统由三大核心组件协同工作：调度引擎负责定时触发、心跳引擎负责决策和执行、消息注入服务负责持久化和推送。

## 架构总览

```
┌──────────────────────┐
│  ReactiveScheduleEngine │  每分钟 tick
└──────────┬───────────┘
           │
           ▼
┌──────────────────────┐
│     HeartbeatJob      │  遍历所有启用的心跳配置
└──────────┬───────────┘
           │  对每个 agent 调用
           ▼
┌──────────────────────┐
│    HeartbeatEngine    │  核心决策逻辑
│                      │
│  processHeartbeat()  │─────────────────┐
│  shouldTrigger()     │                 │
│  executeBeat()       │                 │
└──────────┬───────────┘                 │
           │                             │
     ┌─────┴─────┐                       │
     ▼           ▼                       ▼
┌─────────┐ ┌──────────────┐   ┌──────────────────┐
│ AgentRuntime │ │ HeartbeatLog │   │ ChatInjectionService │
│ (LLM 调用)   │ │  (日志记录)   │   │  (消息注入)          │
└─────────┘ └──────────────┘   └────────┬─────────┘
                                        │
                               ┌────────┴────────┐
                               ▼                  ▼
                        ┌────────────┐    ┌──────────────┐
                        │ transcript │    │  WebSocket    │
                        │ _message   │    │  推送         │
                        └────────────┘    └──────────────┘
```

## Agent 生命周期状态

心跳系统的核心概念是 Agent 的生命周期状态机。每个 Agent 配置了起床时间（wakeTime）和睡觉时间（sleepTime），系统根据当前时间自动计算 Agent 所处的状态。

### 四种状态

| 状态 | 说明 | 时间范围 | 行为 |
|------|------|----------|------|
| SLEEPING（休眠中） | Agent 处于休息时段 | sleepTime 至 wakeTime | 完全静默，不执行任何心跳或任务提醒 |
| WAKING（刚醒来） | Agent 刚起床的第一个小时 | wakeTime 至 wakeTime+1h | 发送早安问候，提及今天的待办。每天仅触发一次 |
| ACTIVE（活跃中） | Agent 正常活跃时段 | wakeTime+1h 至 sleepTime-2h | 按间隔检查，有到期任务时主动提醒；无任务时保持静默 |
| WINDING_DOWN（准备休息） | 即将进入休眠的两小时 | sleepTime-2h 至 sleepTime | 总结今天、提醒明天事项。每天仅触发一次 |

### 状态计算示例

假设配置 wakeTime=07:00、sleepTime=23:00：

```
00:00 ─── SLEEPING ─── 07:00 ─── WAKING ─── 08:00 ─── ACTIVE ─── 21:00 ─── WINDING_DOWN ─── 23:00 ─── SLEEPING
```

状态计算支持跨午夜场景。例如 wakeTime=06:00、sleepTime=02:00 时，SLEEPING 时段为 02:00 到 06:00。

### 状态计算逻辑

`LifecycleState.compute()` 方法接收当前时间、起床时间和睡觉时间三个参数，通过 `isBetween()` 辅助方法判断当前时间落在哪个区间。`isBetween()` 能正确处理跨午夜的时间段（如 23:00 到 07:00）。

## 心跳触发机制

### 调度入口

`HeartbeatJob` 是一个注册在调度系统中的定时任务（默认每分钟执行一次），执行时从 `heartbeat_config` 表加载所有 `enabled=1` 的配置，逐个调用 `HeartbeatEngine.processHeartbeat()`。

需要区分两个层级的"间隔"概念：

检查频率（HeartbeatJob 执行间隔）：每分钟执行一次。好比一个值班员每分钟巡逻一圈，看看有没有什么需要处理的事情。巡逻本身不产生消息，只是"看一看"。

心跳冷却间隔（heartbeatIntervalMinutes，默认 60 分钟）：这是 Agent 在 ACTIVE（日常活跃）状态下，两次主动"找用户说话"之间的最短等待时间。好比你的朋友不会每分钟都发微信问你在干嘛，而是隔一段时间才会主动联系你一次。值班员每分钟巡逻一次，但他看到上次已经跟你说过话了，距离现在还不到 60 分钟，就会选择"这次先不打扰了"。只有当距离上次说话已经过了 60 分钟，并且确实有值得说的内容（比如有待办任务快到期了），他才会再次找你。如果没什么可说的，即使过了 60 分钟，Agent 也会选择保持沉默（LLM 返回 [SILENT]）。

例外情况：到期任务提醒（用户设置了具体提醒时间的任务到时了）会跳过冷却间隔立即触发，就像设了闹钟不管你多久前刚说过话都会响。WAKING 和 WINDING_DOWN 状态不受冷却间隔限制，改为每天仅触发一次的去重逻辑。

### 触发判定流程

`processHeartbeat()` 是心跳引擎的入口方法，执行以下逻辑：

1. 根据 Agent 配置的时区和作息时间，计算当前生命周期状态
2. 如果状态为 SLEEPING，直接返回空（完全静默）
3. 否则调用 `shouldTrigger()` 判断是否需要触发
4. 如果需要触发，执行 `executeBeat()`

### shouldTrigger 决策规则

`shouldTrigger()` 方法实现了分层优先级的触发策略：

第一优先级：到期任务提醒。查询 `agent_task` 表中 `remind_at <= now` 且状态为 pending 的任务。如果存在到期提醒，无视任何频率限制，立即触发。

第二优先级（WAKING / WINDING_DOWN 状态）：每日去重。查询 `heartbeat_log` 表，检查今天是否已经为该 Agent 在该状态下触发过心跳。如果已触发，则不再重复。这里的"今天"基于 Agent 配置的时区计算，避免服务器时区与 Agent 时区不一致导致的去重失效。

第三优先级（ACTIVE 状态）：间隔检查。查询最近一次心跳日志，如果距今已超过配置的 `heartbeatIntervalMinutes`，则触发。如果无历史日志记录，默认触发。

### executeBeat 执行流程

当 `shouldTrigger()` 返回 true 后，`executeBeat()` 负责生成并投递消息：

1. 并行查询两类任务数据：未来 2 小时内到期的任务（upcoming）和已到提醒时间的任务（due）
2. 将两类任务合并去重（due 优先），构建完整的任务上下文
3. 查询 Agent 名称（用于消息署名和 LLM 人设）
4. 调用 `HeartbeatContextBuilder.buildPrompt()` 构建 LLM prompt
5. 通过 `AgentRuntime.dispatch()` 调用 LLM 生成回复
6. 如果 LLM 返回 `[SILENT]` 标记，仅记录日志不推送消息
7. 如果 LLM 返回正常文本，通过 `ChatInjectionService` 持久化并推送
8. 无论 LLM 是否静默，都会清除已处理的到期提醒（将 `remind_at` 置为 NULL）

## LLM Prompt 构建

`HeartbeatContextBuilder` 根据当前状态、任务列表和时间信息构建引导 LLM 生成恰当回复的 prompt。

### Prompt 结构

```
你是 {agentName}，现在是 {datetime}（{stateDescription}）。

待办事项：
- {taskTitle}（截止：{dueAt}）[紧急/重要] ⏰到期提醒
- ...

根据当前情境，请决定是否需要对用户说些什么：
- 如果是「刚醒来」状态：发送温暖的早安问候，提及今天的待办事项
- 如果有到期/即将到期的任务：友好地提醒用户
- 如果是「准备休息」状态：总结今天，提醒明天的事项
- [条件性指令：有到期提醒时禁止返回 SILENT / 无到期时可返回 SILENT]

注意：保持简洁自然，像朋友一样聊天，不要过于正式或冗长（控制在 100 字以内）。
```

### 关键设计决策

到期提醒强制发声：当存在 `remind_at <= now` 的任务时，prompt 中会明确指示 LLM 禁止返回 `[SILENT]`，确保用户设置的提醒一定会被投递。

状态感知的回复风格：WAKING 状态引导生成早安问候，WINDING_DOWN 引导生成晚安总结，ACTIVE 状态侧重任务提醒。

字数限制：限制 100 字以内，避免 LLM 生成冗长的"AI 味"文本，保持像朋友间的自然对话。

### ACTIVE 状态的消息决策

ACTIVE 状态下，心跳冷却间隔（默认 60 分钟）过后触发执行，但是否产生消息取决于任务状态和 LLM 的判断。分三种情况：

完全无任务：LLM 收到的 prompt 显示"暂无待办事项"，加上"如果觉得没有必要说话，仅回复 [SILENT]"的指示。LLM 几乎必然返回 [SILENT]，系统仅记录心跳日志，不向用户推送任何消息。即使 LLM 调用失败，兜底逻辑也返回 [SILENT]。

有任务但未到提醒时间：如果任务的 due_at 在未来 2 小时内，LLM 会在 prompt 中看到这些任务，但没有"⏰到期提醒"强制标记。LLM 可以自行判断是否值得提前提醒（比如"你有个任务 1 小时后到期哦"），也可以选择 [SILENT] 不打扰。这是 LLM 的自由裁量空间。

任务的 remind_at 已到期：这属于到期提醒优先路径，不受冷却间隔限制（每分钟检查时就会触发），而且 prompt 中明确禁止返回 [SILENT]。系统保证用户设置的提醒一定会被投递。

简单来说，60 分钟冷却间隔过后只是让 Agent"有机会说话"，真正说不说取决于有没有值得说的内容。这个设计避免了 Agent 每小时无意义地打招呼，同时确保重要提醒不会被遗漏。

### LLM 容错机制

如果 LLM 调用超时（30 秒）或失败，系统会使用预设的模板文本兜底：WAKING 状态返回"早上好！新的一天开始了。"，WINDING_DOWN 返回"晚上好！今天辛苦了，早点休息。"，ACTIVE 状态如果有任务则返回任务数量提醒，否则返回 `[SILENT]`。

## 消息注入与推送

### ChatInjectionService

`ChatInjectionService` 是心跳系统和定时任务系统共用的消息投递通道，负责两件事：

持久化：将 Agent 主动发出的消息写入 `transcript_message` 表（通过 proactive session），使其成为对话历史的一部分。消息的 `metadata_json` 字段标记来源（heartbeat 或 scheduled_job）。

实时推送：通过 WebSocket 向当前连接该 Agent 的所有客户端推送 `agent.proactive` 事件。

### 离线消息处理

当用户不在线（没有活跃的 WebSocket 连接）时，心跳系统不会为该 Agent 生成任何消息。整个心跳流程被跳过——不调用 LLM、不记录日志、不推送消息。

但是，到期任务的 remind_at 不会被清除。这意味着当用户重新上线后，下一次心跳检查（最多等待 1 分钟）会自动发现这些过期的提醒，正常触发提醒流程并通知用户。

这个设计的理念是：Agent 不会在没人听的时候自言自语，但也不会忘记重要的提醒。用户回来后，过期的待办提醒会在第一时间送达。

## 任务提醒机制

### 任务与心跳的关系

Agent 任务（`agent_task` 表）可以设置 `remind_at` 字段指定提醒时间。心跳系统在每次触发时会检查是否有到期的提醒。

### 提醒触发流程

1. `shouldTrigger()` 检查是否存在 `remind_at <= now` 的 pending 任务
2. 如果存在，立即触发心跳（跳过频率限制和状态去重）
3. `executeBeat()` 将到期任务合并到 prompt 上下文中
4. LLM 被强制要求针对到期任务发送提醒消息
5. 提醒投递后（无论 LLM 是否返回 `[SILENT]`），`remind_at` 被清除为 NULL，防止重复提醒

### SLEEPING 状态下的任务处理

设计决策：SLEEPING 状态下不执行任何心跳或任务提醒。如果用户在 Agent 休眠期间设置了到期提醒，该提醒会在 Agent 醒来后的第一次心跳中被处理。这符合"伙伴在休息时不会打扰你"的产品理念。

## 数据模型

### heartbeat_config 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| agent_id | BIGINT | 关联的 Agent ID |
| enabled | INT | 是否启用（1=启用，0=禁用） |
| timezone | VARCHAR | Agent 时区（如 Asia/Shanghai） |
| wake_time | VARCHAR | 起床时间（HH:mm 格式） |
| sleep_time | VARCHAR | 睡觉时间（HH:mm 格式） |
| heartbeat_interval_minutes | INT | ACTIVE 状态下的心跳间隔（分钟） |

### heartbeat_log 表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| agent_id | BIGINT | Agent ID |
| state | VARCHAR | 触发时的生命周期状态 |
| triggered_at | DATETIME | 触发时间 |
| prompt_used | TEXT | 发送给 LLM 的 prompt |
| response | TEXT | LLM 返回的回复内容 |
| delivered | INT | 投递状态 |

### agent_task 表（心跳相关字段）

| 字段 | 类型 | 说明 |
|------|------|------|
| remind_at | DATETIME | 提醒时间。到期后心跳引擎会触发提醒，处理后置为 NULL |
| due_at | DATETIME | 截止时间。用于构建 LLM prompt 中的任务上下文 |
| priority | INT | 优先级。0=普通，1=重要，2=紧急 |
| status | VARCHAR | 任务状态。pending 状态的任务参与心跳提醒判定 |

## 配置参数

所有配置在 `application.yml` 中管理：

```yaml
intellimate:
  proactive:
    message-ttl-hours: 24      # 主动消息有效期（小时），用于 DataCleanupJob 清理过期 transcript
  scheduler:
    log-retention-days: 30     # 心跳日志和任务日志保留天数
```

每个 Agent 的心跳行为通过 `heartbeat_config` 表独立配置：
- `timezone`：决定生命周期状态计算的时区基准
- `wake_time` / `sleep_time`：定义 Agent 的作息时间
- `heartbeat_interval_minutes`：ACTIVE 状态下的检查间隔
- `enabled`：全局开关

## 数据清理

`DataCleanupJob` 是定期执行的数据清理任务，负责：

1. 清理超过 `log-retention-days` 天的 `scheduled_job_log` 记录
2. 清理超过 `log-retention-days` 天的 `heartbeat_log` 记录
3. 清理超过 `message-ttl-hours` 小时的 proactive `transcript_message` 记录

proactive 消息通过 session 的 `context_id LIKE 'proactive::%'` 来识别，确保只清理 Agent 主动消息，不影响用户正常对话的历史记录。

## API 接口

### 心跳配置管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/heartbeat/configs | 获取所有心跳配置 |
| GET | /api/heartbeat/{agentId} | 获取指定 Agent 的心跳配置 |
| PUT | /api/heartbeat/{agentId} | 更新 Agent 的心跳配置 |
| GET | /api/heartbeat/{agentId}/state | 获取 Agent 当前生命周期状态 |
| GET | /api/heartbeat/{agentId}/logs | 获取 Agent 的心跳日志 |
| POST | /api/heartbeat/{agentId}/trigger | 手动触发一次心跳（跳过触发判定） |

### WebSocket 事件

| 事件名 | 方向 | 说明 |
|--------|------|------|
| agent.proactive | 服务端 → 客户端 | 推送 Agent 主动消息 |
| agent.bind | 客户端 → 服务端 | 绑定到指定 Agent，触发待发消息回放 |

`agent.proactive` 事件的 payload 结构：

```json
{
  "agentName": "shangtang",
  "requestId": "bg-a1b2c3d4",
  "text": "早安呀～今天有两个待办事项等着你呢！",
  "source": "heartbeat",
  "timestamp": 1716350400000
}
```

## 故障处理

### LLM 调用失败

设置 30 秒超时。超时或异常时使用预设模板文本兜底，确保关键状态（WAKING、WINDING_DOWN）下用户仍能收到基本问候。ACTIVE 状态在无任务时兜底为 `[SILENT]`，有任务时兜底为任务数量提醒。

### WebSocket 断连

当用户断开 WebSocket 连接后，心跳系统会检测到该 Agent 无在线会话，自动跳过心跳执行。到期的任务提醒（remind_at）保持不变，等待用户重新上线后在下一次心跳中被处理。用户重连后，心跳系统恢复正常工作，过期的提醒会在首次心跳时送达。

### 数据库查询失败

`HeartbeatJob` 为每个 Agent 的处理独立捕获异常。单个 Agent 的失败不会影响其他 Agent 的心跳执行。

### 重复触发防护

WAKING 和 WINDING_DOWN 状态通过查询 `heartbeat_log` 实现每日去重。到期任务提醒通过在处理后清除 `remind_at` 防止重复触发。
