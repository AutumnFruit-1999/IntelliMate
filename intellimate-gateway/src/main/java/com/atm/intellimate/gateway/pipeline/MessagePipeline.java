package com.atm.intellimate.gateway.pipeline;

import com.atm.intellimate.agent.runtime.AgentEvent;
import com.atm.intellimate.agent.runtime.AgentRunRequest;
import com.atm.intellimate.agent.runtime.AgentRuntime;
import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.SessionKey;

import com.atm.intellimate.core.protocol.EventFrame;
import com.atm.intellimate.core.protocol.GatewayFrame;
import com.atm.intellimate.core.protocol.RequestFrame;
import com.atm.intellimate.core.protocol.ResponseFrame;
import com.atm.intellimate.gateway.audit.AuditService;
import com.atm.intellimate.gateway.channel.ChannelIdentityService;
import com.atm.intellimate.gateway.config.AgentConfigService;
import com.atm.intellimate.gateway.config.ResolvedAgentConfig;
import com.atm.intellimate.gateway.entity.SessionEntity;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.repository.SessionRepository;
import com.atm.intellimate.gateway.service.CrossChannelSyncService;
import com.atm.intellimate.gateway.service.InlinePlanService;
import com.atm.intellimate.gateway.session.SessionManager;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.Message;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class MessagePipeline {

    private static final Logger log = LoggerFactory.getLogger(MessagePipeline.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String WEBCHAT_DEFAULT_EXTERNAL_ID = "default";

    private final SessionManager sessionManager;
    private final MessageConverter messageConverter;
    private final AgentEventMapper agentEventMapper;
    private final AgentRuntime agentRuntime;
    private final IntelliMateProperties properties;
    private final AgentConfigService agentConfigService;
    private final CommandHandler commandHandler;
    private final AuditService auditService;
    private final InlinePlanService inlinePlanService;
    private final SessionRegistry sessionRegistry;
    private final SessionRepository sessionRepository;
    private final ChannelIdentityService channelIdentityService;
    private final CrossChannelSyncService crossChannelSyncService;
    private final ConcurrentMap<String, Long> wsSessionToDbSession = new ConcurrentHashMap<>();
    private final AtomicLong planSeqGenerator = new AtomicLong(0);

    public MessagePipeline(SessionManager sessionManager,
                           MessageConverter messageConverter,
                           AgentEventMapper agentEventMapper,
                           AgentRuntime agentRuntime,
                           IntelliMateProperties properties,
                           AgentConfigService agentConfigService,
                           CommandHandler commandHandler,
                           AuditService auditService,
                           InlinePlanService inlinePlanService,
                           SessionRegistry sessionRegistry,
                           SessionRepository sessionRepository,
                           ChannelIdentityService channelIdentityService,
                           CrossChannelSyncService crossChannelSyncService) {
        this.sessionManager = sessionManager;
        this.messageConverter = messageConverter;
        this.agentEventMapper = agentEventMapper;
        this.agentRuntime = agentRuntime;
        this.properties = properties;
        this.agentConfigService = agentConfigService;
        this.commandHandler = commandHandler;
        this.auditService = auditService;
        this.inlinePlanService = inlinePlanService;
        this.sessionRegistry = sessionRegistry;
        this.sessionRepository = sessionRepository;
        this.channelIdentityService = channelIdentityService;
        this.crossChannelSyncService = crossChannelSyncService;
    }

    public Flux<GatewayFrame> processRequest(RequestFrame request, String wsSessionId) {
        return withTraceMdc(buildRequestFlux(request, wsSessionId));
    }

    /**
     * Processes an inbound message from an external channel (non-WebSocket).
     * Collects the agent's full reply text without streaming.
     */
    public Mono<String> processInbound(InboundEnvelope envelope) {
        return processInbound(envelope, null, null);
    }

    public Mono<String> processInbound(InboundEnvelope envelope, String overrideAgentName) {
        return processInbound(envelope, overrideAgentName, null);
    }

    /**
     * @param sourceChannel the originating channel (e.g. "feishu", "dingtalk").
     *                      For unified cross-channel sessions the envelope's
     *                      sessionKey.channelId is "unified", so this parameter
     *                      preserves which channel the message actually came from.
     */
    public Mono<String> processInbound(InboundEnvelope envelope, String overrideAgentName,
                                       String sourceChannel) {
        SessionKey sessionKey = envelope.sessionKey();
        String rawText = envelope.text() != null ? envelope.text() : "";
        final String userText;
        if ("group".equals(sessionKey.contextType())
                && envelope.senderName() != null && !envelope.senderName().isBlank()) {
            userText = "[" + envelope.senderName() + "] " + rawText;
        } else {
            userText = rawText;
        }
        String effectiveSourceChannel = (sourceChannel != null && !sourceChannel.isBlank())
                ? sourceChannel
                : sessionKey.channelId();

        String agentName = (overrideAgentName != null && !overrideAgentName.isBlank())
                ? overrideAgentName
                : properties.getAgent().getName();

        return resolveSessionForAgent(sessionKey, agentName)
                .flatMap(session -> {
                    if (CommandHandler.isCommand(userText)) {
                        return commandHandler.handle(userText, session, "channel-inbound")
                                .filter(ResponseFrame.class::isInstance)
                                .cast(ResponseFrame.class)
                                .last(ResponseFrame.failure("channel-inbound", "No response"))
                                .map(this::extractResponseText);
                    }
                    return runInboundAgent(session, userText, effectiveSourceChannel);
                })
                .onErrorResume(e -> {
                    log.error("Error processing inbound envelope sessionKey={}: {}",
                            sessionKey, e.getMessage(), e);
                    return Mono.just("Error: " + e.getMessage());
                });
    }

    /**
     * Finds or creates a session scoped to both the session key AND the agent name,
     * ensuring each agent has an isolated conversation context.
     */
    private Mono<SessionEntity> resolveSessionForAgent(SessionKey key, String agentName) {
        return sessionRepository.findBySessionKeyAndAgent(
                        key.channelId(), key.contextType(), key.contextId(), agentName)
                .flatMap(existing -> {
                    existing.setLastActiveAt(LocalDateTime.now());
                    return sessionRepository.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    SessionEntity session = new SessionEntity();
                    session.setChannelId(key.channelId());
                    session.setContextType(key.contextType());
                    session.setContextId(key.contextId());
                    session.setAgentName(agentName);
                    session.setStatus("active");
                    session.setLastActiveAt(LocalDateTime.now());
                    session.setCreatedAt(LocalDateTime.now());
                    session.setDeleted(0);
                    return sessionRepository.save(session)
                            .onErrorResume(DuplicateKeyException.class, e -> {
                                log.warn("Duplicate session key for agent '{}', fetching existing", agentName);
                                return sessionRepository.findBySessionKeyAndAgent(
                                        key.channelId(), key.contextType(), key.contextId(), agentName);
                            });
                }));
    }

    @SuppressWarnings("unchecked")
    private String extractResponseText(ResponseFrame frame) {
        if (!frame.ok()) {
            return frame.error() != null ? frame.error().toString() : "Unknown error";
        }
        if (frame.payload() instanceof Map<?, ?> map) {
            Object text = map.get("text");
            if (text != null) {
                return text.toString();
            }
        }
        return frame.payload() != null ? frame.payload().toString() : "";
    }

    private Mono<String> runInboundAgent(SessionEntity session, String userText,
                                        String sourceChannel) {
        return inlinePlanService.getActivePlan(session.getId())
                .flatMap(activePlan -> runInboundWithPlan(session, userText, sourceChannel, activePlan))
                .switchIfEmpty(Mono.defer(() -> runInboundWithPlan(session, userText, sourceChannel, null)));
    }

    private Mono<String> runInboundWithPlan(SessionEntity session, String userText,
                                            String sourceChannel, TranscriptMessageEntity activePlan) {
        Long activePlanMessageId = resolveActivePlanMessageId(activePlan);
        boolean planExecuting = activePlanMessageId != null;

        TranscriptMessageEntity userMsg = new TranscriptMessageEntity();
        userMsg.setRole("user");
        userMsg.setContent(userText);
        userMsg.setSourceChannel(sourceChannel);
        userMsg.setCreatedAt(LocalDateTime.now());

        String sessionContextId = session.getContextId();
        Mono<Void> saveUserMono = sessionManager.appendMessage(session.getId(), userMsg)
                .then(auditService.log("user_message", "channel", session.getId(),
                        userText.length() > 200 ? userText.substring(0, 200) + "..." : userText))
                .then(crossChannelSyncService.syncToWeb(
                        sessionContextId, "user", userText, sourceChannel))
                .then(crossChannelSyncService.syncToExternalChannels(
                        sessionContextId, "user", userText, session.getAgentName(), sourceChannel));

        Mono<String> planContextMono = planExecuting
                ? inlinePlanService.buildPlanContext(activePlanMessageId)
                : Mono.just("");

        return saveUserMono.then(Mono.zip(
                        messageConverter.loadHistory(session.getId(), activePlanMessageId).collectList(),
                        agentConfigService.resolve(session.getAgentName()),
                        planContextMono
                ))
                .flatMap(tuple -> {
                    List<Message> messages = messageConverter.convertToAiMessages(tuple.getT1());
                    ResolvedAgentConfig resolved = tuple.getT2();
                    String planContext = tuple.getT3().isEmpty() ? null : tuple.getT3();

                    String effectiveUserId = resolveUserId(session);
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
                            false,
                            activePlanMessageId,
                            null,
                            resolved.bridgeNode()
                    );

                    return agentRuntime.dispatch(runRequest)
                            .reduce(new StringBuilder(), MessagePipeline::accumulateAgentText)
                            .map(StringBuilder::toString)
                            .flatMap(completeText -> {
                                TranscriptMessageEntity assistantMsg = new TranscriptMessageEntity();
                                assistantMsg.setRole("assistant");
                                assistantMsg.setContent(completeText);
                                assistantMsg.setSourceChannel(sourceChannel);
                                assistantMsg.setCreatedAt(LocalDateTime.now());
                                return sessionManager.appendMessage(session.getId(), assistantMsg)
                                        .then(auditService.log("agent_response", "channel", session.getId(),
                                                "length=" + completeText.length()))
                                        .then(crossChannelSyncService.syncToWeb(
                                                sessionContextId, "assistant", completeText, sourceChannel))
                                        .then(crossChannelSyncService.syncToExternalChannels(
                                                sessionContextId, "assistant", completeText,
                                                session.getAgentName(), sourceChannel))
                                        .thenReturn(completeText);
                            });
                });
    }

    private static StringBuilder accumulateAgentText(StringBuilder sb, AgentEvent event) {
        switch (event) {
            case AgentEvent.TextChunk tc -> sb.append(tc.text());
            case AgentEvent.Done done -> {
                sb.setLength(0);
                sb.append(done.fullText());
            }
            case AgentEvent.Error err -> throw new IllegalStateException(err.message());
            case AgentEvent.ApprovalRequired ar ->
                    throw new IllegalStateException("Tool approval required: " + ar.toolName());
            default -> { /* plan/delegation events ignored for channel inbound */ }
        }
        return sb;
    }

    private Flux<GatewayFrame> withTraceMdc(Flux<GatewayFrame> flux) {
        return flux.transformDeferredContextual((f, ctx) -> {
            String traceId = ctx.getOrDefault("traceId", "none");
            return f.doOnSubscribe(s -> MDC.put("traceId", traceId))
                    .doFinally(signal -> MDC.remove("traceId"));
        });
    }

    @SuppressWarnings("unchecked")
    private Flux<GatewayFrame> buildRequestFlux(RequestFrame request, String wsSessionId) {
        if ("conversation.cancel".equals(request.method())) {
            return processCancelRequest(request, wsSessionId);
        }

        if ("conversation.approve_tool".equals(request.method())) {
            return processApprovalResponse(request);
        }

        if (request.method() != null && request.method().startsWith("plan.")) {
            return handlePlanAction(request, wsSessionId)
                    .onErrorResume(e -> {
                        log.error("Plan action failed method={}: {}", request.method(), e.getMessage(), e);
                        return Flux.just(ResponseFrame.failure(request.id(), e.getMessage()));
                    });
        }

        if (!"conversation.message".equals(request.method())) {
            return Flux.just(ResponseFrame.failure(request.id(), "Unknown method: " + request.method()));
        }

        Map<String, Object> params = (Map<String, Object>) request.params();
        String userText = (String) params.getOrDefault("text", "");
        boolean forcePlan = Boolean.TRUE.equals(params.get("forcePlan"));
        boolean isRegenerate = Boolean.TRUE.equals(params.get("regenerate"));

        String agentName = (String) params.getOrDefault("agentName", "");
        if (agentName.isBlank()) {
            agentName = properties.getAgent().getName();
        }
        sessionRegistry.bindAgent(wsSessionId, agentName);

        final String finalAgentName = agentName;

        Long authenticatedUserId = sessionRegistry.getUserId(wsSessionId);
        String webchatExternalId = authenticatedUserId != null
                ? String.valueOf(authenticatedUserId) : WEBCHAT_DEFAULT_EXTERNAL_ID;

        return channelIdentityService.resolveUserId("webchat", webchatExternalId, null)
                .flatMapMany(userId -> {
                    SessionKey sessionKey = new SessionKey("unified", "dm", userId);
                    return resolveSessionForAgent(sessionKey, finalAgentName)
                            .flatMapMany(session -> {
                                wsSessionToDbSession.put(wsSessionId, session.getId());
                                return dispatchWebchatMessage(session, userText, request, wsSessionId, forcePlan, isRegenerate);
                            });
                })
                .onErrorResume(e -> {
                    log.error("Error processing request id={}: {}", request.id(), e.getMessage(), e);
                    return Flux.just(ResponseFrame.failure(request.id(), e.getMessage()));
                });
    }

    private Flux<GatewayFrame> dispatchWebchatMessage(
            SessionEntity session, String userText, RequestFrame request,
            String wsSessionId, boolean forcePlan, boolean isRegenerate) {
        if (CommandHandler.isCommand(userText)) {
            return auditService.log("command", wsSessionId, session.getId(), userText)
                    .thenMany(commandHandler.handle(userText, session, request.id()));
        }
        return processMessageStreaming(session, userText, request.id(), wsSessionId, forcePlan, isRegenerate);
    }

    private Flux<GatewayFrame> processMessageStreaming(
            SessionEntity session, String userText, String requestId, String wsSessionId,
            boolean forcePlan, boolean isRegenerate) {

        return inlinePlanService.getActivePlan(session.getId())
                .flatMapMany(activePlan -> processMessageStreamingWithPlan(
                        session, userText, requestId, wsSessionId, forcePlan, isRegenerate, activePlan))
                .switchIfEmpty(Flux.defer(() -> processMessageStreamingWithPlan(
                        session, userText, requestId, wsSessionId, forcePlan, isRegenerate, null)));
    }

    private Flux<GatewayFrame> processMessageStreamingWithPlan(
            SessionEntity session, String userText, String requestId, String wsSessionId,
            boolean forcePlan, boolean isRegenerate, TranscriptMessageEntity activePlan) {

        Long activePlanMessageId = resolveActivePlanMessageId(activePlan);
        boolean planExecuting = activePlanMessageId != null;

        String contextId = session.getContextId();
        Mono<Void> saveUserMono;
        if (isRegenerate) {
            saveUserMono = Mono.empty();
        } else {
            TranscriptMessageEntity userMsg = new TranscriptMessageEntity();
            userMsg.setRole("user");
            userMsg.setContent(userText);
            userMsg.setSourceChannel("webchat");
            userMsg.setCreatedAt(LocalDateTime.now());
            saveUserMono = sessionManager.appendMessage(session.getId(), userMsg)
                    .then(auditService.log("user_message", wsSessionId, session.getId(),
                            userText.length() > 200 ? userText.substring(0, 200) + "..." : userText))
                    .then(crossChannelSyncService.syncToExternalChannels(
                            contextId, "user", userText, session.getAgentName(), null));
        }

        Mono<String> planContextMono = planExecuting
                ? inlinePlanService.buildPlanContext(activePlanMessageId)
                : Mono.just("");

        return saveUserMono.then(Mono.zip(
                        messageConverter.loadHistory(session.getId(), activePlanMessageId).collectList(),
                        agentConfigService.resolve(session.getAgentName()),
                        planContextMono
                ))
                .flatMapMany(tuple -> {
                    List<Message> messages = messageConverter.convertToAiMessages(tuple.getT1());
                    ResolvedAgentConfig resolved = tuple.getT2();
                    String planContext = tuple.getT3().isEmpty() ? null : tuple.getT3();

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
                            activePlanMessageId,
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
                        assistantMsg.setSourceChannel("webchat");
                        assistantMsg.setCreatedAt(LocalDateTime.now());

                        Mono<Void> saveMsgMono = sessionManager.appendMessage(session.getId(), assistantMsg)
                                .then(auditService.log("agent_response", "agent", session.getId(),
                                        "length=" + completeText.length()))
                                .then(crossChannelSyncService.syncToExternalChannels(
                                        contextId, "assistant", completeText, session.getAgentName(), null));

                        return saveMsgMono.thenMany(Flux.just(
                                ResponseFrame.success(requestId, Map.of("text", completeText))));
                    });

                    return Flux.concat(events, tail);
                });
    }

    @SuppressWarnings("unchecked")
    private Flux<GatewayFrame> handlePlanAction(RequestFrame request, String wsSessionId) {
        Map<String, Object> params = (Map<String, Object>) request.params();
        long messageId = ((Number) params.get("messageId")).longValue();

        return switch (request.method()) {
            case "plan.approve" -> {
                boolean approved = Boolean.TRUE.equals(params.get("approved"));
                if (approved) {
                    yield inlinePlanService.updatePlanStatus(messageId, "approved")
                            .thenMany(Flux.concat(
                                    Flux.just(planStatusChangedEvent(messageId, "approved", request.id())),
                                    triggerPlanExecution(messageId, wsSessionId, request.id())
                            ));
                } else {
                    yield inlinePlanService.updatePlanStatus(messageId, "cancelled")
                            .thenMany(Flux.just(
                                    planStatusChangedEvent(messageId, "cancelled", request.id()),
                                    ResponseFrame.success(request.id(), Map.of("status", "cancelled"))
                            ));
                }
            }
            case "plan.pause" -> inlinePlanService.updatePlanStatus(messageId, "paused")
                    .doOnSuccess(v -> agentRuntime.signalPlanPaused(messageId))
                    .thenMany(Flux.just(
                            planStatusChangedEvent(messageId, "paused", request.id()),
                            ResponseFrame.success(request.id(), Map.of("status", "paused"))
                    ));
            case "plan.resume" -> inlinePlanService.updatePlanStatus(messageId, "executing")
                    .doOnSuccess(v -> agentRuntime.clearPlanPaused(messageId))
                    .thenMany(Flux.concat(
                            Flux.just(planStatusChangedEvent(messageId, "executing", request.id())),
                            triggerPlanExecution(messageId, wsSessionId, request.id())
                    ));
            case "plan.cancel" -> inlinePlanService.updatePlanStatus(messageId, "cancelled")
                    .doOnSuccess(v -> agentRuntime.signalPlanPaused(messageId))
                    .thenMany(Flux.just(
                            planStatusChangedEvent(messageId, "cancelled", request.id()),
                            ResponseFrame.success(request.id(), Map.of("status", "cancelled"))
                    ));
            default -> Flux.error(new IllegalArgumentException("Unknown plan method: " + request.method()));
        };
    }

    private Flux<GatewayFrame> triggerPlanExecution(long messageId, String wsSessionId, String requestId) {
        return inlinePlanService.getPlanMessage(messageId)
                .flatMapMany(planMsg -> sessionRepository.findById(planMsg.getSessionId())
                        .flatMapMany(session -> {
                            wsSessionToDbSession.put(wsSessionId, session.getId());
                            agentRuntime.clearPlanPaused(messageId);
                            return processMessageStreaming(session, "开始执行计划", requestId, wsSessionId, false, false);
                        }));
    }

    private EventFrame planStatusChangedEvent(long messageId, String status, String requestId) {
        return new EventFrame(
                "plan.status_changed",
                Map.of("messageId", messageId, "status", status, "requestId", requestId),
                planSeqGenerator.incrementAndGet());
    }

    private Long resolveActivePlanMessageId(TranscriptMessageEntity activePlan) {
        if (activePlan == null || activePlan.getId() == null) {
            return null;
        }
        String status = extractPlanStatus(activePlan);
        if ("executing".equals(status) || "approved".equals(status)) {
            return activePlan.getId();
        }
        return null;
    }

    private String extractPlanStatus(TranscriptMessageEntity planMsg) {
        try {
            Map<String, Object> metadata = parseMetadata(planMsg.getMetadataJson());
            @SuppressWarnings("unchecked")
            Map<String, Object> plan = (Map<String, Object>) metadata.get("plan");
            if (plan == null) {
                return null;
            }
            return (String) plan.get("status");
        } catch (Exception e) {
            log.warn("Failed to parse plan status from messageId={}: {}", planMsg.getId(), e.getMessage());
            return null;
        }
    }

    private Map<String, Object> parseMetadata(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    private Flux<GatewayFrame> processCancelRequest(RequestFrame request, String wsSessionId) {
        try {
            Map<String, Object> params = (Map<String, Object>) request.params();
            String requestId = (String) params.get("requestId");

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

    private String resolveUserId(SessionEntity session) {
        String contextId = session.getContextId();
        return contextId != null && !contextId.isBlank() ? contextId : "default";
    }

}
