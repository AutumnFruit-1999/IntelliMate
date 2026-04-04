package com.atm.javaclaw.gateway.session;

import com.atm.javaclaw.core.model.InboundEnvelope;
import com.atm.javaclaw.core.model.SessionKey;
import com.atm.javaclaw.core.model.SessionMetadata;
import com.atm.javaclaw.gateway.entity.SessionEntity;
import com.atm.javaclaw.gateway.entity.TranscriptMessageEntity;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SessionManager {

    Mono<SessionEntity> resolveSession(InboundEnvelope envelope);

    Mono<SessionEntity> getOrCreate(SessionKey key, SessionMetadata metadata);

    Mono<Void> appendMessage(Long sessionId, TranscriptMessageEntity message);

    Flux<TranscriptMessageEntity> getHistory(Long sessionId, int limit);

    Flux<TranscriptMessageEntity> getPlanHistory(Long sessionId, Long planId, int limit);

    Flux<TranscriptMessageEntity> getChatHistory(Long sessionId, int limit);

    Mono<Void> resetSession(Long sessionId);
}
