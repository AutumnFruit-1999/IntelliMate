# 定时任务产出聊天注入

将 AgentPromptJob 和 HeartbeatEngine 的 LLM 产出实时推送到前端聊天界面，作为 Agent 的主动发言自然融入对话流。

## 背景

当前系统中，`AgentPromptJob` 执行后仅通过 `scheduler.agent.response` 广播 200 字摘要到所有连接，`HeartbeatEngine` 通过 `heartbeat.message` 推送到单个绑定会话。两者的完整产出无法在聊天界面中查看。

用户期望：定时任务（Agent 定时提示词任务、心跳消息）的 LLM 回复内容，以普通 assistant 消息的形式实时出现在聊天对话中，不做视觉区分，不做离线累积。

## 设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 产出类型 | AgentPromptJob LLM 回复 + Heartbeat 消息 | 用户明确需求 |
| 展示位置 | 聊天界面，融入对话流 | 自然、无额外学习成本 |
| 离线处理 | 实时推送，错过就错过 | 简化设计，不引入离线队列 |
| 视觉样式 | 与普通 assistant 消息完全一致 | 体验自然、无干扰 |
| 多会话路由 | 广播到该 Agent 所有活跃 WS 会话 | 多标签页/多设备一致性 |
| 持久化 | 本期不做 | 需求仅为实时查看；未来可扩展 |

## 架构

```
┌─────────────────────────────────────────────────────────┐
│ 定时任务执行层                                            │
│                                                          │
│  AgentPromptJob ──┐                                      │
│                   ├──→ ChatInjectionService               │
│  HeartbeatEngine ─┘         │                            │
│                             ▼                            │
│                    SessionRegistry                        │
│                    .pushToAllAgentSessions()              │
│                             │                            │
│              ┌──────────────┼──────────────┐             │
│              ▼              ▼              ▼             │
│         WS Session 1  WS Session 2  WS Session N        │
│              │              │              │             │
└──────────────┼──────────────┼──────────────┼─────────────┘
               ▼              ▼              ▼
┌─────────────────────────────────────────────────────────┐
│ 前端                                                     │
│                                                          │
│  useWebSocket                                            │
│    case "agent.proactive"                                │
│      → chatStore.addProactiveMessage(agentName, text)    │
│        → 渲染为普通 assistant 消息                         │
└─────────────────────────────────────────────────────────┘
```

## 详细设计

### 1. SessionRegistry 多会话支持

当前 `agentSessions` 为 `ConcurrentHashMap<String, String>`（agentName → 单个 wsSessionId），改为一对多映射。

**数据结构变更：**

```java
// 改前
private final ConcurrentHashMap<String, String> agentSessions = new ConcurrentHashMap<>();

// 改后
private final ConcurrentHashMap<String, Set<String>> agentSessions = new ConcurrentHashMap<>();
```

**方法变更：**

- `bindAgent(wsSessionId, agentName)`：向 Set 中添加 wsSessionId
- `unregister(wsSessionId)`：从所有 agent 的 Set 中移除该 wsSessionId
- `isAgentOnline(agentName)`：检查 Set 非空且其中至少有一个 wsSessionId 在 sessionSinks 中存在
- `pushToAgent(agentName, eventType, payload)`：语义不变，推送到该 agent 的所有绑定会话，返回 `boolean`（任意一个投递成功即为 true）。这是对原有单会话行为的自然扩展，因为现在绑定是多对多。

**新增方法：**

`pushToAllAgentSessions` 与 `pushToAgent` 的区别：前者返回 `int`（投递成功的会话数），用于调用方需要知道投递规模的场景（如日志、监控）。`pushToAgent` 保持返回 `boolean` 的签名兼容。

```java
public int pushToAllAgentSessions(String agentName, String eventType, Map<String, Object> payload) {
    Set<String> sids = agentSessions.get(agentName);
    if (sids == null || sids.isEmpty()) return 0;

    EventFrame frame = new EventFrame(eventType, payload, seqGenerator.incrementAndGet());
    int delivered = 0;
    for (String sid : sids) {
        Sinks.Many<GatewayFrame> sink = sessionSinks.get(sid);
        if (sink != null && sink.tryEmitNext(frame).isSuccess()) {
            delivered++;
        }
    }
    return delivered;
}
```

**线程安全**：Set 使用 `ConcurrentHashMap.newKeySet()` 实现。

### 2. ChatInjectionService

新建服务，职责单一：将文本内容包装为 `agent.proactive` 事件并推送。

```java
@Service
public class ChatInjectionService {

    private final SessionRegistry sessionRegistry;

    public ChatInjectionService(SessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    public int injectAgentMessage(String agentName, String content) {
        String syntheticRequestId = "bg-" + UUID.randomUUID().toString().substring(0, 8);

        return sessionRegistry.pushToAllAgentSessions(agentName, "agent.proactive", Map.of(
            "agentName", agentName,
            "requestId", syntheticRequestId,
            "text", content
        ));
    }
}
```

**事件格式（Wire format）：**

```json
{
  "type": "event",
  "event": "agent.proactive",
  "payload": {
    "agentName": "my-agent",
    "requestId": "bg-a1b2c3d4",
    "text": "完整的 LLM 回复文本..."
  },
  "seq": 42
}
```

### 3. AgentPromptJob 集成

在 `execute()` 方法中，收集完整回复后调用 `ChatInjectionService`：

```java
// 注入 ChatInjectionService 依赖
private final ChatInjectionService chatInjectionService;

// 在 .then(Mono.fromSupplier(...)) 块内，broadcast 之前添加：
chatInjectionService.injectAgentMessage(agentName, response);
```

保留原有的 `sessionRegistry.broadcast("scheduler.agent.response", ...)` 调用，SchedulerDashboard 仍能正常工作。

### 4. HeartbeatEngine 集成

修改 `deliver()` 方法：

```java
private final ChatInjectionService chatInjectionService;

private Mono<Void> deliver(HeartbeatConfigEntity config, String agentName, String response) {
    int count = chatInjectionService.injectAgentMessage(agentName, response);

    if (count > 0) {
        log.info("Heartbeat message delivered to {} sessions for agent {}", count, agentName);
    } else {
        log.info("Agent {} has no active sessions, heartbeat message dropped", agentName);
    }

    return Mono.empty();
}
```

**移除的逻辑：**
- 不再写入 `offline_message` 表（符合"错过就错过"需求）
- 不再使用 `pushToAgent`（改为通过 ChatInjectionService 走 `pushToAllAgentSessions`）

**保留的逻辑：**
- `heartbeat_log` 写入保持不变（调试和审计用途）
- `[SILENT]` 拦截在调用 `deliver()` 之前，不会进入此方法

### 5. 前端事件处理

**useWebSocket.ts 新增 case：**

```typescript
case "agent.proactive": {
    const agentName = event.payload.agentName as string;
    const text = event.payload.text as string;
    const requestId = event.payload.requestId as string;
    store.addProactiveMessage(agentName, text, requestId);
    break;
}
```

**chatStore.ts 新增方法：**

```typescript
addProactiveMessage: (agentName: string, text: string, requestId: string) => {
    const agent = agentName || get().currentAgent;
    const current = get().messagesByAgent[agent] ?? [];
    const newMsg: ChatMessage = {
        id: `assistant-${requestId}`,
        role: "assistant",
        content: text,
        streaming: false,
        timestamp: Date.now(),
        requestId,
    };
    const newByAgent = { ...get().messagesByAgent, [agent]: [...current, newMsg] };

    if (agent === get().currentAgent) {
        set({ messagesByAgent: newByAgent, messages: [...current, newMsg] });
    } else {
        set({ messagesByAgent: newByAgent });
    }
}
```

**ChatState interface 扩展：**

```typescript
addProactiveMessage: (agentName: string, text: string, requestId: string) => void;
```

## 边界情况

| 场景 | 处理方式 |
|------|----------|
| Agent 没有任何活跃 WS 会话 | `pushToAllAgentSessions` 返回 0，消息丢弃，仅日志记录 |
| 用户刷新页面 | Zustand 内存状态丢失，消息不可恢复（设计如此） |
| 多个浏览器标签页打开同一 Agent | 每个标签页的 WS 连接都绑定了该 agent，全部收到消息 |
| Heartbeat 内容为 `[SILENT]` | HeartbeatEngine 在调用 deliver 之前拦截，不推送 |
| AgentPromptJob 执行失败 | execute() 返回 error JobResult，不走成功分支，不推送 |
| 前端收到未知 agentName | 消息存入 `messagesByAgent[agentName]`，用户切换到该 agent 时可见 |

## 不在范围内

- 消息持久化到 `transcript_message`（未来扩展点：在 `ChatInjectionService.injectAgentMessage` 中加持久化调用）
- 历史消息回看/分页加载
- 消息已读/未读状态
- 浏览器 Notification 推送通知
- 离线消息累积和重放

## 影响分析

| 组件 | 变更类型 |
|------|----------|
| `SessionRegistry` | 修改（一对多映射） |
| `ChatInjectionService` | 新建 |
| `AgentPromptJob` | 修改（新增一行调用） |
| `HeartbeatEngine` | 修改（替换 deliver 逻辑） |
| `useWebSocket.ts` | 修改（新增 event case） |
| `chatStore.ts` | 修改（新增方法 + interface 扩展） |
| 数据库 | 无变更 |
| 配置文件 | 无变更 |
