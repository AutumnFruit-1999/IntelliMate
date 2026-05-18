package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.AgentMemoryArchiveEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface AgentMemoryArchiveRepository extends ReactiveCrudRepository<AgentMemoryArchiveEntity, Long> {

    Flux<AgentMemoryArchiveEntity> findByUserId(String userId);

    Flux<AgentMemoryArchiveEntity> findByUserIdAndMemoryType(String userId, String memoryType);

    Flux<AgentMemoryArchiveEntity> findByUserIdAndAgentId(String userId, String agentId);
}
