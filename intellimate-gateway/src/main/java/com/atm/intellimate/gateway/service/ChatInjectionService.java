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
