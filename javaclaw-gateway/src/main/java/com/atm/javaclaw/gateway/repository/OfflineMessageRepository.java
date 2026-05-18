package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.OfflineMessageEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface OfflineMessageRepository extends ReactiveCrudRepository<OfflineMessageEntity, Long> {

    Flux<OfflineMessageEntity> findByAgentIdAndDeliveredOrderByCreatedAt(Long agentId, Integer delivered);

    @Modifying
    @Query("UPDATE offline_message SET delivered = 1, delivered_at = NOW() WHERE id = :id")
    Mono<Integer> markDelivered(Long id);

    @Query("SELECT COUNT(*) FROM offline_message WHERE agent_id = :agentId AND delivered = 0")
    Mono<Long> countPending(Long agentId);

    @Modifying
    @Query("DELETE FROM offline_message WHERE delivered = 1 AND delivered_at < :before")
    Mono<Long> deleteDeliveredBefore(LocalDateTime before);
}
