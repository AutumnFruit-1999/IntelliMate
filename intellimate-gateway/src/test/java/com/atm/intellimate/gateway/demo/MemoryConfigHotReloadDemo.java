package com.atm.intellimate.gateway.demo;

import com.atm.intellimate.gateway.entity.MemoryConfigEntity;
import com.atm.intellimate.gateway.repository.MemoryConfigRepository;
import com.atm.intellimate.gateway.service.MemoryConfigService;
import com.atm.intellimate.memory.config.ResolvedMemoryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * In-memory simulation of {@link MemoryConfigRepository} to show hot reload
 * without a database.
 */
@ExtendWith(MockitoExtension.class)
class MemoryConfigHotReloadDemo {

    private static final String GLOBAL = "_global_";

    @Mock
    MemoryConfigRepository configRepo;

    private final Map<String, MemoryConfigEntity> backing = new HashMap<>();

    private MemoryConfigService service;

    private static Map<String, String> fullConfig() {
        return Map.ofEntries(
                Map.entry("working.token_budget", "128000"),
                Map.entry("working.consolidation_threshold", "0.75"),
                Map.entry("consolidation.model", "qwen-turbo"),
                Map.entry("consolidation.fallback_model", "qwen-lite"),
                Map.entry("consolidation.max_summary_tokens", "1024"),
                Map.entry("consolidation.timeout_ms", "30000"),
                Map.entry("consolidation.max_retries", "2"),
                Map.entry("consolidation.overflow_tolerance", "1.10"),
                Map.entry("long_term.enabled", "false"),
                Map.entry("long_term.max_memories_per_user", "500"),
                Map.entry("long_term.max_injection_tokens", "2048"),
                Map.entry("long_term.decay_lambda", "0.1"),
                Map.entry("long_term.compaction_threshold", "300"),
                Map.entry("long_term.archive_after_days", "30"),
                Map.entry("long_term.min_chunks_for_episodic", "4"),
                Map.entry("vector.enabled", "true"),
                Map.entry("embedding.definition_id", ""),
                Map.entry("retrieval.strategy", "hybrid"),
                Map.entry("retrieval.vector_weight", "0.6"),
                Map.entry("retrieval.keyword_weight", "0.4"),
                Map.entry("scoring.semantic_weight", "1.2"),
                Map.entry("scoring.episodic_weight", "0.8"),
                Map.entry("scoring.procedural_weight", "1.0"),
                Map.entry("scoring.semantic_decay_lambda", "0.03"),
                Map.entry("scoring.episodic_decay_lambda", "0.10"),
                Map.entry("scoring.procedural_decay_lambda", "0.05"),
                Map.entry("long_term.min_fact_importance", "0.3"),
                Map.entry("long_term.max_merged_content_length", "1000")
        );
    }

    @BeforeEach
    void setUp() {
        backing.clear();
        for (var e : fullConfig().entrySet()) {
            MemoryConfigEntity row = new MemoryConfigEntity();
            row.setAgentName(GLOBAL);
            row.setConfigKey(e.getKey());
            row.setConfigValue(e.getValue());
            backing.put(e.getKey(), row);
        }

        when(configRepo.findByAgentName(GLOBAL)).thenAnswer(
                inv -> Flux.fromIterable(new ArrayList<>(backing.values())));
        when(configRepo.upsertForAgent(eq(GLOBAL), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(1);
            String value = inv.getArgument(2);
            MemoryConfigEntity row = backing.getOrDefault(key, new MemoryConfigEntity());
            row.setAgentName(GLOBAL);
            row.setConfigKey(key);
            row.setConfigValue(value);
            backing.put(key, row);
            return Mono.just(1);
        });

        service = new MemoryConfigService(configRepo);
    }

    @Test
    @DisplayName("hotReload_afterUpdate_configChanges")
    void hotReload_afterUpdate_configChanges() {
        ResolvedMemoryConfig before = service.resolve().block();
        assertEquals(128_000, before.tokenBudget());

        service.updateConfig(Map.of("working.token_budget", "64000")).block();

        ResolvedMemoryConfig after = service.resolve().block();
        assertEquals(64_000, after.tokenBudget());
    }
}
