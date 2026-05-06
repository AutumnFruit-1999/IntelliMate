package com.atm.javaclaw.agent.runtime;

import java.util.List;

/**
 * Structured plan state for importance scoring during plan-mode runs.
 * Populated by the gateway from the same snapshot used to build the plan execution prompt.
 */
public record PlanExecutionAssessment(
        String currentStepDescription,
        List<String> completedStepDescriptions,
        List<String> pendingStepDescriptions
) {
}
