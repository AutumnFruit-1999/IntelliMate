# Agent 心跳系统 - 详细设计文档

## 1. 功能概述

### 1.1 核心目标
让 Agent 具备类人的生命节律，能够：
- 根据时间自动切换行为模式（清醒/工作/休闲/睡眠）
- 在合适的时机主动发起对话（问候、提醒、关心）
- 管理用户的待办事项并在到期时主动提醒
- 用户离线时缓存消息，上线后自动投递

### 1.2 用户故事

**场景 1：早安问候**
> 用户早上 7:00 打开应用，看到 Agent 在 6:30 发送的消息：
> "早上好！今天上海天气晴，26°C。你有一个待办事项：10:00 团队周会。需要我帮你准备什么吗？"

**场景 2：任务提醒**
> 用户昨天说"明天下午3点提醒我给客户打电话"
> 14:50 Agent 主动发消息："快到 3 点了，别忘了给客户打电话哦。需要我帮你查一下客户的联系方式吗？"

**场景 3：晚安总结**
> 21:30 Agent 主动发送：
> "今天辛苦了！回顾一下今天完成了：✅ 代码审查 ✅ 文档更新。还有一个未完成：❌ API 设计文档。要改到明天吗？"

**场景 4：离线消息**
> 用户 3 天没上线，Agent 期间缓存了 2 条消息
> 用户上线后立即看到："你好久没来了，一切还好吗？这几天有几件事想告诉你..."

---

## 2. 生命节律状态机

### 2.1 状态定义

```
┌──────────────────────────────────────────────────┐
│                   一天的时间轴                      │
├──────┬──────┬─────────────────────┬───────┬──────┤
│SLEEP │WAKE  │       ACTIVE        │WIND_DN│SLEEP │
│23-06 │06-07 │      07-21          │ 21-23 │23-06 │
└──────┴──────┴─────────────────────┴───────┴──────┘
```

| 状态 | 时段（默认） | Agent 行为 | 触发条件 |
|------|------------|-----------|---------|
| `SLEEPING` | 23:00-06:00 | 完全静默，不主动发消息 | 无 |
| `WAKING` | 06:00-07:00 | 发送早安问候，含天气/日程概览 | 进入此状态时触发一次 |
| `ACTIVE` | 07:00-21:00 | 按间隔检查，有任务到期才发消息 | 每 heartbeat_interval 检查一次 |
| `WINDING_DOWN` | 21:00-23:00 | 发送今日总结，提醒明日事项 | 进入此状态时触发一次 |

### 2.2 状态转换规则

```java
public static LifecycleState compute(LocalTime now, LocalTime wakeTime, LocalTime sleepTime) {
    LocalTime wakingEnd = wakeTime.plusHours(1);     // wake+1h
    LocalTime windingStart = sleepTime.minusHours(2); // sleep-2h

    if (isInRange(now, sleepTime, wakeTime)) return SLEEPING;
    if (isInRange(now, wakeTime, wakingEnd)) return WAKING;
    if (isInRange(now, windingStart, sleepTime)) return WINDING_DOWN;
    return ACTIVE;
}
```

### 2.3 触发决策矩阵

| 条件 | 是否调用 LLM | 说明 |
|------|------------|------|
| 状态=SLEEPING | ❌ | 直接跳过 |
| 状态=WAKING + 今天还没问候 | ✅ | 发送晨间问候 |
| 状态=ACTIVE + 有到期任务 | ✅ | 发送任务提醒 |
| 状态=ACTIVE + 无到期任务 + 未到间隔 | ❌ | 跳过 |
| 状态=ACTIVE + 无到期任务 + 到达间隔 | ❌ | 跳过（无事不扰） |
| 状态=WINDING_DOWN + 今天还没总结 | ✅ | 发送日间总结 |

**关键设计：只有「有事可说」时才调 LLM。** 没有到期任务且不是状态转换时，心跳静默通过。

---

## 3. 技术架构

### 3.1 组件图

```
┌─────────────────────────────────────────────────────────────┐
│                    intellimate-gateway                          │
│                                                             │
│  ┌────────────────────────────────────────────────────┐     │
│  │              HeartbeatScheduler                     │     │
│  │  @Scheduled(fixedRate=60s)                         │     │
│  │  遍历 enabled 的配置 → HeartbeatEngine.process     │     │
│  └────────────────────┬───────────────────────────────┘     │
│                       │                                     │
│  ┌────────────────────▼───────────────────────────────┐     │
│  │              HeartbeatEngine                        │     │
│  │                                                    │     │
│  │  1. computeState(config, now)                      │     │
│  │  2. shouldTrigger(config, state, lastLog)          │     │
│  │  3. buildContext(state, tasks, memory)             │     │
│  │  4. agentRuntime.dispatch(heartbeatRequest)        │     │
│  │  5. deliver(response)                              │     │
│  └──┬─────────────┬──────────────────────┬────────────┘     │
│     │             │                      │                  │
│  ┌──▼──┐    ┌─────▼──────┐    ┌─────────▼──────────┐       │
│  │Task │    │  Agent     │    │  SessionRegistry   │       │
│  │Repo │    │  Runtime   │    │  (在线→WS推送)     │       │
│  └─────┘    └────────────┘    │  (离线→DB缓存)     │       │
│                               └────────────────────┘       │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 心跳执行时序

```
HeartbeatScheduler          HeartbeatEngine           AgentRuntime        SessionRegistry
     │                           │                        │                    │
     │──tick()──────────────────▶│                        │                    │
     │                           │                        │                    │
     │                           │ computeState()         │                    │
     │                           │ shouldTrigger()        │                    │
     │                           │                        │                    │
     │                           │ [如果不触发] return     │                    │
     │                           │                        │                    │
     │                           │ [如果触发]             │                    │
     │                           │ buildContext()         │                    │
     │                           │                        │                    │
     │                           │──dispatch(request)────▶│                    │
     │                           │                        │──stream events──┐  │
     │                           │                        │                 │  │
     │                           │◀──response text────────│◀────────────────┘  │
     │                           │                        │                    │
     │                           │──pushToAgent()─────────────────────────────▶│
     │                           │                        │                    │
     │                           │ [如果离线]             │                    │
     │                           │ saveOfflineMessage()   │                    │
     │                           │                        │                    │
     │◀─────────────────────────│                        │                    │
```

### 3.3 心跳 Prompt 模板

```markdown
你是 {agent_name}，现在是 {current_time}（{state_description}）。

你的性格设定：
{personality_prompt}

当前上下文：
- 状态：{state}（{state_explanation}）
- 用户最后在线：{last_seen}
- 待办事项：
{task_list_or_none}

你的记忆摘要：
{memory_summary}

请根据当前情境决定是否需要主动对用户说些什么。
- 如果是 WAKING 状态：发送温暖的早安问候
- 如果有到期任务：友好地提醒用户
- 如果是 WINDING_DOWN 状态：总结今天的对话和任务完成情况
- 如果觉得没有必要说话：回复 [SILENT]

注意：保持简洁自然，像朋友一样，不要过于正式。
```

---

## 4. 数据模型详解

### 4.1 heartbeat_config

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| id | BIGINT PK | auto | |
| agent_id | BIGINT UK | | 关联的 Agent |
| enabled | TINYINT | 0 | 是否启用心跳 |
| timezone | VARCHAR(50) | Asia/Shanghai | Agent 时区 |
| wake_time | VARCHAR(5) | 06:00 | 起床时间 |
| sleep_time | VARCHAR(5) | 23:00 | 睡觉时间 |
| heartbeat_interval_minutes | INT | 60 | ACTIVE 状态下检查间隔 |
| personality_prompt | TEXT | | 心跳时的性格 prompt |
| created_at | DATETIME | NOW() | |
| updated_at | DATETIME | NOW() | |

### 4.2 agent_task

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| id | BIGINT PK | auto | |
| agent_id | BIGINT | | 所属 Agent |
| user_id | VARCHAR(100) | | 可选的用户标识 |
| title | VARCHAR(500) | | 任务标题 |
| description | TEXT | | 详细描述 |
| due_at | DATETIME | | 截止时间 |
| remind_at | DATETIME | | 提醒时间（可早于 due_at） |
| status | VARCHAR(20) | pending | pending/done/cancelled |
| priority | INT | 0 | 优先级（0=普通，1=重要，2=紧急） |
| created_at | DATETIME | NOW() | |
| updated_at | DATETIME | NOW() | |

### 4.3 heartbeat_log

记录每次心跳触发的历史，用于：
- 避免重复触发（同状态只触发一次）
- 调试和分析
- 展示给用户查看 Agent 的"生活日志"

### 4.4 offline_message

用户离线时 Agent 产生的消息暂存于此，用户重连 WebSocket 后立即投递。

---

## 5. API 设计

### 5.1 心跳配置

```
GET    /api/heartbeat/{agentId}
Response: { id, agentId, enabled, timezone, wakeTime, sleepTime, heartbeatIntervalMinutes, personalityPrompt }

PUT    /api/heartbeat/{agentId}
Body:   { enabled, timezone, wakeTime, sleepTime, heartbeatIntervalMinutes, personalityPrompt }

GET    /api/heartbeat/{agentId}/logs?limit=20
Response: [{ id, state, triggeredAt, response, delivered }]

GET    /api/heartbeat/{agentId}/state
Response: { currentState, nextTransitionAt, lastHeartbeatAt }
```

### 5.2 任务管理

```
GET    /api/tasks/{agentId}?status=pending
Response: [{ id, title, description, dueAt, remindAt, status, priority }]

POST   /api/tasks/{agentId}
Body:   { title, description, dueAt, remindAt, priority }

PUT    /api/tasks/{agentId}/{taskId}
Body:   { title?, description?, dueAt?, remindAt?, status?, priority? }

DELETE /api/tasks/{agentId}/{taskId}
```

---

## 6. 前端 UI 设计

### 6.1 心跳配置面板

在 Agent 配置弹窗中添加「心跳」Tab：
- 总开关（大按钮）
- 作息时间选择器（两个时间输入）
- 心跳间隔滑块（30-180 分钟）
- 时区选择下拉
- 性格 prompt 文本域
- 当前状态指示器（实时显示 SLEEPING/WAKING/ACTIVE/WINDING_DOWN）

### 6.2 任务管理面板

独立的任务管理页面或侧边栏：
- 任务列表（按状态/时间排序）
- 快速添加任务输入框
- 每个任务卡片显示：标题、截止时间、优先级标签
- 滑动完成/长按删除

---

## 7. 安全与限制

- 心跳频率限制：最小间隔 15 分钟，防止滥用
- Token 预算：每次心跳 LLM 调用限制 max_tokens=500
- 离线消息上限：每个 Agent 最多缓存 50 条离线消息
- 只有 Agent 所有者能配置心跳（复用现有认证）

## 8. 向后兼容

- 心跳功能默认关闭（enabled=0）
- 不影响任何现有功能
- 所有新增组件独立于现有代码
