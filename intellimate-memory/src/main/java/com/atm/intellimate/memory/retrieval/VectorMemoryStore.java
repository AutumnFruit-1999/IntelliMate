package com.atm.intellimate.memory.retrieval;

import com.atm.intellimate.memory.model.MemoryEntry;
import reactor.core.publisher.Mono;
import java.util.List;

public interface VectorMemoryStore {
    Mono<Void> store(MemoryEntry entry);
    Mono<List<VectorSearchResult>> search(String query, String userId, String agentId, int topK);
    Mono<Void> deleteById(Long mysqlId);
    Mono<Boolean> isAvailable();
}
