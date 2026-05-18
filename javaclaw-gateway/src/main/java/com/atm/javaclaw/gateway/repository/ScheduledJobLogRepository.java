package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.ScheduledJobLogEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface ScheduledJobLogRepository extends ReactiveCrudRepository<ScheduledJobLogEntity, Long> {

    Flux<ScheduledJobLogEntity> findByJobNameOrderByFireTimeDesc(String jobName);

    @Query("SELECT * FROM scheduled_job_log WHERE job_name = :jobName ORDER BY fire_time DESC LIMIT :limit OFFSET :offset")
    Flux<ScheduledJobLogEntity> findByJobNamePaged(String jobName, int limit, int offset);

    @Query("SELECT COUNT(*) FROM scheduled_job_log WHERE job_name = :jobName")
    Mono<Long> countByJobName(String jobName);

    @Query("SELECT * FROM scheduled_job_log ORDER BY created_at DESC LIMIT :limit")
    Flux<ScheduledJobLogEntity> findRecentLogs(int limit);

    @Query("SELECT COUNT(*) FROM scheduled_job_log WHERE created_at >= :since AND status = :status")
    Mono<Long> countByStatusSince(String status, LocalDateTime since);

    @Query("SELECT COUNT(*) FROM scheduled_job_log WHERE created_at >= :since")
    Mono<Long> countSince(LocalDateTime since);

    @Query("SELECT * FROM scheduled_job_log WHERE job_name = :jobName AND status = 'RUNNING'")
    Flux<ScheduledJobLogEntity> findRunningByJobName(String jobName);

    @Modifying
    @Query("UPDATE scheduled_job_log SET status = :status, end_time = :endTime, duration_ms = :durationMs, result_message = :resultMessage, metrics_json = :metricsJson WHERE id = :id")
    Mono<Void> updateSuccess(Long id, String status, LocalDateTime endTime, Long durationMs, String resultMessage, String metricsJson);

    @Modifying
    @Query("UPDATE scheduled_job_log SET status = :status, end_time = :endTime, duration_ms = :durationMs, error_message = :errorMessage, error_stack = :errorStack WHERE id = :id")
    Mono<Void> updateFailure(Long id, String status, LocalDateTime endTime, Long durationMs, String errorMessage, String errorStack);

    @Modifying
    @Query("DELETE FROM scheduled_job_log WHERE created_at < :before")
    Mono<Long> deleteOlderThan(LocalDateTime before);

    @Query("SELECT * FROM scheduled_job_log WHERE job_name = :jobName AND created_at >= :since ORDER BY fire_time DESC")
    Flux<ScheduledJobLogEntity> findByJobNameSince(String jobName, LocalDateTime since);

    @Query("SELECT job_name, COUNT(*) as cnt, SUM(CASE WHEN status='SUCCESS' THEN 1 ELSE 0 END) as success_cnt, AVG(duration_ms) as avg_duration, MAX(duration_ms) as max_duration FROM scheduled_job_log WHERE job_name = :jobName AND created_at >= :since GROUP BY job_name")
    Mono<java.util.Map<String, Object>> getJobStats(String jobName, LocalDateTime since);

    @Query("SELECT * FROM scheduled_job_log WHERE start_time >= :since AND end_time IS NOT NULL ORDER BY start_time")
    Flux<ScheduledJobLogEntity> findTimelineSince(LocalDateTime since);
}
