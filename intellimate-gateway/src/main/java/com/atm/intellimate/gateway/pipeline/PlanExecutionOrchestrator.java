package com.atm.intellimate.gateway.pipeline;

import com.atm.intellimate.agent.runtime.PlanExecutionAssessment;
import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.core.prompt.PromptLoader;
import com.atm.intellimate.core.protocol.EventFrame;
import com.atm.intellimate.core.protocol.GatewayFrame;
import com.atm.intellimate.gateway.entity.PlanEntity;
import com.atm.intellimate.gateway.entity.PlanStepEntity;
import com.atm.intellimate.gateway.service.PlanService;
import com.atm.intellimate.memory.config.MemoryConfigProvider;
import com.atm.intellimate.memory.longterm.LongTermMemory;
import com.atm.intellimate.memory.model.ExtractedFact;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class PlanExecutionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(PlanExecutionOrchestrator.class);

    public record PlanExecutionPayload(String markdown, PlanExecutionAssessment assessment) {}

    private final PlanService planService;
    private final LongTermMemory longTermMemory;
    private final MemoryConfigProvider memoryConfigProvider;
    private final IntelliMateProperties properties;
    private final MeterRegistry meterRegistry;
    private final AtomicLong seqGenerator = new AtomicLong(0);

    public PlanExecutionOrchestrator(PlanService planService,
                                     @Autowired(required = false) LongTermMemory longTermMemory,
                                     @Autowired(required = false) MemoryConfigProvider memoryConfigProvider,
                                     IntelliMateProperties properties,
                                     @Autowired(required = false) MeterRegistry meterRegistry) {
        this.planService = planService;
        this.longTermMemory = longTermMemory;
        this.memoryConfigProvider = memoryConfigProvider;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    public Mono<PlanExecutionPayload> buildPlanExecutionPayload(Long planId) {
        if (meterRegistry != null) {
            meterRegistry.counter("plan.executions",
                    "status", "started").increment();
        }
        return planService.getSteps(planId)
                .collectList()
                .map(steps -> {
                    StringBuilder dynamicState = new StringBuilder();
                    dynamicState.append("**Plan ID: ").append(planId).append("**\n");
                    dynamicState.append("所有 `updatePlan` 调用必须使用此 Plan ID。\n\n");

                    List<PlanStepEntity> completed = steps.stream()
                            .filter(s -> "completed".equals(s.getStatus())).toList();
                    PlanStepEntity current = steps.stream()
                            .filter(s -> "in_progress".equals(s.getStatus())).findFirst().orElse(null);
                    if (current == null) {
                        current = steps.stream()
                                .filter(s -> "pending".equals(s.getStatus())).findFirst().orElse(null);
                    }
                    List<PlanStepEntity> pending = steps.stream()
                            .filter(s -> "pending".equals(s.getStatus())).toList();

                    if (!completed.isEmpty()) {
                        dynamicState.append("### 已完成的步骤:\n");
                        for (PlanStepEntity s : completed) {
                            dynamicState.append("- [x] Step ").append(s.getStepIndex())
                                    .append(": ").append(s.getTitle());
                            if (s.getResultSummary() != null) {
                                dynamicState.append(" → ").append(s.getResultSummary());
                            }
                            dynamicState.append('\n');
                        }
                        dynamicState.append('\n');
                    }

                    if (current != null) {
                        dynamicState.append("### 当前步骤 (").append(current.getStepIndex())
                                .append("/").append(steps.size()).append("):\n");
                        dynamicState.append("**").append(current.getTitle()).append("**\n");
                        if (current.getDescription() != null) {
                            dynamicState.append(current.getDescription()).append('\n');
                        }
                        dynamicState.append('\n');
                    }

                    if (!pending.isEmpty()) {
                        dynamicState.append("### 待执行的步骤:\n");
                        for (PlanStepEntity s : pending) {
                            dynamicState.append("- [ ] Step ").append(s.getStepIndex())
                                    .append(": ").append(s.getTitle()).append('\n');
                        }
                        dynamicState.append('\n');
                    }

                    String markdown = PromptLoader.format("prompts/plan-execution-context.md", dynamicState.toString());

                    String currentStepDesc = null;
                    if (current != null) {
                        StringBuilder cur = new StringBuilder();
                        if (current.getTitle() != null) {
                            cur.append(current.getTitle());
                        }
                        if (current.getDescription() != null && !current.getDescription().isBlank()) {
                            if (!cur.isEmpty()) {
                                cur.append(' ');
                            }
                            cur.append(current.getDescription());
                        }
                        currentStepDesc = cur.toString().trim();
                        if (currentStepDesc.isEmpty()) {
                            currentStepDesc = null;
                        }
                    }

                    List<String> completedDescs = completed.stream()
                            .map(PlanExecutionOrchestrator::stepTextForImportance)
                            .filter(s -> !s.isBlank())
                            .toList();
                    List<String> pendingDescs = pending.stream()
                            .map(PlanExecutionOrchestrator::stepTitleDescriptionOnly)
                            .filter(s -> !s.isBlank())
                            .toList();

                    PlanExecutionAssessment assessment = new PlanExecutionAssessment(
                            currentStepDesc, completedDescs, pendingDescs);
                    return new PlanExecutionPayload(markdown, assessment);
                });
    }

    private static String stepTextForImportance(PlanStepEntity s) {
        String base = stepTitleDescriptionOnly(s);
        if (s.getResultSummary() != null && !s.getResultSummary().isBlank()) {
            return base.isEmpty() ? s.getResultSummary().trim() : base + " " + s.getResultSummary().trim();
        }
        return base;
    }

    private static String stepTitleDescriptionOnly(PlanStepEntity s) {
        StringBuilder b = new StringBuilder();
        if (s.getTitle() != null && !s.getTitle().isBlank()) {
            b.append(s.getTitle().trim());
        }
        if (s.getDescription() != null && !s.getDescription().isBlank()) {
            if (!b.isEmpty()) {
                b.append(' ');
            }
            b.append(s.getDescription().trim());
        }
        return b.toString().trim();
    }

    /**
     * After the agent loop completes, check the plan state and emit any missing
     * lifecycle events. Handles the case where the LLM completes work for a step
     * but forgets to call updatePlan(markStep, completed) or completePlan.
     */
    public Mono<List<GatewayFrame>> syncPlanAfterExecution(Long planId, Long sessionId, String agentId, String userId) {
        return planService.getSteps(planId).collectList()
                .flatMap(steps -> {
                    List<GatewayFrame> syncEvents = new ArrayList<>();

                    List<PlanStepEntity> nonTerminal = steps.stream()
                            .filter(s -> {
                                String st = s.getStatus();
                                return "in_progress".equals(st) || "pending".equals(st);
                            })
                            .toList();

                    if (nonTerminal.isEmpty()) {
                        return checkAndCompletePlan(planId, steps, syncEvents, sessionId, agentId, userId);
                    }

                    log.info("Post-exec sync: auto-completing {} non-terminal steps for plan {}",
                            nonTerminal.size(), planId);

                    return Flux.fromIterable(nonTerminal)
                            .concatMap(step ->
                                    planService.markStep(planId, step.getStepIndex(), "completed",
                                                    "步骤已由系统自动标记完成")
                                            .doOnSuccess(v -> {
                                                if (meterRegistry != null) {
                                                    meterRegistry.counter("plan.steps.completed").increment();
                                                    if (step.getStartedAt() != null && step.getCompletedAt() != null) {
                                                        long durationMs = java.time.Duration.between(
                                                                step.getStartedAt(), step.getCompletedAt()).toMillis();
                                                        meterRegistry.timer("plan.step.duration",
                                                                "agent", agentId != null ? agentId : "default")
                                                                .record(java.time.Duration.ofMillis(durationMs));
                                                    }
                                                }
                                                syncEvents.add(new EventFrame("plan.step_done",
                                                        Map.of("planId", planId,
                                                                "stepIndex", step.getStepIndex(),
                                                                "status", "completed",
                                                                "resultSummary", "步骤已由系统自动标记完成"),
                                                        seqGenerator.incrementAndGet()));
                                            }))
                            .then(planService.getSteps(planId).collectList())
                            .flatMap(updatedSteps -> checkAndCompletePlan(planId, updatedSteps, syncEvents, sessionId, agentId, userId));
                });
    }

    private Mono<List<GatewayFrame>> checkAndCompletePlan(Long planId, List<PlanStepEntity> steps,
                                                           List<GatewayFrame> syncEvents, Long sessionId,
                                                           String agentId, String userId) {
        boolean allTerminal = steps.stream().allMatch(s -> {
            String st = s.getStatus();
            return "completed".equals(st) || "failed".equals(st) || "skipped".equals(st);
        });
        if (allTerminal && !steps.isEmpty()) {
            log.info("Post-exec sync: all steps terminal, auto-completing plan {}", planId);
            return planService.completePlan(planId, null)
                    .map(plan -> {
                        if (meterRegistry != null) {
                            meterRegistry.counter("plan.completed",
                                    "agent", agentId != null ? agentId : "default",
                                    "status", "completed").increment();
                        }
                        syncEvents.add(new EventFrame("plan.completed",
                                Map.of("planId", planId, "status", "completed"),
                                seqGenerator.incrementAndGet()));
                        schedulePlanCompletionMemoryExtraction(sessionId, planId, agentId, userId);
                        return syncEvents;
                    });
        }
        return Mono.just(syncEvents);
    }

    /**
     * Persists a comprehensive procedural + episodic memory after a plan finishes.
     * Format: "问题 + 解决步骤 + 结果" as one procedural entry, plus a brief episodic entry.
     */
    public void schedulePlanCompletionMemoryExtraction(Long sessionId, Long planId, String agentId, String userId) {
        if (longTermMemory == null || memoryConfigProvider == null || planId == null || sessionId == null) {
            return;
        }
        String effectiveAgentId = agentId != null && !agentId.isBlank() ? agentId : "default";
        String effectiveUserId = userId != null && !userId.isBlank() ? userId : "default";
        memoryConfigProvider.resolve()
                .filter(config -> config.longTermEnabled())
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("Plan completion memory extraction skipped: long-term memory disabled (planId={})", planId);
                    return Mono.empty();
                }))
                .flatMap(config -> Mono.zip(
                        planService.getPlanById(planId).defaultIfEmpty(new PlanEntity()),
                        planService.getSteps(planId).collectList()))
                .flatMap(tuple -> {
                    PlanEntity plan = tuple.getT1();
                    List<PlanStepEntity> steps = tuple.getT2();
                    String title = plan.getTitle();
                    if (title == null || title.isBlank()) {
                        title = "Plan " + planId;
                    }

                    long completedCount = steps.stream().filter(s -> "completed".equals(s.getStatus())).count();
                    long failedCount = steps.stream().filter(s -> "failed".equals(s.getStatus())).count();
                    String outcome = failedCount == 0 ? "成功" : "部分完成(" + completedCount + "成功/" + failedCount + "失败)";

                    String procedural = buildPlanProceduralSummary(title, steps, outcome);
                    procedural = "适用场景：" + title + "\n" + procedural;
                    String episodic = "完成计划: " + title + ", 共" + steps.size() + "步, " + outcome;

                    Mono<Void> proceduralMono = longTermMemory.store(
                            new ExtractedFact("procedural", procedural, 0.7f), effectiveUserId, effectiveAgentId);
                    Mono<Void> episodicMono = longTermMemory.store(
                            new ExtractedFact("episodic", episodic, 0.6f), effectiveUserId, effectiveAgentId);
                    return proceduralMono.then(episodicMono);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        unused -> {
                        },
                        e -> log.warn("Plan completion memory extraction failed for planId={}: {}",
                                planId, e.getMessage(), e));
    }

    private static String buildPlanProceduralSummary(String title, List<PlanStepEntity> steps, String outcome) {
        StringBuilder sb = new StringBuilder();
        sb.append("问题：").append(title).append('\n');
        sb.append("解决步骤：\n");
        for (PlanStepEntity step : steps) {
            sb.append(step.getStepIndex()).append(". ");
            if (step.getTitle() != null && !step.getTitle().isBlank()) {
                sb.append(step.getTitle().trim());
            }
            if (step.getResultSummary() != null && !step.getResultSummary().isBlank()) {
                sb.append(" → ").append(step.getResultSummary().trim());
            } else if (step.getStatus() != null) {
                sb.append(" → ").append(step.getStatus());
            }
            sb.append('\n');
        }
        sb.append("最终结果：").append(outcome);
        return sb.toString();
    }
}
