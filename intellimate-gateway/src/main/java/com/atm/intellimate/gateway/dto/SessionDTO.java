package com.atm.intellimate.gateway.dto;

import com.atm.intellimate.gateway.entity.SessionEntity;

import java.time.LocalDateTime;

public record SessionDTO(
        Long id,
        String channelId,
        String contextType,
        String contextId,
        String agentName,
        LocalDateTime lastActiveAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Integer deleted
) {
    public static SessionDTO fromEntity(SessionEntity entity) {
        return new SessionDTO(
                entity.getId(),
                entity.getChannelId(),
                entity.getContextType(),
                entity.getContextId(),
                entity.getAgentName(),
                entity.getLastActiveAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getDeleted()
        );
    }
}
