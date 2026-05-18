package com.atm.intellimate.gateway.scheduler.jobs;

import com.atm.intellimate.gateway.heartbeat.HeartbeatEngine;
import com.atm.intellimate.gateway.repository.HeartbeatConfigRepository;
import com.atm.intellimate.gateway.scheduler.ScheduledJob;
import com.atm.intellimate.gateway.scheduler.model.JobExecutionContext;
import com.atm.intellimate.gateway.scheduler.model.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class HeartbeatJob implements ScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatJob.class);

    private final HeartbeatEngine engine;
    private final HeartbeatConfigRepository configRepo;

    public HeartbeatJob(HeartbeatEngine engine, HeartbeatConfigRepository configRepo) {
        this.engine = engine;
        this.configRepo = configRepo;
    }

    @Override
    public String getJobName() { return "heartbeat-tick"; }

    @Override
    public String getJobGroup() { return "agent"; }

    @Override
    public Duration getDefaultTimeout() { return Duration.ofSeconds(30); }

    @Override
    public Mono<JobResult> execute(JobExecutionContext context) {
        AtomicInteger processed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        return configRepo.findAllByEnabled(1)
                .flatMap(config -> engine.processHeartbeat(config)
                        .doOnSuccess(v -> processed.incrementAndGet())
                        .onErrorResume(e -> {
                            failed.incrementAndGet();
                            log.error("Heartbeat failed for agent {}: {}", config.getAgentId(), e.getMessage());
                            return Mono.empty();
                        }))
                .then(Mono.fromSupplier(() -> JobResult.ok(
                        "Processed " + processed.get() + " agents",
                        Map.of("agentsProcessed", processed.get(), "agentsFailed", failed.get())
                )));
    }
}
