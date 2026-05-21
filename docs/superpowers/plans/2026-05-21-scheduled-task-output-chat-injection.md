# 定时任务产出聊天注入 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 AgentPromptJob 和 HeartbeatEngine 的 LLM 产出实时推送到聊天界面并持久化，支持 Agent 绑定优化和 streaming 缓冲。

**架构：** 后端新建 `ChatInjectionService` 统一处理持久化（写 `transcript_message`）+ 实时推送（通过改造后的 `SessionRegistry` 多会话广播）。前端新增 `agent.proactive` 事件处理和 `agent.bind` 绑定机制。

**技术栈：** Java 21 / Spring WebFlux / R2DBC / React 19 / TypeScript / Zustand

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `intellimate-gateway/.../websocket/SessionRegistry.java` | 修改 | 从单会话绑定改为多会话绑定，新增 `pushToAllAgentSessions` |
| `intellimate-gateway/.../websocket/GatewayWebSocketHandler.java` | 修改 | 处理 `agent.bind` 客户端事件 |
| `intellimate-gateway/.../session/SessionManager.java` | 修改 | 新增 `findOrCreateProactiveSession` 方法签名 |
| `intellimate-gateway/.../session/SessionManagerImpl.java` | 修改 | 实现 `findOrCreateProactiveSession` |
| `intellimate-gateway/.../service/ChatInjectionService.java` | 新建 | 持久化 + 实时推送的统一入口 |
| `intellimate-gateway/.../scheduler/jobs/AgentPromptJob.java` | 修改 | 调用 ChatInjectionService 替代旧 broadcast |
| `intellimate-gateway/.../heartbeat/HeartbeatEngine.java` | 修改 | 调用 ChatInjectionService 替代旧 deliver 逻辑 |
| `intellimate-web/src/hooks/useWebSocket.ts` | 修改 | 新增 `agent.proactive` case + `agent.bind` 发送 |
| `intellimate-web/src/stores/chatStore.ts` | 修改 | 新增 `addProactiveMessage` + buffer 机制 |
| `intellimate-gateway/.../websocket/SessionRegistryTest.java` | 新建 | SessionRegistry 单元测试 |
| `intellimate-gateway/.../service/ChatInjectionServiceTest.java` | 新建 | ChatInjectionService 单元测试 |

---

### 任务 1：SessionRegistry 多会话支持

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/websocket/SessionRegistry.java`
- 测试：`intellimate-gateway/src/test/java/com/atm/intellimate/gateway/websocket/SessionRegistryTest.java`

- [ ] **步骤 1：编写 SessionRegistry 测试**

```java
package com.atm.intellimate.gateway.websocket;

import com.atm.intellimate.core.protocol.EventFrame;
import com.atm.intellimate.core.protocol.GatewayFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRegistryTest {

    private SessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SessionRegistry();
    }

    @Test
    void bindAgent_multipleSessionsSameAgent_allReceivePush() {
        Sinks.Many<GatewayFrame> sink1 = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<GatewayFrame> sink2 = Sinks.many().unicast().onBackpressureBuffer();

        registry.register("ws-1", sink1);
        registry.register("ws-2", sink2);
        registry.bindAgent("ws-1", "agent-a");
        registry.bindAgent("ws-2", "agent-a");

        int delivered = registry.pushToAllAgentSessions("agent-a", "test.event", Map.of("key", "value"));

        assertThat(delivered).isEqualTo(2);
    }

    @Test
    void pushToAllAgentSessions_noBindings_returnsZero() {
        int delivered = registry.pushToAllAgentSessions("unknown-agent", "test.event", Map.of());
        assertThat(delivered).isEqualTo(0);
    }

    @Test
    void unregister_removesFromAllAgentSets() {
        Sinks.Many<GatewayFrame> sink1 = Sinks.many().unicast().onBackpressureBuffer();
        registry.register("ws-1", sink1);
        registry.bindAgent("ws-1", "agent-a");

        registry.unregister("ws-1");

        assertThat(registry.isAgentOnline("agent-a")).isFalse();
        assertThat(registry.pushToAllAgentSessions("agent-a", "test.event", Map.of())).isEqualTo(0);
    }

    @Test
    void pushToAllAgentSessions_staleSidPruned() {
        Sinks.Many<GatewayFrame> sink1 = Sinks.many().unicast().onBackpressureBuffer();
        registry.register("ws-1", sink1);
        registry.bindAgent("ws-1", "agent-a");
        registry.bindAgent("ws-stale", "agent-a"); // stale: not in sessionSinks

        int delivered = registry.pushToAllAgentSessions("agent-a", "test.event", Map.of());

        assertThat(delivered).isEqualTo(1); // only ws-1 delivered
    }

    @Test
    void pushToAgent_returnsTrue_whenAnyDelivered() {
        Sinks.Many<GatewayFrame> sink1 = Sinks.many().unicast().onBackpressureBuffer();
        registry.register("ws-1", sink1);
        registry.bindAgent("ws-1", "agent-a");

        boolean result = registry.pushToAgent("agent-a", "test.event", Map.of());
        assertThat(result).isTrue();
    }

    @Test
    void isAgentOnline_withValidSink_returnsTrue() {
        Sinks.Many<GatewayFrame> sink1 = Sinks.many().unicast().onBackpressureBuffer();
        registry.register("ws-1", sink1);
        registry.bindAgent("ws-1", "agent-a");

        assertThat(registry.isAgentOnline("agent-a")).isTrue();
    }

    @Test
    void bindAgent_sameSessionTwice_idempotent() {
        Sinks.Many<GatewayFrame> sink1 = Sinks.many().unicast().onBackpressureBuffer();
        registry.register("ws-1", sink1);
        registry.bindAgent("ws-1", "agent-a");
        registry.bindAgent("ws-1", "agent-a");

        int delivered = registry.pushToAllAgentSessions("agent-a", "test.event", Map.of());
        assertThat(delivered).isEqualTo(1);
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd intellimate-gateway && mvn test -pl . -Dtest=SessionRegistryTest -Dsurefire.useFile=false`
预期：编译失败（`pushToAllAgentSessions` 方法不存在）

- [ ] **步骤 3：重写 SessionRegistry 实现**

将 `SessionRegistry.java` 完整替换为：

```java
package com.atm.intellimate.gateway.websocket;

import com.atm.intellimate.core.protocol.EventFrame;
import com.atm.intellimate.core.protocol.GatewayFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final AtomicLong seqGenerator = new AtomicLong(0);
    private final ConcurrentHashMap<String, Sinks.Many<GatewayFrame>> sessionSinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> agentSessions = new ConcurrentHashMap<>();

    public void register(String wsSessionId, Sinks.Many<GatewayFrame> sink) {
        sessionSinks.put(wsSessionId, sink);
        log.debug("Session registered: {}", wsSessionId);
    }

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
                iter.remove();
                continue;
            }
            if (sink.tryEmitNext(frame).isSuccess()) {
                delivered++;
            }
        }
        return delivered;
    }

    public void broadcast(String eventType, Map<String, Object> payload) {
        if (sessionSinks.isEmpty()) return;
        EventFrame frame = new EventFrame(eventType, payload, seqGenerator.incrementAndGet());
        sessionSinks.values().forEach(sink -> sink.tryEmitNext(frame));
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd intellimate-gateway && mvn test -pl . -Dtest=SessionRegistryTest -Dsurefire.useFile=false`
预期：全部 PASS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/websocket/SessionRegistry.java
git add intellimate-gateway/src/test/java/com/atm/intellimate/gateway/websocket/SessionRegistryTest.java
git commit -m "feat(registry): multi-session agent binding with lazy prune"
```

---

### 任务 2：GatewayWebSocketHandler 处理 agent.bind 事件

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/websocket/GatewayWebSocketHandler.java:141-144`

- [ ] **步骤 1：修改 handleEvent 方法**

将 `handleEvent` 从：

```java
private Flux<GatewayFrame> handleEvent(EventFrame event) {
    log.debug("Unhandled client event: {}", event.event());
    return Flux.empty();
}
```

改为：

```java
private Flux<GatewayFrame> handleEvent(EventFrame event, WebSocketSession session) {
    if ("agent.bind".equals(event.event())) {
        Object agentNameObj = event.payload() != null ? event.payload().get("agentName") : null;
        if (agentNameObj instanceof String agentName && !agentName.isBlank()) {
            sessionRegistry.bindAgent(session.getId(), agentName);
            log.debug("Client-initiated agent bind: agent='{}', session={}", agentName, session.getId());
        }
        return Flux.empty();
    }
    log.debug("Unhandled client event: {}", event.event());
    return Flux.empty();
}
```

- [ ] **步骤 2：更新 routeFrame 调用签名**

将 `routeFrame` 中的 `handleEvent(evt)` 改为 `handleEvent(evt, session)`：

```java
private Flux<GatewayFrame> routeFrame(GatewayFrame frame, WebSocketSession session) {
    return switch (frame) {
        case RequestFrame req -> handleRequest(req, session);
        case EventFrame evt -> handleEvent(evt, session);
        case ResponseFrame resp -> Flux.empty();
    };
}
```

- [ ] **步骤 3：编译验证**

运行：`cd intellimate-gateway && mvn compile -pl .`
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/websocket/GatewayWebSocketHandler.java
git commit -m "feat(ws): handle agent.bind client event for proactive delivery"
```

---

### 任务 3：SessionManager 新增 findOrCreateProactiveSession

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/session/SessionManager.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/session/SessionManagerImpl.java`

- [ ] **步骤 1：在 SessionManager 接口添加方法签名**

在 `SessionManager.java` 末尾（`}` 之前）添加：

```java
Mono<Long> findOrCreateProactiveSession(String agentName);
```

- [ ] **步骤 2：在 SessionManagerImpl 实现方法**

在 `SessionManagerImpl.java` 添加：

```java
@Override
public Mono<Long> findOrCreateProactiveSession(String agentName) {
    String channelId = "webchat";
    String contextType = "dm";
    String contextId = "proactive::" + agentName;

    return sessionRepository.findBySessionKey(channelId, contextType, contextId)
            .map(SessionEntity::getId)
            .switchIfEmpty(Mono.defer(() -> {
                SessionEntity newSession = new SessionEntity();
                newSession.setChannelId(channelId);
                newSession.setContextType(contextType);
                newSession.setContextId(contextId);
                newSession.setAgentName(agentName);
                newSession.setLastActiveAt(LocalDateTime.now());
                newSession.setCreatedAt(LocalDateTime.now());
                newSession.setDeleted(0);
                return sessionRepository.save(newSession)
                        .doOnSuccess(s -> log.info("Created proactive session: id={}, agent={}",
                                s.getId(), agentName))
                        .map(SessionEntity::getId);
            }));
}
```

- [ ] **步骤 3：编译验证**

运行：`cd intellimate-gateway && mvn compile -pl .`
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/session/SessionManager.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/session/SessionManagerImpl.java
git commit -m "feat(session): add findOrCreateProactiveSession for background jobs"
```

---

### 任务 4：ChatInjectionService

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/ChatInjectionService.java`
- 测试：`intellimate-gateway/src/test/java/com/atm/intellimate/gateway/service/ChatInjectionServiceTest.java`

- [ ] **步骤 1：编写测试**

```java
package com.atm.intellimate.gateway.service;

import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.session.SessionManager;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatInjectionServiceTest {

    @Mock private SessionRegistry sessionRegistry;
    @Mock private SessionManager sessionManager;

    private ChatInjectionService service;

    @BeforeEach
    void setUp() {
        service = new ChatInjectionService(sessionRegistry, sessionManager);
    }

    @Test
    void injectAgentMessage_persistsAndPushes() {
        when(sessionManager.findOrCreateProactiveSession("agent-a")).thenReturn(Mono.just(42L));
        when(sessionManager.appendMessage(eq(42L), any(TranscriptMessageEntity.class))).thenReturn(Mono.empty());
        when(sessionRegistry.pushToAllAgentSessions(eq("agent-a"), eq("agent.proactive"), any(Map.class))).thenReturn(2);

        StepVerifier.create(service.injectAgentMessage("agent-a", "Hello!", ChatInjectionService.ProactiveSource.HEARTBEAT))
                .expectNext(2)
                .verifyComplete();

        ArgumentCaptor<TranscriptMessageEntity> captor = ArgumentCaptor.forClass(TranscriptMessageEntity.class);
        verify(sessionManager).appendMessage(eq(42L), captor.capture());

        TranscriptMessageEntity saved = captor.getValue();
        assertThat(saved.getRole()).isEqualTo("assistant");
        assertThat(saved.getContent()).isEqualTo("Hello!");
        assertThat(saved.getMetadataJson()).contains("heartbeat");
    }

    @Test
    void injectAgentMessage_persistFails_stillPushes() {
        when(sessionManager.findOrCreateProactiveSession("agent-a")).thenReturn(Mono.error(new RuntimeException("DB down")));
        when(sessionRegistry.pushToAllAgentSessions(eq("agent-a"), eq("agent.proactive"), any(Map.class))).thenReturn(1);

        StepVerifier.create(service.injectAgentMessage("agent-a", "Hi", ChatInjectionService.ProactiveSource.SCHEDULED_JOB))
                .expectNext(1)
                .verifyComplete();
    }

    @Test
    void injectAgentMessage_noSessions_returnsZero() {
        when(sessionManager.findOrCreateProactiveSession("agent-a")).thenReturn(Mono.just(42L));
        when(sessionManager.appendMessage(eq(42L), any())).thenReturn(Mono.empty());
        when(sessionRegistry.pushToAllAgentSessions(eq("agent-a"), eq("agent.proactive"), any(Map.class))).thenReturn(0);

        StepVerifier.create(service.injectAgentMessage("agent-a", "Hi", ChatInjectionService.ProactiveSource.HEARTBEAT))
                .expectNext(0)
                .verifyComplete();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd intellimate-gateway && mvn test -pl . -Dtest=ChatInjectionServiceTest -Dsurefire.useFile=false`
预期：编译失败（`ChatInjectionService` 类不存在）

- [ ] **步骤 3：实现 ChatInjectionService**

创建 `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/ChatInjectionService.java`：

```java
package com.atm.intellimate.gateway.service;

import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.session.SessionManager;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class ChatInjectionService {

    private static final Logger log = LoggerFactory.getLogger(ChatInjectionService.class);

    private final SessionRegistry sessionRegistry;
    private final SessionManager sessionManager;

    public ChatInjectionService(SessionRegistry sessionRegistry, SessionManager sessionManager) {
        this.sessionRegistry = sessionRegistry;
        this.sessionManager = sessionManager;
    }

    public Mono<Integer> injectAgentMessage(String agentName, String content, ProactiveSource source) {
        String syntheticRequestId = "bg-" + UUID.randomUUID().toString().substring(0, 8);

        Mono<Void> persistMono = sessionManager.findOrCreateProactiveSession(agentName)
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

- [ ] **步骤 4：运行测试验证通过**

运行：`cd intellimate-gateway && mvn test -pl . -Dtest=ChatInjectionServiceTest -Dsurefire.useFile=false`
预期：全部 PASS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/ChatInjectionService.java
git add intellimate-gateway/src/test/java/com/atm/intellimate/gateway/service/ChatInjectionServiceTest.java
git commit -m "feat(injection): add ChatInjectionService for proactive message delivery"
```

---

### 任务 5：AgentPromptJob 集成

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/scheduler/jobs/AgentPromptJob.java`

- [ ] **步骤 1：添加 ChatInjectionService 依赖注入**

在 `AgentPromptJob` 的构造函数参数和字段中添加 `ChatInjectionService`：

```java
private final ChatInjectionService chatInjectionService;
```

在构造函数中添加参数并赋值（Spring 自动注入）。

- [ ] **步骤 2：修改 execute 方法的回复处理逻辑**

找到 `.then(Mono.fromSupplier(() -> {` 块内的 `sessionRegistry.broadcast("scheduler.agent.response", ...)` 调用。

将整个 `.then(Mono.fromSupplier(...))` 替换为：

```java
.then(Mono.defer(() -> {
    String response = responseText.get();

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
}))
```

- [ ] **步骤 3：移除旧的 sessionRegistry 字段引用（如不再有其他用途）**

检查 `AgentPromptJob` 是否仍需要 `SessionRegistry` 的直接引用。如果 `broadcast` 是唯一用途，移除该字段和构造函数参数。

- [ ] **步骤 4：编译验证**

运行：`cd intellimate-gateway && mvn compile -pl .`
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/scheduler/jobs/AgentPromptJob.java
git commit -m "feat(scheduler): route AgentPromptJob output through ChatInjectionService"
```

---

### 任务 6：HeartbeatEngine 集成

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/heartbeat/HeartbeatEngine.java`

- [ ] **步骤 1：添加 ChatInjectionService 依赖**

在 `HeartbeatEngine` 的构造函数参数和字段中添加 `ChatInjectionService`。

- [ ] **步骤 2：重写 deliver 方法**

将 `deliver` 方法从：

```java
private Mono<Void> deliver(HeartbeatConfigEntity config, String agentName, String response) {
    boolean delivered = sessionRegistry.pushToAgent(agentName, "heartbeat.message",
            Map.of("content", response, "agentId", config.getAgentId()));

    if (delivered) {
        log.info("Heartbeat message delivered to agent {} via WebSocket", agentName);
        return Mono.empty();
    }

    log.info("Agent {} offline, caching heartbeat message", agentName);
    OfflineMessageEntity msg = new OfflineMessageEntity();
    msg.setAgentId(config.getAgentId());
    msg.setContent(response);
    msg.setMessageType("heartbeat");
    msg.setCreatedAt(LocalDateTime.now());
    msg.setDelivered(0);
    return offlineMsgRepo.save(msg).then();
}
```

改为：

```java
private Mono<Void> deliver(HeartbeatConfigEntity config, String agentName, String response) {
    return chatInjectionService.injectAgentMessage(
            agentName, response, ChatInjectionService.ProactiveSource.HEARTBEAT
    ).doOnNext(deliveredCount -> {
        if (deliveredCount > 0) {
            log.info("Heartbeat delivered to {} sessions for agent {}", deliveredCount, agentName);
        } else {
            log.info("Agent {} has no active sessions, heartbeat persisted only", agentName);
        }
    }).then();
}
```

- [ ] **步骤 3：移除不再需要的 `sessionRegistry` 和 `offlineMsgRepo` 字段**

检查 `HeartbeatEngine` 中 `sessionRegistry` 和 `offlineMsgRepo`（`OfflineMessageRepository`）是否仍有其他用途。如果 `deliver` 是唯一使用点，从构造函数中移除。

注意：如果有其他方法仍在使用这些字段，保留它们。

- [ ] **步骤 4：编译验证**

运行：`cd intellimate-gateway && mvn compile -pl .`
预期：BUILD SUCCESS

- [ ] **步骤 5：修复已有的 HeartbeatEngine 测试**

查找引用 `pushToAgent(..., "heartbeat.message", ...)` 或 `offlineMsgRepo.save(...)` 的测试，更新为 mock `chatInjectionService.injectAgentMessage(...)` 返回 `Mono.just(1)` 或 `Mono.just(0)`。

- [ ] **步骤 6：运行测试验证通过**

运行：`cd intellimate-gateway && mvn test -pl . -Dtest="*Heartbeat*" -Dsurefire.useFile=false`
预期：全部 PASS

- [ ] **步骤 7：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/heartbeat/HeartbeatEngine.java
git add intellimate-gateway/src/test/
git commit -m "feat(heartbeat): route delivery through ChatInjectionService"
```

---

### 任务 7：前端 chatStore 扩展

**文件：**
- 修改：`intellimate-web/src/stores/chatStore.ts`

- [ ] **步骤 1：扩展 ChatState interface**

在 `ChatState` interface 中添加：

```typescript
proactiveBuffer: Array<{ agentName: string; text: string; requestId: string; source: string }>;
addProactiveMessage: (agentName: string, text: string, requestId: string, source: string) => void;
bufferProactiveMessage: (agentName: string, text: string, requestId: string, source: string) => void;
flushProactiveBuffer: () => void;
```

- [ ] **步骤 2：在 store 初始化中添加默认值**

在 `create<ChatState>` 的初始值中添加：

```typescript
proactiveBuffer: [],
```

- [ ] **步骤 3：实现 addProactiveMessage 方法**

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
```

- [ ] **步骤 4：实现 bufferProactiveMessage 方法**

```typescript
bufferProactiveMessage: (agentName, text, requestId, source) => {
    set((state) => ({
        proactiveBuffer: [...state.proactiveBuffer, { agentName, text, requestId, source }],
    }));
},
```

- [ ] **步骤 5：实现 flushProactiveBuffer 方法**

```typescript
flushProactiveBuffer: () => {
    const buffer = get().proactiveBuffer;
    if (buffer.length === 0) return;
    for (const msg of buffer) {
        get().addProactiveMessage(msg.agentName, msg.text, msg.requestId, msg.source);
    }
    set({ proactiveBuffer: [] });
},
```

- [ ] **步骤 6：在 finishStreaming 末尾调用 flush**

在 `finishStreaming` 方法的 `set(...)` 之后添加：

```typescript
// flush 缓冲的 proactive 消息
setTimeout(() => get().flushProactiveBuffer(), 0);
```

使用 `setTimeout` 确保 state 更新完成后再 flush。

- [ ] **步骤 7：Commit**

```bash
git add intellimate-web/src/stores/chatStore.ts
git commit -m "feat(chat): add proactive message support with streaming buffer"
```

---

### 任务 8：前端 useWebSocket 集成

**文件：**
- 修改：`intellimate-web/src/hooks/useWebSocket.ts`

- [ ] **步骤 1：在 session.welcome 后发送 agent.bind**

修改 `case "session.welcome"` 块，在末尾添加：

```typescript
case "session.welcome": {
    store.setWsSessionId(event.payload.wsSessionId as string);
    const planState = usePlanStore.getState();
    if (
        planState.plan &&
        !["completed", "cancelled", "failed"].includes(planState.plan.status)
    ) {
        planState.syncFromServer(planState.plan.planId);
    }
    // 绑定当前 agent 以接收 proactive 消息
    const agentState = useAgentStore.getState();
    const currentAgentName = agentState.currentAgent?.name;
    if (currentAgentName && clientRef.current) {
        clientRef.current.send(JSON.stringify({
            type: "event",
            event: "agent.bind",
            payload: { agentName: currentAgentName },
            seq: 0
        }));
    }
    break;
}
```

- [ ] **步骤 2：新增 agent.proactive 事件处理**

在 `switch (event.event)` 中，在 `case "agent.done"` 之后添加：

```typescript
case "agent.proactive": {
    const agentName = event.payload.agentName as string;
    const text = event.payload.text as string;
    const requestId = event.payload.requestId as string;
    const source = (event.payload.source as string) || "unknown";
    if (!text?.trim() || !agentName?.trim()) break;

    if (store.isWaiting) {
        store.bufferProactiveMessage(agentName, text, requestId, source);
    } else {
        store.addProactiveMessage(agentName, text, requestId, source);
    }
    break;
}
```

- [ ] **步骤 3：在切换 Agent 时发送 agent.bind**

找到 `sendMessage` 函数（或 agent 切换逻辑处），确保切换 agent 后也发送 bind。查看是否有 `setCurrentAgent` 相关的 effect 可以挂载。

如果前端在 `sendMessage` 中已有 `agentName` 参数传给后端（当前 `MessagePipeline` 中的 `bindAgent` 调用），这一步可选——因为首次发消息时后端已经会 bind。但为了覆盖"选中 agent 不发消息"的场景，最好在 `useEffect` 监听 `currentAgent` 变化时发送。

在 `useWebSocket` hook 中添加：

```typescript
// 监听 agent 切换
const currentAgent = useAgentStore((s) => s.currentAgent);
useEffect(() => {
    if (currentAgent?.name && clientRef.current?.isConnected()) {
        clientRef.current.send(JSON.stringify({
            type: "event",
            event: "agent.bind",
            payload: { agentName: currentAgent.name },
            seq: 0
        }));
    }
}, [currentAgent?.name]);
```

- [ ] **步骤 4：前端编译验证**

运行：`cd intellimate-web && npm run build`
预期：BUILD SUCCESS，无 TypeScript 错误

- [ ] **步骤 5：Commit**

```bash
git add intellimate-web/src/hooks/useWebSocket.ts
git commit -m "feat(ws): handle agent.proactive event and send agent.bind on connect/switch"
```

---

### 任务 9：端到端验证

**文件：** 无新文件

- [ ] **步骤 1：启动后端**

运行：`cd intellimate-gateway && mvn spring-boot:run`
预期：应用正常启动，日志无报错

- [ ] **步骤 2：启动前端**

运行：`cd intellimate-web && npm run dev`
预期：Vite dev server 启动

- [ ] **步骤 3：验证 agent.bind 触发**

打开浏览器 → 选择一个 agent → 查看后端日志。
预期：日志出现 `Client-initiated agent bind: agent='xxx', session=yyy`

- [ ] **步骤 4：手动触发定时任务验证 proactive 推送**

通过 REST API 触发一个 AgentPromptJob：
```bash
curl -X POST http://localhost:3007/api/scheduled-jobs/agent-prompt/trigger
```
（如果 `agent-prompt` job 存在）

或手动在数据库 seed 一个测试 job 并触发。

预期：
1. 后端日志出现 `delivered to N sessions`
2. 前端聊天界面出现 Agent 回复气泡
3. 数据库 `transcript_message` 中新增一条 `role=assistant, metadata_json` 含 `scheduled_job`

- [ ] **步骤 5：验证刷新后消息不丢失**

刷新浏览器页面。

注意：当前前端尚未实现从 DB 加载历史消息的功能（这是已知的 gap，不在本次范围内）。但 DB 中的记录为未来实现此功能提供了数据基础。

- [ ] **步骤 6：Commit 最终状态（如果有细微修复）**

```bash
git add -A && git commit -m "fix: minor adjustments from e2e verification"
```

---

## 实现顺序依赖图

```
任务 1 (SessionRegistry) ─┐
任务 2 (WebSocketHandler) ─┤
任务 3 (SessionManager)  ──┼──→ 任务 4 (ChatInjectionService) ──→ 任务 5 (AgentPromptJob)
                           │                                   ──→ 任务 6 (HeartbeatEngine)
                           │
任务 7 (chatStore)  ───────┼──→ 任务 8 (useWebSocket)
                           │
全部完成 ──────────────────┼──→ 任务 9 (E2E 验证)
```

任务 1-3 和任务 7 可以并行。任务 4 依赖 1+3。任务 5-6 依赖 4。任务 8 依赖 7。任务 9 依赖全部。
