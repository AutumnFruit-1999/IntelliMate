package com.atm.intellimate.gateway.pipeline;

import com.atm.intellimate.agent.runtime.AgentRuntime;
import com.atm.intellimate.core.protocol.EventFrame;
import com.atm.intellimate.core.protocol.GatewayFrame;
import com.atm.intellimate.core.protocol.RequestFrame;
import com.atm.intellimate.core.protocol.ResponseFrame;
import com.atm.intellimate.gateway.entity.SessionEntity;
import com.atm.intellimate.gateway.repository.SessionRepository;
import com.atm.intellimate.gateway.service.PlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

@Component
public class PlanRequestHandler {

    private static final Logger log = LoggerFactory.getLogger(PlanRequestHandler.class);

    @FunctionalInterface
    public interface MessageStreamingExecutor {
        Flux<GatewayFrame> execute(SessionEntity session, String userText, String requestId,
                                   String wsSessionId, boolean forcePlan);
    }

    private final PlanService planService;
    private final AgentRuntime agentRuntime;
    private final SessionRepository sessionRepository;
    private final AtomicLong seqGenerator = new AtomicLong(0);

    public PlanRequestHandler(PlanService planService,
                              AgentRuntime agentRuntime,
                              SessionRepository sessionRepository) {
        this.planService = planService;
        this.agentRuntime = agentRuntime;
        this.sessionRepository = sessionRepository;
    }

    @SuppressWarnings("unchecked")
    public Flux<GatewayFrame> processPlanRequest(RequestFrame request,
                                                 String wsSessionId,
                                                 MessageStreamingExecutor messageStreamingExecutor,
                                                 BiConsumer<String, Long> bindWsSessionToDbSession) {
        try {
            Map<String, Object> params = (Map<String, Object>) request.params();
            return switch (request.method()) {
                case "plan.approve_and_execute" -> {
                    Long planId = ((Number) params.get("planId")).longValue();
                    yield planService.approvePlan(planId, true, null)
                            .flatMap(approvedPlan -> planService.resumePlan(planId))
                            .flatMapMany(executingPlan -> {
                                EventFrame statusEvt = new EventFrame("plan.status_changed",
                                        Map.of("planId", executingPlan.getId(), "status", executingPlan.getStatus()),
                                        seqGenerator.incrementAndGet());

                                Long sessionId = executingPlan.getSessionId();
                                Flux<GatewayFrame> agentExecution = sessionRepository.findById(sessionId)
                                        .flatMapMany(session -> {
                                            bindWsSessionToDbSession.accept(wsSessionId, session.getId());
                                            return messageStreamingExecutor.execute(
                                                    session, "开始执行计划", request.id(), wsSessionId, false);
                                        });

                                return Flux.concat(Flux.<GatewayFrame>just(statusEvt), agentExecution);
                            })
                            .onErrorResume(e -> {
                                log.error("plan.approve_and_execute failed for planId={}: {}", planId, e.getMessage(), e);
                                return planService.getPlanById(planId)
                                        .map(p -> ResponseFrame.failure(request.id(),
                                                e.getMessage(),
                                                Map.of("currentStatus", p.getStatus())))
                                        .defaultIfEmpty(ResponseFrame.failure(request.id(), e.getMessage()))
                                        .flux();
                            });
                }
                case "plan.approve" -> {
                    Long planId = ((Number) params.get("planId")).longValue();
                    boolean approved = Boolean.TRUE.equals(params.get("approved"));
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> rawMods = (List<Map<String, Object>>) params.get("modifications");
                    List<PlanService.StepModification> mods = rawMods != null
                            ? rawMods.stream().map(m -> new PlanService.StepModification(
                                    (String) m.get("type"),
                                    m.get("stepIndex") != null ? ((Number) m.get("stepIndex")).intValue() : null,
                                    (String) m.get("title"),
                                    (String) m.get("description"),
                                    m.get("newIndex") != null ? ((Number) m.get("newIndex")).intValue() : null
                            )).toList()
                            : null;
                    yield planService.approvePlan(planId, approved, mods)
                            .flatMapMany(plan -> {
                                EventFrame statusEvt = new EventFrame("plan.status_changed",
                                        Map.of("planId", plan.getId(), "status", plan.getStatus()),
                                        seqGenerator.incrementAndGet());
                                return Flux.<GatewayFrame>just(statusEvt, ResponseFrame.success(request.id(), Map.of("status", plan.getStatus())));
                            });
                }
                case "plan.pause" -> {
                    Long planId = ((Number) params.get("planId")).longValue();
                    yield planService.pausePlan(planId)
                            .flatMapMany(plan -> {
                                agentRuntime.signalPlanPaused(planId);
                                EventFrame evt = new EventFrame("plan.status_changed",
                                        Map.of("planId", plan.getId(), "status", plan.getStatus()),
                                        seqGenerator.incrementAndGet());
                                return Flux.<GatewayFrame>just(evt, ResponseFrame.success(request.id(), Map.of("status", "paused")));
                            });
                }
                case "plan.resume" -> {
                    Long planId = ((Number) params.get("planId")).longValue();
                    yield planService.resumePlan(planId)
                            .flatMapMany(plan -> {
                                EventFrame evt = new EventFrame("plan.status_changed",
                                        Map.of("planId", plan.getId(), "status", plan.getStatus()),
                                        seqGenerator.incrementAndGet());
                                return Flux.<GatewayFrame>just(evt, ResponseFrame.success(request.id(), Map.of("status", "executing")));
                            });
                }
                case "plan.cancel" -> {
                    Long planId = ((Number) params.get("planId")).longValue();
                    yield planService.cancelPlan(planId)
                            .flatMapMany(plan -> {
                                agentRuntime.signalPlanPaused(planId);
                                EventFrame evt = new EventFrame("plan.status_changed",
                                        Map.of("planId", plan.getId(), "status", plan.getStatus()),
                                        seqGenerator.incrementAndGet());
                                return Flux.<GatewayFrame>just(evt, ResponseFrame.success(request.id(), Map.of("status", "cancelled")));
                            });
                }
                case "plan.skip_step" -> {
                    Long planId = ((Number) params.get("planId")).longValue();
                    int stepIndex = ((Number) params.get("stepIndex")).intValue();
                    yield planService.skipStep(planId, stepIndex)
                            .flatMapMany(step -> {
                                EventFrame evt = new EventFrame("plan.step_done",
                                        Map.of("planId", planId, "stepIndex", stepIndex,
                                                "status", "skipped", "resultSummary", "用户跳过"),
                                        seqGenerator.incrementAndGet());
                                return Flux.<GatewayFrame>just(evt, ResponseFrame.success(request.id(), Map.of("status", "skipped")));
                            });
                }
                case "plan.modify_step" -> {
                    Long planId = ((Number) params.get("planId")).longValue();
                    int stepIndex = ((Number) params.get("stepIndex")).intValue();
                    String title = (String) params.get("title");
                    String description = (String) params.get("description");
                    yield planService.modifyStep(planId, stepIndex, title, description)
                            .flatMapMany(step -> Flux.just(
                                    ResponseFrame.success(request.id(), Map.of("status", "modified"))
                            ));
                }
                case "plan.add_step" -> {
                    Long planId = ((Number) params.get("planId")).longValue();
                    int afterIndex = ((Number) params.get("afterIndex")).intValue();
                    String title = (String) params.get("title");
                    String description = (String) params.getOrDefault("description", "");
                    yield planService.addStep(planId, afterIndex, title, (String) description)
                            .flatMapMany(newStep -> planService.getSteps(planId).collectList()
                                    .flatMapMany(steps -> {
                                        List<Map<String, Object>> stepList = steps.stream()
                                                .map(s -> Map.<String, Object>of(
                                                        "index", s.getStepIndex(),
                                                        "title", s.getTitle(),
                                                        "description", s.getDescription() != null ? s.getDescription() : ""
                                                )).toList();
                                        EventFrame adjustedEvt = new EventFrame("plan.adjusted",
                                                Map.of("planId", planId, "action", "addStep", "currentSteps", stepList),
                                                seqGenerator.incrementAndGet());
                                        return Flux.<GatewayFrame>just(adjustedEvt, ResponseFrame.success(request.id(), Map.of("status", "added")));
                                    }));
                }
                case "plan.reorder_steps" -> {
                    Long planId = ((Number) params.get("planId")).longValue();
                    @SuppressWarnings("unchecked")
                    List<Integer> newOrder = ((List<Number>) params.get("newOrder")).stream()
                            .map(Number::intValue).toList();
                    yield planService.reorderSteps(planId, newOrder)
                            .flatMapMany(reordered -> planService.getSteps(planId).collectList()
                                    .flatMapMany(steps -> {
                                        List<Map<String, Object>> stepList = steps.stream()
                                                .map(s -> Map.<String, Object>of(
                                                        "index", s.getStepIndex(),
                                                        "title", s.getTitle(),
                                                        "description", s.getDescription() != null ? s.getDescription() : ""
                                                )).toList();
                                        EventFrame adjustedEvt = new EventFrame("plan.adjusted",
                                                Map.of("planId", planId, "action", "reorder", "currentSteps", stepList),
                                                seqGenerator.incrementAndGet());
                                        return Flux.<GatewayFrame>just(adjustedEvt, ResponseFrame.success(request.id(), Map.of("status", "reordered")));
                                    }));
                }
                default -> Flux.just(ResponseFrame.failure(request.id(), "Unknown plan method: " + request.method()));
            };
        } catch (Exception e) {
            log.error("Failed to process plan request {}: {}", request.method(), e.getMessage(), e);
            return Flux.just(ResponseFrame.failure(request.id(), "Invalid plan request: " + e.getMessage()));
        }
    }
}
