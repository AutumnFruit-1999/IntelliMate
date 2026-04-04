package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.PlanEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PlanRepository extends ReactiveCrudRepository<PlanEntity, Long> {

    @Query("SELECT * FROM plan WHERE session_id = :sessionId AND status IN ('draft','approved','executing','paused') ORDER BY created_at DESC LIMIT 1")
    Mono<PlanEntity> findActivePlanBySessionId(Long sessionId);

    Flux<PlanEntity> findBySessionIdOrderByCreatedAtDesc(Long sessionId);

    @Query("SELECT p.* FROM plan p JOIN session s ON p.session_id = s.id WHERE s.agent_name = :agentName ORDER BY p.created_at DESC")
    Flux<PlanEntity> findByAgentNameOrderByCreatedAtDesc(String agentName);

    @Query("SELECT p.* FROM plan p ORDER BY p.created_at DESC")
    Flux<PlanEntity> findAllOrderByCreatedAtDesc();
}
