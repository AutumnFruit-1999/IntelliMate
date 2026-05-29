package com.atm.intellimate.gateway.service;

import com.atm.intellimate.channel.api.ChannelAdapter;
import com.atm.intellimate.channel.api.ChannelStatus;
import com.atm.intellimate.core.exception.ErrorCode;
import com.atm.intellimate.core.exception.IntelliMateException;
import com.atm.intellimate.gateway.channel.ChannelMetrics;
import com.atm.intellimate.gateway.channel.ChannelsManager;
import com.atm.intellimate.gateway.dto.ChannelInfoDto;
import com.atm.intellimate.gateway.entity.ChannelConfigEntity;
import com.atm.intellimate.gateway.repository.ChannelConfigRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class ChannelConfigService {

    private final ChannelConfigRepository configRepository;
    private final ChannelsManager channelsManager;
    private final ChannelMetrics channelMetrics;
    private final ObjectMapper objectMapper;

    public ChannelConfigService(ChannelConfigRepository configRepository,
                                ChannelsManager channelsManager,
                                ChannelMetrics channelMetrics,
                                ObjectMapper objectMapper) {
        this.configRepository = configRepository;
        this.channelsManager = channelsManager;
        this.channelMetrics = channelMetrics;
        this.objectMapper = objectMapper;
    }

    public Flux<ChannelInfoDto> listChannels() {
        return configRepository.findAll()
                .filter(cfg -> cfg.getDeleted() != null && cfg.getDeleted() == 0)
                .map(this::toDto);
    }

    public Mono<ChannelInfoDto> getChannel(String channelId) {
        return configRepository.findByChannelId(channelId)
                .map(this::toDto);
    }

    public Mono<ChannelConfigEntity> createChannel(String channelId, boolean enabled, Map<String, Object> config) {
        if (channelId == null || channelId.isBlank()) {
            return Mono.error(new IntelliMateException(ErrorCode.VALIDATION_FAILED, "channelId is required"));
        }
        return configRepository.findByChannelId(channelId)
                .flatMap(existing -> Mono.<ChannelConfigEntity>error(
                        new IntelliMateException(ErrorCode.VALIDATION_FAILED, "Channel already exists: " + channelId)))
                .switchIfEmpty(Mono.defer(() ->
                        configRepository.findByChannelIdAndDeleted(channelId, 1)
                                .flatMap(deleted -> {
                                    deleted.setEnabled(enabled);
                                    deleted.setConfigJson(toJson(config));
                                    deleted.setDeleted(0);
                                    deleted.setUpdatedAt(LocalDateTime.now());
                                    return configRepository.save(deleted);
                                })
                                .switchIfEmpty(Mono.defer(() -> {
                                    ChannelConfigEntity entity = new ChannelConfigEntity();
                                    entity.setChannelId(channelId);
                                    entity.setEnabled(enabled);
                                    entity.setConfigJson(toJson(config));
                                    entity.setDeleted(0);
                                    entity.setCreatedAt(LocalDateTime.now());
                                    entity.setUpdatedAt(LocalDateTime.now());
                                    return configRepository.save(entity);
                                }))
                ));
    }

    public Mono<ChannelConfigEntity> updateChannel(String channelId, boolean enabled, Map<String, Object> config) {
        return configRepository.findByChannelId(channelId)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.VALIDATION_FAILED, "Channel not found: " + channelId)))
                .flatMap(entity -> {
                    entity.setEnabled(enabled);
                    if (config != null) {
                        entity.setConfigJson(toJson(config));
                    }
                    entity.setUpdatedAt(LocalDateTime.now());
                    return configRepository.save(entity);
                });
    }

    public Mono<Void> deleteChannel(String channelId) {
        return configRepository.findByChannelId(channelId)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.VALIDATION_FAILED, "Channel not found: " + channelId)))
                .flatMap(entity -> {
                    entity.setDeleted(1);
                    entity.setUpdatedAt(LocalDateTime.now());
                    return configRepository.save(entity).then();
                });
    }

    public Mono<Void> connectChannel(String channelId) {
        return configRepository.findByChannelId(channelId)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.VALIDATION_FAILED, "Channel not found: " + channelId)))
                .flatMap(entity -> channelsManager.connectChannel(channelId, fromJson(entity.getConfigJson())))
                .doOnSuccess(v -> channelMetrics.recordChannelStatus(channelId, true))
                .doOnError(e -> channelMetrics.recordChannelStatus(channelId, false));
    }

    public Mono<Void> disconnectChannel(String channelId) {
        ChannelAdapter adapter = channelsManager.getAdapter(channelId);
        if (adapter == null) {
            return Mono.error(new IntelliMateException(ErrorCode.VALIDATION_FAILED, "No adapter for: " + channelId));
        }
        return adapter.disconnect()
                .doOnSuccess(v -> channelMetrics.recordChannelStatus(channelId, false));
    }

    public Mono<Optional<String>> getDefaultAgent(String channelId) {
        return configRepository.findByChannelId(channelId)
                .map(entity -> {
                    Object agent = fromJson(entity.getConfigJson()).get("defaultAgent");
                    if (agent instanceof String s && !s.isBlank()) {
                        return Optional.of(s);
                    }
                    return Optional.<String>empty();
                })
                .defaultIfEmpty(Optional.empty());
    }

    private ChannelInfoDto toDto(ChannelConfigEntity entity) {
        ChannelAdapter adapter = channelsManager.getAdapter(entity.getChannelId());
        ChannelStatus status = adapter != null ? adapter.getStatus() : ChannelStatus.DISCONNECTED;
        Map<String, Object> config = fromJson(entity.getConfigJson());
        return new ChannelInfoDto(
                entity.getChannelId(),
                status.name(),
                Boolean.TRUE.equals(entity.getEnabled()),
                maskSensitiveFields(config),
                adapter != null ? adapter.getConfigSchema() : null
        );
    }

    private Map<String, Object> maskSensitiveFields(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return config;
        }
        Map<String, Object> masked = new HashMap<>(config);
        masked.forEach((key, value) -> {
            String lower = key.toLowerCase();
            if ((lower.contains("secret") || lower.contains("token") || lower.contains("key"))
                    && value instanceof String s && s.length() > 6) {
                masked.put(key, s.substring(0, 6) + "****");
            }
        });
        return masked;
    }

    private String toJson(Map<String, Object> map) {
        if (map == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
