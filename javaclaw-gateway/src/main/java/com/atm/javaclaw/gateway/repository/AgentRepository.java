package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.AgentEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface AgentRepository extends ReactiveCrudRepository<AgentEntity, Long> {

    Mono<AgentEntity> findByNameAndDeleted(String name, Integer deleted);

    default Mono<AgentEntity> findByName(String name) {
        return findByNameAndDeleted(name, 0);
    }

    @Modifying
    @Query("UPDATE agent SET soul_md = :soulMd, user_md = :userMd, agents_md = :agentsMd WHERE name = :name AND deleted = 0")
    Mono<Integer> updateContextByName(String name, String soulMd, String userMd, String agentsMd);
}
