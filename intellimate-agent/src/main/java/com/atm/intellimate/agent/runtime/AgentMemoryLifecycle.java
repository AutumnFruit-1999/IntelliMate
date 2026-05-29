package com.atm.intellimate.agent.runtime;

import com.atm.intellimate.agent.model.ChatModelRegistry;
import com.atm.intellimate.agent.model.ResolvedModel;
import com.atm.intellimate.memory.MemorySystem;
import com.atm.intellimate.memory.config.MemoryConfigProvider;
import com.atm.intellimate.memory.config.ResolvedMemoryConfig;
import com.atm.intellimate.memory.consolidation.ConsolidationResult;
import com.atm.intellimate.memory.consolidation.MemoryConsolidator;
import com.atm.intellimate.memory.longterm.LongTermMemory;
import com.atm.intellimate.memory.model.ExtractedFact;
import com.atm.intellimate.memory.model.MemoryChunk;
import com.atm.intellimate.memory.working.TokenEstimator;
import com.atm.intellimate.memory.working.WorkingMemory;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class AgentMemoryLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryLifecycle.class);

    private final MemoryConfigProvider memoryConfigProvider;
    private final LongTermMemory longTermMemory;
    private final MemorySystem memorySystem;
    private final ChatModelRegistry chatModelRegistry;
    private final MeterRegistry meterRegistry;

    private record DeferredEpisodicStore(WorkingMemory workingMemory, LongTermMemory ltm,
                                         String userId, String agentId, Long sessionId,
                                         int minChunksForEpisodic) {}

    record MemoryInit(ResolvedMemoryConfig resolved, MemoryConsolidator consolidator) {}

    private final ConcurrentMap<Long, DeferredEpisodicStore> deferredEpisodicStores = new ConcurrentHashMap<>();

    public AgentMemoryLifecycle(@Autowired(required = false) MemoryConfigProvider memoryConfigProvider,
                                @Autowired(required = false) LongTermMemory longTermMemory,
                                @Autowired(required = false) MemorySystem memorySystem,
                                ChatModelRegistry chatModelRegistry,
                                @Autowired(required = false) MeterRegistry meterRegistry) {
        this.memoryConfigProvider = memoryConfigProvider;
        this.longTermMemory = longTermMemory;
        this.memorySystem = memorySystem;
        this.chatModelRegistry = chatModelRegistry;
        this.meterRegistry = meterRegistry;
    }

    public void recordMemoryConsolidation(String agentId) {
        if (meterRegistry != null) {
            meterRegistry.counter("memory.consolidation.triggered",
                    "agent", agentId).increment();
        }
    }

    public void recordWorkingMemoryUsage(String agentId, float usageRatio) {
        if (meterRegistry != null) {
            io.micrometer.core.instrument.Gauge.builder("memory.working.usage_ratio",
                            () -> (double) usageRatio)
                    .tag("agent", agentId)
                    .register(meterRegistry);
        }
    }

    public void recordLongTermRetrievalLatency(String agentId, long durationMs) {
        if (meterRegistry != null) {
            meterRegistry.timer("memory.longterm.retrieval.latency", "agent", agentId)
                    .record(java.time.Duration.ofMillis(durationMs));
        }
    }

    public void recordLongTermStore(String type) {
        if (meterRegistry != null) {
            meterRegistry.counter("memory.longterm.store.count", "type", type).increment();
        }
    }

    private void recordEpisodicStored(String agentId) {
        if (meterRegistry != null) {
            meterRegistry.counter("memory.longterm.store.count",
                    "type", "episodic").increment();
        }
    }

    /**
     * Called on WebSocket disconnect to flush deferred episodic memory for the session.
     * Only stores if chunks > 4 and no prior episodic was generated during this session.
     */
    public boolean flushDeferredEpisodicMemory(Long sessionId) {
        DeferredEpisodicStore deferred = deferredEpisodicStores.remove(sessionId);
        if (deferred == null) {
            log.info("flushDeferredEpisodicMemory: no deferred data for session {}, skipping", sessionId);
            return false;
        }
        log.info("flushDeferredEpisodicMemory: flushing session {}, chunks={}, minChunks={}",
                sessionId, deferred.workingMemory().getChunks().size(), deferred.minChunksForEpisodic());
        storeSessionEpisodicMemory(deferred.workingMemory(), deferred.ltm(),
                deferred.userId(), deferred.agentId(), deferred.sessionId(), deferred.minChunksForEpisodic());
        return true;
    }

    /**
     * Resolves DB-backed memory config once per run for WorkingMemory thresholds,
     * consolidator construction, and long-term retrieval settings.
     */
    public Mono<MemoryInit> loadMemoryInitReactive(TokenEstimator tokenEstimator) {
        if (memoryConfigProvider == null) {
            return Mono.just(new MemoryInit(null, null));
        }
        return memoryConfigProvider.resolve()
                .timeout(Duration.ofSeconds(2))
                .map(memConfig -> new MemoryInit(memConfig, createMemoryConsolidator(memConfig, tokenEstimator)))
                .defaultIfEmpty(new MemoryInit(null, null))
                .onErrorResume(e -> {
                    log.warn("Failed to load memory config, using defaults: {}", e.getMessage());
                    return Mono.just(new MemoryInit(null, null));
                });
    }

    public static AgentEvent.ConsolidationTriggered toConsolidationTriggeredEvent(
            ConsolidationResult cr, WorkingMemory workingMemory) {
        WorkingMemory.MemorySnapshot snap = workingMemory.getSnapshot();
        int tokensBefore = cr.tokensBefore() >= 0 ? cr.tokensBefore() : snap.tokenUsed();
        int tokensAfter = cr.tokensAfter() >= 0 ? cr.tokensAfter() : snap.tokenUsed();
        List<String> factStrings = cr.facts().stream()
                .map(f -> f.content())
                .toList();
        List<AgentEvent.ChunkPreview> candidates = cr.sourceChunkPreviews().stream()
                .map(p -> new AgentEvent.ChunkPreview(p.type(), p.tokens(), p.importance(), p.preview()))
                .toList();
        return new AgentEvent.ConsolidationTriggered(
                cr.sourceChunkCount(), tokensBefore, tokensAfter, factStrings,
                candidates, cr.factsStoredToLongTerm());
    }

    /**
     * Defers episodic storage until WebSocket disconnects. Updates the deferred state on every agent run
     * so that the latest conversation state is used when flush is triggered.
     */
    public void deferEpisodicStore(WorkingMemory workingMemory, LongTermMemory ltm,
                                   String userId, String agentId, Long sessionId, int minChunksForEpisodic) {
        deferredEpisodicStores.put(sessionId, new DeferredEpisodicStore(workingMemory, ltm, userId, agentId, sessionId, minChunksForEpisodic));
    }

    private MemoryConsolidator createMemoryConsolidator(ResolvedMemoryConfig memConfig, TokenEstimator tokenEstimator) {
        try {
            ResolvedModel consolidationModel = resolveModel(memConfig.consolidationModel());
            ChatModel fallbackChatModel = null;
            String fallbackModelId = null;
            try {
                ResolvedModel fallbackResolved = resolveModel(memConfig.fallbackModel());
                fallbackChatModel = fallbackResolved.chatModel();
                fallbackModelId = fallbackResolved.modelId();
            } catch (Exception ignored) {
                // optional fallback model
            }
            LongTermMemory effectiveLtm = memConfig.longTermEnabled() ? longTermMemory : null;
            return new MemoryConsolidator(
                    consolidationModel.chatModel(),
                    fallbackChatModel,
                    effectiveLtm,
                    tokenEstimator,
                    memConfig.maxSummaryTokens(),
                    memConfig.maxRetries(),
                    memConfig.timeoutMs(),
                    consolidationModel.modelId(),
                    fallbackModelId);
        } catch (Exception e) {
            log.warn("Failed to create MemoryConsolidator, using null: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Asynchronously store an episodic summary of the session.
     * Uses LLM summarization when no mid-session consolidation occurred and conversation is substantial.
     */
    void storeSessionEpisodicMemory(WorkingMemory workingMemory, LongTermMemory ltm,
                                    String userId, String agentId, Long sessionId, int minChunksForEpisodic) {
        try {
            List<MemoryChunk> chunks = workingMemory.getChunks();
            if (chunks.isEmpty()) {
                log.info("storeSessionEpisodicMemory: skipped for session {} (no chunks)", sessionId);
                return;
            }
            log.info("storeSessionEpisodicMemory: storing for session {} (chunks={}, consolidationCount={})",
                    sessionId, chunks.size(), workingMemory.getConsolidationCount());

            if (workingMemory.getConsolidationCount() == 0) {
                storeSessionEpisodicViaLLM(workingMemory, ltm, userId, agentId, sessionId);
            } else {
                storeSessionEpisodicSimple(chunks, ltm, userId, agentId, sessionId);
            }
        } catch (Exception e) {
            log.warn("Error building session episodic memory: {}", e.getMessage());
        }
    }

    /**
     * High-quality episodic: call consolidation model to summarize the entire session.
     * Uses summarizeSession (processes ALL chunks) instead of tryConsolidate (which only processes old chunks).
     */
    private void storeSessionEpisodicViaLLM(WorkingMemory workingMemory, LongTermMemory ltm,
                                            String userId, String agentId, Long sessionId) {
        MemoryConsolidator consolidator = workingMemory.getConsolidator();
        if (consolidator == null) {
            consolidator = memorySystem != null ? memorySystem.getConsolidator() : null;
        }
        if (consolidator == null) {
            storeSessionEpisodicSimple(workingMemory.getChunks(), ltm, userId, agentId, sessionId);
            return;
        }

        final MemoryConsolidator effectiveConsolidator = consolidator;
        final List<MemoryChunk> allChunks = new ArrayList<>(workingMemory.getChunks());
        Mono.fromCallable(() -> effectiveConsolidator.summarizeSession(allChunks, agentId, userId))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        result -> {
                            if (result != null && !result.facts().isEmpty()) {
                                log.info("Session {} full summarization: {} facts stored", sessionId, result.facts().size());
                                recordEpisodicStored(agentId);
                            } else {
                                storeSessionEpisodicSimple(allChunks, ltm, userId, agentId, sessionId);
                            }
                        },
                        e -> {
                            log.warn("Session {} full summarization failed, falling back to simple: {}",
                                    sessionId, e.getMessage());
                            storeSessionEpisodicSimple(allChunks, ltm, userId, agentId, sessionId);
                        });
    }

    private void storeSessionEpisodicSimple(List<MemoryChunk> chunks, LongTermMemory ltm,
                                            String userId, String agentId, Long sessionId) {
        StringBuilder summary = new StringBuilder();
        summary.append("Session ").append(sessionId).append(" summary: ");
        int userCount = 0, toolCount = 0;
        for (MemoryChunk c : chunks) {
            switch (c.type()) {
                case USER -> userCount++;
                case TOOL_INTERACTION -> toolCount++;
                default -> {}
            }
        }
        summary.append(userCount).append(" user turns, ").append(toolCount).append(" tool calls. ");

        for (MemoryChunk c : chunks) {
            if (c.type() == com.atm.intellimate.memory.model.ChunkType.USER) {
                String preview = c.content().length() > 100 ? c.content().substring(0, 100) + "..." : c.content();
                summary.append("Topics: ").append(preview);
                break;
            }
        }

        ExtractedFact episodic = new ExtractedFact("episodic", summary.toString(), 0.5f);
        ltm.store(episodic, userId, agentId)
                .subscribe(
                        unused -> recordEpisodicStored(agentId),
                        e -> log.warn("Failed to store session episodic memory for session {}: {}", sessionId, e.getMessage()));
    }

    private ResolvedModel resolveModel(String modelRef) {
        if (modelRef == null || modelRef.isBlank()) {
            throw new IllegalArgumentException("Model reference is null or empty");
        }
        try {
            Long definitionId = Long.parseLong(modelRef);
            return chatModelRegistry.resolve(definitionId);
        } catch (NumberFormatException e) {
            return chatModelRegistry.resolveByModelName(modelRef);
        }
    }
}
