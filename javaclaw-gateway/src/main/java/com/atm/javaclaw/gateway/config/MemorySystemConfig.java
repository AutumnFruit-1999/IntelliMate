package com.atm.javaclaw.gateway.config;

import com.atm.javaclaw.memory.MemorySystem;
import com.atm.javaclaw.memory.longterm.LongTermMemory;
import com.atm.javaclaw.memory.perception.ImportanceAssessor;
import com.atm.javaclaw.memory.retrieval.MemoryRetrieval;
import com.atm.javaclaw.memory.working.TokenEstimator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link MemorySystem} for injection where a shared long-term store exists.
 * {@link com.atm.javaclaw.memory.consolidation.MemoryConsolidator} stays per-run in {@code AgentRuntime}
 * (needs request-scoped {@code ChatModel}); the bean uses {@code null} consolidator — consolidation
 * still works via locally constructed consolidators, while {@code retrieveMemories} / accessors work here.
 */
@Configuration
public class MemorySystemConfig {

    @Bean
    @ConditionalOnBean(LongTermMemory.class)
    public MemorySystem memorySystem(LongTermMemory longTermMemory) {
        TokenEstimator tokenEstimator = new TokenEstimator();
        MemoryRetrieval memoryRetrieval = new MemoryRetrieval(longTermMemory, tokenEstimator);
        return new MemorySystem(
                new ImportanceAssessor(),
                tokenEstimator,
                null,
                longTermMemory,
                memoryRetrieval);
    }
}
