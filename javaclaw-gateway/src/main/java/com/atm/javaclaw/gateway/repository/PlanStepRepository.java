package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.PlanStepEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface PlanStepRepository extends ReactiveCrudRepository<PlanStepEntity, Long> {

    Flux<PlanStepEntity> findByPlanIdOrderByStepIndex(Long planId);

    @Query("SELECT * FROM plan_step WHERE plan_id = :planId AND status = 'pending' ORDER BY step_index ASC LIMIT 1")
    Mono<PlanStepEntity> findNextPendingStep(Long planId);

    @Query("SELECT * FROM plan_step WHERE plan_id = :planId AND step_index = :stepIndex")
    Mono<PlanStepEntity> findByPlanIdAndStepIndex(Long planId, int stepIndex);

    Mono<Void> deleteByPlanId(Long planId);
}
