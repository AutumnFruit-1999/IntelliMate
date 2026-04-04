package com.atm.javaclaw.gateway.service;

import com.atm.javaclaw.agent.plan.PlanOperations;
import com.atm.javaclaw.gateway.entity.PlanEntity;
import com.atm.javaclaw.gateway.entity.PlanStepEntity;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class PlanOperationsImpl implements PlanOperations {

    private final PlanService planService;

    public PlanOperationsImpl(PlanService planService) {
        this.planService = planService;
    }

    @Override
    public PlanResult createPlan(Long sessionId, String title, List<StepInput> steps) {
        List<PlanService.StepInput> serviceSteps = steps.stream()
                .map(s -> new PlanService.StepInput(s.title(), s.description()))
                .toList();
        PlanEntity plan = planService.createPlan(sessionId, title, serviceSteps).block();
        if (plan == null) {
            return new PlanResult(null, "error", "Failed to create plan");
        }
        return new PlanResult(plan.getId(), plan.getStatus(),
                "Plan created with " + steps.size() + " steps. Awaiting user approval.");
    }

    @Override
    public StepResult markStep(Long planId, int stepIndex, String status, String summary) {
        PlanStepEntity step = planService.markStep(planId, stepIndex, status, summary).block();
        if (step == null) {
            return new StepResult(planId, stepIndex, "error", "Step not found");
        }
        return new StepResult(planId, step.getStepIndex(), step.getStatus(),
                "Step " + stepIndex + " marked as " + status);
    }

    @Override
    public StepResult addStep(Long planId, int afterIndex, String title, String description) {
        PlanStepEntity step = planService.addStep(planId, afterIndex, title, description).block();
        if (step == null) {
            return new StepResult(planId, afterIndex + 1, "error", "Failed to add step");
        }
        return new StepResult(planId, step.getStepIndex(), step.getStatus(),
                "Step added at index " + step.getStepIndex());
    }

    @Override
    public StepResult removeStep(Long planId, int stepIndex, String reason) {
        planService.removeStep(planId, stepIndex, reason).block();
        return new StepResult(planId, stepIndex, "removed",
                "Step " + stepIndex + " removed" + (reason != null ? ": " + reason : ""));
    }

    @Override
    public PlanResult completePlan(Long planId, String summary) {
        PlanEntity plan = planService.completePlan(planId, summary).block();
        if (plan == null) {
            return new PlanResult(planId, "error", "Failed to complete plan");
        }
        return new PlanResult(planId, plan.getStatus(), "Plan completed: " + (summary != null ? summary : ""));
    }

    @Override
    public List<StepSnapshot> getSteps(Long planId) {
        return planService.getSteps(planId)
                .map(s -> new StepSnapshot(s.getStepIndex(), s.getTitle(), s.getDescription(), s.getStatus()))
                .collectList()
                .block();
    }

    @Override
    public boolean isPausedOrCancelled(Long planId) {
        if (planId == null) return false;
        PlanEntity plan = planService.getPlanById(planId).block();
        if (plan == null) return false;
        String status = plan.getStatus();
        return "paused".equals(status) || "cancelled".equals(status);
    }
}
