package com.atm.javaclaw.memory.config;

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
        int minChunksForEpisodic
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
                parseIntOrDefault(map, "long_term.min_chunks_for_episodic", 4)
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

    private static int parseIntOrDefault(Map<String, String> map, String key, int defaultValue) {
        String val = map.get(key);
        if (val == null || val.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
