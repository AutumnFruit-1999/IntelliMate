package com.atm.intellimate.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Marker tool for agent handoff (control transfer).
 * Intercepted by AgentRuntime — never executed directly.
 */
@Component
public class HandoffTool {

    @Tool(description = "Transfer control to another agent and stop your own execution. "
            + "The target agent takes over the conversation with the user. "
            + "Use when a task falls outside your expertise and should be fully handled by another agent.")
    public String handoffToAgent(
            @ToolParam(description = "Name of the agent to hand off to") String agentName,
            @ToolParam(description = "Reason for the handoff") String reason,
            @ToolParam(description = "Summary of the conversation context for the target agent") String contextSummary
    ) {
        throw new UnsupportedOperationException(
                "handoffToAgent is intercepted by AgentRuntime — direct invocation should never happen");
    }
}
