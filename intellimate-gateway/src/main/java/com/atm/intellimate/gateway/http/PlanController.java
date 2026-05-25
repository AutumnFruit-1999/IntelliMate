package com.atm.intellimate.gateway.http;

import com.atm.intellimate.core.exception.ErrorCode;
import com.atm.intellimate.core.exception.IntelliMateException;
import com.atm.intellimate.gateway.dto.ApiResponse;
import com.atm.intellimate.gateway.dto.PlanDTO;
import com.atm.intellimate.gateway.dto.PlanStepDTO;
import com.atm.intellimate.gateway.entity.PlanEntity;
import com.atm.intellimate.gateway.entity.PlanStepEntity;
import com.atm.intellimate.gateway.repository.PlanRepository;
import com.atm.intellimate.gateway.repository.PlanStepRepository;
import com.atm.intellimate.gateway.repository.SessionRepository;
import org.springframework.web.bind.annotation.*;
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
    public Mono<ApiResponse<List<PlanDTO>>> listPlans(
            @RequestParam(required = false) String agentName,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long sessionId,
            @RequestParam(required = false, defaultValue = "false") boolean includeSteps,
            @RequestParam(required = false, defaultValue = "50") int limit) {

        Flux<PlanEntity> flux;
        if (sessionId != null) {
            flux = planRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
            if (status != null && !status.isBlank()) {
                String[] statuses = status.split(",");
                Set<String> allowed = Set.of(statuses);
                flux = flux.filter(p -> allowed.contains(p.getStatus()));
            }
        } else {
            boolean hasAgent = agentName != null && !agentName.isBlank();
            boolean hasStatus = status != null && !status.isBlank();
            if (hasAgent && hasStatus) {
                flux = planRepository.findByAgentNameAndStatusOrderByCreatedAtDesc(agentName, status);
            } else if (hasAgent) {
                flux = planRepository.findByAgentNameOrderByCreatedAtDesc(agentName);
            } else if (hasStatus) {
                flux = planRepository.findByStatusOrderByCreatedAtDesc(status);
            } else {
                flux = planRepository.findAllOrderByCreatedAtDesc();
            }
        }

        return flux.take(limit).collectList().flatMap(plans -> {
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
                                    .map(steps -> PlanDTO.summaryFromEntity(
                                            plan, steps, agentMap.get(plan.getSessionId()), includeSteps)))
                            .collectList()
                            .map(list -> {
                                list.sort((a, b) -> {
                                    if (a.createdAt() == null && b.createdAt() == null) return 0;
                                    if (a.createdAt() == null) return 1;
                                    if (b.createdAt() == null) return -1;
                                    return b.createdAt().compareTo(a.createdAt());
                                });
                                return ApiResponse.ok(list);
                            }));
        });
    }

    @GetMapping("/{planId}")
    public Mono<ApiResponse<PlanDTO>> getPlan(@PathVariable Long planId) {
        return planRepository.findById(planId)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.PLAN_NOT_FOUND)))
                .flatMap(plan -> planStepRepository.findByPlanIdOrderByStepIndex(planId)
                        .collectList()
                        .map(steps -> ApiResponse.ok(PlanDTO.fromEntityWithSteps(plan, steps, null))));
    }

    @GetMapping("/{planId}/steps")
    public Mono<ApiResponse<List<PlanStepDTO>>> getPlanSteps(@PathVariable Long planId) {
        return planRepository.findById(planId)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.PLAN_NOT_FOUND)))
                .flatMap(plan -> planStepRepository.findByPlanIdOrderByStepIndex(planId)
                        .map(PlanStepDTO::fromEntity)
                        .collectList()
                        .map(ApiResponse::ok));
    }

    @DeleteMapping("/{planId}")
    public Mono<ApiResponse<Map<String, Object>>> deletePlan(@PathVariable Long planId) {
        return planRepository.findById(planId)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.PLAN_NOT_FOUND)))
                .flatMap(plan -> planStepRepository.deleteByPlanId(planId)
                        .then(planRepository.delete(plan))
                        .thenReturn(ApiResponse.ok(Map.<String, Object>of("deleted", true, "planId", planId))));
    }

    @DeleteMapping("/batch")
    public Mono<ApiResponse<Map<String, Object>>> deletePlansBatch(@RequestBody List<Long> planIds) {
        if (planIds == null || planIds.isEmpty()) {
            return Mono.just(ApiResponse.ok(Map.of("deleted", 0)));
        }
        return planStepRepository.deleteByPlanIdIn(planIds)
                .then(planRepository.deleteAllByIdIn(planIds))
                .thenReturn(ApiResponse.ok(Map.<String, Object>of("deleted", planIds.size())));
    }
}
