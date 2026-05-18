package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.HeartbeatConfigEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface HeartbeatConfigRepository extends ReactiveCrudRepository<HeartbeatConfigEntity, Long> {

    Mono<HeartbeatConfigEntity> findByAgentId(Long agentId);

    Flux<HeartbeatConfigEntity> findAllByEnabled(Integer enabled);
}
