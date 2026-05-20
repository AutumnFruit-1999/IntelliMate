package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.entity.ScheduledJobConfigEntity;
import com.atm.intellimate.gateway.entity.ScheduledJobLogEntity;
import com.atm.intellimate.gateway.repository.ScheduledJobConfigRepository;
import com.atm.intellimate.gateway.repository.ScheduledJobLogRepository;
import com.atm.intellimate.gateway.scheduler.CronCalculator;
import com.atm.intellimate.gateway.scheduler.ReactiveScheduleEngine;
import com.atm.intellimate.gateway.scheduler.TaskRegistry;
import com.atm.intellimate.gateway.scheduler.model.ConfigChangeEvent;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scheduled-jobs")
public class ScheduledJobController {

    private final ScheduledJobConfigRepository configRepo;
    private final ScheduledJobLogRepository logRepo;
    private final ReactiveScheduleEngine engine;
    private final TaskRegistry registry;
    private final CronCalculator cronCalculator;

    public ScheduledJobController(ScheduledJobConfigRepository configRepo,
                                  ScheduledJobLogRepository logRepo,
                                  ReactiveScheduleEngine engine,
                                  TaskRegistry registry,
                                  CronCalculator cronCalculator) {
        this.configRepo = configRepo;
        this.logRepo = logRepo;
        this.engine = engine;
        this.registry = registry;
        this.cronCalculator = cronCalculator;
    }

    @GetMapping
    public Flux<Map<String, Object>> listJobs() {
        List<ScheduledJobConfigEntity> cached = registry.getAllConfigs();
        if (!cached.isEmpty()) {
            return Flux.fromIterable(cached).map(this::toJobSummary);
        }
        return configRepo.findAll().map(this::toJobSummary);
    }

    @PostMapping
    public Mono<Map<String, Object>> createJob(@RequestBody Map<String, Object> body) {
        String jobName = (String) body.get("jobName");
        if (jobName == null || jobName.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "jobName is required"));
        }

        String jobClass = (String) body.getOrDefault("jobClass", "agent-prompt");
        if (registry.getJobBean(jobClass) == null && registry.getJobBean(jobName) == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported jobClass: " + jobClass));
        }

        return configRepo.findByJobName(jobName)
                .flatMap(existing -> Mono.<Map<String, Object>>error(
                        new ResponseStatusException(HttpStatus.CONFLICT, "Job already exists: " + jobName)))
                .switchIfEmpty(Mono.defer(() -> {
                    ScheduledJobConfigEntity config = new ScheduledJobConfigEntity();
                    config.setJobName(jobName);
                    config.setJobClass(jobClass);
                    config.setJobGroup((String) body.getOrDefault("jobGroup", "custom"));
                    config.setDisplayName((String) body.getOrDefault("displayName", jobName));
                    config.setDescription((String) body.get("description"));
                    config.setTriggerType((String) body.getOrDefault("triggerType", "FIXED_RATE"));
                    config.setTriggerValue((String) body.getOrDefault("triggerValue", "60000"));
                    config.setTimezone((String) body.getOrDefault("timezone", "Asia/Shanghai"));
                    config.setTimeoutMs(body.containsKey("timeoutMs") ? ((Number) body.get("timeoutMs")).longValue() : 120000L);
                    config.setMaxRetryCount(body.containsKey("maxRetryCount") ? ((Number) body.get("maxRetryCount")).intValue() : 0);
                    config.setRetryBackoffMs(body.containsKey("retryBackoffMs") ? ((Number) body.get("retryBackoffMs")).longValue() : 5000L);
                    config.setConcurrentAllowed(body.containsKey("concurrentAllowed") ? ((Number) body.get("concurrentAllowed")).intValue() : 0);
                    config.setParamsJson((String) body.get("paramsJson"));
                    config.setEnabled(1);

                    LocalDateTime next = cronCalculator.nextFireTime(
                            config.getTriggerType(), config.getTriggerValue(),
                            config.getTimezone(), LocalDateTime.now());
                    config.setNextFireTime(next);
                    config.setCreatedAt(LocalDateTime.now());
                    config.setUpdatedAt(LocalDateTime.now());
                    config.setConsecutiveFailures(0);

                    return configRepo.save(config)
                            .doOnNext(saved -> {
                                registry.registerJobBean(saved.getJobName(), registry.getJobBean(jobClass));
                                engine.emitConfigChange(new ConfigChangeEvent(jobName, ConfigChangeEvent.ChangeType.UPDATED));
                            })
                            .map(this::toJobSummary);
                }));
    }

    private static final java.util.Set<String> BUILT_IN_JOBS = java.util.Set.of(
            "heartbeat-tick", "memory-nightly-maintenance", "data-cleanup"
    );

    @DeleteMapping("/{jobName}")
    public Mono<Map<String, Object>> deleteJob(@PathVariable String jobName) {
        if (BUILT_IN_JOBS.contains(jobName)) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "Cannot delete built-in job: " + jobName));
        }
        return configRepo.findByJobName(jobName)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobName)))
                .flatMap(config -> configRepo.deleteById(config.getId())
                        .thenReturn(Map.<String, Object>of("status", "deleted", "jobName", jobName)));
    }

    @GetMapping("/{jobName}")
    public Mono<Map<String, Object>> getJob(@PathVariable String jobName) {
        return configRepo.findByJobName(jobName)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobName)))
                .flatMap(config -> logRepo.findByJobNamePaged(jobName, 5, 0)
                        .collectList()
                        .map(logs -> toJobDetail(config, logs)));
    }

    @PutMapping("/{jobName}")
    public Mono<Map<String, Object>> updateJob(@PathVariable String jobName, @RequestBody Map<String, Object> body) {
        return configRepo.findByJobName(jobName)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found: " + jobName)))
                .flatMap(config -> {
                    if (body.containsKey("displayName")) config.setDisplayName((String) body.get("displayName"));
                    if (body.containsKey("description")) config.setDescription((String) body.get("description"));
                    if (body.containsKey("triggerType")) config.setTriggerType((String) body.get("triggerType"));
                    if (body.containsKey("triggerValue")) config.setTriggerValue((String) body.get("triggerValue"));
                    if (body.containsKey("timeoutMs")) config.setTimeoutMs(((Number) body.get("timeoutMs")).longValue());
                    if (body.containsKey("maxRetryCount")) config.setMaxRetryCount(((Number) body.get("maxRetryCount")).intValue());
                    if (body.containsKey("retryBackoffMs")) config.setRetryBackoffMs(((Number) body.get("retryBackoffMs")).longValue());
                    if (body.containsKey("enabled")) config.setEnabled(((Number) body.get("enabled")).intValue());
                    if (body.containsKey("paramsJson")) config.setParamsJson((String) body.get("paramsJson"));

                    LocalDateTime next = cronCalculator.nextFireTime(
                            config.getTriggerType(), config.getTriggerValue(),
                            config.getTimezone() != null ? config.getTimezone() : "Asia/Shanghai",
                            LocalDateTime.now());
                    config.setNextFireTime(next);
                    config.setUpdatedAt(LocalDateTime.now());

                    return configRepo.save(config);
                })
                .doOnNext(saved -> engine.emitConfigChange(
                        new ConfigChangeEvent(jobName, ConfigChangeEvent.ChangeType.UPDATED)))
                .map(this::toJobSummary);
    }

    @PostMapping("/{jobName}/trigger")
    public Mono<Map<String, Object>> triggerJob(@PathVariable String jobName) {
        return engine.triggerManually(jobName)
                .thenReturn(Map.<String, Object>of("status", "triggered", "jobName", jobName));
    }

    @PostMapping("/{jobName}/pause")
    public Mono<Map<String, Object>> pauseJob(@PathVariable String jobName) {
        return configRepo.updateEnabledStatus(jobName, 0, null)
                .doOnSuccess(v -> engine.emitConfigChange(
                        new ConfigChangeEvent(jobName, ConfigChangeEvent.ChangeType.PAUSED)))
                .thenReturn(Map.<String, Object>of("status", "paused", "jobName", jobName));
    }

    @PostMapping("/{jobName}/resume")
    public Mono<Map<String, Object>> resumeJob(@PathVariable String jobName) {
        return configRepo.findByJobName(jobName)
                .flatMap(config -> {
                    LocalDateTime next = cronCalculator.nextFireTime(
                            config.getTriggerType(), config.getTriggerValue(),
                            config.getTimezone() != null ? config.getTimezone() : "Asia/Shanghai",
                            LocalDateTime.now());
                    return configRepo.updateEnabledStatus(jobName, 1, next);
                })
                .doOnSuccess(v -> engine.emitConfigChange(
                        new ConfigChangeEvent(jobName, ConfigChangeEvent.ChangeType.RESUMED)))
                .thenReturn(Map.<String, Object>of("status", "resumed", "jobName", jobName));
    }

    @GetMapping("/{jobName}/logs")
    public Flux<ScheduledJobLogEntity> getJobLogs(
            @PathVariable String jobName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return logRepo.findByJobNamePaged(jobName, size, page * size);
    }

    @GetMapping("/{jobName}/logs/{logId}")
    public Mono<ScheduledJobLogEntity> getLogDetail(@PathVariable String jobName, @PathVariable Long logId) {
        return logRepo.findById(logId)
                .filter(log -> jobName.equals(log.getJobName()))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Log not found for job: " + jobName)));
    }

    @GetMapping("/logs/recent")
    public Flux<ScheduledJobLogEntity> getRecentLogs(@RequestParam(defaultValue = "50") int limit) {
        return logRepo.findRecentLogs(Math.min(limit, 200));
    }

    @GetMapping("/stats/overview")
    public Mono<Map<String, Object>> getOverview() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        return Mono.zip(
                configRepo.count(),
                configRepo.findByEnabled(1).count(),
                logRepo.countSince(todayStart),
                logRepo.countByStatusSince("SUCCESS", todayStart),
                logRepo.countByStatusSince("FAILED", todayStart),
                logRepo.countByStatusSince("TIMEOUT", todayStart)
        ).map(tuple -> {
            long total = tuple.getT1();
            long enabled = tuple.getT2();
            long todayExec = tuple.getT3();
            long todaySuccess = tuple.getT4();
            long todayFailed = tuple.getT5();
            long todayTimeout = tuple.getT6();
            double successRate = todayExec > 0 ? (double) todaySuccess / todayExec : 1.0;

            Map<String, Object> result = new HashMap<>();
            result.put("totalJobs", total);
            result.put("enabledJobs", enabled);
            result.put("todayExecutions", todayExec);
            result.put("todaySuccessRate", Math.round(successRate * 1000.0) / 1000.0);
            result.put("todayFailures", todayFailed);
            result.put("todayTimeouts", todayTimeout);
            result.put("currentlyRunning", registry.getAllConfigs().stream()
                    .filter(c -> engine.isJobCurrentlyRunning(c.getJobName())).count());
            return result;
        });
    }

    @GetMapping("/stats/{jobName}")
    public Mono<Map<String, Object>> getJobStats(
            @PathVariable String jobName,
            @RequestParam(defaultValue = "7") int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return logRepo.findByJobNameSince(jobName, since)
                .collectList()
                .map(logs -> {
                    long total = logs.size();
                    long success = logs.stream().filter(l -> "SUCCESS".equals(l.getStatus())).count();
                    double avgDuration = logs.stream()
                            .filter(l -> l.getDurationMs() != null)
                            .mapToLong(ScheduledJobLogEntity::getDurationMs)
                            .average().orElse(0);
                    long maxDuration = logs.stream()
                            .filter(l -> l.getDurationMs() != null)
                            .mapToLong(ScheduledJobLogEntity::getDurationMs)
                            .max().orElse(0);

                    Map<String, Object> result = new HashMap<>();
                    result.put("jobName", jobName);
                    result.put("days", days);
                    result.put("totalExecutions", total);
                    result.put("successCount", success);
                    result.put("successRate", total > 0 ? (double) success / total : 1.0);
                    result.put("avgDurationMs", Math.round(avgDuration));
                    result.put("maxDurationMs", maxDuration);
                    return result;
                });
    }

    @GetMapping("/stats/timeline")
    public Flux<ScheduledJobLogEntity> getTimeline(@RequestParam(defaultValue = "24") int hours) {
        LocalDateTime since = LocalDateTime.now().minusHours(hours);
        return logRepo.findTimelineSince(since);
    }

    private Map<String, Object> toJobSummary(ScheduledJobConfigEntity config) {
        Map<String, Object> map = new HashMap<>();
        map.put("jobName", config.getJobName());
        map.put("jobGroup", config.getJobGroup());
        map.put("displayName", config.getDisplayName());
        map.put("description", config.getDescription());
        map.put("triggerType", config.getTriggerType());
        map.put("triggerValue", config.getTriggerValue());
        map.put("enabled", config.getEnabled() != null && config.getEnabled() == 1);
        map.put("nextFireTime", config.getNextFireTime());
        map.put("lastFireTime", config.getLastFireTime());
        map.put("lastStatus", config.getLastStatus());
        map.put("consecutiveFailures", config.getConsecutiveFailures());
        map.put("running", engine.isJobCurrentlyRunning(config.getJobName()));
        map.put("timeoutMs", config.getTimeoutMs());
        map.put("maxRetryCount", config.getMaxRetryCount());
        map.put("retryBackoffMs", config.getRetryBackoffMs());
        return map;
    }

    private Map<String, Object> toJobDetail(ScheduledJobConfigEntity config, List<ScheduledJobLogEntity> recentLogs) {
        Map<String, Object> map = toJobSummary(config);
        map.put("description", config.getDescription());
        map.put("jobClass", config.getJobClass());
        map.put("timezone", config.getTimezone());
        map.put("paramsJson", config.getParamsJson());
        map.put("concurrentAllowed", config.getConcurrentAllowed() != null && config.getConcurrentAllowed() == 1);
        map.put("recentLogs", recentLogs);
        return map;
    }
}
