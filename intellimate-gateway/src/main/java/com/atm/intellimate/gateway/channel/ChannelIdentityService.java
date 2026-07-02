package com.atm.intellimate.gateway.channel;

import com.atm.intellimate.gateway.entity.ChannelIdentityEntity;
import com.atm.intellimate.gateway.repository.ChannelIdentityRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class ChannelIdentityService {

    private final ChannelIdentityRepository identityRepository;

    public ChannelIdentityService(ChannelIdentityRepository identityRepository) {
        this.identityRepository = identityRepository;
    }

    /**
     * 解析外部渠道身份 → IntelliMate userId。
     * 如果不存在则自动创建新用户。
     */
    public Mono<String> resolveUserId(String channelId, String externalId, String externalName) {
        return identityRepository.findByChannelIdAndExternalId(channelId, externalId)
                .map(ChannelIdentityEntity::getUserId)
                .switchIfEmpty(createNewIdentity(channelId, externalId, externalName));
    }

    /**
     * 将外部渠道身份绑定到已存在的 userId（用于跨渠道账号关联）。
     */
    public Mono<Void> bindIdentity(String userId, String channelId, String externalId, String externalName) {
        return identityRepository.findByChannelIdAndExternalId(channelId, externalId)
                .flatMap(existing -> {
                    existing.setUserId(userId);
                    if (externalName != null) {
                        existing.setExternalName(externalName);
                    }
                    return identityRepository.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    ChannelIdentityEntity entity = new ChannelIdentityEntity();
                    entity.setUserId(userId);
                    entity.setChannelId(channelId);
                    entity.setExternalId(externalId);
                    entity.setExternalName(externalName);
                    entity.setBoundAt(LocalDateTime.now());
                    return identityRepository.save(entity)
                            .onErrorResume(DuplicateKeyException.class, e ->
                                    identityRepository.findByChannelIdAndExternalId(channelId, externalId)
                                            .flatMap(dup -> {
                                                dup.setUserId(userId);
                                                if (externalName != null) dup.setExternalName(externalName);
                                                return identityRepository.save(dup);
                                            }));
                }))
                .then();
    }

    /**
     * 检查某个外部身份是否已被一个真实 Web 用户绑定。
     * 只有当该 externalId 的 userId 同时关联了 webchat 身份时才视为"已绑定"。
     * 自动创建的独立身份（首次互动时生成）不算"已绑定"，允许被覆盖。
     */
    public Mono<String> findBoundUserId(String channelId, String externalId) {
        return identityRepository.findByChannelIdAndExternalId(channelId, externalId)
                .flatMap(entity ->
                        identityRepository.findByUserId(entity.getUserId())
                                .any(id -> "webchat".equals(id.getChannelId()))
                                .map(hasWebchat -> hasWebchat ? entity.getUserId() : "")
                )
                .defaultIfEmpty("");
    }

    public Flux<ChannelIdentityEntity> listByUserId(String userId) {
        return identityRepository.findByUserId(userId);
    }

    public Mono<Void> unbind(Long identityId) {
        return identityRepository.deleteById(identityId);
    }

    private Mono<String> createNewIdentity(String channelId, String externalId, String externalName) {
        String userId = "user_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        ChannelIdentityEntity entity = new ChannelIdentityEntity();
        entity.setUserId(userId);
        entity.setChannelId(channelId);
        entity.setExternalId(externalId);
        entity.setExternalName(externalName);
        entity.setBoundAt(LocalDateTime.now());
        return identityRepository.save(entity)
                .map(ChannelIdentityEntity::getUserId)
                .onErrorResume(DuplicateKeyException.class, e ->
                        identityRepository.findByChannelIdAndExternalId(channelId, externalId)
                                .map(ChannelIdentityEntity::getUserId));
    }
}
