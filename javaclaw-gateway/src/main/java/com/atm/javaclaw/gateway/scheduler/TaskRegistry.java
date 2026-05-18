package com.atm.javaclaw.gateway.scheduler;

import com.atm.javaclaw.gateway.entity.ScheduledJobConfigEntity;
import com.atm.javaclaw.gateway.repository.ScheduledJobConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(TaskRegistry.class);

    private final ScheduledJobConfigRepository configRepo;
    private final CronCalculator cronCalculator;
    private final Map<String, ScheduledJobConfigEntity> cache = new ConcurrentHashMap<>();
    private final Map<String, ScheduledJob> jobBeans = new ConcurrentHashMap<>();

    public TaskRegistry(ScheduledJobConfigRepository configRepo, CronCalculator cronCalculator) {
        this.configRepo = configRepo;
        this.cronCalculator = cronCalculator;
    }

    public void registerJobBean(String jobName, ScheduledJob job) {
        jobBeans.put(jobName, job);
        log.info("Registered job bean: {}", jobName);
    }

    public ScheduledJob getJobBean(String jobName) {
        ScheduledJob bean = jobBeans.get(jobName);
        if (bean != null) return bean;

        ScheduledJobConfigEntity config = cache.get(jobName);
        if (config != null && config.getJobClass() != null) {
            return jobBeans.get(config.getJobClass());
        }
        return null;
    }

    public Map<String, ScheduledJob> getAllJobBeans() {
        return jobBeans;
    }

    public Mono<Void> loadAll() {
        return configRepo.findAll()
                .doOnNext(config -> {
                    cache.put(config.getJobName(), config);
                    if (config.getJobClass() != null && !config.getJobClass().isBlank()) {
                        ScheduledJob delegateBean = jobBeans.get(config.getJobClass());
                        if (delegateBean != null && !jobBeans.containsKey(config.getJobName())) {
                            jobBeans.put(config.getJobName(), delegateBean);
                        }
                    }
                })
                .then()
                .doOnSuccess(v -> log.info("Loaded {} job configs into cache", cache.size()));
    }

    public Mono<Void> reloadJob(String jobName) {
        return configRepo.findByJobName(jobName)
                .doOnNext(config -> {
                    cache.put(config.getJobName(), config);
                    log.debug("Reloaded config for job: {}", jobName);
                })
                .then();
    }

    public Flux<ScheduledJobConfigEntity> getDueJobs(LocalDateTime now) {
        return Flux.fromIterable(cache.values())
                .filter(c -> c.getEnabled() != null && c.getEnabled() == 1)
                .filter(c -> c.getNextFireTime() != null && !c.getNextFireTime().isAfter(now));
    }

    public List<ScheduledJobConfigEntity> getAllConfigs() {
        return List.copyOf(cache.values());
    }

    public ScheduledJobConfigEntity getConfig(String jobName) {
        return cache.get(jobName);
    }

    public Mono<Void> initializeNextFireTimes() {
        LocalDateTime now = LocalDateTime.now();
        return Flux.fromIterable(cache.values())
                .filter(c -> c.getEnabled() != null && c.getEnabled() == 1)
                .filter(c -> c.getNextFireTime() == null)
                .flatMap(c -> {
                    LocalDateTime next = cronCalculator.nextFireTime(
                            c.getTriggerType(), c.getTriggerValue(),
                            c.getTimezone() != null ? c.getTimezone() : "Asia/Shanghai", now);
                    c.setNextFireTime(next);
                    return configRepo.updateFireStatus(c.getJobName(), next, c.getLastFireTime(), c.getLastStatus());
                })
                .then();
    }

    public Mono<Void> advanceFireTime(String jobName, String actualStatus) {
        ScheduledJobConfigEntity config = cache.get(jobName);
        if (config == null) return Mono.empty();

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime next = cronCalculator.nextFireTime(
                config.getTriggerType(), config.getTriggerValue(),
                config.getTimezone() != null ? config.getTimezone() : "Asia/Shanghai", now);
        config.setNextFireTime(next);
        config.setLastFireTime(now);

        return configRepo.updateFireStatus(jobName, next, now, actualStatus);
    }

    public boolean isJobRunning(String jobName) {
        ScheduledJobConfigEntity config = cache.get(jobName);
        return config != null && "RUNNING".equals(config.getLastStatus());
    }

    public void markRunning(String jobName) {
        ScheduledJobConfigEntity config = cache.get(jobName);
        if (config != null) {
            config.setLastStatus("RUNNING");
        }
    }

    public void markComplete(String jobName, String status) {
        ScheduledJobConfigEntity config = cache.get(jobName);
        if (config != null) {
            config.setLastStatus(status);
        }
    }
}
