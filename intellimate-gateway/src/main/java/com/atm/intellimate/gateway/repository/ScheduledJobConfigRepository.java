package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.ScheduledJobConfigEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface ScheduledJobConfigRepository extends ReactiveCrudRepository<ScheduledJobConfigEntity, Long> {

    Mono<ScheduledJobConfigEntity> findByJobName(String jobName);

    Flux<ScheduledJobConfigEntity> findByEnabled(Integer enabled);

    Flux<ScheduledJobConfigEntity> findByJobGroup(String jobGroup);

    Flux<ScheduledJobConfigEntity> findByEnabledAndJobGroup(Integer enabled, String jobGroup);

    @Query("SELECT * FROM scheduled_job_config WHERE enabled = 1 AND next_fire_time IS NOT NULL AND next_fire_time <= :now")
    Flux<ScheduledJobConfigEntity> findDueJobs(LocalDateTime now);

    @Modifying
    @Query("UPDATE scheduled_job_config SET next_fire_time = :nextFireTime, last_fire_time = :lastFireTime, last_status = :status, updated_at = NOW() WHERE job_name = :jobName")
    Mono<Void> updateFireStatus(String jobName, LocalDateTime nextFireTime, LocalDateTime lastFireTime, String status);

    @Modifying
    @Query("UPDATE scheduled_job_config SET consecutive_failures = :failures, last_status = :status, updated_at = NOW() WHERE job_name = :jobName")
    Mono<Void> updateFailureStatus(String jobName, Integer failures, String status);

    @Modifying
    @Query("UPDATE scheduled_job_config SET consecutive_failures = 0, last_status = 'SUCCESS', updated_at = NOW() WHERE job_name = :jobName")
    Mono<Void> resetFailureStatus(String jobName);

    @Modifying
    @Query("UPDATE scheduled_job_config SET enabled = :enabled, next_fire_time = :nextFireTime, updated_at = NOW() WHERE job_name = :jobName")
    Mono<Void> updateEnabledStatus(String jobName, Integer enabled, LocalDateTime nextFireTime);
}
