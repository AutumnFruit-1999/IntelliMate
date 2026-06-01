package com.atm.intellimate.memory;

import com.atm.intellimate.memory.config.ResolvedMemoryConfig;
import com.atm.intellimate.memory.consolidation.MemoryConsolidator;
import com.atm.intellimate.memory.longterm.LongTermMemory;
import com.atm.intellimate.memory.model.MemoryChunk;
import com.atm.intellimate.memory.perception.ImportanceAssessor;
import com.atm.intellimate.memory.retrieval.HybridMemoryRetrieval;
import com.atm.intellimate.memory.retrieval.MemoryRetrieval;
import com.atm.intellimate.memory.working.TokenEstimator;
import com.atm.intellimate.memory.working.WorkingMemory;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Unified facade for the memory system.
 * AgentRuntime and MessagePipeline interact with memory exclusively through this API.
 */
public class MemorySystem {

    private final ImportanceAssessor importanceAssessor;
    private final TokenEstimator tokenEstimator;
    private final MemoryConsolidator consolidator;
    private final LongTermMemory longTermMemory;
    private final MemoryRetrieval memoryRetrieval;
    private final HybridMemoryRetrieval hybridRetrieval;

    public MemorySystem(ImportanceAssessor importanceAssessor,
                        TokenEstimator tokenEstimator,
                        MemoryConsolidator consolidator,
                        LongTermMemory longTermMemory,
                        MemoryRetrieval memoryRetrieval) {
        this(importanceAssessor, tokenEstimator, consolidator, longTermMemory, memoryRetrieval, null);
    }

    public MemorySystem(ImportanceAssessor importanceAssessor,
                        TokenEstimator tokenEstimator,
                        MemoryConsolidator consolidator,
                        LongTermMemory longTermMemory,
                        MemoryRetrieval memoryRetrieval,
                        HybridMemoryRetrieval hybridRetrieval) {
        this.importanceAssessor = importanceAssessor;
        this.tokenEstimator = tokenEstimator;
        this.consolidator = consolidator;
        this.longTermMemory = longTermMemory;
        this.memoryRetrieval = memoryRetrieval;
        this.hybridRetrieval = hybridRetrieval;
    }

    public WorkingMemory createWorkingMemory(ResolvedMemoryConfig config, String systemPrompt) {
        return createWorkingMemory(config, systemPrompt, null, "default");
    }

    public WorkingMemory createWorkingMemory(ResolvedMemoryConfig config,
                                              String systemPrompt, String planContext) {
        return createWorkingMemory(config, systemPrompt, planContext, "default");
    }

    public WorkingMemory createWorkingMemory(ResolvedMemoryConfig config,
                                              String systemPrompt, String planContext, String agentId) {
        String fullContent = planContext != null && !planContext.isBlank()
                ? systemPrompt + "\n\n" + planContext
                : systemPrompt;
        int systemTokens = tokenEstimator.estimateForMessage(fullContent);
        MemoryChunk systemChunk = MemoryChunk.system(fullContent, systemTokens);
        return new WorkingMemory(config.tokenBudget(), config.consolidationThreshold(),
                config.overflowTolerance(), consolidator, systemChunk, agentId);
    }

    public Mono<List<MemoryChunk>> retrieveMemories(String cue, String userId,
                                                      ResolvedMemoryConfig config) {
        return retrieveMemories(cue, userId, "default", config);
    }

    public Mono<List<MemoryChunk>> retrieveMemories(String cue, String userId, String agentId,
                                                      ResolvedMemoryConfig config) {
        if (!config.longTermEnabled()) {
            return Mono.just(List.of());
        }
        if (hybridRetrieval != null) {
            HybridMemoryRetrieval.Strategy strategy = parseRetrievalStrategy(config);
            return hybridRetrieval.retrieve(cue, userId, agentId,
                    config.maxInjectionTokens(), config.decayLambda(), strategy);
        }
        return memoryRetrieval.retrieve(cue, userId, agentId, config.maxInjectionTokens(), config.decayLambda());
    }

    private static HybridMemoryRetrieval.Strategy parseRetrievalStrategy(ResolvedMemoryConfig config) {
        if (!config.vectorEnabled()) {
            return HybridMemoryRetrieval.Strategy.KEYWORD_ONLY;
        }
        String strategy = config.retrievalStrategy();
        if (strategy == null || strategy.isBlank()) {
            return HybridMemoryRetrieval.Strategy.HYBRID;
        }
        return switch (strategy.toLowerCase()) {
            case "vector_only" -> HybridMemoryRetrieval.Strategy.VECTOR_ONLY;
            case "keyword_only" -> HybridMemoryRetrieval.Strategy.KEYWORD_ONLY;
            default -> HybridMemoryRetrieval.Strategy.HYBRID;
        };
    }

    public ImportanceAssessor getImportanceAssessor() {
        return importanceAssessor;
    }

    public TokenEstimator getTokenEstimator() {
        return tokenEstimator;
    }

    public LongTermMemory getLongTermMemory() {
        return longTermMemory;
    }

    public MemoryRetrieval getMemoryRetrieval() {
        return memoryRetrieval;
    }

    public HybridMemoryRetrieval getHybridRetrieval() {
        return hybridRetrieval;
    }

    public MemoryConsolidator getConsolidator() {
        return consolidator;
    }
}
