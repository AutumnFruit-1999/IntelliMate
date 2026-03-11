package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.ChannelConfigEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface ChannelConfigRepository extends ReactiveCrudRepository<ChannelConfigEntity, Long> {

    Mono<ChannelConfigEntity> findByChannelIdAndDeleted(String channelId, Integer deleted);

    default Mono<ChannelConfigEntity> findByChannelId(String channelId) {
        return findByChannelIdAndDeleted(channelId, 0);
    }
}
