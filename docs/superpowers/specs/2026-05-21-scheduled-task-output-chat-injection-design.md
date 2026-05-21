# 定时任务产出聊天注入

将 AgentPromptJob 和 HeartbeatEngine 的 LLM 产出实时推送到前端聊天界面，作为 Agent 的主动发言自然融入对话流，并持久化以保证上下文连续性。

## 背景

当前系统中，`AgentPromptJob` 执行后仅通过 `scheduler.agent.response` 广播 200 字摘要到所有连接，`HeartbeatEngine` 通过 `heartbeat.message` 推送到单个绑定会话。两者的完整产出无法在聊天界面中查看，且 Agent 无法在后续对话中"记得"自己说过的 proactive 内容。

用户期望：定时任务（Agent 定时提示词任务、心跳消息）的 LLM 回复内容，以 assistant 消息的形式出现在聊天对话中，刷新后仍可见，Agent 回复时有上下文。

## 设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 产出类型 | AgentPromptJob LLM 回复 + Heartbeat 消息 | 用户明确需求 |
| 展示位置 | 聊天界面，融入对话流 | 自然、无额外学习成本 |
| 持久化 | 写入 `transcript_message` | 保证 LLM 上下文连续性 + 刷新可恢复 |
| 离线处理 | 在线实时推送 + 离线保留 offline_message | 兼顾实时性和不丢消息 |
| 视觉样式 | 与普通 assistant 消息基本一致，payload 携带 provenance 供未来区分 | 体验自然、可扩展 |
| 多会话路由 | 推送到该 Agent 所有活跃 WS 会话 | 多标签页一致性 |
| Agent 绑定 | 选中 Agent 即绑定（不依赖首条消息） | 确保 proactive 消息不丢 |

## 架构

```
┌─────────────────────────────────────────────────────────┐
│ 定时任务执行层                                            │
│                                                          │
│  AgentPromptJob ──┐                                      │
│                   ├──→ ChatInjectionService               │
│  HeartbeatEngine ─┘         │                            │
│                          ┌──┴──┐                         │
│                          │     │                         │
│                    持久化  │     │ 实时推送                │
│                 transcript│     │ SessionRegistry         │
│                 _message  │     │ .pushToAllAgentSessions │
│                          ▼     │                         │
│                         DB     ▼                         │
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
│        → 渲染为 assistant 消息                            │
│                                                          │
│  连接时 → 发送 agent.bind 事件绑定当前 Agent               │
│  切换 Agent → 重新发送 agent.bind                         │
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

**完整实现：**

```java
public void bindAgent(String wsSessionId, String agentName) {
    agentSessions
        .computeIfAbsent(agentName, k -> ConcurrentHashMap.newKeySet())
        .add(wsSessionId);
    log.debug("Agent '{}' bound to session {}", agentName, wsSessionId);
}

public void unregister(String wsSessionId) {
    sessionSinks.remove(wsSessionId);
    agentSessions.values().forEach(set -> set.remove(wsSessionId));
    agentSessions.entrySet().removeIf(e -> e.getValue().isEmpty());
    log.debug("Session unregistered: {}", wsSessionId);
}

public boolean isAgentOnline(String agentName) {
    Set<String> sids = agentSessions.get(agentName);
    if (sids == null || sids.isEmpty()) return false;
    return sids.stream().anyMatch(sessionSinks::containsKey);
}

public boolean pushToAgent(String agentName, String eventType, Map<String, Object> payload) {
    return pushToAllAgentSessions(agentName, eventType, payload) > 0;
}

public int pushToAllAgentSessions(String agentName, String eventType, Map<String, Object> payload) {
    Set<String> sids = agentSessions.get(agentName);
    if (sids == null || sids.isEmpty()) return 0;

    EventFrame frame = new EventFrame(eventType, payload, seqGenerator.incrementAndGet());
    int delivered = 0;
    Iterator<String> iter = sids.iterator();
    while (iter.hasNext()) {
        String sid = iter.next();
        Sinks.Many<GatewayFrame> sink = sessionSinks.get(sid);
        if (sink == null) {
            iter.remove(); // lazy prune stale session IDs
            continue;
        }
        if (sink.tryEmitNext(frame).isSuccess()) {
            delivered++;
        }
    }
    return delivered;
}
```

**线程安全说明**：
- Set 使用 `ConcurrentHashMap.newKeySet()` 实现，`add`/`remove`/`contains` 线程安全
- 迭代采用弱一致性（可能跳过新加的 session 或包含刚移除的 session，两种情况都可接受）
- `pushToAllAgentSessions` 中做 lazy prune：发现 sink 为 null 的 sid 直接移除，防止长时间运行导致内存泄漏

### 2. Agent 绑定机制改进

**问题**：当前 `bindAgent` 仅在 `MessagePipeline.processRequest` 的 `conversation.message` 中调用。用户选中 Agent 但未发消息时，不在任何 agent 的绑定集中，proactive 消息投递失败。

**解决方案**：前端在连接建立和切换 Agent 时主动发送绑定事件。

**前端发送 `agent.bind` 事件：**

```typescript
// useWebSocket.ts - 在 session.welcome 后
case "session.welcome": {
    store.setWsSessionId(event.payload.wsSessionId as string);
    // 绑定当前选中的 agent
    const currentAgent = useAgentStore.getState().currentAgent;
    if (currentAgent?.name && clientRef.current) {
        clientRef.current.sendEvent("agent.bind", { agentName: currentAgent.name });
    }
    break;
}
```

```typescript
// 切换 agent 时也发送绑定
// 在 sendMessage 或 agent 切换逻辑中：
if (clientRef.current) {
    clientRef.current.sendEvent("agent.bind", { agentName: newAgentName });
}
```

**后端处理 `agent.bind` 事件：**

在 `GatewayWebSocketHandler` 的 `routeFrame` 中增加对 `agent.bind` EventFrame 的处理：

```java
if (frame instanceof EventFrame ef && "agent.bind".equals(ef.event())) {
    String agentName = (String) ef.payload().get("agentName");
    if (agentName != null && !agentName.isBlank()) {
        sessionRegistry.bindAgent(session.getId(), agentName);
    }
    return Mono.empty(); // 不产生回复帧
}
```

**重连自动 re-bind**：前端 WsClient 在 reconnect 成功后收到 `session.welcome`，触发上述绑定逻辑，无需额外处理。

### 3. ChatInjectionService

新建服务，职责：持久化 + 实时推送。

```java
@Service
public class ChatInjectionService {

    private final SessionRegistry sessionRegistry;
    private final SessionManager sessionManager;

    public ChatInjectionService(SessionRegistry sessionRegistry, SessionManager sessionManager) {
        this.sessionRegistry = sessionRegistry;
        this.sessionManager = sessionManager;
    }

    public Mono<Integer> injectAgentMessage(String agentName, String content, ProactiveSource source) {
        String syntheticRequestId = "bg-" + UUID.randomUUID().toString().substring(0, 8);

        // 1. 持久化到 transcript_message
        Mono<Void> persistMono = sessionManager.findOrCreateSessionForAgent(agentName)
            .flatMap(sessionId -> {
                TranscriptMessageEntity msg = new TranscriptMessageEntity();
                msg.setSessionId(sessionId);
                msg.setRole("assistant");
                msg.setContent(content);
                msg.setMetadataJson("{\"source\":\"" + source.name().toLowerCase() + "\"}");
                msg.setCreatedAt(LocalDateTime.now());
                return sessionManager.appendMessage(sessionId, msg);
            })
            .onErrorResume(e -> {
                log.warn("Failed to persist proactive message for agent {}: {}", agentName, e.getMessage());
                return Mono.empty();
            });

        // 2. 实时推送到所有绑定会话
        return persistMono.then(Mono.fromSupplier(() ->
            sessionRegistry.pushToAllAgentSessions(agentName, "agent.proactive", Map.of(
                "agentName", agentName,
                "requestId", syntheticRequestId,
                "text", content,
                "source", source.name().toLowerCase(),
                "timestamp", System.currentTimeMillis()
            ))
        ));
    }

    public enum ProactiveSource {
        HEARTBEAT,
        SCHEDULED_JOB
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
    "text": "完整的 LLM 回复文本...",
    "source": "heartbeat",
    "timestamp": 1716264000000
  },
  "seq": 42
}
```

**`findOrCreateSessionForAgent` 逻辑**：在 `SessionManager` 中新增方法，查找该 agent 最近的 webchat session（channelId=webchat, contextType=dm），若无则创建。Session 复用策略：优先使用最近活跃的 session，避免为每次 proactive 创建新 session。

### 4. AgentPromptJob 集成

在 `execute()` 方法中，收集完整回复后调用 `ChatInjectionService`：

```java
private final ChatInjectionService chatInjectionService;

// 在 .then(Mono.fromSupplier(...)) 块内，替换为：
return chatInjectionService.injectAgentMessage(
    agentName, response, ChatInjectionService.ProactiveSource.SCHEDULED_JOB
).map(deliveredCount -> {
    String summary = response.length() > 200
        ? response.substring(0, 200) + "..." : response;

    return JobResult.ok("Agent responded (" + response.length() + " chars, delivered to "
        + deliveredCount + " sessions)", Map.of(
        "agentName", agentName,
        "responseLength", response.length(),
        "responseSummary", summary,
        "templateMode", isTemplate,
        "memoriesInjected", history.size(),
        "deliveredSessions", deliveredCount
    ));
});
```

**移除**：原有的 `sessionRegistry.broadcast("scheduler.agent.response", ...)` 调用。`agent.proactive` 事件替代其功能且携带完整内容。SchedulerDashboard 的执行历史通过 REST API (`/api/scheduled-jobs/{jobName}/logs`) 获取，不依赖此 broadcast。

### 5. HeartbeatEngine 集成

修改 `deliver()` 方法：

```java
private final ChatInjectionService chatInjectionService;

private Mono<Void> deliver(HeartbeatConfigEntity config, String agentName, String response) {
    return chatInjectionService.injectAgentMessage(
        agentName, response, ChatInjectionService.ProactiveSource.HEARTBEAT
    ).flatMap(deliveredCount -> {
        if (deliveredCount > 0) {
            log.info("Heartbeat delivered to {} sessions for agent {}", deliveredCount, agentName);
        } else {
            log.info("Agent {} has no active sessions, heartbeat persisted for later", agentName);
        }
        return Mono.empty();
    });
}
```

**保留的逻辑：**
- `heartbeat_log` 写入保持不变（调试和审计用途）
- `[SILENT]` 拦截在调用 `deliver()` 之前，不会进入此方法

**变更的逻辑：**
- 不再使用 `pushToAgent`（改为通过 `ChatInjectionService`）
- 不再写入 `offline_message`——因为已经写入 `transcript_message`，用户重新连接后从 DB 加载历史即可看到。`offline_message` 原来的作用（用户上线时主动推一次缓存消息）通过前端加载历史消息来替代。

### 6. 前端事件处理

**useWebSocket.ts 新增 case：**

```typescript
case "agent.proactive": {
    const agentName = event.payload.agentName as string;
    const text = event.payload.text as string;
    const requestId = event.payload.requestId as string;
    const source = event.payload.source as string;
    if (!text?.trim() || !agentName?.trim()) break;

    // 如果当前正在 streaming，缓冲 proactive 消息
    const chatState = useChatStore.getState();
    if (chatState.isWaiting) {
        chatState.bufferProactiveMessage(agentName, text, requestId, source);
    } else {
        chatState.addProactiveMessage(agentName, text, requestId, source);
    }
    break;
}
```

**chatStore.ts 新增方法：**

```typescript
interface ChatState {
    // ... existing fields ...
    proactiveBuffer: Array<{ agentName: string; text: string; requestId: string; source: string }>;

    addProactiveMessage: (agentName: string, text: string, requestId: string, source: string) => void;
    bufferProactiveMessage: (agentName: string, text: string, requestId: string, source: string) => void;
    flushProactiveBuffer: () => void;
}
```

```typescript
addProactiveMessage: (agentName, text, requestId, source) => {
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
},

bufferProactiveMessage: (agentName, text, requestId, source) => {
    set((state) => ({
        proactiveBuffer: [...state.proactiveBuffer, { agentName, text, requestId, source }],
    }));
},

flushProactiveBuffer: () => {
    const buffer = get().proactiveBuffer;
    if (buffer.length === 0) return;
    for (const msg of buffer) {
        get().addProactiveMessage(msg.agentName, msg.text, msg.requestId, msg.source);
    }
    set({ proactiveBuffer: [] });
},
```

**在 `finishStreaming` 末尾调用 `flushProactiveBuffer()`**——确保 streaming 完成后再展示缓冲的 proactive 消息。

### 7. SessionManager 新增方法

```java
public Mono<Long> findOrCreateSessionForAgent(String agentName) {
    SessionKey key = new SessionKey("webchat", "dm", "proactive::" + agentName);
    return sessionRepository.findByChannelIdAndContextTypeAndContextId(
        key.channelId(), key.contextType(), key.contextId()
    ).map(SessionEntity::getId)
    .switchIfEmpty(Mono.defer(() -> {
        SessionEntity newSession = new SessionEntity();
        newSession.setChannelId(key.channelId());
        newSession.setContextType(key.contextType());
        newSession.setContextId(key.contextId());
        newSession.setAgentName(agentName);
        newSession.setCreatedAt(LocalDateTime.now());
        newSession.setLastActivityAt(LocalDateTime.now());
        return sessionRepository.save(newSession).map(SessionEntity::getId);
    }));
}
```

**Session 策略**：每个 agent 有一个专属的 proactive session（contextId = `proactive::{agentName}`），所有 proactive 消息写入同一个 session。这样：
- LLM 加载历史时可以选择是否包含 proactive context
- 不污染用户主动发起的对话 session
- 前端可以在展示时合并两个 source 的消息

## 边界情况

| 场景 | 处理方式 |
|------|----------|
| Agent 没有任何活跃 WS 会话 | 消息仍持久化到 DB；`pushToAllAgentSessions` 返回 0，仅日志记录 |
| 用户刷新页面 | 重连后前端从 DB 加载历史（含 proactive 消息） |
| 多个浏览器标签页打开同一 Agent | 每个标签页在 welcome 后 bind，全部收到推送 |
| Heartbeat 内容为 `[SILENT]` | HeartbeatEngine 在调用 deliver 之前拦截，不推送不持久化 |
| AgentPromptJob 执行失败 | execute() 返回 error JobResult，不走成功分支，不推送不持久化 |
| Proactive 到达时用户正在等待 streaming 回复 | 缓冲 proactive 消息，streaming 完成后 flush |
| 前端收到未知/空 agentName 或空 text | 忽略（break），不追加消息 |
| WS 连接不稳定频繁断连 | 每次重连自动 re-bind（welcome 触发）；断线期间消息已持久化 |
| Agent 改名 | 绑定基于 agentName 字符串；改名后需用户重新选中新名称触发 re-bind |

## 不在范围内

- 消息已读/未读状态
- 浏览器 Notification 推送通知
- AgentPromptJob 产出的 streaming 式推送（当前为收集完整回复后一次性推送）
- Proactive 消息的 mute/snooze 功能
- 根据 messageKind 区分不同类型 proactive 消息的 UI（为 payload 中的 `source` 字段预留了扩展点）

## 影响分析

| 组件 | 变更类型 |
|------|----------|
| `SessionRegistry` | 修改（一对多映射 + lazy prune） |
| `GatewayWebSocketHandler` | 修改（处理 `agent.bind` 事件） |
| `ChatInjectionService` | 新建 |
| `SessionManager` | 修改（新增 `findOrCreateSessionForAgent`） |
| `AgentPromptJob` | 修改（调用 ChatInjectionService，移除旧 broadcast） |
| `HeartbeatEngine` | 修改（替换 deliver 逻辑，移除 offline_message 写入） |
| `useWebSocket.ts` | 修改（新增 `agent.proactive` case + `agent.bind` 发送） |
| `chatStore.ts` | 修改（新增 `addProactiveMessage` + buffer 机制） |
| 数据库 | 无 schema 变更（复用 `transcript_message` + `session`） |
| 配置文件 | 无变更 |

## 测试要点

| 测试项 | 验证内容 |
|--------|----------|
| SessionRegistry 多会话绑定 | bind 多个 WS session → push 全部收到 |
| SessionRegistry 并发安全 | 并发 bind/unregister/push 无异常 |
| SessionRegistry stale 清理 | disconnect 后 set 自动清理 + lazy prune |
| agent.bind 事件处理 | 前端发送 bind → 后端正确绑定 |
| 重连 re-bind | 断连重连后自动 re-bind，proactive 可投递 |
| ChatInjectionService 持久化 | proactive 消息写入 transcript_message |
| HeartbeatEngine 集成 | heartbeat 产出 → DB + WS 推送 |
| AgentPromptJob 集成 | job 成功 → DB + WS；job 失败 → 不推送 |
| 前端 streaming 缓冲 | streaming 中收到 proactive → 缓冲 → flush |
| 前端 payload 校验 | 空 text / 空 agentName → 不渲染 |
