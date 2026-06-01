package com.atm.intellimate.memory.retrieval;

import com.atm.intellimate.memory.model.MemoryChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.*;

public class HybridMemoryRetrieval {

    private static final Logger log = LoggerFactory.getLogger(HybridMemoryRetrieval.class);

    public enum Strategy { HYBRID, VECTOR_ONLY, KEYWORD_ONLY }

    private final MemoryRetrieval keywordRetrieval;
    private final VectorMemoryStore vectorStore;
    private final float vectorWeight;
    private final float keywordWeight;

    public HybridMemoryRetrieval(MemoryRetrieval keywordRetrieval,
                                  VectorMemoryStore vectorStore,
                                  float vectorWeight, float keywordWeight) {
        this.keywordRetrieval = keywordRetrieval;
        this.vectorStore = vectorStore;
        this.vectorWeight = vectorWeight;
        this.keywordWeight = keywordWeight;
    }

    public Mono<List<MemoryChunk>> retrieve(
            String cue, String userId, String agentId,
            int maxInjectionTokens, double lambda, Strategy strategy) {

        if (vectorStore == null || strategy == Strategy.KEYWORD_ONLY) {
            return keywordRetrieval.retrieve(cue, userId, agentId, maxInjectionTokens, lambda);
        }

        return vectorStore.isAvailable()
                .onErrorReturn(false)
                .flatMap(available -> {
                    if (!available) {
                        log.info("Vector store unavailable, falling back to keyword retrieval");
                        return keywordRetrieval.retrieve(cue, userId, agentId, maxInjectionTokens, lambda);
                    }
                    if (strategy == Strategy.VECTOR_ONLY) {
                        return vectorOnlyRetrieve(cue, userId, agentId, maxInjectionTokens);
                    }
                    return hybridRetrieve(cue, userId, agentId, maxInjectionTokens, lambda);
                });
    }

    private Mono<List<MemoryChunk>> vectorOnlyRetrieve(
            String cue, String userId, String agentId, int maxInjectionTokens) {
        return vectorStore.search(cue, userId, agentId, 20)
                .map(results -> {
                    List<MemoryChunk> chunks = new ArrayList<>();
                    int tokenCount = 0;
                    for (VectorSearchResult r : results) {
                        int estimatedTokens = r.content().length() / 3; // rough estimate
                        if (tokenCount + estimatedTokens > maxInjectionTokens) break;
                        tokenCount += estimatedTokens;
                        chunks.add(r.toRecalledChunk(estimatedTokens));
                    }
                    return chunks;
                });
    }

    private Mono<List<MemoryChunk>> hybridRetrieve(
            String cue, String userId, String agentId,
            int maxInjectionTokens, double lambda) {
        Mono<List<VectorSearchResult>> vectorMono = vectorStore.search(cue, userId, agentId, 20)
                .onErrorResume(e -> {
                    log.warn("Vector search failed, falling back to keyword only: {}", e.getMessage());
                    return Mono.just(List.of());
                });
        Mono<List<MemoryChunk>> keywordMono = keywordRetrieval.retrieve(cue, userId, agentId, maxInjectionTokens, lambda);

        return Mono.zip(vectorMono, keywordMono)
                .map(tuple -> mergeAndRank(tuple.getT1(), tuple.getT2(), maxInjectionTokens));
    }

    private List<MemoryChunk> mergeAndRank(List<VectorSearchResult> vectorResults,
                                            List<MemoryChunk> keywordResults,
                                            int maxTokens) {
        // Use a map keyed by content hash to deduplicate
        Map<String, MergedCandidate> candidates = new LinkedHashMap<>();

        // Add vector results
        for (VectorSearchResult vr : vectorResults) {
            String key = vr.mysqlId() != null ? "id:" + vr.mysqlId() : "content:" + vr.content().hashCode();
            candidates.put(key, new MergedCandidate(
                    vr.toRecalledChunk(vr.content().length() / 3),
                    vr.similarity() * vectorWeight,
                    0.0
            ));
        }

        // Merge keyword results
        double maxKeywordScore = keywordResults.stream()
                .mapToDouble(MemoryChunk::importance)
                .max().orElse(1.0);
        if (maxKeywordScore == 0) maxKeywordScore = 1.0;

        for (int i = 0; i < keywordResults.size(); i++) {
            MemoryChunk kw = keywordResults.get(i);
            // Normalize keyword rank score
            double normalizedKeywordScore = 1.0 - ((double) i / keywordResults.size());
            String key = "keyword:" + kw.content().hashCode();
            MergedCandidate existing = candidates.get(key);
            if (existing != null) {
                // Already from vector, boost with keyword score
                candidates.put(key, new MergedCandidate(
                        existing.chunk,
                        existing.vectorScore,
                        normalizedKeywordScore * keywordWeight
                ));
            } else {
                candidates.put(key, new MergedCandidate(
                        kw, 0.0, normalizedKeywordScore * keywordWeight));
            }
        }

        // Sort by combined score
        List<MergedCandidate> sorted = candidates.values().stream()
                .sorted(Comparator.comparingDouble(MergedCandidate::totalScore).reversed())
                .toList();

        // Token budget truncation
        List<MemoryChunk> result = new ArrayList<>();
        int tokenCount = 0;
        for (MergedCandidate mc : sorted) {
            int tokens = mc.chunk.estimatedTokens();
            if (tokenCount + tokens > maxTokens) break;
            tokenCount += tokens;
            result.add(mc.chunk);
        }
        return result;
    }

    private record MergedCandidate(MemoryChunk chunk, double vectorScore, double keywordScore) {
        double totalScore() { return vectorScore + keywordScore; }
    }
}
