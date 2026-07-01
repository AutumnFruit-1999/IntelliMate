package com.atm.intellimate.memory.config;

import java.util.Map;

/**
 * Immutable snapshot of the memory configuration resolved from the database.
 */
public record ResolvedMemoryConfig(
        int tokenBudget,
        float consolidationThreshold,
        String consolidationModel,
        String fallbackModel,
        int maxSummaryTokens,
        int timeoutMs,
        int maxRetries,
        float overflowTolerance,
        boolean longTermEnabled,
        int maxMemoriesPerUser,
        int maxInjectionTokens,
        float decayLambda,
        int compactionThreshold,
        int archiveAfterDays,
        int minChunksForEpisodic,
        boolean vectorEnabled,
        String embeddingDefinitionId,
        String retrievalStrategy,
        float vectorWeight,
        float keywordWeight,
        float semanticWeight,
        float episodicWeight,
        float proceduralWeight,
        float semanticDecayLambda,
        float episodicDecayLambda,
        float proceduralDecayLambda,
        float minFactImportance,
        int maxMergedContentLength,
        float similarityThreshold,
        float topicSimilarityThreshold
) {

    public static ResolvedMemoryConfig fromMap(Map<String, String> map) {
        return new ResolvedMemoryConfig(
                parseInt(map, "working.token_budget"),
                parseFloat(map, "working.consolidation_threshold"),
                requireKey(map, "consolidation.model"),
                requireKey(map, "consolidation.fallback_model"),
                parseInt(map, "consolidation.max_summary_tokens"),
                parseInt(map, "consolidation.timeout_ms"),
                parseInt(map, "consolidation.max_retries"),
                parseFloat(map, "consolidation.overflow_tolerance"),
                parseBoolean(map, "long_term.enabled"),
                parseInt(map, "long_term.max_memories_per_user"),
                parseInt(map, "long_term.max_injection_tokens"),
                parseFloat(map, "long_term.decay_lambda"),
                parseInt(map, "long_term.compaction_threshold"),
                parseInt(map, "long_term.archive_after_days"),
                parseInt(map, "long_term.min_chunks_for_episodic"),
                parseBoolean(map, "vector.enabled"),
                getOrEmpty(map, "embedding.definition_id"),
                getOrDefault(map, "retrieval.strategy", "keyword_only"),
                parseFloatOrDefault(map, "retrieval.vector_weight", 0.6f),
                parseFloatOrDefault(map, "retrieval.keyword_weight", 0.4f),
                parseFloatOrDefault(map, "scoring.semantic_weight", 1.2f),
                parseFloatOrDefault(map, "scoring.episodic_weight", 0.8f),
                parseFloatOrDefault(map, "scoring.procedural_weight", 1.0f),
                parseFloatOrDefault(map, "scoring.semantic_decay_lambda", 0.03f),
                parseFloatOrDefault(map, "scoring.episodic_decay_lambda", 0.10f),
                parseFloatOrDefault(map, "scoring.procedural_decay_lambda", 0.05f),
                parseFloat(map, "long_term.min_fact_importance"),
                parseInt(map, "long_term.max_merged_content_length"),
                parseFloatOrDefault(map, "vector.similarity_threshold", 0.35f),
                parseFloatOrDefault(map, "consolidation.topic_similarity_threshold", 0.7f)
        );
    }

    private static String requireKey(Map<String, String> map, String key) {
        String val = map.get(key);
        if (val == null) {
            throw new IllegalArgumentException("Missing required memory config key: " + key);
        }
        return val;
    }

    private static int parseInt(Map<String, String> map, String key) {
        String val = requireKey(map, key);
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid integer for memory config key '" + key + "': " + val, e);
        }
    }

    private static float parseFloat(Map<String, String> map, String key) {
        String val = requireKey(map, key);
        try {
            return Float.parseFloat(val);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid float for memory config key '" + key + "': " + val, e);
        }
    }

    private static boolean parseBoolean(Map<String, String> map, String key) {
        String val = requireKey(map, key);
        return Boolean.parseBoolean(val);
    }

    private static String getOrEmpty(Map<String, String> map, String key) {
        String val = map.get(key);
        return val != null ? val : "";
    }

    private static String getOrDefault(Map<String, String> map, String key, String defaultValue) {
        String val = map.get(key);
        return (val != null && !val.isBlank()) ? val : defaultValue;
    }

    private static float parseFloatOrDefault(Map<String, String> map, String key, float defaultValue) {
        String val = map.get(key);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Float.parseFloat(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
