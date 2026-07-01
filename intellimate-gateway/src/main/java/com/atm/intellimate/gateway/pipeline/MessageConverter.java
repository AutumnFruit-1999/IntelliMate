package com.atm.intellimate.gateway.pipeline;

import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.session.SessionManager;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

@Component
public class MessageConverter {

    private final SessionManager sessionManager;
    private final IntelliMateProperties properties;

    public MessageConverter(SessionManager sessionManager, IntelliMateProperties properties) {
        this.sessionManager = sessionManager;
        this.properties = properties;
    }

    public Flux<TranscriptMessageEntity> loadHistory(Long sessionId, Long planMessageId) {
        int limit = properties.getAgent().getHistoryLimit();
        return sessionManager.getHistory(sessionId, limit);
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
                default -> { }
            }
        }
        return messages;
    }
}
