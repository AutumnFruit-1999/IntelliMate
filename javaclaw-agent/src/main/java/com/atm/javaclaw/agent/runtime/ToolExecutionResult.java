package com.atm.javaclaw.agent.runtime;

/**
 * Immutable result of a single tool execution within the agent loop.
 */
public record ToolExecutionResult(
        String id,
        String name,
        String result,
        boolean success
) {}
