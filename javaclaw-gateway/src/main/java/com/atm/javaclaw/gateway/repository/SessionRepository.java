package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.SessionEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface SessionRepository extends ReactiveCrudRepository<SessionEntity, Long> {

    Mono<SessionEntity> findByChannelIdAndContextTypeAndContextIdAndDeleted(
            String channelId, String contextType, String contextId, Integer deleted);

    default Mono<SessionEntity> findBySessionKey(String channelId, String contextType, String contextId) {
        return findByChannelIdAndContextTypeAndContextIdAndDeleted(channelId, contextType, contextId, 0);
    }
}
