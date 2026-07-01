package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.AgentMemoryEntity;
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

    Flux<AgentMemoryEntity> findByUserIdAndAgentIdAndTopic(String userId, String agentId, String topic);

    Mono<Long> countByUserIdAndAgentId(String userId, String agentId);

    @Query("SELECT * FROM agent_memory WHERE user_id = :userId AND agent_id = :agentId AND content LIKE CONCAT('%', :keyword, '%') LIMIT 100")
    Flux<AgentMemoryEntity> findByUserIdAndAgentIdAndContentContaining(String userId, String agentId, String keyword);

    @Query("""
        SELECT *,
               (MATCH(content) AGAINST(:searchExpr IN BOOLEAN MODE)
                + CASE WHEN keywords IS NOT NULL
                       THEN MATCH(keywords) AGAINST(:searchExpr IN BOOLEAN MODE)
                       ELSE 0 END) AS ft_score
        FROM agent_memory
        WHERE user_id = :userId AND agent_id = :agentId
          AND (MATCH(content) AGAINST(:searchExpr IN BOOLEAN MODE)
               OR (keywords IS NOT NULL AND MATCH(keywords) AGAINST(:searchExpr IN BOOLEAN MODE)))
        ORDER BY ft_score DESC
        LIMIT 10
        """)
    Flux<AgentMemoryEntity> fulltextSearch(String userId, String agentId, String searchExpr);

    @Query("""
        SELECT *,
               (MATCH(content) AGAINST(:searchExpr IN BOOLEAN MODE)
                + CASE WHEN keywords IS NOT NULL
                       THEN MATCH(keywords) AGAINST(:searchExpr IN BOOLEAN MODE)
                       ELSE 0 END) AS ft_score
        FROM agent_memory
        WHERE agent_id = :agentId
          AND (MATCH(content) AGAINST(:searchExpr IN BOOLEAN MODE)
               OR (keywords IS NOT NULL AND MATCH(keywords) AGAINST(:searchExpr IN BOOLEAN MODE)))
        ORDER BY ft_score DESC
        LIMIT 10
        """)
    Flux<AgentMemoryEntity> fulltextSearchByAgentId(String agentId, String searchExpr);

    @Modifying
    @Query("UPDATE agent_memory SET access_count = access_count + 1, last_accessed_at = NOW() WHERE id = :id")
    Mono<Integer> incrementAccessCount(Long id);

    @Modifying
    @Query("UPDATE agent_memory SET access_count = access_count + 1, last_accessed_at = NOW(), importance = LEAST(importance + :boost, 1.0) WHERE id = :id")
    Mono<Integer> incrementAccessCountWithBoost(Long id, float boost);

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

    @Query("""
        SELECT DISTINCT CONCAT(user_id, CHAR(31), COALESCE(NULLIF(agent_id, ''), 'default'))
        FROM agent_memory
        WHERE memory_level = 'detail' AND DATE(created_at) = CURDATE()
        """)
    Flux<String> findDistinctUserAgentPairsWithTodayDetails();

    @Query("""
        SELECT * FROM agent_memory
        WHERE user_id = :userId AND agent_id = :agentId
          AND memory_level = 'detail' AND DATE(created_at) = CURDATE()
        ORDER BY created_at
        """)
    Flux<AgentMemoryEntity> findTodayDetailMemories(String userId, String agentId);
}
