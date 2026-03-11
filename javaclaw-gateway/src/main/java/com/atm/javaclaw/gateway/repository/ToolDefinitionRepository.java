package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.ToolDefinitionEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ToolDefinitionRepository extends ReactiveCrudRepository<ToolDefinitionEntity, Long> {

    Flux<ToolDefinitionEntity> findAllByEnabled(Integer enabled);

    Mono<ToolDefinitionEntity> findByName(String name);
}
