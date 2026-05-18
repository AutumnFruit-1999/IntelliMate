package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.HeartbeatLogEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface HeartbeatLogRepository extends ReactiveCrudRepository<HeartbeatLogEntity, Long> {

    @Query("SELECT * FROM heartbeat_log WHERE agent_id = :agentId ORDER BY triggered_at DESC LIMIT 1")
    Mono<HeartbeatLogEntity> findLatestByAgentId(Long agentId);

    @Query("SELECT * FROM heartbeat_log WHERE agent_id = :agentId AND state = :state AND DATE(triggered_at) = CURDATE() ORDER BY triggered_at DESC LIMIT 1")
    Mono<HeartbeatLogEntity> findTodayByAgentIdAndState(Long agentId, String state);

    @Query("SELECT * FROM heartbeat_log WHERE agent_id = :agentId ORDER BY triggered_at DESC LIMIT :limit")
    Flux<HeartbeatLogEntity> findRecentByAgentId(Long agentId, int limit);

    @Modifying
    @Query("DELETE FROM heartbeat_log WHERE created_at < :before")
    Mono<Long> deleteOlderThan(LocalDateTime before);
}
