package com.atm.javaclaw.gateway.scheduler.jobs;

import com.atm.javaclaw.gateway.entity.ModelProviderEntity;
import com.atm.javaclaw.gateway.repository.ModelProviderRepository;
import com.atm.javaclaw.gateway.repository.ScheduledJobConfigRepository;
import com.atm.javaclaw.gateway.scheduler.ScheduledJob;
import com.atm.javaclaw.gateway.scheduler.model.JobExecutionContext;
import com.atm.javaclaw.gateway.scheduler.model.JobResult;
import io.r2dbc.pool.ConnectionPool;
import io.r2dbc.pool.PoolMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class HealthCheckJob implements ScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(HealthCheckJob.class);

    private final ModelProviderRepository providerRepo;
    private final ScheduledJobConfigRepository configRepo;
    private final DatabaseClient databaseClient;
    private final Optional<ConnectionPool> connectionPool;

    public HealthCheckJob(ModelProviderRepository providerRepo,
                          ScheduledJobConfigRepository configRepo,
                          DatabaseClient databaseClient,
                          Optional<ConnectionPool> connectionPool) {
        this.providerRepo = providerRepo;
        this.configRepo = configRepo;
        this.databaseClient = databaseClient;
        this.connectionPool = connectionPool;
    }

    @Override
    public String getJobName() { return "health-check"; }

    @Override
    public String getJobGroup() { return "monitor"; }

    @Override
    public Duration getDefaultTimeout() { return Duration.ofMinutes(1); }

    @Override
    public Mono<JobResult> execute(JobExecutionContext context) {
        log.debug("Starting health check");

        Map<String, Object> metrics = new HashMap<>();
        List<String> issues = new ArrayList<>();

        return checkJvmHealth(metrics, issues)
                .then(checkR2dbcPool(metrics, issues))
                .then(checkModelProviders(metrics, issues))
                .then(checkJobHealth(metrics, issues))
                .then(Mono.fromSupplier(() -> {
                    metrics.put("issues", issues);
                    if (issues.isEmpty()) {
                        return JobResult.ok("All systems healthy", metrics);
                    } else {
                        return JobResult.fail("Issues detected: " + String.join("; ", issues), metrics);
                    }
                }));
    }

    private Mono<Void> checkJvmHealth(Map<String, Object> metrics, List<String> issues) {
        return Mono.fromRunnable(() -> {
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            long heapUsed = memoryBean.getHeapMemoryUsage().getUsed();
            long heapMax = memoryBean.getHeapMemoryUsage().getMax();
            double heapPercent = heapMax > 0 ? (double) heapUsed / heapMax * 100 : 0;
            metrics.put("heapUsedMB", heapUsed / (1024 * 1024));
            metrics.put("heapMaxMB", heapMax / (1024 * 1024));
            metrics.put("heapUsagePercent", Math.round(heapPercent * 10.0) / 10.0);
            if (heapPercent > 90) {
                issues.add("JVM heap usage critical: " + Math.round(heapPercent) + "%");
            }
        });
    }

    private Mono<Void> checkR2dbcPool(Map<String, Object> metrics, List<String> issues) {
        if (connectionPool.isEmpty()) {
            return databaseClient.sql("SELECT 1")
                    .fetch().rowsUpdated()
                    .doOnNext(r -> metrics.put("dbConnectivity", "OK"))
                    .onErrorResume(e -> {
                        metrics.put("dbConnectivity", "FAILED");
                        issues.add("Database connectivity failed: " + e.getMessage());
                        return Mono.just(0L);
                    })
                    .then();
        }
        ConnectionPool pool = connectionPool.get();
        return Mono.fromRunnable(() -> {
            Optional<PoolMetrics> pm = pool.getMetrics();
            if (pm.isPresent()) {
                PoolMetrics m = pm.get();
                metrics.put("dbPoolAcquired", m.acquiredSize());
                metrics.put("dbPoolIdle", m.idleSize());
                metrics.put("dbPoolPending", m.pendingAcquireSize());
                metrics.put("dbPoolMaxSize", m.getMaxAllocatedSize());
                if (m.pendingAcquireSize() > 5) {
                    issues.add("R2DBC pool congestion: " + m.pendingAcquireSize() + " pending acquisitions");
                }
            }
        });
    }

    private Mono<Void> checkModelProviders(Map<String, Object> metrics, List<String> issues) {
        return providerRepo.findAll()
                .filter(p -> p.getEnabled() != null && p.getEnabled() == 1)
                .collectList()
                .flatMap(providers -> {
                    metrics.put("providersTotal", providers.size());
                    if (providers.isEmpty()) {
                        return Mono.empty();
                    }
                    List<String> names = providers.stream()
                            .map(ModelProviderEntity::getName)
                            .toList();
                    metrics.put("providersEnabled", names);
                    return Mono.<Void>empty();
                })
                .then();
    }

    private Mono<Void> checkJobHealth(Map<String, Object> metrics, List<String> issues) {
        return configRepo.findByEnabled(1)
                .filter(c -> c.getConsecutiveFailures() != null && c.getConsecutiveFailures() >= 3)
                .collectList()
                .doOnNext(unhealthyJobs -> {
                    metrics.put("unhealthyJobs", unhealthyJobs.size());
                    if (!unhealthyJobs.isEmpty()) {
                        List<String> names = unhealthyJobs.stream()
                                .map(j -> j.getJobName() + "(" + j.getConsecutiveFailures() + " failures)")
                                .toList();
                        metrics.put("unhealthyJobNames", names);
                        issues.add("Unhealthy jobs: " + String.join(", ", names));
                        log.warn("Unhealthy jobs detected: {}", names);
                    }
                })
                .then();
    }
}
