package com.atm.intellimate.gateway.pipeline;

import com.atm.intellimate.agent.runtime.AgentRunRequest;
import com.atm.intellimate.agent.runtime.AgentRuntime;
import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.core.model.SessionKey;
import com.atm.intellimate.core.model.SessionMetadata;
import com.atm.intellimate.core.protocol.GatewayFrame;
import com.atm.intellimate.core.protocol.RequestFrame;
import com.atm.intellimate.core.protocol.ResponseFrame;
import com.atm.intellimate.gateway.audit.AuditService;
import com.atm.intellimate.gateway.config.AgentConfigService;
import com.atm.intellimate.gateway.config.ResolvedAgentConfig;
import com.atm.intellimate.gateway.entity.PlanEntity;
import com.atm.intellimate.gateway.entity.SessionEntity;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.repository.SessionRepository;
import com.atm.intellimate.gateway.service.PlanService;
import com.atm.intellimate.gateway.session.SessionManager;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
    private final PlanRequestHandler planRequestHandler;
    private final PlanService planService;
    private final PlanExecutionOrchestrator planExecutionOrchestrator;
    private final SessionRegistry sessionRegistry;
    private final SessionRepository sessionRepository;
    private final ConcurrentMap<String, Long> wsSessionToDbSession = new ConcurrentHashMap<>();

    public MessagePipeline(SessionManager sessionManager,
                           MessageConverter messageConverter,
                           AgentEventMapper agentEventMapper,
                           AgentRuntime agentRuntime,
                           IntelliMateProperties properties,
                           AgentConfigService agentConfigService,
                           CommandHandler commandHandler,
                           AuditService auditService,
                           PlanRequestHandler planRequestHandler,
                           PlanService planService,
                           PlanExecutionOrchestrator planExecutionOrchestrator,
                           SessionRegistry sessionRegistry,
                           SessionRepository sessionRepository) {
        this.sessionManager = sessionManager;
        this.messageConverter = messageConverter;
        this.agentEventMapper = agentEventMapper;
        this.agentRuntime = agentRuntime;
        this.properties = properties;
        this.agentConfigService = agentConfigService;
        this.commandHandler = commandHandler;
        this.auditService = auditService;
        this.planRequestHandler = planRequestHandler;
        this.planService = planService;
        this.planExecutionOrchestrator = planExecutionOrchestrator;
        this.sessionRegistry = sessionRegistry;
        this.sessionRepository = sessionRepository;
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
            return planRequestHandler.processPlanRequest(
                    request, wsSessionId, this::processMessageStreaming, wsSessionToDbSession::put);
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
                                            ? planExecutionOrchestrator.buildPlanExecutionPayload(planId)
                                            : Mono.just(new PlanExecutionOrchestrator.PlanExecutionPayload("", null))
                            ))
                            .flatMapMany(tuple -> {
                                List<Message> messages = messageConverter.convertToAiMessages(tuple.getT1());
                                ResolvedAgentConfig resolved = tuple.getT2();
                                PlanExecutionOrchestrator.PlanExecutionPayload planPayload = tuple.getT3();
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
                                                event, requestId, fullResponse, session))
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
                                        syncMono = planExecutionOrchestrator.syncPlanAfterExecution(
                                                effectivePlanId, session.getId(), memoryAgentId, memoryUserId);
                                    } else {
                                        syncMono = planService.getActivePlan(session.getId())
                                                .filter(p -> "executing".equals(p.getStatus())
                                                        || "approved".equals(p.getStatus()))
                                                .flatMap(p -> planExecutionOrchestrator.syncPlanAfterExecution(
                                                        p.getId(), session.getId(), memoryAgentId, memoryUserId))
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
