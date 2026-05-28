package com.atm.intellimate.agent.runtime;

import com.atm.intellimate.agent.plan.PlanOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tracks the active plan step during execution. When the LLM calls non-plan
 * tools without first marking a step as in_progress via updatePlan, this
 * tracker auto-starts the next pending step so the frontend receives proper
 * step lifecycle events regardless of LLM behavior.
 */
public class PlanStepTracker {
    private static final Logger tlog = LoggerFactory.getLogger(PlanStepTracker.class);

    private final Long planId;
    private final PlanOperations ops;
    private Integer activeStepIndex;
    private final Set<Integer> autoStartedSteps = new HashSet<>();

    PlanStepTracker(Long planId, PlanOperations ops) {
        this.planId = planId;
        this.ops = ops;
    }

    /**
     * If no step is currently active and the turn contains non-plan tool calls
     * (without the LLM also calling updatePlan markStep in_progress), auto-start
     * the next pending step and return the events to emit ahead of ToolCall events.
     */
    List<AgentEvent> ensureStepActive(List<AssistantMessage.ToolCall> toolCalls) {
        if (activeStepIndex != null) return List.of();

        boolean hasNonPlanTools = toolCalls.stream()
                .anyMatch(tc -> !"writePlan".equals(tc.name()) && !"updatePlan".equals(tc.name()));
        if (!hasNonPlanTools) return List.of();

        boolean llmStartingStep = toolCalls.stream()
                .anyMatch(tc -> "updatePlan".equals(tc.name())
                        && tc.arguments() != null
                        && tc.arguments().contains("\"in_progress\""));
        if (llmStartingStep) return List.of();

        try {
            List<PlanOperations.StepSnapshot> steps = ops.getSteps(planId);
            PlanOperations.StepSnapshot next = steps.stream()
                    .filter(s -> "pending".equals(s.status()))
                    .findFirst().orElse(null);
            if (next == null) return List.of();

            int idx = next.index();
            PlanOperations.StepResult markResult = ops.markStep(planId, idx, "in_progress", null);
            if ("error".equals(markResult.status())) {
                tlog.warn("Failed to auto-start step {} for plan {}: {}",
                        idx, planId, markResult.message());
                return List.of();
            }
            activeStepIndex = idx;
            autoStartedSteps.add(idx);
            tlog.info("Auto-started step {} for plan {}", idx, planId);

            return List.of(
                    new AgentEvent.PlanStatusChanged(planId, "executing"),
                    new AgentEvent.PlanStepStart(planId, idx, next.title()));
        } catch (Exception e) {
            tlog.warn("Failed to auto-start step for plan {}: {}", planId, e.getMessage());
            return List.of();
        }
    }

    boolean isAutoStartedStep(int stepIndex) {
        return autoStartedSteps.contains(stepIndex);
    }

    void onStepStart(int stepIndex) {
        activeStepIndex = stepIndex;
    }

    void onStepDone(int stepIndex) {
        if (activeStepIndex != null && activeStepIndex == stepIndex) {
            activeStepIndex = null;
        }
        autoStartedSteps.remove(stepIndex);
    }

    void onPlanCompleted() {
        activeStepIndex = null;
    }
}
