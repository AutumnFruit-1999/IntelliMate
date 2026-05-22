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
import java.time.ZoneId;
import java.time.Instant;
import java.util.List;
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

    private final ConcurrentHashMap<String, Long> lastDeliveredTimestamp = new ConcurrentHashMap<>();

    public ChatInjectionService(SessionRegistry sessionRegistry,
                                SessionManager sessionManager,
                                TranscriptMessageRepository transcriptRepo,
                                IntelliMateProperties properties) {
        this.sessionRegistry = sessionRegistry;
        this.sessionManager = sessionManager;
        this.transcriptRepo = transcriptRepo;
        this.messageTtlHours = properties.getProactive() != null ? properties.getProactive().getMessageTtlHours() : 24;
        this.replayLimit = properties.getProactive() != null ? properties.getProactive().getReplayLimit() : 20;
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
                        lastDeliveredTimestamp.put(agentName, System.currentTimeMillis());
                    } else {
                        log.warn("Proactive message for agent '{}' not delivered: no connected sessions found", agentName);
                    }
                    return delivered;
                }
        ));
    }

    /**
     * Replay pending proactive messages to a reconnected agent session.
     * Uses TTL and lastDeliveredTimestamp for dedup; keyed by agentName
     * so disconnect does NOT clear the checkpoint.
     */
    public Mono<Integer> deliverPendingMessages(String agentName) {
        LocalDateTime ttlCutoff = LocalDateTime.now().minusHours(messageTtlHours);
        Long lastTs = lastDeliveredTimestamp.get(agentName);
        LocalDateTime dedupCutoff = lastTs != null
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(lastTs), ZoneId.systemDefault())
                : LocalDateTime.MIN;
        LocalDateTime effectiveCutoff = ttlCutoff.isAfter(dedupCutoff) ? ttlCutoff : dedupCutoff;

        return sessionManager.findOrCreateProactiveSession(agentName)
                .flatMap(sessionId -> transcriptRepo
                        .findRecentBySessionIdNoPlanAfter(sessionId, effectiveCutoff, replayLimit)
                        .collectList()
                        .flatMap(messages -> pushReplayMessages(agentName, messages)));
    }

    private Mono<Integer> pushReplayMessages(String agentName, List<TranscriptMessageEntity> messages) {
        if (messages.isEmpty()) {
            return Mono.just(0);
        }

        int pushed = 0;
        for (TranscriptMessageEntity msg : messages) {
            long ts = msg.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            int count = sessionRegistry.pushToAllAgentSessions(agentName, "agent.proactive", Map.of(
                    "agentName", agentName,
                    "requestId", "replay-" + msg.getId(),
                    "text", msg.getContent(),
                    "source", "replay",
                    "timestamp", ts
            ));
            if (count > 0) pushed++;
        }

        messages.stream()
                .map(m -> m.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .max(Long::compareTo)
                .ifPresent(ts -> lastDeliveredTimestamp.put(agentName, ts));

        log.info("Replayed {} pending message(s) for agent '{}'", pushed, agentName);
        return Mono.just(pushed);
    }

    public boolean isAgentOnline(String agentName) {
        return sessionRegistry.isAgentOnline(agentName);
    }

    public enum ProactiveSource {
        HEARTBEAT,
        SCHEDULED_JOB
    }
}
