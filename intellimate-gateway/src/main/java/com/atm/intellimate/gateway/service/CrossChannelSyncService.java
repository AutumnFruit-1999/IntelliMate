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
 * When a message + reply occurs on one channel, pushes the reply to other bound channels.
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

    public CrossChannelSyncService(SessionRegistry sessionRegistry,
                                   ChannelsManager channelsManager,
                                   ChannelIdentityRepository identityRepository) {
        this.sessionRegistry = sessionRegistry;
        this.channelsManager = channelsManager;
        this.identityRepository = identityRepository;
    }

    /**
     * Push a message to Web clients when the original message came from an external channel.
     * Looks up the user's webchat identity to find the DB userId, then pushes via WebSocket.
     */
    public Mono<Void> syncToWeb(String unifiedUserId, String role, String content, String sourceChannel) {
        log.info("syncToWeb: userId={}, role={}, contentLen={}, source={}",
                unifiedUserId, role, content != null ? content.length() : 0, sourceChannel);
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
                        int delivered = sessionRegistry.pushToUser(dbUserId, "message.sync", payload);
                        if (delivered > 0) {
                            log.debug("Synced {} to {} web session(s) for user {}", role, delivered, unifiedUserId);
                        }
                        return Mono.<Void>empty();
                    } catch (NumberFormatException e) {
                        return Mono.empty();
                    }
                })
                .then();
    }

    /**
     * Push a message (user or agent) to external channels when the original came from web.
     * For user messages, prefixes with [Web] to indicate origin.
     */
    public Mono<Void> syncToExternalChannels(String unifiedUserId, String role, String content) {
        String formattedContent = "user".equals(role)
                ? "[Web] " + content
                : content;
        log.info("syncToExternalChannels: userId={}, role={}, contentLen={}",
                unifiedUserId, role, content != null ? content.length() : 0);
        return identityRepository.findByUserId(unifiedUserId)
                .filter(identity -> EXTERNAL_CHANNELS.contains(identity.getChannelId()))
                .flatMap(identity -> sendToExternalChannel(identity, formattedContent))
                .then()
                .onErrorResume(e -> {
                    log.error("syncToExternalChannels failed for userId={}: {}", unifiedUserId, e.getMessage(), e);
                    return Mono.empty();
                });
    }

    private Mono<Void> sendToExternalChannel(ChannelIdentityEntity identity, String agentReply) {
        String channelId = identity.getChannelId();
        String externalId = identity.getExternalId();

        if (channelsManager.getAdapter(channelId) == null
                || !channelsManager.getAdapter(channelId).isConnected()) {
            log.debug("Skipping sync to {} (not connected)", channelId);
            return Mono.empty();
        }

        SessionKey sessionKey = new SessionKey(channelId, "dm", externalId);
        OutboundMessage outbound = new OutboundMessage(sessionKey, agentReply, List.of(), null);

        return channelsManager.send(outbound)
                .doOnSuccess(v -> log.debug("Synced reply to channel {} for externalId {}", channelId, externalId))
                .onErrorResume(e -> {
                    log.warn("Failed to sync reply to channel {}: {}", channelId, e.getMessage());
                    return Mono.empty();
                });
    }
}
