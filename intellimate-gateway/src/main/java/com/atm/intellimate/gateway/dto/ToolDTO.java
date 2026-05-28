package com.atm.intellimate.gateway.dto;

import com.atm.intellimate.gateway.entity.ToolDefinitionEntity;

import java.time.LocalDateTime;

public record ToolDTO(
        Long id,
        String name,
        String type,
        String description,
        String parametersSchema,
        String executionConfig,
        Integer timeoutSeconds,
        String groupName,
        String agentName,
        Integer enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ToolDTO fromEntity(ToolDefinitionEntity entity) {
        return new ToolDTO(
                entity.getId(),
                entity.getName(),
                entity.getType(),
                entity.getDescription(),
                entity.getParametersSchema(),
                entity.getExecutionConfig(),
                entity.getTimeoutSeconds(),
                entity.getGroupName(),
                entity.getAgentName(),
                entity.getEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
