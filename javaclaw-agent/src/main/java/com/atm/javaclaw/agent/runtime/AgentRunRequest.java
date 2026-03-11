package com.atm.javaclaw.agent.runtime;

import com.atm.javaclaw.core.config.JavaClawProperties;

import java.util.List;

/**
 * Encapsulates everything needed for a single agent run.
 *
 * @param sessionId     the session this run belongs to
 * @param agent         resolved agent configuration
 * @param userMessage   the user's input text
 * @param history       conversation history as Spring AI messages
 * @param toolsEnabled  tool filtering spec: null="full", or profile name, or JSON array
 */
public record AgentRunRequest(
        Long sessionId,
        JavaClawProperties.Agent agent,
        String userMessage,
        List<org.springframework.ai.chat.messages.Message> history,
        String toolsEnabled
) {
    public AgentRunRequest(Long sessionId, JavaClawProperties.Agent agent,
                           String userMessage, List<org.springframework.ai.chat.messages.Message> history) {
        this(sessionId, agent, userMessage, history, null);
    }
}
