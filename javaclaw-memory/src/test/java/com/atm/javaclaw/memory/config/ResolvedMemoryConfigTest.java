package com.atm.javaclaw.memory.config;

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
        return map;
    }

    @Test
    @DisplayName("fromMap parses all 14 fields correctly")
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
    }

    @Test
    @DisplayName("fromMap with missing key throws descriptive error")
    void fromMap_missingKey_throwsDescriptiveError() {
        Map<String, String> incomplete = new HashMap<>(fullDefaults());
        incomplete.remove("working.token_budget");
        var ex = assertThrows(IllegalArgumentException.class,
                () -> ResolvedMemoryConfig.fromMap(incomplete));
        assertTrue(ex.getMessage().contains("working.token_budget"));
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
