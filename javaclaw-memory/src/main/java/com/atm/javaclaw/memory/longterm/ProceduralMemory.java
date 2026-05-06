package com.atm.javaclaw.memory.longterm;

import com.atm.javaclaw.memory.model.ExtractedFact;
import com.atm.javaclaw.memory.model.MemoryEntry;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Procedural long-term memories: delegates to {@link LongTermMemory} with type {@code procedural}.
 */
public class ProceduralMemory {

    private final LongTermMemory delegate;

    public ProceduralMemory(LongTermMemory delegate) {
        this.delegate = delegate;
    }

    public Mono<Void> store(ExtractedFact fact, String userId, String agentId) {
        return delegate.store(new ExtractedFact("procedural", fact.content(), fact.importance()), userId, agentId);
    }

    public Flux<MemoryEntry> search(String cue, String userId, String agentId) {
        return delegate.search(cue, userId, agentId).filter(e -> "procedural".equals(e.getMemoryType()));
    }

    public Flux<MemoryEntry> findAll(String userId, String agentId) {
        return delegate.findByUserId(userId, agentId).filter(e -> "procedural".equals(e.getMemoryType()));
    }
}
