package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.McpServerEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface McpServerRepository extends ReactiveCrudRepository<McpServerEntity, Long> {

    Flux<McpServerEntity> findAllByEnabled(Integer enabled);

    Mono<McpServerEntity> findByName(String name);
}
