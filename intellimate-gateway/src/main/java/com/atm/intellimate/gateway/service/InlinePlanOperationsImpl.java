package com.atm.intellimate.gateway.service;

import com.atm.intellimate.agent.plan.PlanOperations;
import com.atm.intellimate.agent.tools.AgentSessionContext;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.*;

@Component
public class InlinePlanOperationsImpl implements PlanOperations {

    private final InlinePlanService inlinePlanService;
    private final AgentSessionContext sessionContext;

    public InlinePlanOperationsImpl(InlinePlanService inlinePlanService,
                                     AgentSessionContext sessionContext) {
        this.inlinePlanService = inlinePlanService;
        this.sessionContext = sessionContext;
    }

    @Override
    public Mono<PlanResult> createPlan(long sessionId, String title, List<StepInput> steps) {
        List<Map<String, Object>> stepMaps = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            StepInput s = steps.get(i);
            Map<String, Object> stepMap = new LinkedHashMap<>();
            stepMap.put("index", i);
            stepMap.put("title", s.title());
            stepMap.put("description", s.description());
            stepMap.put("verification", s.verification());
            stepMap.put("status", "pending");
            stepMap.put("resultSummary", null);
            stepMaps.add(stepMap);
        }

        return inlinePlanService.createPlanMessage(sessionId, title, stepMaps)
                .map(entity -> new PlanResult(entity.getId(), "draft", "Plan created successfully"));
    }

    @Override
    public Mono<PlanResult> updateStep(long messageId, int stepIndex, String status, String resultSummary) {
        Mono<Long> resolvedId;
        if (messageId == 0) {
            Long sessionId = sessionContext.getCurrentSessionId();
            if (sessionId == null) {
                return Mono.error(new IllegalStateException("No active session context"));
            }
            resolvedId = inlinePlanService.getActivePlan(sessionId)
                    .map(entity -> entity.getId())
                    .switchIfEmpty(Mono.error(new IllegalStateException("No active plan in current session")));
        } else {
            resolvedId = Mono.just(messageId);
        }

        return resolvedId.flatMap(id ->
                inlinePlanService.updateStepStatus(id, stepIndex, status, resultSummary)
                        .thenReturn(new PlanResult(id, status, "Step " + stepIndex + " updated to " + status)));
    }

    @Override
    public Mono<PlanResult> completePlan(long messageId, String summary) {
        Mono<Long> resolvedId;
        if (messageId == 0) {
            Long sessionId = sessionContext.getCurrentSessionId();
            if (sessionId == null) {
                return Mono.error(new IllegalStateException("No active session context"));
            }
            resolvedId = inlinePlanService.getActivePlan(sessionId)
                    .map(entity -> entity.getId())
                    .switchIfEmpty(Mono.error(new IllegalStateException("No active plan in current session")));
        } else {
            resolvedId = Mono.just(messageId);
        }

        return resolvedId.flatMap(id ->
                inlinePlanService.completePlan(id, summary)
                        .thenReturn(new PlanResult(id, "completed", "Plan completed")));
    }

    @Override
    public Mono<Boolean> isPausedOrCancelled(long messageId) {
        return inlinePlanService.getPlanData(messageId)
                .map(metadata -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> plan = (Map<String, Object>) metadata.get("plan");
                    String status = (String) plan.get("status");
                    return "paused".equals(status) || "cancelled".equals(status);
                })
                .defaultIfEmpty(false);
    }
}
