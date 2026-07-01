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
        this(longTermMemory, tokenEstimator, new ScoringFunction());
    }

    public MemoryRetrieval(LongTermMemory longTermMemory,
                           TokenEstimator tokenEstimator,
                           ScoringFunction scoringFunction) {
        this.longTermMemory = longTermMemory;
        this.scoringFunction = scoringFunction;
        this.keywordExtractor = new KeywordExtractor();
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * Retrieve the most relevant memories within the token budget.
     * Always tries FULLTEXT search first; falls back to full load only when search returns empty.
     */
    public Mono<List<MemoryChunk>> retrieve(String cue, String userId, String agentId,
                                             int maxInjectionTokens, double lambda) {
        return longTermMemory.search(cue, userId, agentId)
                .collectList()
                .map(searchResults -> {
                    log.info("[记忆检索] FULLTEXT 命中 {} 条（SQL 已按相关性排序取 top 10）", searchResults.size());
                    if (searchResults.isEmpty()) {
                        return List.<MemoryChunk>of();
                    }
                    return selectAndScore(searchResults, cue, maxInjectionTokens, lambda);
                });
    }

    private static final double MIN_RELEVANCE_THRESHOLD = 0.20;

    private List<MemoryChunk> selectAndScore(List<MemoryEntry> candidates, String cue,
                                              int maxInjectionTokens, double lambda) {
        log.info("[记忆评分] cue='{}', 候选数={}, 阈值={}, tokenBudget={}",
                cue, candidates.size(), MIN_RELEVANCE_THRESHOLD, maxInjectionTokens);

        List<ScoredMemory> allScored = candidates.stream()
                .map(m -> {
                    double relevance = keywordExtractor.jaccardSimilarity(cue, m.getContent());
                    double score = scoringFunction.computeRetrievalScore(m, Math.max(0.1, relevance), lambda);
                    return new ScoredMemory(m, score, relevance);
                })
                .toList();

        allScored.forEach(sm -> {
            String preview = sm.entry().getContent();
            if (preview != null && preview.length() > 40) preview = preview.substring(0, 40) + "...";
            log.info("[记忆评分]   >> id={}, Jaccard={}, score={}, content='{}'",
                    sm.entry().getId(), String.format("%.3f", sm.relevance()),
                    String.format("%.4f", sm.score()), preview);
        });

        List<ScoredMemory> scored = allScored.stream()
                .filter(sm -> sm.relevance() >= MIN_RELEVANCE_THRESHOLD)
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
                .toList();

        log.info("[记忆评分] 通过阈值 {} 条 / 候选 {} 条", scored.size(), allScored.size());

        List<MemoryChunk> result = new ArrayList<>();
        int tokenCount = 0;
        for (ScoredMemory sm : scored) {
            int chunkTokens = tokenEstimator.estimate(sm.entry().getContent());
            if (tokenCount + chunkTokens > maxInjectionTokens) {
                break;
            }
            tokenCount += chunkTokens;

            try {
                longTermMemory.recordAccess(sm.entry())
                        .subscribe(null, err -> log.warn("recordAccess failed for memory {}: {}", sm.entry().getId(), err.getMessage()));
            } catch (Exception e) {
                log.warn("Failed to record memory access", e);
            }

            result.add(sm.entry().toRecalledChunk(chunkTokens, sm.relevance()));
        }

        log.info("[记忆评分] 最终注入 {} 条, 消耗 {} tokens", result.size(), tokenCount);
        return result;
    }


    private record ScoredMemory(MemoryEntry entry, double score, double relevance) {}
}
