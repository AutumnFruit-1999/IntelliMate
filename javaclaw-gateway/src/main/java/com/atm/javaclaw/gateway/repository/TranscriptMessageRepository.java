package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.TranscriptMessageEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TranscriptMessageRepository extends ReactiveCrudRepository<TranscriptMessageEntity, Long> {

    @Query("SELECT * FROM transcript_message WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit")
    Flux<TranscriptMessageEntity> findRecentBySessionId(Long sessionId, int limit);

    Mono<Void> deleteBySessionId(Long sessionId);
}
