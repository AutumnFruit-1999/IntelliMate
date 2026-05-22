# 心跳触发机制详解

## 架构总览

心跳系统由四层组成：调度引擎 → HeartbeatJob → HeartbeatEngine → 前端接收。

```mermaid
flowchart TB
    subgraph scheduler [第一层：调度引擎]
        RSE[ReactiveScheduleEngine<br/>每10秒轮询]
        DB[(scheduled_job_config)]
        RSE --> DB
    end

    subgraph job [第二层：HeartbeatJob]
        HBJ[HeartbeatJob<br/>每60秒触发]
        HBC[(heartbeat_config)]
        HBJ --> HBC
    end

    subgraph engine [第三层：HeartbeatEngine]
        LS[LifecycleState<br/>状态机]
        ST[shouldTrigger<br/>触发判断]
        EB[executeBeat<br/>LLM调用+注入]
    end

    subgraph delivery [第四层：前端接收]
        CIS[ChatInjectionService]
        WS[WebSocket<br/>agent.proactive]
        FE[前端 chatStore<br/>消息气泡]
    end

    DB -->|heartbeat-tick 到期| HBJ
    HBC -->|每个启用的 agent| LS
    LS --> ST
    ST -->|允许| EB
    EB --> CIS
    CIS --> WS
    WS --> FE
```

## 第一层：调度引擎（时钟）

`ReactiveScheduleEngine` 是 Spring `SmartLifecycle` 服务，启动后运行一个 `Flux.interval` 定时循环。

| 参数 | 值 | 配置位置 |
|------|-----|---------|
| 轮询周期 | 10 秒 | `application.yml` → `intellimate.scheduler.tick-period-seconds` |
| heartbeat-tick 间隔 | 60 秒 | `scheduled_job_config.trigger_value = 60000` |

每次 tick 检查数据库中 `scheduled_job_config` 表里所有已启用 job 的 `next_fire_time`。`heartbeat-tick` 配置为 `FIXED_RATE = 60000ms`，所以每 60 秒被调度一次。

```mermaid
sequenceDiagram
    participant RSE as ReactiveScheduleEngine<br/>每10秒tick
    participant DB as scheduled_job_config
    participant HBJ as HeartbeatJob

    loop 每10秒
        RSE->>DB: getDueJobs(now)
        Note over DB: 检查 next_fire_time <= now
        alt heartbeat-tick 到期
            DB-->>RSE: 返回 heartbeat-tick job
            RSE->>HBJ: execute(context)
        else 未到期
            DB-->>RSE: 跳过
        end
    end
```

## 第二层：HeartbeatJob（遍历 agent）

`HeartbeatJob` 从 `heartbeat_config` 表中查出所有 `enabled=1` 的 agent 配置，逐个调用 `HeartbeatEngine.processHeartbeat(config)`。

```mermaid
sequenceDiagram
    participant HBJ as HeartbeatJob
    participant DB as heartbeat_config
    participant HE as HeartbeatEngine

    HBJ->>DB: findAllByEnabled(1)
    DB-->>HBJ: 返回所有启用心跳的 agent 配置
    loop 每个 agent
        HBJ->>HE: processHeartbeat(config)
    end
```

## 第三层：HeartbeatEngine（决策 + 执行）

### 3.1 生命周期状态计算

根据当前时间和 agent 配置的 `wake_time`/`sleep_time`/`timezone`，计算出当前处于哪个生命周期状态。

```mermaid
flowchart LR
    subgraph timeline ["一天的时间线（默认 06:00-23:00）"]
        S1["23:00→06:00<br/>SLEEPING"]
        W["06:00→07:00<br/>WAKING"]
        A["07:00→21:00<br/>ACTIVE"]
        WD["21:00→23:00<br/>WINDING_DOWN"]
    end
    S1 --> W --> A --> WD --> S1
```

| 状态 | 默认时间窗 | 说明 |
|------|-----------|------|
| SLEEPING | 23:00 → 06:00 | 不触发任何心跳 |
| WAKING | 06:00 → 07:00 | 每天一次晨间问候 |
| ACTIVE | 07:00 → 21:00 | 任务提醒 + 间隔主动消息 |
| WINDING_DOWN | 21:00 → 23:00 | 每天一次晚间总结 |

### 3.2 触发判断（shouldTrigger）

```mermaid
flowchart TD
    Start["processHeartbeat(config)"]
    State{"当前状态?"}
    Start --> State

    State -->|SLEEPING| Skip["跳过，不触发"]
    State -->|WAKING| WCheck{"今天是否已发过<br/>WAKING 消息?"}
    State -->|WINDING_DOWN| WDCheck{"今天是否已发过<br/>WINDING_DOWN 消息?"}
    State -->|ACTIVE| DueCheck{"是否有到期<br/>提醒任务?<br/>remind_at <= now"}

    WCheck -->|是| Skip
    WCheck -->|否| Fire["触发 executeBeat()"]
    WDCheck -->|是| Skip
    WDCheck -->|否| Fire

    DueCheck -->|是| Fire
    DueCheck -->|否| IntervalCheck{"距上次心跳是否<br/>超过 interval?<br/>默认60分钟"}
    IntervalCheck -->|是| Fire
    IntervalCheck -->|否| Skip
```

核心规则：

- **SLEEPING**：永不触发
- **WAKING / WINDING_DOWN**：每天各触发一次
- **ACTIVE**：
  - 有到期提醒任务 → **立即触发**（绕过间隔限制）
  - 无到期任务 → 检查 `heartbeat_interval_minutes`（默认 60 分钟）间隔

### 3.3 执行流程（executeBeat）

```mermaid
sequenceDiagram
    participant HE as HeartbeatEngine
    participant TR as AgentTaskRepository
    participant AR as AgentRepository
    participant CB as HeartbeatContextBuilder
    participant LLM as AgentRuntime
    participant CIS as ChatInjectionService
    participant WS as WebSocket

    HE->>TR: findUpcomingTasks(agentId, now+2h)
    TR-->>HE: 任务列表
    HE->>AR: findById(agentId)
    AR-->>HE: agent 名称
    HE->>CB: buildPrompt(agentName, state, tasks, now)
    CB-->>HE: 中文 prompt
    HE->>LLM: dispatch(AgentRunRequest)
    LLM-->>HE: LLM 回复文本

    alt 回复 == "[SILENT]"
        HE->>HE: saveLog() 仅记录
    else 有内容
        HE->>HE: saveLog()
        HE->>CIS: injectAgentMessage(agentName, response, HEARTBEAT)
        CIS->>CIS: 持久化到 transcript_message
        CIS->>WS: pushToAllAgentSessions("agent.proactive")
        WS-->>CIS: 推送到前端
    end
```

| 步骤 | 说明 |
|------|------|
| findUpcomingTasks | 查找未来 2 小时内到期的任务 |
| buildPrompt | 将 agent 名、状态、任务列表组装成中文 prompt |
| dispatch | 调用 LLM 生成自然语言回复，超时 30 秒 |
| [SILENT] | LLM 判断不需要说话时返回此标记 |
| injectAgentMessage | 持久化 + WebSocket 推送 |

LLM 调用失败时，自动降级为硬编码的占位文本（如 "早上好！新的一天开始了"）。

## 第四层：前端接收

```mermaid
sequenceDiagram
    participant Server as 后端 WebSocket
    participant Hook as useWebSocket
    participant Store as chatStore
    participant UI as MessageBubble

    Note over Hook: 连接建立时发送 agent.bind
    Server->>Hook: agent.proactive 事件
    alt 用户正在等待回复(isWaiting)
        Hook->>Store: bufferProactiveMessage()
        Note over Store: 缓冲，等当前回复完成后显示
    else 空闲
        Hook->>Store: addProactiveMessage()
        Store->>UI: 渲染为 assistant 消息气泡
    end
```

### 离线恢复

用户断线重连时：

1. 前端发送 `agent.bind` 事件
2. 后端调用 `ChatInjectionService.deliverPendingMessages()`
3. 从 `transcript_message` 中查找 TTL 窗口内（默认 24 小时）的未送达消息
4. 逐条以 `agent.proactive` 事件推送到前端
5. 超过 TTL 的过期消息自动丢弃

## 配置参数汇总

| 参数 | 默认值 | 配置位置 | 说明 |
|------|--------|---------|------|
| tick-period-seconds | 10 | `application.yml` | 调度引擎轮询周期 |
| heartbeat-tick trigger_value | 60000 (60s) | `scheduled_job_config` DB | 心跳检查频率 |
| heartbeat_interval_minutes | 60 | `heartbeat_config` DB（每 agent） | 无任务时的主动消息间隔 |
| wake_time / sleep_time | 06:00 / 23:00 | `heartbeat_config` DB（每 agent） | 活跃窗口 |
| timezone | Asia/Shanghai | `heartbeat_config` DB（每 agent） | 时区 |
| enabled | 0（默认关闭） | `heartbeat_config` DB（每 agent） | 需手动开启 |
| LLM 超时 | 30s | `HeartbeatEngine.LLM_TIMEOUT` | LLM 调用超时 |
| message-ttl-hours | 24 | `application.yml` → `intellimate.proactive` | 主动消息有效期 |
| replay-limit | 20 | `application.yml` → `intellimate.proactive` | 重连时最大回放数 |
| log-retention-days | 30 | `application.yml` → `intellimate.scheduler` | 日志保留天数 |

## 完整示例：一个提醒任务的生命周期

用户说："1 分钟后提醒我喝水"

```mermaid
sequenceDiagram
    participant User as 用户
    participant Agent as Agent (LLM)
    participant TaskDB as agent_task 表
    participant Scheduler as 调度引擎
    participant HBJ as HeartbeatJob
    participant HE as HeartbeatEngine
    participant LLM as LLM
    participant CIS as ChatInjectionService
    participant FE as 前端

    User->>Agent: "1分钟后提醒我喝水"
    Agent->>TaskDB: 创建任务 remind_at = now+1min
    Agent->>User: "已设好提醒"

    Note over Scheduler: 最多等待60秒...
    Scheduler->>HBJ: heartbeat-tick 触发
    HBJ->>HE: processHeartbeat(config)
    HE->>HE: 计算状态 = ACTIVE
    HE->>TaskDB: findDueReminders(agentId, now)
    TaskDB-->>HE: 找到"喝水提醒"（remind_at 已过期）
    Note over HE: 有到期任务 → 立即触发
    HE->>LLM: 生成提醒文本
    LLM-->>HE: "该喝水了！记得补充水分哦～"
    HE->>CIS: injectAgentMessage()
    CIS->>FE: agent.proactive WebSocket 推送
    FE->>User: 显示消息气泡
```
