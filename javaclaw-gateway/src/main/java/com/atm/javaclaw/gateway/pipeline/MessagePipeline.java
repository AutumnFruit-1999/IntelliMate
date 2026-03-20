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
import com.atm.javaclaw.gateway.entity.SessionEntity;
import com.atm.javaclaw.gateway.entity.TranscriptMessageEntity;
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
    private final AtomicLong seqGenerator = new AtomicLong(0);

    public MessagePipeline(SessionManager sessionManager,
                           AgentRuntime agentRuntime,
                           JavaClawProperties properties,
                           AgentConfigService agentConfigService,
                           CommandHandler commandHandler,
                           AuditService auditService) {
        this.sessionManager = sessionManager;
        this.agentRuntime = agentRuntime;
        this.properties = properties;
        this.agentConfigService = agentConfigService;
        this.commandHandler = commandHandler;
        this.auditService = auditService;
    }

    @SuppressWarnings("unchecked")
    public Flux<GatewayFrame> processRequest(RequestFrame request, String wsSessionId) {
        if ("conversation.approve_tool".equals(request.method())) {
            return processApprovalResponse(request);
        }

        if (!"conversation.message".equals(request.method())) {
            return Flux.just(ResponseFrame.failure(request.id(), "Unknown method: " + request.method()));
        }

        Map<String, Object> params = (Map<String, Object>) request.params();
        String userText = (String) params.getOrDefault("text", "");
        String channelId = (String) params.getOrDefault("channelId", "webchat");
        String contextType = (String) params.getOrDefault("contextType", "dm");
        String baseContextId = (String) params.getOrDefault("contextId", wsSessionId);

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
                    return processMessageStreaming(session, userText, request.id(), wsSessionId);
                })
                .onErrorResume(e -> {
                    log.error("Error processing request id={}: {}", request.id(), e.getMessage(), e);
                    return Flux.just(ResponseFrame.failure(request.id(), e.getMessage()));
                });
    }

    private Flux<GatewayFrame> processMessageStreaming(
            SessionEntity session, String userText, String requestId, String wsSessionId) {

        TranscriptMessageEntity userMsg = new TranscriptMessageEntity();
        userMsg.setRole("user");
        userMsg.setContent(userText);
        userMsg.setCreatedAt(LocalDateTime.now());

        return sessionManager.appendMessage(session.getId(), userMsg)
                .then(auditService.log("user_message", wsSessionId, session.getId(),
                        userText.length() > 200 ? userText.substring(0, 200) + "..." : userText))
                .then(Mono.zip(
                        sessionManager.getHistory(session.getId(), properties.getAgent().getHistoryLimit()).collectList(),
                        agentConfigService.resolve(session.getAgentName())
                ))
                .flatMapMany(tuple -> {
                    List<Message> messages = convertToAiMessages(tuple.getT1());
                    ResolvedAgentConfig resolved = tuple.getT2();

                    AgentRunRequest runRequest = new AgentRunRequest(
                            session.getId(),
                            resolved.agent(),
                            userText,
                            messages,
                            resolved.toolsEnabled(),
                            resolved.mcpToolsEnabled(),
                            resolved.skillsEnabled()
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

                        return sessionManager.appendMessage(session.getId(), assistantMsg)
                                .then(auditService.log("agent_response", "agent", session.getId(),
                                        "length=" + completeText.length()))
                                .thenMany(Flux.just(
                                        ResponseFrame.success(requestId, Map.of("text", completeText))
                                ));
                    });

                    return Flux.concat(events, tail);
                });
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
