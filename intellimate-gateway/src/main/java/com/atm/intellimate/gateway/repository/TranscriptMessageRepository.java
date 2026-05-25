package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

public interface TranscriptMessageRepository extends ReactiveCrudRepository<TranscriptMessageEntity, Long> {

    @Query("SELECT * FROM transcript_message WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit")
    Flux<TranscriptMessageEntity> findRecentBySessionId(Long sessionId, int limit);

    @Query("SELECT * FROM transcript_message WHERE session_id = :sessionId AND id < :beforeId ORDER BY created_at DESC LIMIT :limit")
    Flux<TranscriptMessageEntity> findRecentBySessionIdBeforeId(Long sessionId, Long beforeId, int limit);

    @Query("SELECT * FROM transcript_message WHERE session_id = :sessionId AND plan_id = :planId ORDER BY created_at DESC LIMIT :limit")
    Flux<TranscriptMessageEntity> findRecentBySessionIdAndPlanId(Long sessionId, Long planId, int limit);

    @Query("SELECT * FROM transcript_message WHERE session_id = :sessionId AND plan_id IS NULL ORDER BY created_at DESC LIMIT :limit")
    Flux<TranscriptMessageEntity> findRecentBySessionIdNoPlan(Long sessionId, int limit);

    Mono<Void> deleteBySessionId(Long sessionId);

    @Modifying
    @Query("DELETE FROM transcript_message WHERE session_id IN " +
           "(SELECT id FROM session WHERE context_id LIKE 'proactive::%') " +
           "AND created_at < :before")
    Mono<Long> deleteExpiredProactiveMessages(LocalDateTime before);

    @Query("SELECT * FROM transcript_message WHERE session_id = :sessionId AND plan_id IS NULL AND created_at > :after ORDER BY created_at ASC LIMIT :limit")
    Flux<TranscriptMessageEntity> findRecentBySessionIdNoPlanAfter(Long sessionId, LocalDateTime after, int limit);
}
