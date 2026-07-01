package com.atm.intellimate.gateway.dto;

import com.atm.intellimate.gateway.entity.ModelDefinitionEntity;

import java.time.LocalDateTime;

public record ModelDTO(
        Long id,
        Long providerId,
        String modelId,
        String category,
        Integer dimensions,
        String displayName,
        String description,
        Integer maxTokens,
        String capabilities,
        Integer enabled,
        Integer sortOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static ModelDTO fromEntity(ModelDefinitionEntity entity) {
        return new ModelDTO(
                entity.getId(),
                entity.getProviderId(),
                entity.getModelId(),
                entity.getCategory(),
                entity.getDimensions(),
                entity.getDisplayName(),
                entity.getDescription(),
                entity.getMaxTokens(),
                entity.getCapabilities(),
                entity.getEnabled(),
                entity.getSortOrder(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
