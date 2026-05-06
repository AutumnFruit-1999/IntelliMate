package com.atm.javaclaw.memory.working;

import com.atm.javaclaw.memory.consolidation.ConsolidationResult;
import com.atm.javaclaw.memory.consolidation.MemoryConsolidator;
import com.atm.javaclaw.memory.model.ChunkType;
import com.atm.javaclaw.memory.model.MemoryChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages the current LLM-visible context.
 * The sole gateway for constructing LLM input — replaces ContextWindowTracker,
 * ContextCondenser, history-limit, and max-turns.
 */
public class WorkingMemory {

    private static final Logger log = LoggerFactory.getLogger(WorkingMemory.class);

    private final int tokenBudget;
    private final float consolidationThreshold;
    private final float overflowTolerance;
    private final MemoryConsolidator consolidator;
    private final String agentId;
    private String userId = "default";

    private MemoryChunk systemChunk;
    private final List<MemoryChunk> chunks = new CopyOnWriteArrayList<>();

    private final AtomicReference<Mono<ConsolidationResult>> pendingConsolidation = new AtomicReference<>();
    private volatile String taskContext = "";

    private static final long CONSOLIDATION_COOLDOWN_MS = 60_000;
    private volatile long lastConsolidationFailureTime = 0;
    private int consolidationCount = 0;

    /** Last prompt token total reported by the LLM API for the completed request. */
    private int lastKnownActualTokens = 0;
    /** Estimated tokens added since the last API usage (e.g. tool results before the next call). */
    private int incrementalEstimatedTokens = 0;

    public WorkingMemory(int tokenBudget, float consolidationThreshold,
                         float overflowTolerance, MemoryConsolidator consolidator,
                         MemoryChunk systemChunk) {
        this(tokenBudget, consolidationThreshold, overflowTolerance, consolidator, systemChunk, "default");
    }

    public WorkingMemory(int tokenBudget, float consolidationThreshold,
                         float overflowTolerance, MemoryConsolidator consolidator,
                         MemoryChunk systemChunk, String agentId) {
        this.tokenBudget = tokenBudget;
        this.consolidationThreshold = consolidationThreshold;
        this.overflowTolerance = overflowTolerance;
        this.consolidator = consolidator;
        this.systemChunk = systemChunk;
        this.agentId = agentId == null || agentId.isBlank() ? "default" : agentId;
    }

    /**
     * Set the actual prompt token count from an API response. Resets the incremental counter.
     */
    public void setActualTokenUsage(int promptTokens) {
        this.lastKnownActualTokens = promptTokens;
        this.incrementalEstimatedTokens = 0;
    }

    /**
     * Track incremental token addition for content added between API calls (e.g. tool results).
     */
    public void addIncrementalTokens(int tokens) {
        if (tokens > 0) {
            this.incrementalEstimatedTokens += tokens;
        }
    }

    /**
     * Best-known prompt-side usage: API-reported base plus incremental estimate since then.
     */
    public int getTokenUsage() {
        if (lastKnownActualTokens > 0) {
            return lastKnownActualTokens + incrementalEstimatedTokens;
        }
        return estimateTokenUsage();
    }

    private void resetTrackedApiTokens() {
        this.lastKnownActualTokens = 0;
        this.incrementalEstimatedTokens = 0;
    }

    private final AtomicBoolean consolidationInFlight = new AtomicBoolean(false);

    /**
     * Accept a new chunk into working memory.
     * If capacity threshold is exceeded, triggers async consolidation (single-flight gate).
     * If consolidation is in cooldown and overflow tolerance is exceeded, performs emergency truncation.
     */
    public Mono<ConsolidationResult> accept(MemoryChunk chunk) {
        chunks.add(chunk);

        boolean inCooldown = (System.currentTimeMillis() - lastConsolidationFailureTime) < CONSOLIDATION_COOLDOWN_MS;

        if (usageRatio() > consolidationThreshold && consolidator != null
                && !inCooldown && consolidationInFlight.compareAndSet(false, true)) {
            Sinks.One<ConsolidationResult> sink = Sinks.one();
            Mono<ConsolidationResult> consolidation = sink.asMono().cache();
            pendingConsolidation.set(consolidation);

            Mono.fromCallable(() -> Optional.ofNullable(consolidator.tryConsolidate(this, agentId, userId)))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            optResult -> {
                                consolidationInFlight.set(false);
                                if (optResult.isPresent()) {
                                    consolidationCount++;
                                    sink.tryEmitValue(optResult.get());
                                } else {
                                    lastConsolidationFailureTime = System.currentTimeMillis();
                                    sink.tryEmitEmpty();
                                }
                            },
                            error -> {
                                consolidationInFlight.set(false);
                                lastConsolidationFailureTime = System.currentTimeMillis();
                                log.warn("Async consolidation failed", error);
                                sink.tryEmitEmpty();
                            }
                    );
            return consolidation;
        }

        if (inCooldown && usageRatio() > overflowTolerance) {
            emergencyTruncate();
        }

        return Mono.empty();
    }

    /**
     * Emergency truncation: discard oldest non-SYSTEM chunks until usage drops below consolidation threshold.
     */
    private void emergencyTruncate() {
        int removed = 0;
        while (usageRatio() > consolidationThreshold && !chunks.isEmpty()) {
            MemoryChunk oldest = chunks.get(0);
            if (oldest.type() == ChunkType.SYSTEM) {
                if (chunks.size() <= 1) break;
                chunks.remove(0);
            } else {
                chunks.remove(0);
            }
            removed++;
        }
        if (removed > 0) {
            log.warn("Emergency truncation: removed {} oldest chunks to reduce usage ratio to {}",
                    removed, usageRatio());
            resetTrackedApiTokens();
        }
    }

    /**
     * Waits for any consolidation scheduled by {@link #accept(MemoryChunk)} and clears the pending handle.
     * Does not block threads when composed reactively.
     */
    public Mono<Optional<ConsolidationResult>> awaitPendingConsolidation() {
        Mono<ConsolidationResult> pending = pendingConsolidation.getAndSet(null);
        if (pending == null) {
            return Mono.just(Optional.empty());
        }
        return pending.map(Optional::of).defaultIfEmpty(Optional.empty());
    }

    /**
     * Build LLM input messages. Waits for any pending consolidation to complete first.
     */
    public Mono<List<Message>> buildLLMInput() {
        Mono<ConsolidationResult> pending = pendingConsolidation.getAndSet(null);
        Mono<Void> waitStep = pending != null
                ? pending.then()
                : Mono.empty();

        return waitStep.then(Mono.fromCallable(() -> {
            List<Message> messages = new ArrayList<>();
            messages.addAll(systemChunk.toMessages());
            for (MemoryChunk chunk : chunks) {
                messages.addAll(chunk.toMessages());
            }
            return messages;
        }));
    }

    /**
     * Build LLM input synchronously (for non-reactive contexts).
     */
    public List<Message> buildLLMInputSync() {
        List<Message> messages = new ArrayList<>();
        messages.addAll(systemChunk.toMessages());
        for (MemoryChunk chunk : chunks) {
            messages.addAll(chunk.toMessages());
        }
        return messages;
    }

    public int estimateTokenUsage() {
        int total = systemChunk.estimatedTokens();
        for (MemoryChunk c : chunks) {
            total += c.estimatedTokens();
        }
        return total;
    }

    public float usageRatio() {
        return (float) getTokenUsage() / tokenBudget;
    }

    public void updateSystemChunk(MemoryChunk newSystem) {
        this.systemChunk = newSystem;
    }

    /**
     * Returns consolidation candidate chunks (lowest relevance first).
     * Excludes SYSTEM chunks and the most recent minKeep chunks.
     */
    public List<MemoryChunk> getConsolidationCandidates(int minChunks) {
        if (chunks.size() <= minChunks) {
            return List.of();
        }

        int keepRecent = Math.min(3, chunks.size() / 2);
        List<MemoryChunk> candidates = new ArrayList<>();
        for (int i = 0; i < chunks.size() - keepRecent; i++) {
            MemoryChunk c = chunks.get(i);
            if (c.type() != ChunkType.SYSTEM) {
                candidates.add(c);
            }
        }

        candidates.sort(Comparator.comparingDouble(c ->
                c.importance() * recencyWeight(c) * taskAlignmentWeight(c)));

        return candidates.size() >= minChunks
                ? candidates.subList(0, Math.min(candidates.size(), minChunks + 2))
                : List.of();
    }

    private double recencyWeight(MemoryChunk chunk) {
        int index = chunks.indexOf(chunk);
        if (index < 0) return 0.5;
        return 0.3 + 0.7 * ((double) index / chunks.size());
    }

    private double taskAlignmentWeight(MemoryChunk chunk) {
        if (taskContext == null || taskContext.isBlank() || chunk.content() == null) {
            return 1.0;
        }
        String[] contextWords = taskContext.toLowerCase().split("\\s+");
        String lower = chunk.content().toLowerCase();
        int matches = 0;
        for (String w : contextWords) {
            if (w.length() > 2 && lower.contains(w)) matches++;
        }
        double ratio = contextWords.length > 0 ? (double) matches / contextWords.length : 0;
        return 0.5 + 0.5 * ratio;
    }

    /**
     * Set the current task context for task-alignment scoring in consolidation candidates.
     */
    public void setTaskContext(String context) {
        this.taskContext = context != null ? context : "";
    }

    public void setUserId(String userId) {
        this.userId = userId != null && !userId.isBlank() ? userId : "default";
    }

    /**
     * Replace original chunks with a consolidated summary chunk.
     */
    public void replaceWithConsolidated(List<MemoryChunk> originals, MemoryChunk consolidated) {
        int insertPos = -1;
        for (MemoryChunk orig : originals) {
            int idx = chunks.indexOf(orig);
            if (idx >= 0) {
                if (insertPos < 0) insertPos = idx;
                chunks.remove(idx);
                if (insertPos > idx) insertPos--;
            }
        }
        if (insertPos >= 0 && insertPos <= chunks.size()) {
            chunks.add(insertPos, consolidated);
        } else {
            chunks.add(consolidated);
        }
        consolidationCount++;
        resetTrackedApiTokens();
    }

    /**
     * Clears all non-system chunks. Used when the message history is rebuilt
     * (e.g., after external condensation).
     */
    public void clearChunks() {
        chunks.clear();
        resetTrackedApiTokens();
    }

    public int getTokenBudget() { return tokenBudget; }

    public int getChunkCount() { return chunks.size(); }

    public List<MemoryChunk> getChunks() { return List.copyOf(chunks); }

    public MemoryChunk getSystemChunk() { return systemChunk; }

    public float getOverflowTolerance() { return overflowTolerance; }

    public int getConsolidationCount() { return consolidationCount; }

    public MemoryConsolidator getConsolidator() { return consolidator; }

    /**
     * Returns a snapshot of the current state for frontend observation.
     */
    public MemorySnapshot getSnapshot() {
        List<ChunkInfo> chunkInfos = new ArrayList<>();
        for (MemoryChunk c : chunks) {
            chunkInfos.add(new ChunkInfo(
                    c.id(), c.type().name(), c.category().name(),
                    c.importance(), c.estimatedTokens(),
                    c.content().length() > 200 ? c.content().substring(0, 200) + "..." : c.content(),
                    c.createdAt().toString()
            ));
        }
        int tracked = getTokenUsage();
        int chunkSum = estimateTokenUsage();
        return new MemorySnapshot(
                tokenBudget, tracked, chunkSum, usageRatio(),
                chunks.size(), chunkInfos
        );
    }

    /**
     * @param tokenUsed    Tracked usage ({@link #getTokenUsage()}): API base + incremental estimate.
     * @param tokenEstimated Sum of per-chunk {@link MemoryChunk#estimatedTokens()} (heuristic baseline).
     */
    public record MemorySnapshot(
            int tokenBudget, int tokenUsed, int tokenEstimated, float usageRatio,
            int chunkCount, List<ChunkInfo> chunks
    ) {}

    public record ChunkInfo(
            String id, String type, String category,
            float importance, int tokens, String contentPreview,
            String createdAt
    ) {}
}
