package com.atm.javaclaw.gateway.scheduler.jobs;

import com.atm.javaclaw.core.config.JavaClawProperties;
import com.atm.javaclaw.gateway.repository.HeartbeatLogRepository;
import com.atm.javaclaw.gateway.repository.OfflineMessageRepository;
import com.atm.javaclaw.gateway.repository.ScheduledJobLogRepository;
import com.atm.javaclaw.gateway.scheduler.ScheduledJob;
import com.atm.javaclaw.gateway.scheduler.model.JobExecutionContext;
import com.atm.javaclaw.gateway.scheduler.model.JobResult;
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
    private final OfflineMessageRepository offlineMsgRepo;
    private final JavaClawProperties properties;

    public DataCleanupJob(ScheduledJobLogRepository jobLogRepo,
                          HeartbeatLogRepository heartbeatLogRepo,
                          OfflineMessageRepository offlineMsgRepo,
                          JavaClawProperties properties) {
        this.jobLogRepo = jobLogRepo;
        this.heartbeatLogRepo = heartbeatLogRepo;
        this.offlineMsgRepo = offlineMsgRepo;
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
        LocalDateTime jobLogCutoff = LocalDateTime.now().minusDays(retentionDays);
        LocalDateTime heartbeatCutoff = LocalDateTime.now().minusDays(30);
        LocalDateTime offlineMsgCutoff = LocalDateTime.now().minusDays(7);

        AtomicLong jobLogsDeleted = new AtomicLong(0);
        AtomicLong heartbeatLogsDeleted = new AtomicLong(0);
        AtomicLong offlineMsgsDeleted = new AtomicLong(0);

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
                .then(offlineMsgRepo.deleteDeliveredBefore(offlineMsgCutoff))
                .doOnNext(count -> {
                    offlineMsgsDeleted.set(count != null ? count : 0);
                    log.debug("Deleted {} offline messages older than {}", offlineMsgsDeleted.get(), offlineMsgCutoff);
                })
                .then(Mono.fromSupplier(() -> {
                    Map<String, Object> metrics = new HashMap<>();
                    metrics.put("jobLogsDeleted", jobLogsDeleted.get());
                    metrics.put("heartbeatLogsDeleted", heartbeatLogsDeleted.get());
                    metrics.put("offlineMsgsDeleted", offlineMsgsDeleted.get());
                    return JobResult.ok("Cleanup completed", metrics);
                }));
    }
}
