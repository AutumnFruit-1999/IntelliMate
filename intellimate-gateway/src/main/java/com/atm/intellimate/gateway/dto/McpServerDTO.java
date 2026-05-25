package com.atm.intellimate.gateway.dto;

import com.atm.intellimate.gateway.entity.McpServerEntity;

import java.time.LocalDateTime;

public record McpServerDTO(
        Long id,
        String name,
        String serverUrl,
        String transportType,
        String authConfig,
        Integer enabled,
        LocalDateTime lastConnectedAt,
        String toolsDiscovered,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static McpServerDTO fromEntity(McpServerEntity entity) {
        return new McpServerDTO(
                entity.getId(),
                entity.getName(),
                entity.getServerUrl(),
                entity.getTransportType(),
                entity.getAuthConfig(),
                entity.getEnabled(),
                entity.getLastConnectedAt(),
                entity.getToolsDiscovered(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
