package com.atm.intellimate.gateway.dto;

import com.atm.intellimate.gateway.entity.SkillDefinitionEntity;

import java.time.LocalDateTime;

public record SkillDTO(
        Long id,
        String name,
        String displayName,
        String description,
        String content,
        String tags,
        String metadata,
        Integer hasScripts,
        Integer hasReferences,
        Integer enabled,
        String gitUrl,
        String gitSubPath,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static SkillDTO fromEntity(SkillDefinitionEntity entity) {
        return new SkillDTO(
                entity.getId(),
                entity.getName(),
                entity.getDisplayName(),
                entity.getDescription(),
                entity.getContent(),
                entity.getTags(),
                entity.getMetadata(),
                entity.getHasScripts(),
                entity.getHasReferences(),
                entity.getEnabled(),
                entity.getGitUrl(),
                entity.getGitSubPath(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
