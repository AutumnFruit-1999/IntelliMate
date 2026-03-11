package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.PairingRequestEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface PairingRequestRepository extends ReactiveCrudRepository<PairingRequestEntity, Long> {

    Mono<PairingRequestEntity> findByPairingCodeAndStatus(String pairingCode, String status);

    Mono<PairingRequestEntity> findByChannelIdAndSenderIdAndStatus(String channelId, String senderId, String status);
}
