package com.atm.javaclaw.gateway.security;

import com.atm.javaclaw.core.config.JavaClawProperties;
import com.atm.javaclaw.gateway.repository.AllowlistEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central security service handling token validation, allowlist checking,
 * and DM pairing logic. Allowlist is backed by the database with a local cache.
 */
@Service
public class SecurityService {

    private static final Logger log = LoggerFactory.getLogger(SecurityService.class);

    private final JavaClawProperties properties;
    private final AllowlistEntryRepository allowlistRepository;

    private final Set<String> allowlistCache = ConcurrentHashMap.newKeySet();
    private volatile boolean cacheLoaded = false;

    public SecurityService(JavaClawProperties properties,
                           AllowlistEntryRepository allowlistRepository) {
        this.properties = properties;
        this.allowlistRepository = allowlistRepository;
    }

    /**
     * Validate the provided authentication token.
     * If no authToken is configured, all connections are allowed (dev mode).
     */
    public boolean validateToken(String token) {
        String configuredToken = properties.getSecurity().getAuthToken();
        if (configuredToken == null || configuredToken.isBlank()) {
            log.warn("No auth token configured — accepting all connections (dev mode)");
            return true;
        }
        return configuredToken.equals(token);
    }

    /**
     * Check whether a sender is allowed for the given channel.
     * Checks DB-backed allowlist first, falls back to config allowlist.
     * Empty allowlist means all senders are allowed.
     */
    public Mono<Boolean> isAllowed(String channelId, String senderId) {
        List<String> configAllowlist = properties.getSecurity().getAllowlist();
        if (configAllowlist != null && !configAllowlist.isEmpty()) {
            return Mono.just(configAllowlist.contains(senderId));
        }

        return allowlistRepository.isSenderAllowed(channelId, senderId)
                .flatMap(found -> {
                    if (found) return Mono.just(true);
                    // No entries at all means open access
                    return allowlistRepository.findByChannelIdAndDeleted(channelId, 0)
                            .hasElements()
                            .map(hasEntries -> !hasEntries);
                });
    }

    /**
     * Synchronous allowlist check using only config (for WebSocket handshake).
     * Empty allowlist means all senders are allowed.
     */
    public boolean isAllowedSync(String senderId) {
        List<String> allowlist = properties.getSecurity().getAllowlist();
        if (allowlist == null || allowlist.isEmpty()) {
            return true;
        }
        return allowlist.contains(senderId);
    }

    public boolean isDmPairingRequired() {
        return properties.getSecurity().isDmPairingEnabled();
    }
}
