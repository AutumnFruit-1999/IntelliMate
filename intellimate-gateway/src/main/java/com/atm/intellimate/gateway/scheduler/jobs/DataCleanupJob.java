package com.atm.intellimate.gateway.scheduler.jobs;

import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.gateway.repository.HeartbeatLogRepository;
import com.atm.intellimate.gateway.repository.ScheduledJobLogRepository;
import com.atm.intellimate.gateway.repository.TranscriptMessageRepository;
import com.atm.intellimate.gateway.scheduler.ScheduledJob;
import com.atm.intellimate.gateway.scheduler.model.JobExecutionContext;
import com.atm.intellimate.gateway.scheduler.model.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DataCleanupJob implements ScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(DataCleanupJob.class);

    private final ScheduledJobLogRepository jobLogRepo;
    private final HeartbeatLogRepository heartbeatLogRepo;
    private final TranscriptMessageRepository transcriptRepo;
    private final IntelliMateProperties properties;

    public DataCleanupJob(ScheduledJobLogRepository jobLogRepo,
                          HeartbeatLogRepository heartbeatLogRepo,
                          TranscriptMessageRepository transcriptRepo,
                          IntelliMateProperties properties) {
        this.jobLogRepo = jobLogRepo;
        this.heartbeatLogRepo = heartbeatLogRepo;
        this.transcriptRepo = transcriptRepo;
        this.properties = properties;
    }

    @Override
    public String getJobName() { return "data-cleanup"; }

    @Override
    public String getJobGroup() { return "data"; }

    @Override
    public Duration getDefaultTimeout() { return Duration.ofMinutes(10); }

    @Override
    public Mono<JobResult> execute(JobExecutionContext context) {
        log.info("Starting data cleanup job");

        int retentionDays = properties.getScheduler() != null ? properties.getScheduler().getLogRetentionDays() : 30;
        int proactiveTtlHours = properties.getProactive() != null ? properties.getProactive().getMessageTtlHours() : 24;

        LocalDateTime jobLogCutoff = LocalDateTime.now().minusDays(retentionDays);
        LocalDateTime heartbeatCutoff = LocalDateTime.now().minusDays(retentionDays);
        LocalDateTime proactiveCutoff = LocalDateTime.now().minusHours(proactiveTtlHours);

        AtomicLong jobLogsDeleted = new AtomicLong(0);
        AtomicLong heartbeatLogsDeleted = new AtomicLong(0);
        AtomicLong proactiveMsgsDeleted = new AtomicLong(0);

        return jobLogRepo.deleteOlderThan(jobLogCutoff)
                .doOnNext(count -> {
                    jobLogsDeleted.set(count != null ? count : 0);
                    log.debug("Deleted {} scheduled job logs older than {}", jobLogsDeleted.get(), jobLogCutoff);
                })
                .then(heartbeatLogRepo.deleteOlderThan(heartbeatCutoff))
                .doOnNext(count -> {
                    heartbeatLogsDeleted.set(count != null ? count : 0);
                    log.debug("Deleted {} heartbeat logs older than {}", heartbeatLogsDeleted.get(), heartbeatCutoff);
                })
                .then(transcriptRepo.deleteExpiredProactiveMessages(proactiveCutoff))
                .doOnNext(count -> {
                    proactiveMsgsDeleted.set(count != null ? count : 0);
                    log.debug("Deleted {} expired proactive transcript messages older than {}", proactiveMsgsDeleted.get(), proactiveCutoff);
                })
                .then(Mono.fromSupplier(() -> {
                    Map<String, Object> metrics = new HashMap<>();
                    metrics.put("jobLogsDeleted", jobLogsDeleted.get());
                    metrics.put("heartbeatLogsDeleted", heartbeatLogsDeleted.get());
                    metrics.put("proactiveMsgsDeleted", proactiveMsgsDeleted.get());
                    return JobResult.ok("Cleanup completed", metrics);
                }));
    }
}
