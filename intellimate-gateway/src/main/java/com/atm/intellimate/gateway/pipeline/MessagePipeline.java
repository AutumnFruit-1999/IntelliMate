package com.atm.intellimate.gateway.pipeline;

import com.atm.intellimate.agent.runtime.AgentRunRequest;
import com.atm.intellimate.agent.runtime.AgentRuntime;
import com.atm.intellimate.agent.runtime.PlanExecutionAssessment;
import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.core.model.SessionKey;
import com.atm.intellimate.core.prompt.PromptLoader;
import com.atm.intellimate.core.model.SessionMetadata;
import com.atm.intellimate.core.protocol.EventFrame;
import com.atm.intellimate.core.protocol.GatewayFrame;
import com.atm.intellimate.core.protocol.RequestFrame;
import com.atm.intellimate.core.protocol.ResponseFrame;
import com.atm.intellimate.gateway.audit.AuditService;
import com.atm.intellimate.gateway.config.AgentConfigService;
import com.atm.intellimate.gateway.config.ResolvedAgentConfig;
import com.atm.intellimate.gateway.entity.PlanEntity;
import com.atm.intellimate.gateway.entity.PlanStepEntity;
import com.atm.intellimate.gateway.entity.SessionEntity;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.repository.SessionRepository;
import com.atm.intellimate.gateway.service.PlanService;
import com.atm.intellimate.gateway.session.SessionManager;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import com.atm.intellimate.memory.config.MemoryConfigProvider;
import com.atm.intellimate.memory.longterm.LongTermMemory;
import com.atm.intellimate.memory.model.ExtractedFact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MessagePipeline {

    private static final Logger log = LoggerFactory.getLogger(MessagePipeline.class);

    private final SessionManager sessionManager;
    private final MessageConverter messageConverter;
    private final AgentEventMapper agentEventMapper;
    private final AgentRuntime agentRuntime;
    private final IntelliMateProperties properties;
    private final AgentConfigService agentConfigService;
    private final CommandHandler commandHandler;
    private final AuditService auditService;
    private final PlanService planService;
    private final LongTermMemory longTermMemory;
    private final MemoryConfigProvider memoryConfigProvider;
    private final SessionRegistry sessionRegistry;
    private final SessionRepository sessionRepository;
    private final AtomicLong seqGenerator = new AtomicLong(0);
    private final ConcurrentMap<String, Long> wsSessionToDbSession = new ConcurrentHashMap<>();

    private record PlanExecutionPayload(String markdown, PlanExecutionAssessment assessment) {}

    public MessagePipeline(SessionManager sessionManager,
                           MessageConverter messageConverter,
                           AgentEventMapper agentEventMapper,
                           AgentRuntime agentRuntime,
                           IntelliMateProperties properties,
                           AgentConfigService agentConfigService,
                           CommandHandler commandHandler,
                           AuditService auditService,
                           PlanService planService,
                           SessionRegistry sessionRegistry,
                           SessionRepository sessionRepository,
                           @Autowired(required = false) LongTermMemory longTermMemory,
                           @Autowired(required = false) MemoryConfigProvider memoryConfigProvider) {
        this.sessionManager = sessionManager;
        this.messageConverter = messageConverter;
        this.agentEventMapper = agentEventMapper;
        this.agentRuntime = agentRuntime;
        this.properties = properties;
        this.agentConfigService = agentConfigService;
        this.commandHandler = commandHandler;
        this.auditService = auditService;
        this.planService = planService;
        this.sessionRegistry = sessionRegistry;
        this.sessionRepository = sessionRepository;
        this.longTermMemory = longTermMemory;
        this.memoryConfigProvider = memoryConfigProvider;
    }

    @SuppressWarnings("unchecked")
    public Flux<GatewayFrame> processRequest(RequestFrame request, String wsSessionId) {
        if ("conversation.cancel".equals(request.method())) {
            return processCancelRequest(request, wsSessionId);
        }

        if ("conversation.approve_tool".equals(request.method())) {
            return processApprovalResponse(request);
        }

        if (request.method() != null && request.method().startsWith("plan.")) {
            return processPlanRequest(request, wsSessionId);
        }

        if (!"conversation.message".equals(request.method())) {
            return Flux.just(ResponseFrame.failure(request.id(), "Unknown method: " + request.method()));
        }

        Map<String, Object> params = (Map<String, Object>) request.params();
        String userText = (String) params.getOrDefault("text", "");
        String channelId = (String) params.getOrDefault("channelId", "webchat");
        String contextType = (String) params.getOrDefault("contextType", "dm");
        String baseContextId = (String) params.getOrDefault("contextId", wsSessionId);
        boolean forcePlan = Boolean.TRUE.equals(params.get("forcePlan"));

        String agentName = (String) params.getOrDefault("agentName", "");
        if (agentName.isBlank()) {
            agentName = properties.getAgent().getName();
        }
        sessionRegistry.bindAgent(wsSessionId, agentName);

        String contextId = baseContextId + "::" + agentName;

        SessionKey sessionKey = new SessionKey(channelId, contextType, contextId);
        SessionMetadata metadata = new SessionMetadata(
                agentName, null,
                channelId, contextType, contextId
        );

        return sessionManager.getOrCreate(sessionKey, metadata)
                .flatMapMany(session -> {
                    wsSessionToDbSession.put(wsSessionId, session.getId());
                    if (CommandHandler.isCommand(userText)) {
                        return auditService.log("command", wsSessionId, session.getId(), userText)
                                .thenMany(commandHandler.handle(userText, session, request.id()));
                    }
                    return processMessageStreaming(session, userText, request.id(), wsSessionId, forcePlan);
                })
                .onErrorResume(e -> {
                    log.error("Error processing request id={}: {}", request.id(), e.getMessage(), e);
                    return Flux.just(ResponseFrame.failure(request.id(), e.getMessage()));
                });
    }

    private Flux<GatewayFrame> processMessageStreaming(
            SessionEntity session, String userText, String requestId, String wsSessionId,
            boolean forcePlan) {

        return planService.getActivePlan(session.getId())
                .defaultIfEmpty(new PlanEntity())
                .flatMapMany(activePlan -> {
                    Long planId = activePlan.getId();
                    String planStatus = activePlan.getStatus();
                    boolean planExecuting = planId != null
                            && ("executing".equals(planStatus) || "approved".equals(planStatus));

                    TranscriptMessageEntity userMsg = new TranscriptMessageEntity();
                    userMsg.setRole("user");
                    userMsg.setContent(userText);
                    userMsg.setCreatedAt(LocalDateTime.now());
                    if (planExecuting) {
                        userMsg.setPlanId(planId);
                    }

                    final Long effectivePlanId = planExecuting ? planId : null;

                    return sessionManager.appendMessage(session.getId(), userMsg)
                            .then(auditService.log("user_message", wsSessionId, session.getId(),
                                    userText.length() > 200 ? userText.substring(0, 200) + "..." : userText))
                            .then(Mono.zip(
                                    messageConverter.loadHistory(session.getId(), effectivePlanId).collectList(),
                                    agentConfigService.resolve(session.getAgentName()),
                                    planExecuting
                                            ? buildPlanExecutionPayload(planId)
                                            : Mono.just(new PlanExecutionPayload("", null))
                            ))
                            .flatMapMany(tuple -> {
                                List<Message> messages = messageConverter.convertToAiMessages(tuple.getT1());
                                ResolvedAgentConfig resolved = tuple.getT2();
                                PlanExecutionPayload planPayload = tuple.getT3();
                                String planContext = planPayload.markdown().isEmpty() ? null : planPayload.markdown();

                                String effectiveUserId = session.getContextId() != null && !session.getContextId().isBlank()
                                        ? session.getContextId() : "default";
                                AgentRunRequest runRequest = new AgentRunRequest(
                                        session.getId(),
                                        effectiveUserId,
                                        resolved.agent(),
                                        userText,
                                        messages,
                                        resolved.toolsEnabled(),
                                        resolved.mcpToolsEnabled(),
                                        resolved.skillsEnabled(),
                                        resolved.skillGroupsEnabled(),
                                        planContext,
                                        forcePlan,
                                        effectivePlanId,
                                        planPayload.assessment(),
                                        null,
                                        resolved.bridgeNode()
                                );

                                StringBuilder fullResponse = new StringBuilder();

                                Flux<GatewayFrame> events = agentRuntime.dispatch(runRequest)
                                        .concatMap(event -> agentEventMapper.mapAgentEvent(
                                                event, requestId, fullResponse, session,
                                                this::schedulePlanCompletionMemoryExtraction))
                                        .doOnSubscribe(sub -> agentRuntime.registerWsRun(wsSessionId, sub))
                                        .doFinally(sig -> agentRuntime.unregisterWsRun(wsSessionId));

                                Flux<GatewayFrame> tail = Flux.defer(() -> {
                                    String completeText = fullResponse.toString();

                                    TranscriptMessageEntity assistantMsg = new TranscriptMessageEntity();
                                    assistantMsg.setRole("assistant");
                                    assistantMsg.setContent(completeText);
                                    assistantMsg.setCreatedAt(LocalDateTime.now());
                                    if (planExecuting) {
                                        assistantMsg.setPlanId(planId);
                                    }

                                    Mono<Void> saveMsgMono = sessionManager.appendMessage(session.getId(), assistantMsg)
                                            .then(auditService.log("agent_response", "agent", session.getId(),
                                                    "length=" + completeText.length()));

                                    String memoryAgentId = resolveAgentId(session);
                                    String memoryUserId = resolveUserId(session);
                                    Mono<List<GatewayFrame>> syncMono;
                                    if (planExecuting && effectivePlanId != null) {
                                        syncMono = syncPlanAfterExecution(effectivePlanId, session.getId(), memoryAgentId, memoryUserId);
                                    } else {
                                        syncMono = planService.getActivePlan(session.getId())
                                                .filter(p -> "executing".equals(p.getStatus())
                                                        || "approved".equals(p.getStatus()))
                                                .flatMap(p -> syncPlanAfterExecution(p.getId(), session.getId(), memoryAgentId, memoryUserId))
                                                .defaultIfEmpty(List.of());
                                    }

                                    return saveMsgMono
                                            .then(syncMono)
                                            .flatMapMany(planSyncEvents -> {
                                                List<GatewayFrame> frames = new ArrayList<>(planSyncEvents);
                                                frames.add(ResponseFrame.success(requestId, Map.of("text", completeText)));
                                                return Flux.fromIterable(frames);
                                            });
                                });

                                return Flux.concat(events, tail);
                            });
                });
    }

    private Mono<PlanExecutionPayload> buildPlanExecutionPayload(Long planId) {
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
                            .map(MessagePipeline::stepTextForImportance)
                            .filter(s -> !s.isBlank())
                            .toList();
                    List<String> pendingDescs = pending.stream()
                            .map(MessagePipeline::stepTitleDescriptionOnly)
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
    private Mono<List<GatewayFrame>> syncPlanAfterExecution(Long planId, Long sessionId, String agentId, String userId) {
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
                                            .doOnSuccess(v -> syncEvents.add(new EventFrame("plan.step_done",
                                                    Map.of("planId", planId,
                                                            "stepIndex", step.getStepIndex(),
                                                            "status", "completed",
                                                            "resultSummary", "步骤已由系统自动标记完成"),
                                                    seqGenerator.incrementAndGet()))))
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
    private void schedulePlanCompletionMemoryExtraction(Long sessionId, Long planId, String agentId, String userId) {
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

    @SuppressWarnings("unchecked")
    private Flux<GatewayFrame> processPlanRequest(RequestFrame request, String wsSessionId) {
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
                                            wsSessionToDbSession.put(wsSessionId, session.getId());
                                            return processMessageStreaming(session, "开始执行计划", request.id(), wsSessionId, false);
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

    @SuppressWarnings("unchecked")
    private Flux<GatewayFrame> processCancelRequest(RequestFrame request, String wsSessionId) {
        try {
            Map<String, Object> params = (Map<String, Object>) request.params();
            String requestId = (String) params.get("requestId");
            log.info("Cancelling request {} for wsSession {}", requestId, wsSessionId);

            agentRuntime.cancelByWsSession(wsSessionId);

            return Flux.just(ResponseFrame.success(request.id(), Map.of("cancelled", true)));
        } catch (Exception e) {
            log.warn("Cancel request failed: {}", e.getMessage());
            return Flux.just(ResponseFrame.failure(request.id(), "Cancel failed: " + e.getMessage()));
        }
    }

    @SuppressWarnings("unchecked")
    private Flux<GatewayFrame> processApprovalResponse(RequestFrame request) {
        try {
            Map<String, Object> params = (Map<String, Object>) request.params();
            Long sessionId = ((Number) params.get("sessionId")).longValue();
            String toolCallId = (String) params.get("toolCallId");
            boolean approved = Boolean.TRUE.equals(params.get("approved"));
            String modifiedArguments = (String) params.getOrDefault("modifiedArguments", null);

            log.info("Processing approval response: sessionId={}, toolCallId={}, approved={}", sessionId, toolCallId, approved);
            agentRuntime.resolveApproval(sessionId, toolCallId, approved, modifiedArguments);

            return Flux.just(ResponseFrame.success(request.id(), Map.of("status", "ok")));
        } catch (Exception e) {
            log.error("Failed to process approval response: {}", e.getMessage(), e);
            return Flux.just(ResponseFrame.failure(request.id(), "Invalid approval request: " + e.getMessage()));
        }
    }


    /**
     * Called when WebSocket disconnects. Flushes deferred episodic memory for the associated session.
     */
    public void onWebSocketDisconnect(String wsSessionId) {
        Long sessionId = wsSessionToDbSession.remove(wsSessionId);
        if (sessionId != null) {
            agentRuntime.flushDeferredEpisodicMemory(sessionId);
        }
    }

    private String resolveAgentId(SessionEntity session) {
        String name = session.getAgentName();
        if (name == null || name.isBlank()) {
            return properties.getAgent().getName();
        }
        return name;
    }

    private String resolveUserId(SessionEntity session) {
        String contextId = session.getContextId();
        return contextId != null && !contextId.isBlank() ? contextId : "default";
    }

}
