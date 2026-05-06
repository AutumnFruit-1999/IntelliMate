package com.atm.javaclaw.memory;

import com.atm.javaclaw.memory.config.ResolvedMemoryConfig;
import com.atm.javaclaw.memory.consolidation.MemoryConsolidator;
import com.atm.javaclaw.memory.longterm.LongTermMemory;
import com.atm.javaclaw.memory.model.MemoryChunk;
import com.atm.javaclaw.memory.perception.ImportanceAssessor;
import com.atm.javaclaw.memory.retrieval.MemoryRetrieval;
import com.atm.javaclaw.memory.working.TokenEstimator;
import com.atm.javaclaw.memory.working.WorkingMemory;
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

    public MemorySystem(ImportanceAssessor importanceAssessor,
                        TokenEstimator tokenEstimator,
                        MemoryConsolidator consolidator,
                        LongTermMemory longTermMemory,
                        MemoryRetrieval memoryRetrieval) {
        this.importanceAssessor = importanceAssessor;
        this.tokenEstimator = tokenEstimator;
        this.consolidator = consolidator;
        this.longTermMemory = longTermMemory;
        this.memoryRetrieval = memoryRetrieval;
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
        return memoryRetrieval.retrieve(cue, userId, agentId, config.maxInjectionTokens(), config.decayLambda());
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

    public MemoryConsolidator getConsolidator() {
        return consolidator;
    }
}
