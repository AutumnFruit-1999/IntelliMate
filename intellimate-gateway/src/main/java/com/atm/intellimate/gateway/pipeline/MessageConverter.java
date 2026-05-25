package com.atm.intellimate.gateway.pipeline;

import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class MessageConverter {

    private static final Logger log = LoggerFactory.getLogger(MessageConverter.class);

    private final SessionManager sessionManager;
    private final IntelliMateProperties properties;

    public MessageConverter(SessionManager sessionManager, IntelliMateProperties properties) {
        this.sessionManager = sessionManager;
        this.properties = properties;
    }

    public Flux<TranscriptMessageEntity> loadHistory(Long sessionId, Long planId) {
        int limit = properties.getAgent().getHistoryLimit();
        if (planId != null) {
            int contextLimit = Math.max(4, limit / 4);
            return Flux.merge(
                    sessionManager.getChatHistory(sessionId, contextLimit),
                    sessionManager.getPlanHistory(sessionId, planId, limit)
            ).sort(Comparator.comparing(TranscriptMessageEntity::getCreatedAt)).take(limit);
        }
        return sessionManager.getChatHistory(sessionId, limit);
    }

    public List<Message> convertToAiMessages(List<TranscriptMessageEntity> history) {
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
