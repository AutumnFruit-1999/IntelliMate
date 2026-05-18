package com.atm.intellimate.memory.retrieval;

import com.atm.intellimate.memory.longterm.LongTermMemory;
import com.atm.intellimate.memory.model.MemoryChunk;
import com.atm.intellimate.memory.model.MemoryEntry;
import com.atm.intellimate.memory.working.TokenEstimator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Retrieves relevant long-term memories using cue-based search and scoring.
 * For large memory sets ({@code > 1000} per user/agent), uses staged retrieval: keyword DB search (capped),
 * score, top 20, then token-budget selection. For smaller sets, loads all rows for that scope then scores.
 */
public class MemoryRetrieval {

    private static final Logger log = LoggerFactory.getLogger(MemoryRetrieval.class);

    private final LongTermMemory longTermMemory;
    private final ScoringFunction scoringFunction;
    private final KeywordExtractor keywordExtractor;
    private final TokenEstimator tokenEstimator;

    public MemoryRetrieval(LongTermMemory longTermMemory,
                           TokenEstimator tokenEstimator) {
        this.longTermMemory = longTermMemory;
        this.scoringFunction = new ScoringFunction();
        this.keywordExtractor = new KeywordExtractor();
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * Retrieve the most relevant memories within the token budget.
     */
    public Mono<List<MemoryChunk>> retrieve(String cue, String userId, String agentId,
                                             int maxInjectionTokens, double lambda) {
        return longTermMemory.countByUserId(userId, agentId)
                .flatMap(count -> {
                    if (count > 1000) {
                        log.info(
                                "Memory retrieval: userId={} agentId={} memoryCount={} (>1000), using staged retrieval "
                                        + "(keyword search LIMIT 100, score, top 20, token budget)",
                                userId, agentId, count);
                        // Stage 1: keyword DB search (LIMIT 100 per keyword); cap in case cue has no keywords (full scan).
                        return longTermMemory.search(cue, userId, agentId)
                                .take(100)
                                .collectList()
                                .map(candidates -> {
                                    List<MemoryEntry> topCandidates = candidates.stream()
                                            .sorted(Comparator.comparingDouble(m -> -scoringFunction.computeRetrievalScore(
                                                    m,
                                                    Math.max(0.1, keywordExtractor.jaccardSimilarity(cue, m.getContent())),
                                                    lambda)))
                                            .limit(20)
                                            .toList();
                                    return selectAndScore(topCandidates, cue, maxInjectionTokens, lambda);
                                });
                    }
                    log.debug(
                            "Memory retrieval: userId={} agentId={} memoryCount={} (<=1000), load all for user/agent "
                                    + "then score within token budget",
                            userId, agentId, count);
                    return longTermMemory.findByUserId(userId, agentId)
                            .collectList()
                            .map(candidates -> selectAndScore(candidates, cue, maxInjectionTokens, lambda));
                });
    }

    private static final double MIN_RELEVANCE_THRESHOLD = 0.05;

    private List<MemoryChunk> selectAndScore(List<MemoryEntry> candidates, String cue,
                                              int maxInjectionTokens, double lambda) {
        record ScoredMemory(MemoryEntry entry, double score, double relevance) {}

        List<ScoredMemory> scored = candidates.stream()
                .map(m -> {
                    double relevance = keywordExtractor.jaccardSimilarity(cue, m.getContent());
                    double score = scoringFunction.computeRetrievalScore(m, Math.max(0.1, relevance), lambda);
                    return new ScoredMemory(m, score, relevance);
                })
                .filter(sm -> sm.relevance() >= MIN_RELEVANCE_THRESHOLD)
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
                .toList();

        List<MemoryChunk> result = new ArrayList<>();
        int tokenCount = 0;
        for (ScoredMemory sm : scored) {
            int chunkTokens = tokenEstimator.estimate(sm.entry().getContent());
            if (tokenCount + chunkTokens > maxInjectionTokens) break;
            tokenCount += chunkTokens;

            try {
                longTermMemory.recordAccess(sm.entry())
                        .subscribe(null, err -> log.warn("recordAccess failed for memory {}: {}", sm.entry().getId(), err.getMessage()));
            } catch (Exception e) {
                log.warn("Failed to record memory access", e);
            }

            result.add(sm.entry().toRecalledChunk(chunkTokens));
        }
        return result;
    }
}
