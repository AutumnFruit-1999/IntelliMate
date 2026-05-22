package com.atm.intellimate.gateway.service;

import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.repository.TranscriptMessageRepository;
import com.atm.intellimate.gateway.session.SessionManager;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChatInjectionService {

    private static final Logger log = LoggerFactory.getLogger(ChatInjectionService.class);

    private final SessionRegistry sessionRegistry;
    private final SessionManager sessionManager;
    private final TranscriptMessageRepository transcriptRepo;
    private final int messageTtlHours;
    private final int replayLimit;

    public ChatInjectionService(SessionRegistry sessionRegistry,
                                SessionManager sessionManager,
                                TranscriptMessageRepository transcriptRepo,
                                IntelliMateProperties properties) {
        this.sessionRegistry = sessionRegistry;
        this.sessionManager = sessionManager;
        this.transcriptRepo = transcriptRepo;
        IntelliMateProperties.Proactive proactive = properties.getProactive();
        this.messageTtlHours = proactive != null ? proactive.getMessageTtlHours() : 24;
        this.replayLimit = proactive != null ? proactive.getReplayLimit() : 20;
    }

    public Mono<Integer> injectAgentMessage(String agentName, String content, ProactiveSource source) {
        log.info("injectAgentMessage called: agent='{}', source={}, contentLength={}",
                agentName, source, content != null ? content.length() : 0);
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

        return persistMono.then(Mono.fromSupplier(() -> {
                    int delivered = sessionRegistry.pushToAllAgentSessions(agentName, "agent.proactive", Map.of(
                            "agentName", agentName,
                            "requestId", syntheticRequestId,
                            "text", content,
                            "source", source.name().toLowerCase(),
                            "timestamp", System.currentTimeMillis()
                    ));
                    if (delivered > 0) {
                        log.info("Proactive message pushed to {} session(s) for agent '{}'", delivered, agentName);
                    } else {
                        log.warn("Proactive message for agent '{}' not delivered: no connected sessions found", agentName);
                    }
                    return delivered;
                }
        ));
    }

    /**
     * Tracks last-delivered timestamp per agent (not per WS session) to avoid
     * replaying the same messages on every reconnect.
     */
    private final ConcurrentHashMap<String, Long> lastDeliveredTimestamp = new ConcurrentHashMap<>();

    /**
     * Called when a client binds to an agent via WebSocket.
     * Only delivers proactive messages generated AFTER the last delivery checkpoint.
     * On first bind (after server start), records the current time as checkpoint
     * without replaying historical messages.
     */
    public Mono<Integer> deliverPendingMessages(String agentName, String wsSessionId) {
        Long lastTs = lastDeliveredTimestamp.get(agentName);
        if (lastTs == null) {
            lastDeliveredTimestamp.put(agentName, System.currentTimeMillis());
            log.debug("First bind for agent '{}', setting delivery checkpoint (no replay)", agentName);
            return Mono.just(0);
        }

        LocalDateTime sinceCheckpoint = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(lastTs), java.time.ZoneId.systemDefault());
        LocalDateTime ttlCutoff = LocalDateTime.now().minusHours(messageTtlHours);
        LocalDateTime since = sinceCheckpoint.isAfter(ttlCutoff) ? sinceCheckpoint : ttlCutoff;

        return sessionManager.findOrCreateProactiveSession(agentName)
                .flatMapMany(sessionId -> transcriptRepo.findRecentBySessionIdNoPlanAfter(sessionId, since, replayLimit))
                .collectList()
                .flatMap(messages -> {
                    if (messages.isEmpty()) {
                        return Mono.just(0);
                    }
                    int pushed = 0;
                    for (TranscriptMessageEntity msg : messages) {
                        String requestId = "replay-" + UUID.randomUUID().toString().substring(0, 8);
                        String source = "unknown";
                        if (msg.getMetadataJson() != null) {
                            if (msg.getMetadataJson().contains("heartbeat")) source = "heartbeat";
                            else if (msg.getMetadataJson().contains("scheduled_job")) source = "scheduled_job";
                        }
                        boolean ok = sessionRegistry.pushToSession(wsSessionId, "agent.proactive", Map.of(
                                "agentName", agentName,
                                "requestId", requestId,
                                "text", msg.getContent(),
                                "source", source,
                                "timestamp", msg.getCreatedAt() != null
                                        ? msg.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                                        : System.currentTimeMillis()
                        ));
                        if (ok) pushed++;
                    }
                    lastDeliveredTimestamp.put(agentName, System.currentTimeMillis());
                    log.info("Delivered {} pending proactive message(s) to agent '{}' session {}", pushed, agentName, wsSessionId);
                    return Mono.just(pushed);
                })
                .onErrorResume(e -> {
                    log.warn("Failed to deliver pending messages for agent '{}': {}", agentName, e.getMessage());
                    return Mono.just(0);
                });
    }

    /**
     * @deprecated No-op retained for backward compatibility. Dedup is now per-agent,
     *             not per-session, and never cleared on disconnect.
     */
    @Deprecated
    public void clearSessionDeliveryState(String wsSessionId) {
    }

    public enum ProactiveSource {
        HEARTBEAT,
        SCHEDULED_JOB
    }
}
