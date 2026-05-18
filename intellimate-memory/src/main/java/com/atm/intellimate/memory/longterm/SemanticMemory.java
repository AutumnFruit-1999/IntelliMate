package com.atm.intellimate.memory.longterm;

import com.atm.intellimate.memory.model.ExtractedFact;
import com.atm.intellimate.memory.model.MemoryEntry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Semantic long-term memories: delegates to {@link LongTermMemory} with type {@code semantic}.
 */
public class SemanticMemory {

    private final LongTermMemory delegate;

    public SemanticMemory(LongTermMemory delegate) {
        this.delegate = delegate;
    }

    public Mono<Void> store(ExtractedFact fact, String userId, String agentId) {
        return delegate.store(new ExtractedFact("semantic", fact.content(), fact.importance()), userId, agentId);
    }

    public Flux<MemoryEntry> search(String cue, String userId, String agentId) {
        return delegate.search(cue, userId, agentId).filter(e -> "semantic".equals(e.getMemoryType()));
    }

    public Flux<MemoryEntry> findAll(String userId, String agentId) {
        return delegate.findByUserId(userId, agentId).filter(e -> "semantic".equals(e.getMemoryType()));
    }
}
