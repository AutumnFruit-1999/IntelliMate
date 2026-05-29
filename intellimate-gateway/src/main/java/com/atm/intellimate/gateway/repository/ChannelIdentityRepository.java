package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.ChannelIdentityEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ChannelIdentityRepository extends ReactiveCrudRepository<ChannelIdentityEntity, Long> {

    Mono<ChannelIdentityEntity> findByChannelIdAndExternalId(String channelId, String externalId);

    Flux<ChannelIdentityEntity> findByUserId(String userId);
}
