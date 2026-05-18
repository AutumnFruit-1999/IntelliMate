package com.atm.intellimate.gateway.scheduler;

import com.atm.intellimate.gateway.entity.ScheduledJobConfigEntity;
import com.atm.intellimate.gateway.entity.ScheduledJobLogEntity;
import com.atm.intellimate.gateway.repository.ScheduledJobLogRepository;
import com.atm.intellimate.gateway.scheduler.model.JobResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

@Component
public class ExecutionTracker {

    private static final Logger log = LoggerFactory.getLogger(ExecutionTracker.class);
    private static final int MAX_STACK_LENGTH = 4000;

    private final ScheduledJobLogRepository logRepo;
    private final ObjectMapper objectMapper;

    public ExecutionTracker(ScheduledJobLogRepository logRepo, ObjectMapper objectMapper) {
        this.logRepo = logRepo;
        this.objectMapper = objectMapper;
    }

    public Mono<Long> createRunningLog(ScheduledJobConfigEntity config, String triggerSource, int retryCount) {
        ScheduledJobLogEntity entry = new ScheduledJobLogEntity();
        entry.setJobName(config.getJobName());
        entry.setJobGroup(config.getJobGroup());
        entry.setFireTime(config.getNextFireTime() != null ? config.getNextFireTime() : LocalDateTime.now());
        entry.setStartTime(LocalDateTime.now());
        entry.setStatus("RUNNING");
        entry.setRetryCount(retryCount);
        entry.setTriggerSource(triggerSource);
        entry.setCreatedAt(LocalDateTime.now());
        return logRepo.save(entry).map(ScheduledJobLogEntity::getId);
    }

    public Mono<Void> markSuccess(Long logId, JobResult result, LocalDateTime startTime) {
        LocalDateTime endTime = LocalDateTime.now();
        long durationMs = Duration.between(startTime, endTime).toMillis();
        String metricsJson = serializeMetrics(result.metrics());
        String message = truncate(result.message(), 1000);

        return logRepo.updateSuccess(logId, "SUCCESS", endTime, durationMs, message, metricsJson);
    }

    public Mono<Void> markFailed(Long logId, Throwable error, LocalDateTime startTime) {
        LocalDateTime endTime = LocalDateTime.now();
        long durationMs = Duration.between(startTime, endTime).toMillis();
        String errorMessage = truncate(error.getMessage(), 500);
        String errorStack = truncateStack(error);

        return logRepo.updateFailure(logId, "FAILED", endTime, durationMs, errorMessage, errorStack);
    }

    public Mono<Void> markTimeout(Long logId, LocalDateTime startTime) {
        LocalDateTime endTime = LocalDateTime.now();
        long durationMs = Duration.between(startTime, endTime).toMillis();
        return logRepo.updateFailure(logId, "TIMEOUT", endTime, durationMs, "Execution timed out", null);
    }

    public Mono<Void> markSkipped(Long logId) {
        return logRepo.updateSuccess(logId, "SKIPPED", LocalDateTime.now(), 0L, "Skipped: concurrent execution not allowed", null);
    }

    private String serializeMetrics(Map<String, Object> metrics) {
        if (metrics == null || metrics.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(metrics);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metrics: {}", e.getMessage());
            return null;
        }
    }

    private String truncateStack(Throwable error) {
        StringBuilder sb = new StringBuilder();
        sb.append(error.getClass().getName()).append(": ").append(error.getMessage()).append("\n");
        for (StackTraceElement el : error.getStackTrace()) {
            if (sb.length() > MAX_STACK_LENGTH) break;
            sb.append("\tat ").append(el).append("\n");
        }
        return sb.length() > MAX_STACK_LENGTH ? sb.substring(0, MAX_STACK_LENGTH) : sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
    }
}
