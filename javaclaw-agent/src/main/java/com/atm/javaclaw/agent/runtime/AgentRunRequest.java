package com.atm.javaclaw.agent.runtime;

import com.atm.javaclaw.core.config.JavaClawProperties;

import java.util.List;

/**
 * Encapsulates everything needed for a single agent run.
 *
 * @param sessionId          the session this run belongs to
 * @param agent              resolved agent configuration
 * @param userMessage        the user's input text
 * @param history            conversation history as Spring AI messages
 * @param toolsEnabled       tool filtering spec for builtin+custom: null="full", or profile name, or JSON array
 * @param mcpToolsEnabled    MCP tool filtering spec: null=none, "full"=all, or JSON array of tool names
 */
public record AgentRunRequest(
        Long sessionId,
        JavaClawProperties.Agent agent,
        String userMessage,
        List<org.springframework.ai.chat.messages.Message> history,
        String toolsEnabled,
        String mcpToolsEnabled
) {
    public AgentRunRequest(Long sessionId, JavaClawProperties.Agent agent,
                           String userMessage, List<org.springframework.ai.chat.messages.Message> history) {
        this(sessionId, agent, userMessage, history, null, null);
    }
}
