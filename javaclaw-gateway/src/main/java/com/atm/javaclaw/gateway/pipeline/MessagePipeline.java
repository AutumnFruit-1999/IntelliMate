package com.atm.javaclaw.gateway.pipeline;

import com.atm.javaclaw.agent.runtime.AgentEvent;
import com.atm.javaclaw.agent.runtime.AgentRunRequest;
import com.atm.javaclaw.agent.runtime.AgentRuntime;
import com.atm.javaclaw.core.config.JavaClawProperties;
import com.atm.javaclaw.core.model.SessionKey;
import com.atm.javaclaw.core.model.SessionMetadata;
import com.atm.javaclaw.core.protocol.EventFrame;
import com.atm.javaclaw.core.protocol.GatewayFrame;
import com.atm.javaclaw.core.protocol.RequestFrame;
import com.atm.javaclaw.core.protocol.ResponseFrame;
import com.atm.javaclaw.gateway.audit.AuditService;
import com.atm.javaclaw.gateway.config.AgentConfigService;
import com.atm.javaclaw.gateway.config.ResolvedAgentConfig;
import com.atm.javaclaw.gateway.entity.PlanEntity;
import com.atm.javaclaw.gateway.entity.PlanStepEntity;
import com.atm.javaclaw.gateway.entity.SessionEntity;
import com.atm.javaclaw.gateway.entity.TranscriptMessageEntity;
import com.atm.javaclaw.gateway.service.PlanService;
import com.atm.javaclaw.gateway.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MessagePipeline {

    private static final Logger log = LoggerFactory.getLogger(MessagePipeline.class);

    private final SessionManager sessionManager;
    private final AgentRuntime agentRuntime;
    private final JavaClawProperties properties;
    private final AgentConfigService agentConfigService;
    private final CommandHandler commandHandler;
    private final AuditService auditService;
    private final PlanService planService;
    private final AtomicLong seqGenerator = new AtomicLong(0);

    public MessagePipeline(SessionManager sessionManager,
                           AgentRuntime agentRuntime,
                           JavaClawProperties properties,
                           AgentConfigService agentConfigService,
                           CommandHandler commandHandler,
                           AuditService auditService,
                           PlanService planService) {
        this.sessionManager = sessionManager;
        this.agentRuntime = agentRuntime;
        this.properties = properties;
        this.agentConfigService = agentConfigService;
        this.commandHandler = commandHandler;
        this.auditService = auditService;
        this.planService = planService;
    }

    @SuppressWarnings("unchecked")
    public Flux<GatewayFrame> processRequest(RequestFrame request, String wsSessionId) {
        if ("conversation.approve_tool".equals(request.method())) {
            return processApprovalResponse(request);
        }

        if (request.method() != null && request.method().startsWith("plan.")) {
            return processPlanRequest(request);
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

        String contextId = baseContextId + "::" + agentName;

        SessionKey sessionKey = new SessionKey(channelId, contextType, contextId);
        SessionMetadata metadata = new SessionMetadata(
                agentName, null,
                channelId, contextType, contextId
        );

        return sessionManager.getOrCreate(sessionKey, metadata)
                .flatMapMany(session -> {
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
                    boolean planExecuting = planId != null && "executing".equals(activePlan.getStatus());

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
                                    loadHistory(session.getId(), effectivePlanId).collectList(),
                                    agentConfigService.resolve(session.getAgentName()),
                                    planExecuting
                                            ? buildPlanExecutionContext(planId).defaultIfEmpty("")
                                            : Mono.just("")
                            ))
                            .flatMapMany(tuple -> {
                                List<Message> messages = convertToAiMessages(tuple.getT1());
                                ResolvedAgentConfig resolved = tuple.getT2();
                                String planContext = tuple.getT3();

                                AgentRunRequest runRequest = new AgentRunRequest(
                                        session.getId(),
                                        resolved.agent(),
                                        userText,
                                        messages,
                                        resolved.toolsEnabled(),
                                        resolved.mcpToolsEnabled(),
                                        resolved.skillsEnabled(),
                                        planContext.isEmpty() ? null : planContext,
                                        forcePlan,
                                        effectivePlanId
                                );

                                StringBuilder fullResponse = new StringBuilder();

                                Flux<GatewayFrame> events = agentRuntime.dispatch(runRequest)
                                        .concatMap(event -> mapAgentEvent(event, requestId, fullResponse));

                                Flux<GatewayFrame> tail = Flux.defer(() -> {
                                    String completeText = fullResponse.toString();

                                    TranscriptMessageEntity assistantMsg = new TranscriptMessageEntity();
                                    assistantMsg.setRole("assistant");
                                    assistantMsg.setContent(completeText);
                                    assistantMsg.setCreatedAt(LocalDateTime.now());
                                    if (planExecuting) {
                                        assistantMsg.setPlanId(planId);
                                    }

                                    return sessionManager.appendMessage(session.getId(), assistantMsg)
                                            .then(auditService.log("agent_response", "agent", session.getId(),
                                                    "length=" + completeText.length()))
                                            .thenMany(Flux.just(
                                                    ResponseFrame.success(requestId, Map.of("text", completeText))
                                            ));
                                });

                                return Flux.concat(events, tail);
                            });
                });
    }

    private Flux<TranscriptMessageEntity> loadHistory(Long sessionId, Long planId) {
        int limit = properties.getAgent().getHistoryLimit();
        if (planId != null) {
            return sessionManager.getPlanHistory(sessionId, planId, limit);
        }
        return sessionManager.getChatHistory(sessionId, limit);
    }

    private Mono<String> buildPlanExecutionContext(Long planId) {
        return planService.getSteps(planId)
                .collectList()
                .map(steps -> {
                    StringBuilder sb = new StringBuilder("## PLAN EXECUTION CONTEXT\n\n");
                    sb.append("你正在执行当前会话中的活动计划（用户可能已通过「批准并执行」或已开始推进步骤）。\n\n");

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
                        sb.append("### 已完成的步骤:\n");
                        for (PlanStepEntity s : completed) {
                            sb.append("- [x] Step ").append(s.getStepIndex())
                                    .append(": ").append(s.getTitle());
                            if (s.getResultSummary() != null) {
                                sb.append(" → ").append(s.getResultSummary());
                            }
                            sb.append('\n');
                        }
                        sb.append('\n');
                    }

                    if (current != null) {
                        sb.append("### 当前步骤 (").append(current.getStepIndex() + 1)
                                .append("/").append(steps.size()).append("):\n");
                        sb.append("**").append(current.getTitle()).append("**\n");
                        if (current.getDescription() != null) {
                            sb.append(current.getDescription()).append('\n');
                        }
                        sb.append('\n');
                    }

                    if (!pending.isEmpty()) {
                        sb.append("### 待执行的步骤:\n");
                        for (PlanStepEntity s : pending) {
                            sb.append("- [ ] Step ").append(s.getStepIndex())
                                    .append(": ").append(s.getTitle()).append('\n');
                        }
                        sb.append('\n');
                    }

                    sb.append("请专注于完成当前步骤。完成后调用 `updatePlan` 的 `markStep` 标记完成。\n");
                    sb.append("如果发现需要调整计划，可以使用 addStep / removeStep / completePlan。\n\n");
                    sb.append("### 输出与总结要求\n");
                    sb.append("- 每步完成时：在 `markStep` 的 `resultSummary` 中写**面向用户的简短总结**（1～3 句，说明做了什么、关键结果）。\n");
                    sb.append("- 每步结束后：在回复中用自然语言简要确认本步结果，再开始下一步（除非已无待办步骤）。\n");
                    sb.append("- **步骤衔接**：在进入下一步、调用 `markStep(..., in_progress)` 之前，必须在**对话正文**中先用 1～2 句话复述上一步已完成的内容与结果（可与上一步的 `resultSummary` 一致，但必须对用户可见），然后再开始当前步的工具调用。\n");
                    sb.append("- 全部完成时：调用 `completePlan` 并在 `resultSummary` 或最终回复中给出**整体总结**。\n");
                    return sb.toString();
                });
    }

    @SuppressWarnings("unchecked")
    private Flux<GatewayFrame> processPlanRequest(RequestFrame request) {
        try {
            Map<String, Object> params = (Map<String, Object>) request.params();
            return switch (request.method()) {
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

    private Flux<GatewayFrame> mapAgentEvent(
            AgentEvent event, String requestId, StringBuilder fullResponse) {

        return switch (event) {
            case AgentEvent.TurnStart ts -> Flux.just(new EventFrame(
                    "agent.turn_start",
                    Map.of("turn", ts.turn(),
                           "maxTurns", ts.maxTurns(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.TextChunk tc -> {
                fullResponse.append(tc.text());
                yield Flux.just(new EventFrame(
                        "agent.chunk",
                        Map.of("text", tc.text(), "requestId", requestId),
                        seqGenerator.incrementAndGet()
                ));
            }

            case AgentEvent.ToolCall tc -> Flux.just(new EventFrame(
                    "agent.tool_call",
                    Map.of("toolCallId", tc.toolCallId(),
                           "name", tc.name(),
                           "arguments", tc.arguments(),
                           "turn", tc.turn(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.ToolResult tr -> Flux.just(new EventFrame(
                    "agent.tool_result",
                    Map.of("toolCallId", tr.toolCallId(),
                           "name", tr.name(),
                           "result", tr.result(),
                           "success", tr.success(),
                           "turn", tr.turn(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.Done done -> {
                fullResponse.delete(0, fullResponse.length());
                fullResponse.append(done.fullText());
                yield Flux.just(new EventFrame(
                        "agent.done",
                        Map.of("text", done.fullText(),
                               "totalTurns", done.totalTurns(),
                               "requestId", requestId),
                        seqGenerator.incrementAndGet()
                ));
            }

            case AgentEvent.Error err -> Flux.just(
                    ResponseFrame.failure(requestId, err.message())
            );

            case AgentEvent.ApprovalRequired ar -> Flux.just(new EventFrame(
                    "agent.approval_required",
                    Map.of("toolCallId", ar.toolCallId(),
                           "name", ar.toolName(),
                           "arguments", ar.arguments(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.ApprovalResponse ignored -> Flux.empty();

            case AgentEvent.PlanCreated pc -> Flux.just(new EventFrame(
                    "plan.created",
                    Map.of("planId", pc.planId(),
                           "title", pc.title(),
                           "steps", pc.steps().stream()
                                   .map(s -> Map.of("index", s.index(), "title", s.title(),
                                           "description", s.description() != null ? s.description() : ""))
                                   .toList(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.PlanAwaitingApproval pa -> Flux.just(new EventFrame(
                    "plan.awaiting_approval",
                    Map.of("planId", pa.planId(), "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.PlanStatusChanged psc -> Flux.just(new EventFrame(
                    "plan.status_changed",
                    Map.of("planId", psc.planId(), "status", psc.status(), "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.PlanStepStart pss -> Flux.just(new EventFrame(
                    "plan.step_start",
                    Map.of("planId", pss.planId(), "stepIndex", pss.stepIndex(),
                           "title", pss.title(), "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.PlanStepDone psd -> Flux.just(new EventFrame(
                    "plan.step_done",
                    Map.of("planId", psd.planId(), "stepIndex", psd.stepIndex(),
                           "status", psd.status(),
                           "resultSummary", psd.resultSummary() != null ? psd.resultSummary() : "",
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.PlanAdjusted pa -> Flux.just(new EventFrame(
                    "plan.adjusted",
                    Map.of("planId", pa.planId(), "adjustType", pa.adjustType(),
                           "currentSteps", pa.currentSteps().stream()
                                   .map(s -> Map.of("index", s.index(), "title", s.title(),
                                           "description", s.description() != null ? s.description() : ""))
                                   .toList(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.PlanCompleted pcomp -> Flux.just(new EventFrame(
                    "plan.completed",
                    Map.of("planId", pcomp.planId(), "status", pcomp.status(), "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));
        };
    }

    private List<Message> convertToAiMessages(List<TranscriptMessageEntity> history) {
        List<Message> messages = new ArrayList<>();
        for (TranscriptMessageEntity msg : history) {
            switch (msg.getRole()) {
                case "user" -> messages.add(new UserMessage(msg.getContent()));
                case "assistant" -> messages.add(new AssistantMessage(msg.getContent()));
                case "tool" -> {
                    String toolCallId = msg.getToolCallId() != null ? msg.getToolCallId() : "";
                    String toolName = msg.getToolName() != null ? msg.getToolName() : "";
                    String content = msg.getContent() != null ? msg.getContent() : "";
                    messages.add(new ToolResponseMessage(List.of(
                            new ToolResponseMessage.ToolResponse(toolCallId, toolName, content)
                    )));
                }
                default -> log.debug("Skipping message with role: {}", msg.getRole());
            }
        }
        return messages;
    }
}
