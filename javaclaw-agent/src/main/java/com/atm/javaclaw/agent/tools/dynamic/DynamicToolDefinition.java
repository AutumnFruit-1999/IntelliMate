package com.atm.javaclaw.agent.tools.dynamic;

/**
 * Portable representation of a custom tool definition,
 * decoupled from the persistence entity in javaclaw-gateway.
 */
public record DynamicToolDefinition(
        long id,
        String name,
        String type,
        String description,
        String parametersSchema,
        String executionConfig,
        int timeoutSeconds,
        String groupName,
        String agentName
) {}
