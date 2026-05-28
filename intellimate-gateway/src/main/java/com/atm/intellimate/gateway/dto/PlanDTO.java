package com.atm.intellimate.gateway.dto;

import com.atm.intellimate.gateway.entity.PlanEntity;
import com.atm.intellimate.gateway.entity.PlanStepEntity;

import java.time.LocalDateTime;
import java.util.List;

public record PlanDTO(
        Long planId,
        Long sessionId,
        String title,
        String status,
        String completionSummary,
        String agentName,
        Integer totalSteps,
        Long completedSteps,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<PlanStepDTO> steps
) {
    public static PlanDTO fromEntity(PlanEntity entity) {
        return new PlanDTO(
                entity.getId(),
                entity.getSessionId(),
                entity.getTitle(),
                entity.getStatus(),
                entity.getCompletionSummary(),
                null,
                null,
                null,
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                null
        );
    }

    public static PlanDTO fromEntityWithSteps(PlanEntity entity, List<PlanStepEntity> steps, String agentName) {
        long completed = steps.stream().filter(s -> "completed".equals(s.getStatus())).count();
        List<PlanStepDTO> stepDtos = steps.stream().map(PlanStepDTO::fromEntity).toList();
        return new PlanDTO(
                entity.getId(),
                entity.getSessionId(),
                entity.getTitle(),
                entity.getStatus(),
                entity.getCompletionSummary(),
                agentName,
                steps.size(),
                completed,
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                stepDtos
        );
    }

    public static PlanDTO summaryFromEntity(PlanEntity entity, List<PlanStepEntity> steps,
                                            String agentName, boolean includeSteps) {
        long completed = steps.stream().filter(s -> "completed".equals(s.getStatus())).count();
        List<PlanStepDTO> stepDtos = includeSteps
                ? steps.stream().map(PlanStepDTO::fromEntity).toList()
                : null;
        return new PlanDTO(
                entity.getId(),
                entity.getSessionId(),
                entity.getTitle(),
                entity.getStatus(),
                entity.getCompletionSummary(),
                agentName,
                steps.size(),
                completed,
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                stepDtos
        );
    }
}
