package com.atm.intellimate.agent.plan;

import reactor.core.publisher.Mono;
import java.util.List;

public interface PlanOperations {

    record StepInput(String title, String description, String verification) {}

    record PlanResult(long messageId, String status, String message) {}

    Mono<PlanResult> createPlan(long sessionId, String title, List<StepInput> steps);

    Mono<PlanResult> updateStep(long messageId, int stepIndex, String status, String resultSummary);

    Mono<PlanResult> completePlan(long messageId, String summary);

    Mono<Boolean> isPausedOrCancelled(long messageId);
}
