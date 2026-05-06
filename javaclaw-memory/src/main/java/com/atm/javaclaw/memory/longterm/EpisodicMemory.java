package com.atm.javaclaw.memory.longterm;

import com.atm.javaclaw.memory.model.ExtractedFact;
import com.atm.javaclaw.memory.model.MemoryEntry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Episodic long-term memories: delegates to {@link LongTermMemory} with type {@code episodic}.
 */
public class EpisodicMemory {

    private final LongTermMemory delegate;

    public EpisodicMemory(LongTermMemory delegate) {
        this.delegate = delegate;
    }

    public Mono<Void> store(ExtractedFact fact, String userId, String agentId) {
        return delegate.store(new ExtractedFact("episodic", fact.content(), fact.importance()), userId, agentId);
    }

    public Flux<MemoryEntry> search(String cue, String userId, String agentId) {
        return delegate.search(cue, userId, agentId).filter(e -> "episodic".equals(e.getMemoryType()));
    }

    public Flux<MemoryEntry> findAll(String userId, String agentId) {
        return delegate.findByUserId(userId, agentId).filter(e -> "episodic".equals(e.getMemoryType()));
    }
}
