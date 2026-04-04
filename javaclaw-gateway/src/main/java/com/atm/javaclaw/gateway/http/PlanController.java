package com.atm.javaclaw.gateway.http;

import com.atm.javaclaw.gateway.entity.PlanEntity;
import com.atm.javaclaw.gateway.entity.PlanStepEntity;
import com.atm.javaclaw.gateway.repository.PlanRepository;
import com.atm.javaclaw.gateway.repository.PlanStepRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanRepository planRepository;
    private final PlanStepRepository planStepRepository;

    public PlanController(PlanRepository planRepository, PlanStepRepository planStepRepository) {
        this.planRepository = planRepository;
        this.planStepRepository = planStepRepository;
    }

    @GetMapping
    public Mono<List<Map<String, Object>>> listPlans(@RequestParam(required = false) String agentName) {
        var flux = (agentName != null && !agentName.isBlank())
                ? planRepository.findByAgentNameOrderByCreatedAtDesc(agentName)
                : planRepository.findAllOrderByCreatedAtDesc();

        return flux.flatMap(plan -> planStepRepository.findByPlanIdOrderByStepIndex(plan.getId())
                        .collectList()
                        .map(steps -> toPlanSummary(plan, steps)))
                .collectList();
    }

    @GetMapping("/{planId}/steps")
    public Mono<List<Map<String, Object>>> getPlanSteps(@PathVariable Long planId) {
        return planRepository.findById(planId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")))
                .flatMap(plan -> planStepRepository.findByPlanIdOrderByStepIndex(planId)
                        .map(this::toStepMap)
                        .collectList());
    }

    @DeleteMapping("/{planId}")
    public Mono<Map<String, Object>> deletePlan(@PathVariable Long planId) {
        return planRepository.findById(planId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")))
                .flatMap(plan -> planStepRepository.deleteByPlanId(planId)
                        .then(planRepository.delete(plan))
                        .thenReturn(Map.<String, Object>of("deleted", true, "planId", planId)));
    }

    private Map<String, Object> toPlanSummary(PlanEntity plan, List<PlanStepEntity> steps) {
        long completed = steps.stream().filter(s -> "completed".equals(s.getStatus())).count();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("planId", plan.getId());
        map.put("title", plan.getTitle());
        map.put("status", plan.getStatus());
        map.put("totalSteps", steps.size());
        map.put("completedSteps", completed);
        map.put("createdAt", plan.getCreatedAt() != null ? plan.getCreatedAt().toString() : null);
        return map;
    }

    private Map<String, Object> toStepMap(PlanStepEntity step) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("index", step.getStepIndex());
        map.put("title", step.getTitle());
        map.put("description", step.getDescription());
        map.put("status", step.getStatus());
        map.put("resultSummary", step.getResultSummary());
        return map;
    }
}
