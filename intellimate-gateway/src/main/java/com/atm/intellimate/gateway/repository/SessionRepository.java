package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.SessionEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SessionRepository extends ReactiveCrudRepository<SessionEntity, Long> {

    Mono<SessionEntity> findByChannelIdAndContextTypeAndContextIdAndDeleted(
            String channelId, String contextType, String contextId, Integer deleted);

    default Mono<SessionEntity> findBySessionKey(String channelId, String contextType, String contextId) {
        return findByChannelIdAndContextTypeAndContextIdAndDeleted(channelId, contextType, contextId, 0);
    }

    @Query("SELECT * FROM session WHERE agent_name = :agentName AND status = 'active' AND deleted = 0 AND channel_id IN ('unified', 'webchat') ORDER BY CASE channel_id WHEN 'unified' THEN 0 ELSE 1 END, last_active_at DESC LIMIT 1")
    Mono<SessionEntity> findActiveByAgentName(String agentName);

    @Query("SELECT * FROM session WHERE agent_name = :agentName AND status = 'archived' AND deleted = 0 AND channel_id IN ('unified', 'webchat') ORDER BY last_active_at DESC LIMIT :limit OFFSET :offset")
    Flux<SessionEntity> findArchivedByAgentName(String agentName, int limit, int offset);

    @Query("SELECT COUNT(*) FROM session WHERE agent_name = :agentName AND status = 'archived' AND deleted = 0 AND channel_id IN ('unified', 'webchat')")
    Mono<Long> countArchivedByAgentName(String agentName);

    @Query("SELECT * FROM session WHERE agent_name = :agentName AND status = 'active' AND deleted = 0 AND channel_id != 'webchat'")
    Flux<SessionEntity> findExternalChannelSessionsByAgentName(String agentName);

    @Modifying
    @Query("UPDATE session SET status = 'archived', title = :title, context_id = CONCAT(context_id, '::archived::', id) WHERE id = :id")
    Mono<Long> archiveSession(Long id, String title);
}
