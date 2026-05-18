package com.atm.intellimate.agent.tools.dynamic;

/**
 * Portable representation of a custom tool definition,
 * decoupled from the persistence entity in intellimate-gateway.
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
