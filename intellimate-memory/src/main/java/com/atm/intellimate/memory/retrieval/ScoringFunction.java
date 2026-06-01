package com.atm.intellimate.memory.retrieval;

import com.atm.intellimate.memory.model.MemoryEntry;

import java.time.Duration;
import java.time.Instant;

/**
 * Computes retrieval/retention scores for long-term memories.
 * score = relevance * importance * type_weight * recency_decay * (1 + log(1 + access_count))
 * recency_decay = e^(-type_lambda * daysSinceLastAccess)
 */
public class ScoringFunction {

    private final double semanticWeight;
    private final double episodicWeight;
    private final double proceduralWeight;
    private final double semanticDecayLambda;
    private final double episodicDecayLambda;
    private final double proceduralDecayLambda;

    public ScoringFunction() {
        this(1.2, 0.8, 1.0, 0.03, 0.10, 0.05);
    }

    public ScoringFunction(double semanticWeight, double episodicWeight, double proceduralWeight,
                           double semanticDecayLambda, double episodicDecayLambda, double proceduralDecayLambda) {
        this.semanticWeight = semanticWeight;
        this.episodicWeight = episodicWeight;
        this.proceduralWeight = proceduralWeight;
        this.semanticDecayLambda = semanticDecayLambda;
        this.episodicDecayLambda = episodicDecayLambda;
        this.proceduralDecayLambda = proceduralDecayLambda;
    }

    public double computeRetrievalScore(MemoryEntry memory, double relevance, double lambda) {
        double importance = memory.getImportance();
        double typeWeight = getTypeWeight(memory.getMemoryType());
        double typeLambda = getTypeLambda(memory.getMemoryType(), lambda);
        double recencyDecay = computeRecencyDecay(memory.getLastAccessedAt(), typeLambda);
        double accessBoost = 1.0 + Math.log1p(memory.getAccessCount());
        return relevance * importance * typeWeight * recencyDecay * accessBoost;
    }

    public double computeRetentionScore(MemoryEntry memory, double lambda) {
        double importance = memory.getImportance();
        double typeWeight = getTypeWeight(memory.getMemoryType());
        double typeLambda = getTypeLambda(memory.getMemoryType(), lambda);
        double recencyDecay = computeRecencyDecay(memory.getLastAccessedAt(), typeLambda);
        double accessBoost = 1.0 + Math.log1p(memory.getAccessCount());
        return importance * typeWeight * recencyDecay * accessBoost;
    }

    public double computeRecencyDecay(Instant lastAccessed, double lambda) {
        if (lastAccessed == null) {
            return 0.5;
        }
        long daysSince = Duration.between(lastAccessed, Instant.now()).toDays();
        return Math.exp(-lambda * daysSince);
    }

    private double getTypeWeight(String memoryType) {
        if (memoryType == null) return 1.0;
        return switch (memoryType) {
            case "semantic" -> semanticWeight;
            case "episodic" -> episodicWeight;
            case "procedural" -> proceduralWeight;
            default -> 1.0;
        };
    }

    private double getTypeLambda(String memoryType, double baseLambda) {
        if (memoryType == null) return baseLambda;
        return switch (memoryType) {
            case "semantic" -> semanticDecayLambda;
            case "episodic" -> episodicDecayLambda;
            case "procedural" -> proceduralDecayLambda;
            default -> baseLambda;
        };
    }
}
