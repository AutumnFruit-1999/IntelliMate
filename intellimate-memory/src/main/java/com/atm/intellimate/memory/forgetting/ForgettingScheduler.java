package com.atm.intellimate.memory.forgetting;

import com.atm.intellimate.memory.longterm.LongTermMemory;
import com.atm.intellimate.memory.model.ExtractedFact;
import com.atm.intellimate.memory.model.MemoryEntry;
import com.atm.intellimate.memory.retrieval.KeywordExtractor;
import com.atm.intellimate.memory.retrieval.ScoringFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Scheduled forgetting mechanism: removes low-retention memories to maintain signal-to-noise ratio.
 * Also handles memory compaction (merging similar memories) and cold memory archiving.
 */
public class ForgettingScheduler {

    private static final Logger log = LoggerFactory.getLogger(ForgettingScheduler.class);
    private static final double FORGET_THRESHOLD = 0.1;
    private static final double SIMILARITY_THRESHOLD = 0.85;

    private final LongTermMemory longTermMemory;
    private final ScoringFunction scoringFunction;
    private final KeywordExtractor keywordExtractor;
    private final double decayLambda;
    private final int maxMemoriesPerUser;

    public ForgettingScheduler(LongTermMemory longTermMemory,
                                double decayLambda,
                                int maxMemoriesPerUser) {
        this.longTermMemory = longTermMemory;
        this.scoringFunction = new ScoringFunction();
        this.keywordExtractor = new KeywordExtractor();
        this.decayLambda = decayLambda;
        this.maxMemoriesPerUser = maxMemoriesPerUser;
    }

    /**
     * Run the forgetting cycle for a user: decay-based cleanup, then enforce max limit.
     */
    public Mono<ForgettingResult> forgetForUser(String userId) {
        return forgetForUser(userId, "default");
    }

    public Mono<ForgettingResult> forgetForUser(String userId, String agentId) {
        return forgetForUser(userId, agentId, decayLambda, maxMemoriesPerUser);
    }

    /**
     * Same as {@link #forgetForUser(String)} but uses runtime config (e.g. resolved from DB).
     */
    public Mono<ForgettingResult> forgetForUser(String userId, double decayLambda, int maxMemoriesPerUser) {
        return forgetForUser(userId, "default", decayLambda, maxMemoriesPerUser);
    }

    /**
     * Forgetting cycle for a user within a specific agent scope.
     */
    public Mono<ForgettingResult> forgetForUser(String userId, String agentId, double decayLambda, int maxMemoriesPerUser) {
        return longTermMemory.findByUserId(userId, agentId)
                .collectList()
                .flatMap(memories -> {
                    java.util.List<Long> toDelete = new java.util.ArrayList<>();

                    for (MemoryEntry m : memories) {
                        double retentionScore = scoringFunction.computeRetentionScore(m, decayLambda);
                        if (retentionScore < FORGET_THRESHOLD) {
                            toDelete.add(m.getId());
                        }
                    }

                    int remaining = memories.size() - toDelete.size();
                    if (maxMemoriesPerUser > 0 && remaining > maxMemoriesPerUser) {
                        java.util.List<MemoryEntry> survivors = new java.util.ArrayList<>();
                        for (MemoryEntry m : memories) {
                            if (!toDelete.contains(m.getId())) {
                                survivors.add(m);
                            }
                        }
                        survivors.sort(java.util.Comparator.comparingDouble(
                                (MemoryEntry m) -> scoringFunction.computeRetentionScore(m, decayLambda)));
                        int excess = remaining - maxMemoriesPerUser;
                        for (int i = 0; i < excess && i < survivors.size(); i++) {
                            toDelete.add(survivors.get(i).getId());
                        }
                    }

                    return Flux.fromIterable(toDelete)
                            .flatMap(id -> longTermMemory.deleteById(id))
                            .then(Mono.just(new ForgettingResult(toDelete.size(), 0)));
                });
    }

    /**
     * Cold memories eligible for archiving (global). Persistence is handled by the gateway layer.
     */
    public Flux<MemoryEntry> archiveColdMemories(int archiveDays, float importanceThreshold) {
        return longTermMemory.findColdMemories(archiveDays, importanceThreshold);
    }

    /**
     * Cold memories eligible for archiving, scoped by user and agent.
     */
    public Flux<MemoryEntry> archiveColdMemories(int archiveDays, float importanceThreshold, String userId, String agentId) {
        return longTermMemory.findColdMemories(archiveDays, importanceThreshold, userId, agentId);
    }

    /**
     * Compact similar memories by merging them using Jaccard similarity.
     * Optimized: groups by memoryType first to reduce comparison count.
     */
    public Mono<Integer> compactMemories(String userId) {
        return compactMemories(userId, "default");
    }

    public Mono<Integer> compactMemories(String userId, String agentId) {
        return longTermMemory.findByUserId(userId, agentId)
                .collectList()
                .flatMap(memories -> {
                    java.util.Map<String, java.util.List<MemoryEntry>> byType = new java.util.HashMap<>();
                    for (MemoryEntry m : memories) {
                        byType.computeIfAbsent(m.getMemoryType(), k -> new java.util.ArrayList<>()).add(m);
                    }

                    java.util.Set<Long> mergedIds = new java.util.HashSet<>();
                    java.util.List<MemoryEntry> survivorsToSave = new java.util.ArrayList<>();

                    for (java.util.List<MemoryEntry> group : byType.values()) {
                        compactGroup(group, mergedIds, survivorsToSave);
                    }

                    int totalCompacted = mergedIds.size();
                    Mono<Void> saveSurvivors = Flux.fromIterable(survivorsToSave)
                            .flatMap(survivor -> longTermMemory.store(
                                    ExtractedFact.legacy(survivor.getMemoryType(), survivor.getContent(), survivor.getImportance()),
                                    userId, agentId)
                                    .onErrorResume(e -> {
                                        log.warn("Failed to save merged survivor {}", survivor.getId(), e);
                                        return Mono.empty();
                                    }))
                            .then();

                    Mono<Void> deleteDuplicates = Flux.fromIterable(mergedIds)
                            .flatMap(id -> longTermMemory.deleteById(id)
                                    .onErrorResume(e -> {
                                        log.warn("Failed to delete merged memory {}", id, e);
                                        return Mono.empty();
                                    }))
                            .then();

                    return saveSurvivors.then(deleteDuplicates).thenReturn(totalCompacted);
                });
    }

    private void compactGroup(java.util.List<MemoryEntry> group,
                              java.util.Set<Long> mergedIds,
                              java.util.List<MemoryEntry> survivorsToSave) {
        boolean[] merged = new boolean[group.size()];

        if (group.size() > 100) {
            compactGroupWithMinHash(group, merged, mergedIds, survivorsToSave);
        } else {
            compactGroupBruteForce(group, merged, mergedIds, survivorsToSave);
        }
    }

    private void compactGroupBruteForce(java.util.List<MemoryEntry> group, boolean[] merged,
                                         java.util.Set<Long> mergedIds,
                                         java.util.List<MemoryEntry> survivorsToSave) {
        for (int i = 0; i < group.size(); i++) {
            if (merged[i]) continue;
            boolean wasMerged = false;
            for (int j = i + 1; j < group.size(); j++) {
                if (merged[j]) continue;
                MemoryEntry a = group.get(i);
                MemoryEntry b = group.get(j);
                double sim = keywordExtractor.jaccardSimilarity(a.getContent(), b.getContent());
                if (sim > SIMILARITY_THRESHOLD) {
                    a.setImportance(Math.max(a.getImportance(), b.getImportance()));
                    a.setAccessCount(a.getAccessCount() + b.getAccessCount());
                    if (!a.getContent().contains(b.getContent()) && b.getContent().length() < 500) {
                        a.setContent(a.getContent() + "\n" + b.getContent());
                    }
                    merged[j] = true;
                    mergedIds.add(b.getId());
                    wasMerged = true;
                }
            }
            if (wasMerged) {
                survivorsToSave.add(group.get(i));
            }
        }
    }

    /**
     * MinHash-based pre-filtering for large groups: generate signatures, find candidate pairs
     * with estimated similarity > 0.7, then verify with exact Jaccard.
     */
    private void compactGroupWithMinHash(java.util.List<MemoryEntry> group, boolean[] merged,
                                          java.util.Set<Long> mergedIds,
                                          java.util.List<MemoryEntry> survivorsToSave) {
        int numHashes = 64;
        int[][] signatures = new int[group.size()][numHashes];

        for (int i = 0; i < group.size(); i++) {
            java.util.Set<String> tokens = new java.util.HashSet<>(keywordExtractor.extract(group.get(i).getContent()));
            for (int h = 0; h < numHashes; h++) {
                int minHash = Integer.MAX_VALUE;
                for (String token : tokens) {
                    int hash = (token.hashCode() * (h + 1) * 31 + h * 7919) & 0x7FFFFFFF;
                    if (hash < minHash) minHash = hash;
                }
                signatures[i][h] = minHash;
            }
        }

        for (int i = 0; i < group.size(); i++) {
            if (merged[i]) continue;
            boolean wasMerged = false;
            for (int j = i + 1; j < group.size(); j++) {
                if (merged[j]) continue;
                int matches = 0;
                for (int h = 0; h < numHashes; h++) {
                    if (signatures[i][h] == signatures[j][h]) matches++;
                }
                double estimatedSim = (double) matches / numHashes;
                if (estimatedSim < 0.7) continue;

                MemoryEntry a = group.get(i);
                MemoryEntry b = group.get(j);
                double exactSim = keywordExtractor.jaccardSimilarity(a.getContent(), b.getContent());
                if (exactSim > SIMILARITY_THRESHOLD) {
                    a.setImportance(Math.max(a.getImportance(), b.getImportance()));
                    a.setAccessCount(a.getAccessCount() + b.getAccessCount());
                    if (!a.getContent().contains(b.getContent()) && b.getContent().length() < 500) {
                        a.setContent(a.getContent() + "\n" + b.getContent());
                    }
                    merged[j] = true;
                    mergedIds.add(b.getId());
                    wasMerged = true;
                }
            }
            if (wasMerged) {
                survivorsToSave.add(group.get(i));
            }
        }
    }

    public record ForgettingResult(int forgotten, int compacted) {}
}
