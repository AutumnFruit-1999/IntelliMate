package com.atm.intellimate.gateway.service;

import com.atm.intellimate.core.model.OutboundMessage;
import com.atm.intellimate.core.model.SessionKey;
import com.atm.intellimate.gateway.channel.ChannelsManager;
import com.atm.intellimate.gateway.entity.ChannelIdentityEntity;
import com.atm.intellimate.gateway.repository.ChannelIdentityRepository;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Syncs messages across channels bound to the same unified user.
 * Routes based on channel's configured defaultAgent — only pushes to channels
 * whose defaultAgent matches the current agent.
 */
@Service
public class CrossChannelSyncService {

    private static final Logger log = LoggerFactory.getLogger(CrossChannelSyncService.class);

    private static final Set<String> EXTERNAL_CHANNELS = Set.of(
            "dingtalk-stream", "dingtalk", "feishu", "wechat"
    );

    private final SessionRegistry sessionRegistry;
    private final ChannelsManager channelsManager;
    private final ChannelIdentityRepository identityRepository;
    private final ChannelConfigService channelConfigService;

    public CrossChannelSyncService(SessionRegistry sessionRegistry,
                                   ChannelsManager channelsManager,
                                   ChannelIdentityRepository identityRepository,
                                   ChannelConfigService channelConfigService) {
        this.sessionRegistry = sessionRegistry;
        this.channelsManager = channelsManager;
        this.identityRepository = identityRepository;
        this.channelConfigService = channelConfigService;
    }

    /**
     * Push a message to Web clients when the original message came from an external channel.
     * Looks up the user's webchat identity to find the DB userId, then pushes via WebSocket.
     */
    public Mono<Void> syncToWeb(String unifiedUserId, String role, String content, String sourceChannel) {
        return identityRepository.findByUserId(unifiedUserId)
                .filter(identity -> "webchat".equals(identity.getChannelId()))
                .next()
                .flatMap(webchatIdentity -> {
                    try {
                        Long dbUserId = Long.parseLong(webchatIdentity.getExternalId());
                        Map<String, Object> payload = Map.of(
                                "role", role,
                                "content", content,
                                "sourceChannel", sourceChannel != null ? sourceChannel : "external"
                        );
                        sessionRegistry.pushToUser(dbUserId, "message.sync", payload);
                        return Mono.<Void>empty();
                    } catch (NumberFormatException e) {
                        return Mono.empty();
                    }
                })
                .then();
    }

    /**
     * Push a message to external channels with agent-based routing.
     * Only pushes to channels whose configured defaultAgent matches the given agentName.
     *
     * @param unifiedUserId the unified user ID
     * @param role          "user" or "assistant"
     * @param content       message content
     * @param agentName     current agent name for routing
     * @param excludeChannel channel to exclude (source channel to avoid echo), nullable
     */
    public Mono<Void> syncToExternalChannels(String unifiedUserId, String role, String content,
                                             String agentName, String excludeChannel) {
        if (agentName == null || agentName.isBlank()) {
            log.info("[CrossSync] skipped: agentName is null/blank, userId={}", unifiedUserId);
            return Mono.empty();
        }
        log.info("[CrossSync] syncToExternalChannels called: userId={}, role={}, agent={}, excludeChannel={}",
                unifiedUserId, role, agentName, excludeChannel);

        String formattedContent = "user".equals(role)
                ? "[Web] " + content
                : content;
        return identityRepository.findByUserId(unifiedUserId)
                .filter(identity -> EXTERNAL_CHANNELS.contains(identity.getChannelId()))
                .filter(identity -> !identity.getChannelId().equals(excludeChannel))
                .collectList()
                .flatMap(identities -> {
                    if (identities.isEmpty()) {
                        log.info("[CrossSync] no external identities found for userId={}", unifiedUserId);
                        return Mono.empty();
                    }
                    return reactor.core.publisher.Flux.fromIterable(identities)
                            .doOnNext(identity -> log.info("[CrossSync] found external identity: channel={}, externalId={}",
                                    identity.getChannelId(), identity.getExternalId()))
                            .flatMap(identity -> shouldSyncToChannel(identity.getChannelId(), agentName)
                                    .doOnNext(should -> log.info("[CrossSync] shouldSync channel={} agent={} -> {}",
                                            identity.getChannelId(), agentName, should))
                                    .flatMap(shouldSync -> shouldSync
                                            ? sendToExternalChannel(identity, formattedContent)
                                            : Mono.<Void>empty()))
                            .then();
                })
                .onErrorResume(e -> {
                    log.error("[CrossSync] FAILED userId={}, agent={}: {}",
                            unifiedUserId, agentName, e.getMessage(), e);
                    return Mono.empty();
                });
    }

    private Mono<Boolean> shouldSyncToChannel(String channelId, String agentName) {
        return channelConfigService.getDefaultAgent(channelId)
                .map(optAgent -> optAgent.isPresent() && optAgent.get().equals(agentName))
                .defaultIfEmpty(false);
    }

    private Mono<Void> sendToExternalChannel(ChannelIdentityEntity identity, String agentReply) {
        String channelId = identity.getChannelId();
        String externalId = identity.getExternalId();

        if (channelsManager.getAdapter(channelId) == null
                || !channelsManager.getAdapter(channelId).isConnected()) {
            log.warn("[CrossSync] adapter not connected, skipping: channel={}", channelId);
            return Mono.empty();
        }

        log.info("[CrossSync] sending to channel={}, externalId={}, contentLength={}",
                channelId, externalId, agentReply.length());

        SessionKey sessionKey = new SessionKey(channelId, "dm", externalId);
        OutboundMessage outbound = new OutboundMessage(sessionKey, agentReply, List.of(), null);

        return channelsManager.send(outbound)
                .doOnSuccess(v -> log.info("[CrossSync] send completed: channel={}", channelId))
                .onErrorResume(e -> {
                    log.error("[CrossSync] send failed to channel={}: {}", channelId, e.getMessage(), e);
                    return Mono.empty();
                });
    }
}
