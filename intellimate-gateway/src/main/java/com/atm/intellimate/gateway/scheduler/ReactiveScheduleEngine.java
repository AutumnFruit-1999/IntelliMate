package com.atm.intellimate.gateway.scheduler;

import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.gateway.entity.ScheduledJobConfigEntity;
import com.atm.intellimate.gateway.scheduler.model.ConfigChangeEvent;
import com.atm.intellimate.gateway.scheduler.model.JobExecutionContext;
import com.atm.intellimate.gateway.scheduler.model.JobResult;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class ReactiveScheduleEngine implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ReactiveScheduleEngine.class);

    private final TaskRegistry registry;
    private final ExecutionTracker tracker;
    private final RetryHandler retryHandler;
    private final SessionRegistry sessionRegistry;
    private final IntelliMateProperties properties;
    private final ObjectMapper objectMapper;

    private final Sinks.Many<ConfigChangeEvent> configSink = Sinks.many().multicast().onBackpressureBuffer();
    private final ConcurrentHashMap<String, Boolean> runningJobs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastManualTrigger = new ConcurrentHashMap<>();
    private static final long MANUAL_TRIGGER_COOLDOWN_MS = 10_000;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Disposable tickDisposable;
    private Disposable configDisposable;
    private Scheduler jobScheduler;

    public ReactiveScheduleEngine(TaskRegistry registry,
                                  ExecutionTracker tracker,
                                  RetryHandler retryHandler,
                                  SessionRegistry sessionRegistry,
                                  IntelliMateProperties properties,
                                  ObjectMapper objectMapper,
                                  List<ScheduledJob> jobs) {
        this.registry = registry;
        this.tracker = tracker;
        this.retryHandler = retryHandler;
        this.sessionRegistry = sessionRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;

        jobs.forEach(job -> registry.registerJobBean(job.getJobName(), job));
    }

    @Override
    public void start() {
        IntelliMateProperties.Scheduler schedulerProps = properties.getScheduler();
        if (schedulerProps != null && !schedulerProps.isEnabled()) {
            return;
        }
        if (!running.compareAndSet(false, true)) return;

        int tickPeriod = schedulerProps != null ? schedulerProps.getTickPeriodSeconds() : 10;
        int poolSize = schedulerProps != null ? schedulerProps.getPoolSize() : 4;
        long debouncMs = schedulerProps != null ? schedulerProps.getConfigReloadDebounceMs() : 500;

        jobScheduler = Schedulers.newBoundedElastic(poolSize, Integer.MAX_VALUE, "scheduler-pool");

        registry.loadAll()
                .then(registry.initializeNextFireTimes())
                .subscribe();

        tickDisposable = Flux.interval(Duration.ofSeconds(tickPeriod))
                .flatMap(tick -> registry.getDueJobs(LocalDateTime.now())
                        .filter(this::shouldExecute)
                        .flatMap(config -> dispatchJob(config, "AUTO", 0)
                                .subscribeOn(jobScheduler), maxConcurrentJobs())
                )
                .subscribe();

        configDisposable = configSink.asFlux()
                .bufferTimeout(10, Duration.ofMillis(debouncMs))
                .flatMap(events -> {
                    events.stream()
                            .map(ConfigChangeEvent::jobName)
                            .distinct()
                            .forEach(name -> handleConfigChange(new ConfigChangeEvent(name, events.getLast().type())));
                    return Mono.empty();
                })
                .subscribe();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (tickDisposable != null) tickDisposable.dispose();
        if (configDisposable != null) configDisposable.dispose();

        if (!runningJobs.isEmpty()) {
            IntelliMateProperties.Scheduler schedulerProps = properties.getScheduler();
            int awaitSeconds = schedulerProps != null ? schedulerProps.getShutdownAwaitSeconds() : 30;
            long deadline = System.currentTimeMillis() + awaitSeconds * 1000L;
            while (!runningJobs.isEmpty() && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            if (!runningJobs.isEmpty()) {
                log.warn("Forcing shutdown with {} jobs still running: {}", runningJobs.size(), runningJobs.keySet());
            }
        }

        if (jobScheduler != null) {
            jobScheduler.dispose();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }

    public void emitConfigChange(ConfigChangeEvent event) {
        configSink.tryEmitNext(event);
    }

    public Mono<Void> triggerManually(String jobName) {
        ScheduledJobConfigEntity config = registry.getConfig(jobName);
        if (config == null) {
            return Mono.error(new IllegalArgumentException("Job not found: " + jobName));
        }
        long now = System.currentTimeMillis();
        Long lastTrigger = lastManualTrigger.get(jobName);
        if (lastTrigger != null && now - lastTrigger < MANUAL_TRIGGER_COOLDOWN_MS) {
            return Mono.error(new IllegalStateException("Cooldown active, try again after 10s"));
        }
        lastManualTrigger.put(jobName, now);
        return dispatchJob(config, "MANUAL", 0);
    }

    public boolean isJobCurrentlyRunning(String jobName) {
        return runningJobs.containsKey(jobName);
    }

    private boolean shouldExecute(ScheduledJobConfigEntity config) {
        if (registry.getJobBean(config.getJobName()) == null) {
            log.warn("No bean registered for job: {}", config.getJobName());
            return false;
        }
        if (config.getConcurrentAllowed() == null || config.getConcurrentAllowed() == 0) {
            if (runningJobs.containsKey(config.getJobName())) {
                return false;
            }
        }
        return true;
    }

    private Mono<Void> dispatchJob(ScheduledJobConfigEntity config, String triggerSource, int retryCount) {
        String jobName = config.getJobName();
        ScheduledJob job = registry.getJobBean(jobName);
        if (job == null) return Mono.empty();

        runningJobs.put(jobName, true);
        registry.markRunning(jobName);
        LocalDateTime startTime = LocalDateTime.now();

        pushEvent("scheduler.job.started", Map.of(
                "jobName", jobName,
                "executionId", 0,
                "fireTime", startTime.toString()
        ));

        return tracker.createRunningLog(config, triggerSource, retryCount)
                .flatMap(logId -> {
                    JobExecutionContext ctx = new JobExecutionContext(
                            logId, jobName, config.getJobGroup(),
                            parseParams(config.getParamsJson()),
                            retryCount, config.getNextFireTime(), startTime);

                    long timeoutMs = config.getTimeoutMs() != null ? config.getTimeoutMs() : 300_000L;

                    return job.execute(ctx)
                            .timeout(Duration.ofMillis(timeoutMs))
                            .flatMap(result -> {
                                if (result.success()) {
                                    return handleSuccess(config, logId, result, startTime);
                                } else {
                                    return handleError(config, logId,
                                            new RuntimeException(result.message() != null ? result.message() : "Job returned failure"),
                                            startTime, retryCount);
                                }
                            })
                            .onErrorResume(TimeoutException.class, e -> handleTimeout(config, logId, startTime, retryCount))
                            .onErrorResume(e -> handleError(config, logId, e, startTime, retryCount));
                })
                .doFinally(signal -> {
                    String finalStatus = config.getLastStatus() != null ? config.getLastStatus() : "SUCCESS";
                    runningJobs.remove(jobName);
                    if (!"MANUAL".equals(triggerSource)) {
                        registry.advanceFireTime(jobName, finalStatus).subscribe();
                    }
                });
    }

    private Mono<Void> handleSuccess(ScheduledJobConfigEntity config, Long logId, JobResult result, LocalDateTime startTime) {
        String jobName = config.getJobName();
        registry.markComplete(jobName, "SUCCESS");

        pushEvent("scheduler.job.completed", Map.of(
                "jobName", jobName,
                "status", "SUCCESS",
                "durationMs", Duration.between(startTime, LocalDateTime.now()).toMillis(),
                "message", result.message() != null ? result.message() : ""
        ));

        return tracker.markSuccess(logId, result, startTime)
                .then(retryHandler.recordSuccess(config));
    }

    private Mono<Void> handleTimeout(ScheduledJobConfigEntity config, Long logId, LocalDateTime startTime, int retryCount) {
        String jobName = config.getJobName();
        registry.markComplete(jobName, "TIMEOUT");
        log.warn("Job '{}' timed out after {}ms", jobName, config.getTimeoutMs());

        pushEvent("scheduler.job.completed", Map.of(
                "jobName", jobName,
                "status", "TIMEOUT",
                "durationMs", Duration.between(startTime, LocalDateTime.now()).toMillis(),
                "message", "Execution timed out"
        ));

        TimeoutException timeoutEx = new TimeoutException("Job timed out after " + config.getTimeoutMs() + "ms");
        return tracker.markTimeout(logId, startTime)
                .then(retryHandler.recordFailure(config, logId, timeoutEx, startTime))
                .then(maybeRetry(config, timeoutEx, startTime, retryCount));
    }

    private Mono<Void> handleError(ScheduledJobConfigEntity config, Long logId, Throwable error, LocalDateTime startTime, int retryCount) {
        String jobName = config.getJobName();
        registry.markComplete(jobName, "FAILED");

        pushEvent("scheduler.job.completed", Map.of(
                "jobName", jobName,
                "status", "FAILED",
                "durationMs", Duration.between(startTime, LocalDateTime.now()).toMillis(),
                "message", error.getMessage() != null ? error.getMessage() : "Unknown error"
        ));

        return retryHandler.recordFailure(config, logId, error, startTime)
                .then(maybeRetry(config, error, startTime, retryCount));
    }

    private Mono<Void> maybeRetry(ScheduledJobConfigEntity config, Throwable error, LocalDateTime startTime, int currentRetry) {
        if (!retryHandler.shouldRetry(config, currentRetry)) {
            return Mono.empty();
        }
        Duration delay = retryHandler.getRetryDelay(config, currentRetry);

        return Mono.delay(delay)
                .flatMap(tick -> dispatchJob(config, "RETRY", currentRetry + 1))
                .then();
    }

    private void handleConfigChange(ConfigChangeEvent event) {
        registry.reloadJob(event.jobName()).subscribe();
    }

    private void pushEvent(String eventType, Map<String, Object> payload) {
        sessionRegistry.broadcast(eventType, payload);
    }

    private Map<String, Object> parseParams(String paramsJson) {
        if (paramsJson == null || paramsJson.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(paramsJson, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse job params: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private int maxConcurrentJobs() {
        IntelliMateProperties.Scheduler schedulerProps = properties.getScheduler();
        return schedulerProps != null ? schedulerProps.getMaxConcurrentJobs() : 8;
    }
}
