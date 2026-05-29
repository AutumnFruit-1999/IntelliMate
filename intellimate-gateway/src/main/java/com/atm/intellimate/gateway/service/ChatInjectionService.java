package com.atm.intellimate.gateway.service;

import com.atm.intellimate.core.model.OutboundMessage;
import com.atm.intellimate.core.model.SessionKey;
import com.atm.intellimate.gateway.channel.ChannelsManager;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.repository.SessionRepository;
import com.atm.intellimate.gateway.session.SessionManager;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

@Service
public class ChatInjectionService {

    private static final Logger log = LoggerFactory.getLogger(ChatInjectionService.class);

    private final SessionRegistry sessionRegistry;
    private final SessionManager sessionManager;
    private final SessionRepository sessionRepository;
    private final ChannelsManager channelsManager;

    public ChatInjectionService(SessionRegistry sessionRegistry,
                                SessionManager sessionManager,
                                SessionRepository sessionRepository,
                                ChannelsManager channelsManager) {
        this.sessionRegistry = sessionRegistry;
        this.sessionManager = sessionManager;
        this.sessionRepository = sessionRepository;
        this.channelsManager = channelsManager;
    }

    public Mono<Integer> injectAgentMessage(String agentName, String content, ProactiveSource source) {
        log.info("injectAgentMessage called: agent='{}', source={}, contentLength={}",
                agentName, source, content != null ? content.length() : 0);
        if (content == null || content.isBlank()) {
            log.warn("Skipping empty proactive message for agent '{}'", agentName);
            return Mono.just(0);
        }
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

        Mono<Integer> wsMono = Mono.fromSupplier(() -> {
            int delivered = sessionRegistry.pushToAllAgentSessions(agentName, "agent.proactive", Map.of(
                    "agentName", agentName,
                    "requestId", syntheticRequestId,
                    "text", content,
                    "source", source.name().toLowerCase(),
                    "timestamp", System.currentTimeMillis()
            ));
            if (delivered > 0) {
                log.info("Proactive message pushed to {} WebSocket session(s) for agent '{}'", delivered, agentName);
            }
            return delivered;
        });

        Mono<Integer> channelMono = pushToExternalChannels(agentName, content);

        return persistMono
                .then(Mono.zip(wsMono, channelMono))
                .map(tuple -> {
                    int total = tuple.getT1() + tuple.getT2();
                    if (total == 0) {
                        log.warn("Proactive message for agent '{}' not delivered: no connected sessions/channels", agentName);
                    }
                    return total;
                });
    }

    private Mono<Integer> pushToExternalChannels(String agentName, String content) {
        return sessionRepository.findExternalChannelSessionsByAgentName(agentName)
                .filter(session -> {
                    var adapter = channelsManager.getAdapter(session.getChannelId());
                    return adapter != null && adapter.isConnected();
                })
                .flatMap(session -> {
                    SessionKey key = new SessionKey(
                            session.getChannelId(),
                            session.getContextType(),
                            session.getContextId()
                    );
                    OutboundMessage outbound = new OutboundMessage(key, content, Collections.emptyList(), null);
                    return channelsManager.send(outbound)
                            .thenReturn(1)
                            .doOnSuccess(v -> log.info("Proactive message pushed to channel={}, contextId={}",
                                    session.getChannelId(), session.getContextId()))
                            .onErrorResume(e -> {
                                log.warn("Failed to push proactive message to channel={}: {}",
                                        session.getChannelId(), e.getMessage());
                                return Mono.just(0);
                            });
                })
                .reduce(0, Integer::sum);
    }

    public boolean isAgentOnline(String agentName) {
        return sessionRegistry.isAgentOnline(agentName);
    }

    public Mono<Boolean> isAgentReachable(String agentName) {
        if (sessionRegistry.isAgentOnline(agentName)) {
            return Mono.just(true);
        }
        return sessionRepository.findExternalChannelSessionsByAgentName(agentName)
                .any(session -> {
                    var adapter = channelsManager.getAdapter(session.getChannelId());
                    return adapter != null && adapter.isConnected();
                });
    }

    public enum ProactiveSource {
        HEARTBEAT,
        SCHEDULED_JOB
    }
}
