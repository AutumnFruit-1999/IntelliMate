package com.atm.intellimate.gateway.dto;

import com.atm.intellimate.gateway.entity.PlanStepEntity;

public record PlanStepDTO(
        Integer index,
        String title,
        String description,
        String status,
        String resultSummary
) {
    public static PlanStepDTO fromEntity(PlanStepEntity entity) {
        return new PlanStepDTO(
                entity.getStepIndex(),
                entity.getTitle(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getResultSummary()
        );
    }
}
