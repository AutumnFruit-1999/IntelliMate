package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.entity.PlanEntity;
import com.atm.intellimate.gateway.entity.PlanStepEntity;
import com.atm.intellimate.gateway.repository.PlanRepository;
import com.atm.intellimate.gateway.repository.PlanStepRepository;
import com.atm.intellimate.gateway.repository.SessionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/plans")
public class PlanController {

    private final PlanRepository planRepository;
    private final PlanStepRepository planStepRepository;
    private final SessionRepository sessionRepository;

    public PlanController(PlanRepository planRepository,
                          PlanStepRepository planStepRepository,
                          SessionRepository sessionRepository) {
        this.planRepository = planRepository;
        this.planStepRepository = planStepRepository;
        this.sessionRepository = sessionRepository;
    }

    @GetMapping
    public Mono<List<Map<String, Object>>> listPlans(
            @RequestParam(required = false) String agentName,
            @RequestParam(required = false) String status) {

        boolean hasAgent = agentName != null && !agentName.isBlank();
        boolean hasStatus = status != null && !status.isBlank();

        Flux<PlanEntity> flux;
        if (hasAgent && hasStatus) {
            flux = planRepository.findByAgentNameAndStatusOrderByCreatedAtDesc(agentName, status);
        } else if (hasAgent) {
            flux = planRepository.findByAgentNameOrderByCreatedAtDesc(agentName);
        } else if (hasStatus) {
            flux = planRepository.findByStatusOrderByCreatedAtDesc(status);
        } else {
            flux = planRepository.findAllOrderByCreatedAtDesc();
        }

        return flux.collectList().flatMap(plans -> {
            Set<Long> sessionIds = plans.stream()
                    .map(PlanEntity::getSessionId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            Mono<Map<Long, String>> agentMapMono = sessionIds.isEmpty()
                    ? Mono.just(Collections.emptyMap())
                    : Flux.fromIterable(sessionIds)
                        .flatMap(sid -> sessionRepository.findById(sid)
                                .map(s -> Map.entry(sid, s.getAgentName())))
                        .collectMap(Map.Entry::getKey, Map.Entry::getValue);

            return agentMapMono.flatMap(agentMap ->
                    Flux.fromIterable(plans)
                            .flatMap(plan -> planStepRepository.findByPlanIdOrderByStepIndex(plan.getId())
                                    .collectList()
                                    .map(steps -> toPlanSummary(plan, steps, agentMap.get(plan.getSessionId()))))
                            .collectList()
                            .map(list -> {
                                list.sort((a, b) -> {
                                    String ca = (String) a.get("createdAt");
                                    String cb = (String) b.get("createdAt");
                                    if (ca == null && cb == null) return 0;
                                    if (ca == null) return 1;
                                    if (cb == null) return -1;
                                    return cb.compareTo(ca);
                                });
                                return list;
                            }));
        });
    }

    @GetMapping("/{planId}")
    public Mono<Map<String, Object>> getPlan(@PathVariable Long planId) {
        return planRepository.findById(planId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found")))
                .flatMap(plan -> planStepRepository.findByPlanIdOrderByStepIndex(planId)
                        .map(this::toStepMap)
                        .collectList()
                        .map(steps -> {
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("planId", plan.getId());
                            result.put("title", plan.getTitle());
                            result.put("status", plan.getStatus());
                            result.put("steps", steps);
                            return result;
                        }));
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

    @DeleteMapping("/batch")
    public Mono<Map<String, Object>> deletePlansBatch(@RequestBody List<Long> planIds) {
        if (planIds == null || planIds.isEmpty()) {
            return Mono.just(Map.of("deleted", 0));
        }
        return planStepRepository.deleteByPlanIdIn(planIds)
                .then(planRepository.deleteAllByIdIn(planIds))
                .thenReturn(Map.<String, Object>of("deleted", planIds.size()));
    }

    private Map<String, Object> toPlanSummary(PlanEntity plan, List<PlanStepEntity> steps, String agentName) {
        long completed = steps.stream().filter(s -> "completed".equals(s.getStatus())).count();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("planId", plan.getId());
        map.put("title", plan.getTitle());
        map.put("status", plan.getStatus());
        map.put("totalSteps", steps.size());
        map.put("completedSteps", completed);
        map.put("createdAt", plan.getCreatedAt() != null ? plan.getCreatedAt().toString() : null);
        map.put("agentName", agentName);
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
