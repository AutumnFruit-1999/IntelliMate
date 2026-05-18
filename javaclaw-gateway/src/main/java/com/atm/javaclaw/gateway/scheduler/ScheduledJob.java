package com.atm.javaclaw.gateway.scheduler;

import com.atm.javaclaw.gateway.scheduler.model.JobExecutionContext;
import com.atm.javaclaw.gateway.scheduler.model.JobResult;
import reactor.core.publisher.Mono;

import java.time.Duration;

public interface ScheduledJob {

    String getJobName();

    Mono<JobResult> execute(JobExecutionContext context);

    default String getJobGroup() { return "system"; }

    default Duration getDefaultTimeout() { return Duration.ofMinutes(5); }

    default boolean allowConcurrent() { return false; }
}
