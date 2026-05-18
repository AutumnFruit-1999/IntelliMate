package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.AllowlistEntryEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AllowlistEntryRepository extends ReactiveCrudRepository<AllowlistEntryEntity, Long> {

    Flux<AllowlistEntryEntity> findByChannelIdAndDeleted(String channelId, Integer deleted);

    Mono<AllowlistEntryEntity> findByChannelIdAndSenderIdAndDeleted(String channelId, String senderId, Integer deleted);

    default Mono<Boolean> isSenderAllowed(String channelId, String senderId) {
        return findByChannelIdAndSenderIdAndDeleted(channelId, senderId, 0).hasElement();
    }
}
