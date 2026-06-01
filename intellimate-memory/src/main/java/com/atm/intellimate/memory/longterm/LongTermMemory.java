package com.atm.intellimate.memory.longterm;

import com.atm.intellimate.memory.model.ExtractedFact;
import com.atm.intellimate.memory.model.MemoryEntry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Long-term memory: episodic, semantic, and procedural sub-systems.
 * Defines the interface; gateway module provides the R2DBC implementation.
 */
public interface LongTermMemory {

    /**
     * Store a new memory entry with de-duplication and merge.
     */
    Mono<Void> store(ExtractedFact fact, String userId, String agentId);

    /**
     * Search all sub-systems for memories matching the cue.
     */
    Flux<MemoryEntry> search(String cue, String userId, String agentId);

    /**
     * Record that a memory was accessed (retrieval practice effect).
     */
    Mono<Void> recordAccess(MemoryEntry entry);

    /**
     * Record access and optionally boost importance (retrieval practice effect).
     */
    default Mono<Void> recordAccess(MemoryEntry entry, float importanceBoost) {
        return recordAccess(entry);
    }

    /**
     * Store with optional metadata JSON (e.g. episodic topics/outcome).
     */
    default Mono<Void> store(ExtractedFact fact, String userId, String agentId, String metadataJson) {
        return store(fact, userId, agentId);
    }

    /**
     * Find all memories for a user within an agent scope.
     */
    Flux<MemoryEntry> findByUserId(String userId, String agentId);

    /**
     * Count memories for a user within an agent scope.
     */
    Mono<Long> countByUserId(String userId, String agentId);

    /**
     * Delete a memory entry by id.
     */
    Mono<Void> deleteById(Long id);

    /**
     * Find cold memories eligible for archiving (global, all users/agents).
     */
    Flux<MemoryEntry> findColdMemories(int archiveDays, float importanceThreshold);

    /**
     * Find cold memories eligible for archiving, scoped to a specific user and agent.
     */
    Flux<MemoryEntry> findColdMemories(int archiveDays, float importanceThreshold, String userId, String agentId);

    /**
     * Get stats for all memory types for a user within an agent scope.
     */
    Mono<MemoryStats> getStats(String userId, String agentId);

    record MemoryStats(
            long episodicCount,
            long semanticCount,
            long proceduralCount,
            long totalCount
    ) {}
}
