package com.atm.javaclaw.memory.retrieval;

import com.atm.javaclaw.memory.model.MemoryEntry;

import java.time.Duration;
import java.time.Instant;

/**
 * Computes retrieval/retention scores for long-term memories.
 * score = relevance * importance * recency_decay * (1 + log(1 + access_count))
 * recency_decay = e^(-lambda * daysSinceLastAccess)
 */
public class ScoringFunction {

    public double computeRetrievalScore(MemoryEntry memory, double relevance, double lambda) {
        double importance = memory.getImportance();
        double recencyDecay = computeRecencyDecay(memory.getLastAccessedAt(), lambda);
        double accessBoost = 1.0 + Math.log1p(memory.getAccessCount());
        return relevance * importance * recencyDecay * accessBoost;
    }

    public double computeRetentionScore(MemoryEntry memory, double lambda) {
        double importance = memory.getImportance();
        double recencyDecay = computeRecencyDecay(memory.getLastAccessedAt(), lambda);
        double accessBoost = 1.0 + Math.log1p(memory.getAccessCount());
        return importance * recencyDecay * accessBoost;
    }

    public double computeRecencyDecay(Instant lastAccessed, double lambda) {
        if (lastAccessed == null) {
            return 0.5;
        }
        long daysSince = Duration.between(lastAccessed, Instant.now()).toDays();
        return Math.exp(-lambda * daysSince);
    }
}
