package com.atm.intellimate.gateway.scheduler.jobs;

import com.atm.intellimate.gateway.scheduler.ScheduledJob;
import com.atm.intellimate.gateway.scheduler.model.JobExecutionContext;
import com.atm.intellimate.gateway.scheduler.model.JobResult;
import com.atm.intellimate.gateway.service.DailyMemoryConsolidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Component
public class DailyMemoryConsolidationJob implements ScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(DailyMemoryConsolidationJob.class);

    private final DailyMemoryConsolidator consolidator;

    public DailyMemoryConsolidationJob(DailyMemoryConsolidator consolidator) {
        this.consolidator = consolidator;
    }

    @Override
    public String getJobName() {
        return "memory-daily-consolidation";
    }

    @Override
    public String getJobGroup() {
        return "data";
    }

    @Override
    public Duration getDefaultTimeout() {
        return Duration.ofMinutes(30);
    }

    @Override
    public Mono<JobResult> execute(JobExecutionContext context) {
        log.info("[每日整合] 开始执行每日记忆整合任务");
        return consolidator.consolidateAll()
                .map(stats -> {
                    log.info("[每日整合] 完成: {}", stats);
                    Map<String, Object> metrics = new HashMap<>();
                    stats.forEach(metrics::put);
                    return JobResult.ok("Daily memory consolidation completed", metrics);
                })
                .onErrorResume(e -> {
                    log.error("[每日整合] 任务失败", e);
                    return Mono.just(JobResult.fail("Consolidation failed: " + e.getMessage()));
                });
    }
}
