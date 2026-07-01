package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.MemoryConfigEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface MemoryConfigRepository extends ReactiveCrudRepository<MemoryConfigEntity, Long> {

    Mono<MemoryConfigEntity> findByConfigKey(String configKey);

    Mono<MemoryConfigEntity> findByAgentNameAndConfigKey(String agentName, String configKey);

    Flux<MemoryConfigEntity> findByAgentName(String agentName);

    @Modifying
    @Query("UPDATE memory_config SET config_value = :value, updated_at = NOW() WHERE config_key = :key AND agent_name = :agentName")
    Mono<Integer> updateByAgentAndKey(String agentName, String key, String value);

    @Modifying
    @Query("""
        INSERT INTO memory_config (agent_name, config_key, config_value, description)
        VALUES (:agentName, :key, :value, :description)
        ON DUPLICATE KEY UPDATE config_value = :value, updated_at = NOW()
    """)
    Mono<Integer> upsertForAgent(String agentName, String key, String value, String description);

    @Modifying
    @Query("DELETE FROM memory_config WHERE agent_name = :agentName")
    Mono<Integer> deleteByAgentName(String agentName);

    @Modifying
    @Query("""
        INSERT INTO memory_config (config_key, config_value, description)
        VALUES (:key, :value, :description)
        ON DUPLICATE KEY UPDATE config_value = :value, updated_at = NOW()
    """)
    Mono<Integer> upsert(String key, String value, String description);
}
