package com.atm.javaclaw.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Marker tool for single-agent delegation.
 * The actual execution is intercepted by AgentRuntime.processToolCalls;
 * the call() method here is never invoked directly.
 */
@Component
public class DelegateAgentTool {

    @Tool(description = "Delegate a sub-task to a specialist agent. "
            + "The agent executes independently and returns results. "
            + "Use when a task requires specific expertise that another agent has.")
    public String delegateAgent(
            @ToolParam(description = "Name of the agent to delegate to") String agentName,
            @ToolParam(description = "Clear, self-contained task description for the worker") String task,
            @ToolParam(description = "Optional background context to help the worker") String context
    ) {
        throw new UnsupportedOperationException(
                "delegateAgent is intercepted by AgentRuntime — direct invocation should never happen");
    }
}
