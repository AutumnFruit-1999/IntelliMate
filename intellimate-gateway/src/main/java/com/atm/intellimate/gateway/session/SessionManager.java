package com.atm.intellimate.gateway.session;

import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.SessionKey;
import com.atm.intellimate.core.model.SessionMetadata;
import com.atm.intellimate.gateway.entity.SessionEntity;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
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

    Mono<Long> findOrCreateProactiveSession(String agentName);

    Mono<SessionEntity> findActiveSession(String agentName);

    Mono<SessionEntity> archiveAndCreateNew(String agentName);

    Flux<SessionEntity> getArchivedSessions(String agentName, int limit, int offset);

    Mono<Long> countArchivedSessions(String agentName);
}
