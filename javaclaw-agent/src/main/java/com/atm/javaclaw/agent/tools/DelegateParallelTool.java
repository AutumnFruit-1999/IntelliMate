package com.atm.javaclaw.agent.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Marker tool for parallel multi-agent delegation (fan-out / fan-in).
 * Intercepted by AgentRuntime — never executed directly.
 */
@Component
public class DelegateParallelTool {

    @Tool(description = "Delegate tasks to multiple agents in parallel. "
            + "All agents execute concurrently and their results are aggregated. "
            + "Provide a JSON array of objects, each with 'agent' (agent name) and 'task' (task description).")
    public String delegateAgentsParallel(
            @ToolParam(description = "JSON array of {\"agent\":\"name\",\"task\":\"description\"} objects") String tasks
    ) {
        throw new UnsupportedOperationException(
                "delegateAgentsParallel is intercepted by AgentRuntime — direct invocation should never happen");
    }
}
