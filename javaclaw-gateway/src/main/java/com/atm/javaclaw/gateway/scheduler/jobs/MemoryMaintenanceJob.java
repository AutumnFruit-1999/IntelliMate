package com.atm.javaclaw.gateway.scheduler.jobs;

import com.atm.javaclaw.gateway.config.ForgettingSchedulerConfig;
import com.atm.javaclaw.gateway.scheduler.ScheduledJob;
import com.atm.javaclaw.gateway.scheduler.model.JobExecutionContext;
import com.atm.javaclaw.gateway.scheduler.model.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

@Component
public class MemoryMaintenanceJob implements ScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(MemoryMaintenanceJob.class);

    private final ForgettingSchedulerConfig.ForgettingSchedulerJobs forgettingJobs;

    public MemoryMaintenanceJob(ForgettingSchedulerConfig.ForgettingSchedulerJobs forgettingJobs) {
        this.forgettingJobs = forgettingJobs;
    }

    @Override
    public String getJobName() { return "memory-nightly-maintenance"; }

    @Override
    public String getJobGroup() { return "data"; }

    @Override
    public Duration getDefaultTimeout() { return Duration.ofMinutes(30); }

    @Override
    public Mono<JobResult> execute(JobExecutionContext context) {
        log.info("Starting nightly memory maintenance (retry: {})", context.retryCount());

        return forgettingJobs.runMaintenanceReactive()
                .map(stats -> JobResult.ok(
                        "Memory maintenance completed",
                        Map.of(
                                "usersProcessed", stats.getOrDefault("usersProcessed", 0),
                                "memoriesArchived", stats.getOrDefault("memoriesArchived", 0),
                                "memoriesForgotten", stats.getOrDefault("memoriesForgotten", 0)
                        )
                ))
                .onErrorResume(e -> {
                    log.error("Memory maintenance failed", e);
                    return Mono.just(JobResult.fail("Maintenance failed: " + e.getMessage()));
                });
    }
}
