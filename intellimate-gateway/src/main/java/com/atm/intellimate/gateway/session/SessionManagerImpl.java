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
                    if (existing.getStatus() == null) {
                        existing.setStatus("active");
                    }
                    return sessionRepository.save(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    SessionEntity session = new SessionEntity();
                    session.setChannelId(key.channelId());
                    session.setContextType(key.contextType());
                    session.setContextId(key.contextId());
                    session.setAgentName(metadata.agentName());
                    session.setStatus("active");
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
        return findActiveSession(agentName)
                .map(SessionEntity::getId)
                .switchIfEmpty(Mono.defer(() -> {
                    SessionEntity newSession = new SessionEntity();
                    newSession.setChannelId("webchat");
                    newSession.setContextType("dm");
                    newSession.setContextId(agentName);
                    newSession.setAgentName(agentName);
                    newSession.setStatus("active");
                    newSession.setLastActiveAt(LocalDateTime.now());
                    newSession.setCreatedAt(LocalDateTime.now());
                    newSession.setDeleted(0);
                    return sessionRepository.save(newSession)
                            .doOnSuccess(s -> log.info("Created active session for proactive message: id={}, agent={}",
                                    s.getId(), agentName))
                            .map(SessionEntity::getId);
                }));
    }

    @Override
    public Mono<SessionEntity> findActiveSession(String agentName) {
        return sessionRepository.findActiveByAgentName(agentName)
                .switchIfEmpty(Mono.defer(() -> {
                    // 兼容旧数据：查找没有明确 status 的旧 session 并设为 active
                    return sessionRepository.findBySessionKey("webchat", "dm", agentName)
                            .flatMap(s -> {
                                s.setStatus("active");
                                return sessionRepository.save(s);
                            });
                }));
    }

    @Override
    public Mono<SessionEntity> archiveAndCreateNew(String agentName) {
        return findActiveSession(agentName)
                .flatMap(activeSession ->
                    transcriptRepository.findRecentBySessionIdNoPlanAfter(
                            activeSession.getId(),
                            activeSession.getCreatedAt(),
                            50
                    )
                    .filter(m -> "user".equals(m.getRole()))
                    .next()
                    .flatMap(firstMsg -> {
                        String content = firstMsg.getContent();
                        String title = content.length() > 30 ? content.substring(0, 30) : content;
                        return sessionRepository.archiveSession(activeSession.getId(), title)
                                .then(Mono.defer(() -> createNewActiveSession(agentName)));
                    })
                    .switchIfEmpty(Mono.defer(() -> {
                        // No user messages — reset session instead of archiving
                        return transcriptRepository.deleteBySessionId(activeSession.getId())
                                .then(Mono.defer(() -> {
                                    activeSession.setCreatedAt(LocalDateTime.now());
                                    activeSession.setLastActiveAt(LocalDateTime.now());
                                    return sessionRepository.save(activeSession);
                                }));
                    }))
                )
                .switchIfEmpty(Mono.defer(() -> createNewActiveSession(agentName)));
    }

    @Override
    public Flux<SessionEntity> getArchivedSessions(String agentName, int limit, int offset) {
        return sessionRepository.findArchivedByAgentName(agentName, limit, offset);
    }

    @Override
    public Mono<Long> countArchivedSessions(String agentName) {
        return sessionRepository.countArchivedByAgentName(agentName);
    }

    @Override
    public Mono<Void> deleteArchivedSession(Long sessionId) {
        return sessionRepository.findById(sessionId)
                .flatMap(session -> {
                    session.setDeleted(1);
                    return sessionRepository.save(session);
                })
                .then(transcriptRepository.deleteBySessionId(sessionId))
                .doOnSuccess(v -> log.info("Deleted archived session: id={}", sessionId));
    }

    private Mono<SessionEntity> createNewActiveSession(String agentName) {
        SessionEntity newSession = new SessionEntity();
        newSession.setChannelId("webchat");
        newSession.setContextType("dm");
        newSession.setContextId(agentName);
        newSession.setAgentName(agentName);
        newSession.setStatus("active");
        newSession.setLastActiveAt(LocalDateTime.now());
        newSession.setCreatedAt(LocalDateTime.now());
        newSession.setDeleted(0);
        return sessionRepository.save(newSession)
                .doOnSuccess(s -> log.info("Created new active session: id={}, agent={}", s.getId(), agentName))
                .onErrorResume(org.springframework.dao.DuplicateKeyException.class, e -> {
                    log.warn("Duplicate session key for agent '{}', fetching existing", agentName);
                    return sessionRepository.findActiveByAgentName(agentName);
                });
    }
}
