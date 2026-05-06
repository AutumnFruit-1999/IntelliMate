package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.AgentMemoryEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AgentMemoryRepository extends ReactiveCrudRepository<AgentMemoryEntity, Long> {

    Flux<AgentMemoryEntity> findByUserIdAndAgentId(String userId, String agentId);

    Flux<AgentMemoryEntity> findByAgentId(String agentId);

    Flux<AgentMemoryEntity> findByAgentIdAndMemoryType(String agentId, String memoryType);

    /**
     * Distinct (user_id, agent_id) pairs for batch jobs. Uses ASCII unit separator (CHAR(31)) between parts.
     */
    @Query("SELECT DISTINCT CONCAT(user_id, CHAR(31), COALESCE(NULLIF(agent_id, ''), 'default')) FROM agent_memory")
    Flux<String> findDistinctUserAgentPairs();

    Flux<AgentMemoryEntity> findByUserIdAndAgentIdAndMemoryType(String userId, String agentId, String memoryType);

    Mono<Long> countByUserIdAndAgentId(String userId, String agentId);

    @Query("SELECT * FROM agent_memory WHERE user_id = :userId AND agent_id = :agentId AND content LIKE CONCAT('%', :keyword, '%') LIMIT 100")
    Flux<AgentMemoryEntity> findByUserIdAndAgentIdAndContentContaining(String userId, String agentId, String keyword);

    @Query("SELECT * FROM agent_memory WHERE user_id = :userId AND agent_id = :agentId AND MATCH(content) AGAINST(:searchExpr IN BOOLEAN MODE) LIMIT :maxResults")
    Flux<AgentMemoryEntity> fulltextSearch(String userId, String agentId, String searchExpr, int maxResults);

    @Query("SELECT * FROM agent_memory WHERE agent_id = :agentId AND MATCH(content) AGAINST(:searchExpr IN BOOLEAN MODE) LIMIT :maxResults")
    Flux<AgentMemoryEntity> fulltextSearchByAgentId(String agentId, String searchExpr, int maxResults);

    @Modifying
    @Query("UPDATE agent_memory SET access_count = access_count + 1, last_accessed_at = NOW() WHERE id = :id")
    Mono<Integer> incrementAccessCount(Long id);

    @Query("""
        SELECT * FROM agent_memory
        WHERE (last_accessed_at IS NULL OR last_accessed_at < DATE_SUB(NOW(), INTERVAL :days DAY))
          AND importance < :importanceThreshold
    """)
    Flux<AgentMemoryEntity> findColdMemories(int days, float importanceThreshold);

    @Query("""
        SELECT * FROM agent_memory
        WHERE user_id = :userId AND agent_id = :agentId
          AND (last_accessed_at IS NULL OR last_accessed_at < DATE_SUB(NOW(), INTERVAL :days DAY))
          AND importance < :importanceThreshold
    """)
    Flux<AgentMemoryEntity> findColdMemoriesByUserIdAndAgentId(String userId, String agentId, int days, float importanceThreshold);

    Mono<Long> countByUserIdAndAgentIdAndMemoryType(String userId, String agentId, String memoryType);

    Flux<AgentMemoryEntity> findByUserId(String userId);

    Flux<AgentMemoryEntity> findByUserIdAndMemoryType(String userId, String memoryType);

    Mono<Long> countByUserId(String userId);

    @Query("SELECT * FROM agent_memory WHERE user_id = :userId AND content LIKE CONCAT('%', :keyword, '%') LIMIT 100")
    Flux<AgentMemoryEntity> findByUserIdAndContentContaining(String userId, String keyword);

    Mono<Long> countByUserIdAndMemoryType(String userId, String memoryType);
}
