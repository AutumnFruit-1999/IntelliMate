package com.atm.javaclaw.gateway.scheduler;

import com.atm.javaclaw.gateway.entity.ScheduledJobConfigEntity;
import com.atm.javaclaw.gateway.repository.ScheduledJobConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class RetryHandler {

    private static final Logger log = LoggerFactory.getLogger(RetryHandler.class);

    private final ScheduledJobConfigRepository configRepo;
    private final ExecutionTracker executionTracker;

    public RetryHandler(ScheduledJobConfigRepository configRepo, ExecutionTracker executionTracker) {
        this.configRepo = configRepo;
        this.executionTracker = executionTracker;
    }

    public boolean shouldRetry(ScheduledJobConfigEntity config, int currentRetryCount) {
        return config.getMaxRetryCount() != null && currentRetryCount < config.getMaxRetryCount();
    }

    public Duration getRetryDelay(ScheduledJobConfigEntity config, int retryCount) {
        long backoffMs = config.getRetryBackoffMs() != null ? config.getRetryBackoffMs() : 5000L;
        long delayMs = backoffMs * (1L << retryCount);
        return Duration.ofMillis(Math.min(delayMs, 600_000L));
    }

    public Mono<Void> recordFailure(ScheduledJobConfigEntity config, Long logId, Throwable error, LocalDateTime startTime) {
        int failures = (config.getConsecutiveFailures() != null ? config.getConsecutiveFailures() : 0) + 1;
        config.setConsecutiveFailures(failures);
        config.setLastStatus("FAILED");

        log.error("Job '{}' failed (consecutive: {}): {}", config.getJobName(), failures, error.getMessage());

        return executionTracker.markFailed(logId, error, startTime)
                .then(configRepo.updateFailureStatus(config.getJobName(), failures, "FAILED"));
    }

    public Mono<Void> recordSuccess(ScheduledJobConfigEntity config) {
        if (config.getConsecutiveFailures() != null && config.getConsecutiveFailures() > 0) {
            config.setConsecutiveFailures(0);
            return configRepo.resetFailureStatus(config.getJobName());
        }
        return Mono.empty();
    }
}
