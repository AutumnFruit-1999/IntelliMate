package com.atm.intellimate.gateway.dto;

import com.atm.intellimate.gateway.entity.SkillGroupEntity;

import java.time.LocalDateTime;

public record SkillGroupDTO(
        Long id,
        String name,
        String displayName,
        String description,
        Integer sortOrder,
        Integer enabled,
        Long skillCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static SkillGroupDTO fromEntity(SkillGroupEntity entity) {
        return fromEntity(entity, null);
    }

    public static SkillGroupDTO fromEntity(SkillGroupEntity entity, Long skillCount) {
        return new SkillGroupDTO(
                entity.getId(),
                entity.getName(),
                entity.getDisplayName(),
                entity.getDescription(),
                entity.getSortOrder(),
                entity.getEnabled(),
                skillCount,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
