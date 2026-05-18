package com.atm.intellimate.agent.plan;

import java.util.List;

/**
 * SPI for Plan state management.
 * Defined in intellimate-agent, implemented in intellimate-gateway.
 */
public interface PlanOperations {

    record StepInput(String title, String description) {}

    record PlanResult(Long planId, String status, String message) {}

    record StepResult(Long planId, int stepIndex, String status, String message) {}

    record StepSnapshot(int index, String title, String description, String status) {}

    PlanResult createPlan(Long sessionId, String title, List<StepInput> steps);

    StepResult markStep(Long planId, int stepIndex, String status, String summary);

    StepResult addStep(Long planId, int afterIndex, String title, String description);

    StepResult removeStep(Long planId, int stepIndex, String reason);

    PlanResult completePlan(Long planId, String summary);

    List<StepSnapshot> getSteps(Long planId);

    boolean isPausedOrCancelled(Long planId);
}
