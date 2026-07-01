package com.atm.intellimate.agent.runtime;

import com.atm.intellimate.core.config.IntelliMateProperties;

import java.util.List;

/**
 * Encapsulates everything needed for a single agent run.
 *
 * @param sessionId          the session this run belongs to
 * @param userId             the end-user identifier (for memory isolation); null falls back to "default"
 * @param agent              resolved agent configuration
 * @param userMessage        the user's input text
 * @param history            conversation history as Spring AI messages
 * @param toolsEnabled       tool filtering spec for builtin+custom: null="full", or profile name, or JSON array
 * @param mcpToolsEnabled    MCP tool filtering spec: null=none, "full"=all, or JSON array of tool names
 * @param skillsEnabled      skill filtering spec: null=none, "full"=all, or JSON array of skill names
 * @param skillGroupsEnabled skill group filtering spec: null=none, "full"=all, or JSON array of group names
 * @param planContext        optional Plan execution context injected into system prompt when a plan is active
 * @param forcePlan          if true, system prompt instructs agent to always create a plan first
 * @param activePlanMessageId       ID of the plan message being executed (null if no plan), used for pause check
 * @param delegationContext         delegation nesting/limit state; null for top-level user requests
 * @param bridgeNode                bound Bridge node name for local tool execution; null = server-side only
 */
public record AgentRunRequest(
        Long sessionId,
        String userId,
        IntelliMateProperties.Agent agent,
        String userMessage,
        List<org.springframework.ai.chat.messages.Message> history,
        String toolsEnabled,
        String mcpToolsEnabled,
        String skillsEnabled,
        String skillGroupsEnabled,
        String planContext,
        boolean forcePlan,
        Long activePlanMessageId,
        DelegationContext delegationContext,
        String bridgeNode
) {
    public AgentRunRequest(Long sessionId, IntelliMateProperties.Agent agent,
                           String userMessage, List<org.springframework.ai.chat.messages.Message> history) {
        this(sessionId, null, agent, userMessage, history, null, null, null, null, null, false, null, null, null);
    }

    public AgentRunRequest(Long sessionId, IntelliMateProperties.Agent agent,
                           String userMessage, List<org.springframework.ai.chat.messages.Message> history,
                           String toolsEnabled, String mcpToolsEnabled, String skillsEnabled) {
        this(sessionId, null, agent, userMessage, history, toolsEnabled, mcpToolsEnabled, skillsEnabled, null, null, false, null, null, null);
    }
}
