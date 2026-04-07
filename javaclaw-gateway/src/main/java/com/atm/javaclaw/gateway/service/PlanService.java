package com.atm.javaclaw.gateway.service;

import com.atm.javaclaw.gateway.entity.PlanEntity;
import com.atm.javaclaw.gateway.entity.PlanStepEntity;
import com.atm.javaclaw.gateway.repository.PlanRepository;
import com.atm.javaclaw.gateway.repository.PlanStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final PlanRepository planRepository;
    private final PlanStepRepository planStepRepository;

    public PlanService(PlanRepository planRepository, PlanStepRepository planStepRepository) {
        this.planRepository = planRepository;
        this.planStepRepository = planStepRepository;
    }

    // ===== WritePlanTool 回调 =====

    public record StepInput(String title, String description) {}

    public Mono<PlanEntity> createPlan(Long sessionId, String title, List<StepInput> steps) {
        PlanEntity plan = new PlanEntity();
        plan.setSessionId(sessionId);
        plan.setTitle(title);
        plan.setStatus("draft");
        plan.setCreatedAt(LocalDateTime.now());
        plan.setUpdatedAt(LocalDateTime.now());

        return planRepository.save(plan)
                .flatMap(savedPlan -> {
                    Long planId = savedPlan.getId();
                    log.info("createPlan: plan saved with id={}, sessionId={}", planId, sessionId);
                    AtomicInteger idx = new AtomicInteger(0);
                    int expectedCount = steps.size();
                    return Flux.fromIterable(steps)
                            .map(s -> {
                                PlanStepEntity step = new PlanStepEntity();
                                step.setPlanId(planId);
                                step.setStepIndex(idx.getAndIncrement());
                                step.setTitle(s.title());
                                step.setDescription(s.description());
                                step.setStatus("pending");
                                return step;
                            })
                            .concatMap(step -> planStepRepository.save(step)
                                    .doOnNext(saved -> log.debug(
                                            "createPlan: step saved id={}, planId={}, stepIndex={}",
                                            saved.getId(), saved.getPlanId(), saved.getStepIndex())))
                            .count()
                            .flatMap(savedCount -> {
                                if (savedCount != expectedCount) {
                                    log.error("createPlan: saved {}/{} steps for plan {}",
                                            savedCount, expectedCount, planId);
                                }
                                log.info("createPlan: saved {} steps for plan {}", savedCount, planId);
                                return planStepRepository.findByPlanIdOrderByStepIndex(planId)
                                        .collectList()
                                        .flatMap(verified -> {
                                            if (verified.size() != expectedCount) {
                                                log.error("createPlan: verification failed! Expected {} steps but found {} for plan {}. " +
                                                                "Found indices: {}",
                                                        expectedCount, verified.size(), planId,
                                                        verified.stream().map(PlanStepEntity::getStepIndex).toList());
                                                return Mono.error(new IllegalStateException(
                                                        "Plan step verification failed: expected " + expectedCount
                                                                + " but found " + verified.size()));
                                            }
                                            log.info("createPlan: verification OK, {} steps confirmed for plan {}",
                                                    verified.size(), planId);
                                            return Mono.just(savedPlan);
                                        });
                            });
                });
    }

    // ===== UpdatePlanTool 回调 =====

    public Mono<PlanStepEntity> markStep(Long planId, int stepIndex, String status, String summary) {
        return planStepRepository.findByPlanIdAndStepIndex(planId, stepIndex)
                .flatMap(step -> {
                    step.setStatus(status);
                    step.setResultSummary(summary);
                    if ("in_progress".equals(status)) {
                        step.setStartedAt(LocalDateTime.now());
                    }
                    if ("completed".equals(status) || "failed".equals(status)) {
                        step.setCompletedAt(LocalDateTime.now());
                    }
                    return planStepRepository.save(step);
                })
                .flatMap(step -> {
                    if ("failed".equals(status)) {
                        return pausePlan(planId).thenReturn(step);
                    }
                    if ("in_progress".equals(status)) {
                        return promotePlanToExecutingIfDraftOrApproved(planId).thenReturn(step);
                    }
                    return Mono.just(step);
                });
    }

    /**
     * When a step becomes in_progress, align parent plan status with actual execution
     * (draft/approved plans may still receive updatePlan from the agent).
     */
    private Mono<PlanEntity> promotePlanToExecutingIfDraftOrApproved(Long planId) {
        return planRepository.findById(planId)
                .flatMap(plan -> {
                    String s = plan.getStatus();
                    if ("draft".equals(s) || "approved".equals(s)) {
                        log.info("Plan {} auto-promoted to executing (was {})", planId, s);
                        plan.setStatus("executing");
                        plan.setUpdatedAt(LocalDateTime.now());
                        return planRepository.save(plan);
                    }
                    return Mono.just(plan);
                });
    }

    public Mono<PlanStepEntity> addStep(Long planId, int afterIndex, String title, String description) {
        return planStepRepository.findByPlanIdOrderByStepIndex(planId)
                .collectList()
                .flatMap(existingSteps -> {
                    int newIndex = afterIndex + 1;
                    Flux<PlanStepEntity> reindex = Flux.fromIterable(existingSteps)
                            .filter(s -> s.getStepIndex() >= newIndex)
                            .flatMap(s -> {
                                s.setStepIndex(s.getStepIndex() + 1);
                                return planStepRepository.save(s);
                            });

                    PlanStepEntity newStep = new PlanStepEntity();
                    newStep.setPlanId(planId);
                    newStep.setStepIndex(newIndex);
                    newStep.setTitle(title);
                    newStep.setDescription(description);
                    newStep.setStatus("pending");

                    return reindex.then(planStepRepository.save(newStep));
                })
                .flatMap(saved -> touchPlanUpdatedAt(planId).thenReturn(saved));
    }

    public Mono<Void> removeStep(Long planId, int stepIndex, String reason) {
        return planStepRepository.findByPlanIdAndStepIndex(planId, stepIndex)
                .flatMap(step -> planStepRepository.delete(step))
                .then(planStepRepository.findByPlanIdOrderByStepIndex(planId)
                        .collectList()
                        .flatMapMany(steps -> {
                            AtomicInteger idx = new AtomicInteger(0);
                            return Flux.fromIterable(steps)
                                    .flatMap(s -> {
                                        s.setStepIndex(idx.getAndIncrement());
                                        return planStepRepository.save(s);
                                    });
                        })
                        .then())
                .then(touchPlanUpdatedAt(planId))
                .then();
    }

    public Mono<PlanEntity> completePlan(Long planId, String summary) {
        return planStepRepository.findByPlanIdOrderByStepIndex(planId)
                .collectList()
                .flatMap(steps -> {
                    Flux<PlanStepEntity> skipPending = Flux.fromIterable(steps)
                            .filter(s -> "pending".equals(s.getStatus()))
                            .flatMap(s -> {
                                s.setStatus("skipped");
                                s.setResultSummary("随计划提前完成");
                                s.setCompletedAt(LocalDateTime.now());
                                return planStepRepository.save(s);
                            });
                    return skipPending.then(Mono.empty());
                })
                .then(updatePlanStatus(planId, "completed"));
    }

    // ===== 用户操作 =====

    public Mono<PlanEntity> approvePlan(Long planId, boolean approved, List<StepModification> mods) {
        if (!approved) {
            return updatePlanStatus(planId, "cancelled");
        }
        Mono<Void> applyMods = Mono.empty();
        if (mods != null && !mods.isEmpty()) {
            applyMods = Flux.fromIterable(mods)
                    .concatMap(mod -> applyStepModification(planId, mod))
                    .then();
        }
        return applyMods.then(planRepository.findById(planId)
                .flatMap(plan -> {
                    String st = plan.getStatus();
                    if ("executing".equals(st) || "paused".equals(st) || "completed".equals(st)
                            || "cancelled".equals(st)) {
                        return Mono.just(plan);
                    }
                    plan.setStatus("approved");
                    plan.setUpdatedAt(LocalDateTime.now());
                    return planRepository.save(plan);
                }));
    }

    public record StepModification(String type, Integer stepIndex, String title, String description, Integer newIndex) {}

    private Mono<Void> applyStepModification(Long planId, StepModification mod) {
        return switch (mod.type()) {
            case "edit" -> planStepRepository.findByPlanIdAndStepIndex(planId, mod.stepIndex())
                    .flatMap(step -> {
                        if (mod.title() != null) step.setTitle(mod.title());
                        if (mod.description() != null) step.setDescription(mod.description());
                        return planStepRepository.save(step);
                    }).then();
            case "add" -> {
                int afterIdx = mod.stepIndex() != null ? mod.stepIndex() : -1;
                yield addStep(planId, afterIdx, mod.title(), mod.description()).then();
            }
            case "remove" -> removeStep(planId, mod.stepIndex(), "用户审批时移除");
            default -> Mono.empty();
        };
    }

    public Mono<PlanEntity> pausePlan(Long planId) {
        return updatePlanStatus(planId, "paused");
    }

    public Mono<PlanEntity> resumePlan(Long planId) {
        return updatePlanStatus(planId, "executing");
    }

    public Mono<PlanEntity> cancelPlan(Long planId) {
        return planStepRepository.findByPlanIdOrderByStepIndex(planId)
                .collectList()
                .flatMap(steps -> {
                    Flux<PlanStepEntity> cancelPending = Flux.fromIterable(steps)
                            .filter(s -> "pending".equals(s.getStatus()) || "in_progress".equals(s.getStatus()))
                            .flatMap(s -> {
                                s.setStatus("skipped");
                                s.setResultSummary("计划已取消");
                                s.setCompletedAt(LocalDateTime.now());
                                return planStepRepository.save(s);
                            });
                    return cancelPending.then(Mono.empty());
                })
                .then(updatePlanStatus(planId, "cancelled"));
    }

    public Mono<PlanStepEntity> skipStep(Long planId, int stepIndex) {
        return planStepRepository.findByPlanIdAndStepIndex(planId, stepIndex)
                .flatMap(step -> {
                    step.setStatus("skipped");
                    step.setResultSummary("用户跳过");
                    step.setCompletedAt(LocalDateTime.now());
                    return planStepRepository.save(step);
                });
    }

    public Mono<List<PlanStepEntity>> reorderSteps(Long planId, List<Integer> newOrder) {
        return planStepRepository.findByPlanIdOrderByStepIndex(planId)
                .collectList()
                .flatMap(existingSteps -> {
                    if (newOrder.size() != existingSteps.size()) {
                        return Mono.error(new IllegalArgumentException("Order list size mismatch"));
                    }
                    Map<Integer, PlanStepEntity> byIndex = new java.util.HashMap<>();
                    for (PlanStepEntity s : existingSteps) {
                        byIndex.put(s.getStepIndex(), s);
                    }
                    List<Mono<PlanStepEntity>> saves = new ArrayList<>();
                    for (int i = 0; i < newOrder.size(); i++) {
                        PlanStepEntity step = byIndex.get(newOrder.get(i));
                        if (step == null) {
                            return Mono.error(new IllegalArgumentException("Invalid step index: " + newOrder.get(i)));
                        }
                        step.setStepIndex(i);
                        saves.add(planStepRepository.save(step));
                    }
                    return Flux.concat(saves).collectList();
                })
                .flatMap(saved -> touchPlanUpdatedAt(planId).thenReturn(saved));
    }

    public Mono<PlanStepEntity> modifyStep(Long planId, int stepIndex, String title, String description) {
        return planStepRepository.findByPlanIdAndStepIndex(planId, stepIndex)
                .flatMap(step -> {
                    if (title != null) step.setTitle(title);
                    if (description != null) step.setDescription(description);
                    return planStepRepository.save(step);
                });
    }

    // ===== 查询 =====

    public Mono<PlanEntity> getActivePlan(Long sessionId) {
        return planRepository.findActivePlanBySessionId(sessionId);
    }

    public Mono<PlanEntity> getPlanById(Long planId) {
        return planRepository.findById(planId);
    }

    public Flux<PlanStepEntity> getSteps(Long planId) {
        return planStepRepository.findByPlanIdOrderByStepIndex(planId);
    }

    public Mono<PlanStepEntity> getNextPendingStep(Long planId) {
        return planStepRepository.findNextPendingStep(planId);
    }

    // ===== 内部辅助 =====

    private Mono<PlanEntity> updatePlanStatus(Long planId, String status) {
        return planRepository.findById(planId)
                .flatMap(plan -> {
                    log.info("Plan {} status: {} -> {}", planId, plan.getStatus(), status);
                    plan.setStatus(status);
                    plan.setUpdatedAt(LocalDateTime.now());
                    return planRepository.save(plan);
                });
    }

    private Mono<Void> touchPlanUpdatedAt(Long planId) {
        return planRepository.findById(planId)
                .flatMap(plan -> {
                    plan.setUpdatedAt(LocalDateTime.now());
                    return planRepository.save(plan);
                })
                .then();
    }
}
