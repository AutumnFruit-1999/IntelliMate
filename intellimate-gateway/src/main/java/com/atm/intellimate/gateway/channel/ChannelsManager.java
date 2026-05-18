package com.atm.intellimate.gateway.channel;

import com.atm.intellimate.channel.api.ChannelAdapter;
import com.atm.intellimate.channel.api.ChannelStatus;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.OutboundMessage;
import com.atm.intellimate.gateway.entity.ChannelConfigEntity;
import com.atm.intellimate.gateway.repository.ChannelConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Manages the lifecycle of all channel adapters.
 * Discovers adapters via Spring DI, loads configs from DB,
 * and connects enabled channels at startup.
 */
@Service
public class ChannelsManager {

    private static final Logger log = LoggerFactory.getLogger(ChannelsManager.class);

    private final Map<String, ChannelAdapter> adapters = new ConcurrentHashMap<>();
    private final ChannelConfigRepository configRepository;
    private final ObjectMapper objectMapper;
    private Consumer<InboundEnvelope> inboundHandler;

    public ChannelsManager(List<ChannelAdapter> adapterList,
                           ChannelConfigRepository configRepository,
                           ObjectMapper objectMapper) {
        this.configRepository = configRepository;
        this.objectMapper = objectMapper;
        for (ChannelAdapter adapter : adapterList) {
            adapters.put(adapter.getChannelId(), adapter);
            log.info("Discovered channel adapter: {}", adapter.getChannelId());
        }
    }

    public void setInboundHandler(Consumer<InboundEnvelope> handler) {
        this.inboundHandler = handler;
        adapters.values().forEach(a -> a.onMessage(handler));
    }

    @PostConstruct
    public void init() {
        log.info("ChannelsManager initializing with {} adapter(s)", adapters.size());
        connectEnabledChannels().subscribe(
                null,
                e -> log.error("Error during channel initialization", e),
                () -> log.info("Channel initialization complete")
        );
    }

    @PreDestroy
    public void shutdown() {
        log.info("ChannelsManager shutting down");
        adapters.values().forEach(adapter -> {
            if (adapter.isConnected()) {
                adapter.disconnect().subscribe(
                        null,
                        e -> log.error("Error disconnecting channel {}", adapter.getChannelId(), e)
                );
            }
        });
    }

    private Mono<Void> connectEnabledChannels() {
        return configRepository.findAll()
                .filter(cfg -> cfg.getEnabled() != null && cfg.getEnabled() && cfg.getDeleted() != null && cfg.getDeleted() == 0)
                .flatMap(this::connectChannel)
                .then();
    }

    private Mono<Void> connectChannel(ChannelConfigEntity config) {
        ChannelAdapter adapter = adapters.get(config.getChannelId());
        if (adapter == null) {
            log.warn("No adapter found for channel: {}", config.getChannelId());
            return Mono.empty();
        }

        Map<String, Object> settings = parseConfig(config.getConfigJson());

        if (inboundHandler != null) {
            adapter.onMessage(inboundHandler);
        }

        return adapter.connect(settings)
                .doOnSuccess(v -> log.info("Channel connected: {}", config.getChannelId()))
                .doOnError(e -> log.error("Failed to connect channel: {}", config.getChannelId(), e))
                .onErrorComplete();
    }

    public Mono<Void> send(OutboundMessage message) {
        String channelId = message.sessionKey().channelId();
        ChannelAdapter adapter = adapters.get(channelId);
        if (adapter == null) {
            return Mono.error(new com.atm.intellimate.core.exception.ChannelException(channelId, "No adapter registered"));
        }
        if (!adapter.isConnected()) {
            return Mono.error(new com.atm.intellimate.core.exception.ChannelException(channelId, "Channel not connected"));
        }
        return adapter.send(message);
    }

    public ChannelAdapter getAdapter(String channelId) {
        return adapters.get(channelId);
    }

    public Map<String, ChannelStatus> getAllStatuses() {
        Map<String, ChannelStatus> statuses = new ConcurrentHashMap<>();
        adapters.forEach((id, adapter) -> statuses.put(id, adapter.getStatus()));
        return statuses;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseConfig(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.error("Failed to parse channel config JSON", e);
            return Collections.emptyMap();
        }
    }
}
