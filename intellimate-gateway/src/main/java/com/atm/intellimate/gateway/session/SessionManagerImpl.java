package com.atm.intellimate.gateway.session;

import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.SessionKey;
import com.atm.intellimate.core.model.SessionMetadata;
import com.atm.intellimate.gateway.entity.SessionEntity;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.repository.SessionRepository;
import com.atm.intellimate.gateway.repository.TranscriptMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Comparator;

@Service
public class SessionManagerImpl implements SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManagerImpl.class);

    private final SessionRepository sessionRepository;
    private final TranscriptMessageRepository transcriptRepository;

    public SessionManagerImpl(SessionRepository sessionRepository,
                              TranscriptMessageRepository transcriptRepository) {
        this.sessionRepository = sessionRepository;
        this.transcriptRepository = transcriptRepository;
    }

    @Override
    public Mono<SessionEntity> resolveSession(InboundEnvelope envelope) {
        SessionKey key = envelope.sessionKey();
        SessionMetadata meta = new SessionMetadata(
                null, envelope.senderName(),
                key.channelId(), key.contextType(), key.contextId()
        );
        return getOrCreate(key, meta);
    }

    @Override
    public Mono<SessionEntity> getOrCreate(SessionKey key, SessionMetadata metadata) {
        return sessionRepository.findBySessionKey(key.channelId(), key.contextType(), key.contextId())
                .flatMap(existing -> {
                    existing.setLastActiveAt(LocalDateTime.now());
                    return sessionRepository.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    SessionEntity session = new SessionEntity();
                    session.setChannelId(key.channelId());
                    session.setContextType(key.contextType());
                    session.setContextId(key.contextId());
                    session.setAgentName(metadata.agentName());
                    session.setLastActiveAt(LocalDateTime.now());
                    session.setCreatedAt(LocalDateTime.now());
                    session.setDeleted(0);
                    return sessionRepository.save(session)
                            .doOnSuccess(s -> log.info("Created new session: id={}, key={}",
                                    s.getId(), key.toCompositeKey()));
                }));
    }

    @Override
    public Mono<Void> appendMessage(Long sessionId, TranscriptMessageEntity message) {
        message.setSessionId(sessionId);
        return transcriptRepository.save(message).then();
    }

    @Override
    public Flux<TranscriptMessageEntity> getHistory(Long sessionId, int limit) {
        return transcriptRepository.findRecentBySessionId(sessionId, limit)
                .sort(Comparator.comparing(TranscriptMessageEntity::getCreatedAt));
    }

    @Override
    public Flux<TranscriptMessageEntity> getPlanHistory(Long sessionId, Long planId, int limit) {
        return transcriptRepository.findRecentBySessionIdAndPlanId(sessionId, planId, limit)
                .sort(Comparator.comparing(TranscriptMessageEntity::getCreatedAt));
    }

    @Override
    public Flux<TranscriptMessageEntity> getChatHistory(Long sessionId, int limit) {
        return transcriptRepository.findRecentBySessionIdNoPlan(sessionId, limit)
                .sort(Comparator.comparing(TranscriptMessageEntity::getCreatedAt));
    }

    @Override
    public Mono<Void> resetSession(Long sessionId) {
        return transcriptRepository.deleteBySessionId(sessionId)
                .doOnSuccess(v -> log.info("Reset session: id={}", sessionId));
    }

    @Override
    public Mono<Long> findOrCreateProactiveSession(String agentName) {
        String channelId = "webchat";
        String contextType = "dm";
        String contextId = "proactive::" + agentName;

        return sessionRepository.findBySessionKey(channelId, contextType, contextId)
                .map(SessionEntity::getId)
                .switchIfEmpty(Mono.defer(() -> {
                    SessionEntity newSession = new SessionEntity();
                    newSession.setChannelId(channelId);
                    newSession.setContextType(contextType);
                    newSession.setContextId(contextId);
                    newSession.setAgentName(agentName);
                    newSession.setLastActiveAt(LocalDateTime.now());
                    newSession.setCreatedAt(LocalDateTime.now());
                    newSession.setDeleted(0);
                    return sessionRepository.save(newSession)
                            .doOnSuccess(s -> log.info("Created proactive session: id={}, agent={}",
                                    s.getId(), agentName))
                            .map(SessionEntity::getId);
                }));
    }
}
