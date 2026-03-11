package com.atm.javaclaw.gateway.pipeline;

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
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Orchestrates the full message processing pipeline with streaming support:
 * 1. Parse incoming request
 * 2. Resolve/create session
 * 3. Load conversation history
 * 4. Dispatch to AgentRuntime (streaming)
 * 5. Emit chunk events as tokens arrive
 * 6. Persist complete transcript + audit log
 * 7. Emit done event + response frame
 */
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

    private Flux<GatewayFrame> processMessageStreaming(SessionEntity session, String userText, String requestId, String wsSessionId) {
        TranscriptMessageEntity userMsg = new TranscriptMessageEntity();
        userMsg.setRole("user");
        userMsg.setContent(userText);
        userMsg.setCreatedAt(LocalDateTime.now());

        return sessionManager.appendMessage(session.getId(), userMsg)
                .then(auditService.log("user_message", wsSessionId, session.getId(),
                        userText.length() > 200 ? userText.substring(0, 200) + "..." : userText))
                .then(Mono.zip(
                        sessionManager.getHistory(session.getId(), 50).collectList(),
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
                            resolved.toolsEnabled()
                    );

                    StringBuilder fullResponse = new StringBuilder();

                    Flux<GatewayFrame> chunks = agentRuntime.dispatch(runRequest)
                            .doOnNext(fullResponse::append)
                            .map(token -> new EventFrame(
                                    "agent.chunk",
                                    Map.of("text", token, "requestId", requestId),
                                    seqGenerator.incrementAndGet()
                            ));

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
                                        new EventFrame(
                                                "agent.done",
                                                Map.of("text", completeText, "requestId", requestId),
                                                seqGenerator.incrementAndGet()
                                        ),
                                        ResponseFrame.success(requestId, Map.of("text", completeText))
                                ));
                    });

                    return Flux.concat(chunks, tail);
                });
    }

    private List<Message> convertToAiMessages(List<TranscriptMessageEntity> history) {
        List<Message> messages = new ArrayList<>();
        for (TranscriptMessageEntity msg : history) {
            switch (msg.getRole()) {
                case "user" -> messages.add(new UserMessage(msg.getContent()));
                case "assistant" -> messages.add(new AssistantMessage(msg.getContent()));
                default -> log.debug("Skipping message with role: {}", msg.getRole());
            }
        }
        return messages;
    }
}
