package com.atm.intellimate.gateway.dto;

import com.atm.intellimate.gateway.entity.ScheduledJobConfigEntity;

import java.time.LocalDateTime;

public record ScheduledJobDTO(
        Long id,
        String jobName,
        String jobGroup,
        String displayName,
        String description,
        String jobClass,
        String triggerType,
        String triggerValue,
        String timezone,
        Boolean enabled,
        Integer maxRetryCount,
        Long retryBackoffMs,
        Long timeoutMs,
        String paramsJson,
        Boolean concurrentAllowed,
        LocalDateTime nextFireTime,
        LocalDateTime lastFireTime,
        String lastStatus,
        Integer consecutiveFailures,
        Boolean running,
        Boolean builtIn,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    private static final java.util.Set<String> BUILT_IN_JOBS = java.util.Set.of(
            "heartbeat-tick", "memory-nightly-maintenance", "data-cleanup"
    );

    public static ScheduledJobDTO fromEntity(ScheduledJobConfigEntity entity) {
        return fromEntity(entity, false);
    }

    public static ScheduledJobDTO fromEntity(ScheduledJobConfigEntity entity, boolean running) {
        return new ScheduledJobDTO(
                entity.getId(),
                entity.getJobName(),
                entity.getJobGroup(),
                entity.getDisplayName(),
                entity.getDescription(),
                entity.getJobClass(),
                entity.getTriggerType(),
                entity.getTriggerValue(),
                entity.getTimezone(),
                entity.getEnabled() != null && entity.getEnabled() == 1,
                entity.getMaxRetryCount(),
                entity.getRetryBackoffMs(),
                entity.getTimeoutMs(),
                entity.getParamsJson(),
                entity.getConcurrentAllowed() != null && entity.getConcurrentAllowed() == 1,
                entity.getNextFireTime(),
                entity.getLastFireTime(),
                entity.getLastStatus(),
                entity.getConsecutiveFailures(),
                running,
                BUILT_IN_JOBS.contains(entity.getJobName()),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
