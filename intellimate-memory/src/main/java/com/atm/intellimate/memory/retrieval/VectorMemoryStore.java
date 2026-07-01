package com.atm.intellimate.memory.retrieval;

import com.atm.intellimate.memory.model.MemoryEntry;
import reactor.core.publisher.Mono;
import java.util.List;

public interface VectorMemoryStore {
    Mono<Void> store(MemoryEntry entry);

    default Mono<List<VectorSearchResult>> search(String query, String userId, String agentId, int topK) {
        return search(query, userId, agentId, topK, 0f);
    }

    Mono<List<VectorSearchResult>> search(String query, String userId, String agentId, int topK, float similarityThreshold);

    Mono<Void> deleteById(Long mysqlId);
    Mono<Boolean> isAvailable();
}
