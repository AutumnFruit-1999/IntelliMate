package com.atm.intellimate.gateway.service;

import com.atm.intellimate.core.model.OutboundMessage;
import com.atm.intellimate.core.model.SessionKey;
import com.atm.intellimate.gateway.channel.ChannelsManager;
import com.atm.intellimate.gateway.dto.ChannelInfoDto;
import com.atm.intellimate.gateway.entity.ChannelIdentityEntity;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.repository.ChannelIdentityRepository;
import com.atm.intellimate.gateway.repository.SessionRepository;
import com.atm.intellimate.gateway.session.SessionManager;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ChatInjectionService {

    private static final Logger log = LoggerFactory.getLogger(ChatInjectionService.class);

    private final SessionRegistry sessionRegistry;
    private final SessionManager sessionManager;
    private final SessionRepository sessionRepository;
    private final ChannelsManager channelsManager;
    private final ChannelConfigService channelConfigService;
    private final ChannelIdentityRepository identityRepository;

    public ChatInjectionService(SessionRegistry sessionRegistry,
                                SessionManager sessionManager,
                                SessionRepository sessionRepository,
                                ChannelsManager channelsManager,
                                ChannelConfigService channelConfigService,
                                ChannelIdentityRepository identityRepository) {
        this.sessionRegistry = sessionRegistry;
        this.sessionManager = sessionManager;
        this.sessionRepository = sessionRepository;
        this.channelsManager = channelsManager;
        this.channelConfigService = channelConfigService;
        this.identityRepository = identityRepository;
    }

    public Mono<Integer> injectAgentMessage(String agentName, String content, ProactiveSource source) {
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
        return channelConfigService.listChannels()
                .filter(ch -> {
                    Object agent = ch.config() != null ? ch.config().get("defaultAgent") : null;
                    return agentName.equals(agent);
                })
                .filter(ch -> {
                    var adapter = channelsManager.getAdapter(ch.channelId());
                    return adapter != null && adapter.isConnected();
                })
                .flatMap(ch -> identityRepository.findByChannelId(ch.channelId())
                        .flatMap(identity -> {
                            SessionKey key = new SessionKey(ch.channelId(), "dm", identity.getExternalId());
                            OutboundMessage outbound = new OutboundMessage(key, content, Collections.emptyList(), null);
                            return channelsManager.send(outbound)
                                    .thenReturn(1)
                                    .onErrorResume(e -> {
                                        log.warn("Failed to push proactive message to channel={}: {}",
                                                ch.channelId(), e.getMessage());
                                        return Mono.just(0);
                                    });
                        }))
                .reduce(0, Integer::sum);
    }

    public boolean isAgentOnline(String agentName) {
        return sessionRegistry.isAgentOnline(agentName);
    }

    public Mono<Boolean> isAgentReachable(String agentName) {
        if (sessionRegistry.isAgentOnline(agentName)) {
            return Mono.just(true);
        }
        return channelConfigService.listChannels()
                .any(ch -> {
                    Object agent = ch.config() != null ? ch.config().get("defaultAgent") : null;
                    if (!agentName.equals(agent)) return false;
                    var adapter = channelsManager.getAdapter(ch.channelId());
                    return adapter != null && adapter.isConnected();
                });
    }

    public enum ProactiveSource {
        HEARTBEAT,
        SCHEDULED_JOB
    }
}
