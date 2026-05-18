package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.MemoryConfigEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface MemoryConfigRepository extends ReactiveCrudRepository<MemoryConfigEntity, Long> {

    Mono<MemoryConfigEntity> findByConfigKey(String configKey);

    @Modifying
    @Query("UPDATE memory_config SET config_value = :value, updated_at = NOW() WHERE config_key = :key")
    Mono<Integer> updateByConfigKey(String key, String value);

    @Modifying
    @Query("""
        INSERT INTO memory_config (config_key, config_value, description)
        VALUES (:key, :value, :description)
        ON DUPLICATE KEY UPDATE config_value = :value, updated_at = NOW()
    """)
    Mono<Integer> upsert(String key, String value, String description);
}
