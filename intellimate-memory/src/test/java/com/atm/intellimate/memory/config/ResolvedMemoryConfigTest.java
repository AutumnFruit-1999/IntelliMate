package com.atm.intellimate.memory.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ResolvedMemoryConfig")
class ResolvedMemoryConfigTest {

    private Map<String, String> fullDefaults() {
        Map<String, String> map = new HashMap<>();
        map.put("working.token_budget", "128000");
        map.put("working.consolidation_threshold", "0.75");
        map.put("consolidation.model", "qwen-turbo");
        map.put("consolidation.fallback_model", "qwen-lite");
        map.put("consolidation.max_summary_tokens", "1024");
        map.put("consolidation.timeout_ms", "5000");
        map.put("consolidation.max_retries", "2");
        map.put("consolidation.overflow_tolerance", "1.10");
        map.put("long_term.enabled", "false");
        map.put("long_term.max_memories_per_user", "500");
        map.put("long_term.max_injection_tokens", "2048");
        map.put("long_term.decay_lambda", "0.1");
        map.put("long_term.compaction_threshold", "300");
        map.put("long_term.archive_after_days", "30");
        map.put("long_term.min_chunks_for_episodic", "4");
        map.put("vector.enabled", "true");
        map.put("embedding.definition_id", "10");
        map.put("retrieval.strategy", "hybrid");
        map.put("retrieval.vector_weight", "0.6");
        map.put("retrieval.keyword_weight", "0.4");
        map.put("scoring.semantic_weight", "1.2");
        map.put("scoring.episodic_weight", "0.8");
        map.put("scoring.procedural_weight", "1.0");
        map.put("scoring.semantic_decay_lambda", "0.03");
        map.put("scoring.episodic_decay_lambda", "0.10");
        map.put("scoring.procedural_decay_lambda", "0.05");
        map.put("long_term.min_fact_importance", "0.3");
        map.put("long_term.max_merged_content_length", "1000");
        return map;
    }

    @Test
    @DisplayName("fromMap parses all fields correctly")
    void fromMap_parsesAllFields() {
        ResolvedMemoryConfig cfg = ResolvedMemoryConfig.fromMap(fullDefaults());
        assertEquals(128000, cfg.tokenBudget());
        assertEquals(0.75f, cfg.consolidationThreshold(), 0.001f);
        assertEquals("qwen-turbo", cfg.consolidationModel());
        assertEquals("qwen-lite", cfg.fallbackModel());
        assertEquals(1024, cfg.maxSummaryTokens());
        assertEquals(5000, cfg.timeoutMs());
        assertEquals(2, cfg.maxRetries());
        assertEquals(1.10f, cfg.overflowTolerance(), 0.001f);
        assertFalse(cfg.longTermEnabled());
        assertEquals(500, cfg.maxMemoriesPerUser());
        assertEquals(2048, cfg.maxInjectionTokens());
        assertEquals(0.1f, cfg.decayLambda(), 0.001f);
        assertEquals(300, cfg.compactionThreshold());
        assertEquals(30, cfg.archiveAfterDays());
        assertEquals(4, cfg.minChunksForEpisodic());
        assertTrue(cfg.vectorEnabled());
        assertEquals("10", cfg.embeddingDefinitionId());
        assertEquals("hybrid", cfg.retrievalStrategy());
        assertEquals(0.6f, cfg.vectorWeight(), 0.001f);
        assertEquals(0.4f, cfg.keywordWeight(), 0.001f);
        assertEquals(1.2f, cfg.semanticWeight(), 0.001f);
        assertEquals(0.8f, cfg.episodicWeight(), 0.001f);
        assertEquals(1.0f, cfg.proceduralWeight(), 0.001f);
        assertEquals(0.03f, cfg.semanticDecayLambda(), 0.001f);
        assertEquals(0.10f, cfg.episodicDecayLambda(), 0.001f);
        assertEquals(0.05f, cfg.proceduralDecayLambda(), 0.001f);
        assertEquals(0.3f, cfg.minFactImportance(), 0.001f);
        assertEquals(1000, cfg.maxMergedContentLength());
    }

    private static final java.util.Set<String> OPTIONAL_KEYS = java.util.Set.of(
            "embedding.definition_id",
            "retrieval.strategy", "retrieval.vector_weight", "retrieval.keyword_weight",
            "scoring.semantic_weight", "scoring.episodic_weight", "scoring.procedural_weight",
            "scoring.semantic_decay_lambda", "scoring.episodic_decay_lambda", "scoring.procedural_decay_lambda",
            "vector.similarity_threshold", "consolidation.topic_similarity_threshold"
    );

    @Test
    @DisplayName("fromMap with missing required key throws descriptive error")
    void fromMap_missingRequiredKey_throwsDescriptiveError() {
        for (String key : fullDefaults().keySet()) {
            if (OPTIONAL_KEYS.contains(key)) continue;
            Map<String, String> incomplete = new HashMap<>(fullDefaults());
            incomplete.remove(key);
            var ex = assertThrows(IllegalArgumentException.class,
                    () -> ResolvedMemoryConfig.fromMap(incomplete),
                    "Should throw for missing key: " + key);
            assertTrue(ex.getMessage().contains(key),
                    "Error message should mention key: " + key);
        }
    }

    @Test
    @DisplayName("fromMap with missing optional keys uses defaults")
    void fromMap_missingOptionalKeys_usesDefaults() {
        Map<String, String> minimal = new HashMap<>(fullDefaults());
        OPTIONAL_KEYS.forEach(minimal::remove);
        ResolvedMemoryConfig cfg = ResolvedMemoryConfig.fromMap(minimal);
        assertEquals("", cfg.embeddingDefinitionId());
        assertEquals("keyword_only", cfg.retrievalStrategy());
        assertEquals(0.6f, cfg.vectorWeight(), 0.001f);
        assertEquals(1.2f, cfg.semanticWeight(), 0.001f);
        assertEquals(0.35f, cfg.similarityThreshold(), 0.001f);
        assertEquals(0.7f, cfg.topicSimilarityThreshold(), 0.001f);
    }

    @Test
    @DisplayName("fromMap parses v3 similarity threshold fields when present")
    void fromMap_parsesV3SimilarityFields() {
        Map<String, String> map = new HashMap<>(fullDefaults());
        map.put("vector.similarity_threshold", "0.42");
        map.put("consolidation.topic_similarity_threshold", "0.85");
        ResolvedMemoryConfig cfg = ResolvedMemoryConfig.fromMap(map);
        assertEquals(0.42f, cfg.similarityThreshold(), 0.001f);
        assertEquals(0.85f, cfg.topicSimilarityThreshold(), 0.001f);
    }

    @Test
    @DisplayName("fromMap uses default v3 similarity thresholds when keys absent")
    void fromMap_v3SimilarityFields_useDefaults() {
        Map<String, String> map = new HashMap<>(fullDefaults());
        map.remove("vector.similarity_threshold");
        map.remove("consolidation.topic_similarity_threshold");
        ResolvedMemoryConfig cfg = ResolvedMemoryConfig.fromMap(map);
        assertEquals(0.35f, cfg.similarityThreshold(), 0.001f);
        assertEquals(0.7f, cfg.topicSimilarityThreshold(), 0.001f);
    }

    @Test
    @DisplayName("fromMap with invalid number throws descriptive error")
    void fromMap_invalidNumber_throwsDescriptiveError() {
        Map<String, String> bad = new HashMap<>(fullDefaults());
        bad.put("working.token_budget", "not_a_number");
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ResolvedMemoryConfig.fromMap(bad));
        assertTrue(ex.getMessage().contains("working.token_budget"));
    }
}
