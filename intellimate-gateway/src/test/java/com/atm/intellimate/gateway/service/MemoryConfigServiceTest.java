package com.atm.intellimate.gateway.service;

import com.atm.intellimate.gateway.entity.MemoryConfigEntity;
import com.atm.intellimate.gateway.repository.MemoryConfigRepository;
import com.atm.intellimate.memory.config.ResolvedMemoryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryConfigService")
class MemoryConfigServiceTest {

    private static final String GLOBAL_AGENT = "_global_";

    @Mock
    private MemoryConfigRepository configRepo;

    private MemoryConfigService service;

    @BeforeEach
    void setUp() {
        service = new MemoryConfigService(configRepo);
    }

    @Test
    @DisplayName("resolve() builds ResolvedMemoryConfig from DB entries")
    void resolve_returnsResolvedConfig() {
        when(configRepo.findByAgentName(GLOBAL_AGENT)).thenReturn(Flux.fromIterable(
                fullConfigEntries(GLOBAL_AGENT).entrySet().stream()
                        .map(e -> entity(GLOBAL_AGENT, e.getKey(), e.getValue()))
                        .toList()
        ));

        StepVerifier.create(service.resolve())
                .assertNext(config -> {
                    assertThat(config).isNotNull();
                    assertThat(config.tokenBudget()).isEqualTo(128000);
                    assertThat(config.consolidationThreshold()).isEqualTo(0.75f);
                    assertThat(config.consolidationModel()).isEqualTo("qwen-turbo");
                    assertThat(config.longTermEnabled()).isFalse();
                    assertThat(config.maxRetries()).isEqualTo(2);
                    assertThat(config.archiveAfterDays()).isEqualTo(30);
                    assertThat(config.vectorEnabled()).isTrue();
                    assertThat(config.embeddingDefinitionId()).isEqualTo("");
                    assertThat(config.retrievalStrategy()).isEqualTo("hybrid");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("resolveGrouped() returns config items with metadata")
    void resolveGrouped_containsAllKeysWithMetadata() {
        when(configRepo.findByAgentName(GLOBAL_AGENT)).thenReturn(Flux.fromIterable(
                fullConfigEntries(GLOBAL_AGENT).entrySet().stream()
                        .map(e -> entity(GLOBAL_AGENT, e.getKey(), e.getValue()))
                        .toList()
        ));

        StepVerifier.create(service.resolveGrouped())
                .assertNext(items -> {
                    assertThat(items).hasSize(MemoryConfigService.ALL_CONFIG_KEYS.size());
                    var tokenBudget = items.get("working.token_budget");
                    assertThat(tokenBudget).isNotNull();
                    assertThat(tokenBudget.value()).isEqualTo("128000");
                    assertThat(tokenBudget.type()).isEqualTo("number");
                    var retrievalStrategy = items.get("retrieval.strategy");
                    assertThat(retrievalStrategy).isNotNull();
                    assertThat(retrievalStrategy.type()).isEqualTo("string");
                    var vectorEnabled = items.get("vector.enabled");
                    assertThat(vectorEnabled).isNotNull();
                    assertThat(vectorEnabled.type()).isEqualTo("boolean");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("updateConfig() calls upsertForAgent for each entry")
    void updateConfig_upsertsEachEntry() {
        when(configRepo.upsertForAgent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(1));

        StepVerifier.create(service.updateConfig(Map.of(
                        "working.token_budget", "64000",
                        "long_term.enabled", "true"
                )))
                .verifyComplete();

        verify(configRepo).upsertForAgent(eq(GLOBAL_AGENT), eq("working.token_budget"), eq("64000"), anyString());
        verify(configRepo).upsertForAgent(eq(GLOBAL_AGENT), eq("long_term.enabled"), eq("true"), anyString());
    }

    @Test
    @DisplayName("deleteConfigForAgent() deletes all config for the agent")
    void deleteConfigForAgent_deletesAll() {
        when(configRepo.deleteByAgentName("GroupChat")).thenReturn(Mono.just(5));

        StepVerifier.create(service.deleteConfigForAgent("GroupChat"))
                .verifyComplete();

        verify(configRepo).deleteByAgentName("GroupChat");
    }

    @Test
    @DisplayName("ALL_CONFIG_KEYS contains all 28 config keys")
    void allConfigKeys_returnsExpectedSize() {
        assertThat(MemoryConfigService.ALL_CONFIG_KEYS).hasSize(28);
        assertThat(MemoryConfigService.ALL_CONFIG_KEYS).contains(
                "working.token_budget", "long_term.enabled", "vector.enabled", "retrieval.strategy");
    }

    @Test
    @DisplayName("resolveForAgent() reads only from DB for the specified agent")
    void resolveForAgent_readsOnlyAgentConfig() {
        String agentName = "GroupChat";
        when(configRepo.findByAgentName(agentName)).thenReturn(Flux.fromIterable(
                fullConfigEntries(agentName).entrySet().stream()
                        .map(e -> entity(agentName, e.getKey(), e.getValue()))
                        .toList()
        ));

        StepVerifier.create(service.resolveForAgent(agentName))
                .assertNext(config -> {
                    assertThat(config.tokenBudget()).isEqualTo(128000);
                    assertThat(config.consolidationModel()).isEqualTo("qwen-turbo");
                })
                .verifyComplete();

        verify(configRepo).findByAgentName(agentName);
        verify(configRepo, never()).findByAgentName(GLOBAL_AGENT);
    }

    @Test
    @DisplayName("resolveForAgent() with no DB records fails due to missing keys")
    void resolveForAgent_noRecords_failsDueToMissingKeys() {
        String agentName = "NewAgent";
        when(configRepo.findByAgentName(agentName)).thenReturn(Flux.empty());

        StepVerifier.create(service.resolveForAgent(agentName))
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    @DisplayName("resolveForAgent() with null/blank agentName falls back to _global_")
    void resolveForAgent_nullAgent_fallsToGlobal() {
        when(configRepo.findByAgentName(GLOBAL_AGENT)).thenReturn(Flux.fromIterable(
                fullConfigEntries(GLOBAL_AGENT).entrySet().stream()
                        .map(e -> entity(GLOBAL_AGENT, e.getKey(), e.getValue()))
                        .toList()
        ));

        StepVerifier.create(service.resolveForAgent(null))
                .assertNext(config -> {
                    assertThat(config.tokenBudget()).isEqualTo(128000);
                })
                .verifyComplete();

        verify(configRepo).findByAgentName(GLOBAL_AGENT);
    }

    @Test
    @DisplayName("resolveGroupedForAgent() reads only agent config, shows empty for missing keys")
    void resolveGroupedForAgent_readsOnlyAgentConfig() {
        String agentName = "GroupChat";
        when(configRepo.findByAgentName(agentName)).thenReturn(Flux.just(
                entity(agentName, "long_term.enabled", "true"),
                entity(agentName, "working.token_budget", "64000")
        ));

        StepVerifier.create(service.resolveGroupedForAgent(agentName))
                .assertNext(items -> {
                    assertThat(items).hasSize(MemoryConfigService.ALL_CONFIG_KEYS.size());
                    var ltEnabled = items.get("long_term.enabled");
                    assertThat(ltEnabled).isNotNull();
                    assertThat(ltEnabled.value()).isEqualTo("true");
                    var tokenBudget = items.get("working.token_budget");
                    assertThat(tokenBudget).isNotNull();
                    assertThat(tokenBudget.value()).isEqualTo("64000");
                    var timeout = items.get("consolidation.timeout_ms");
                    assertThat(timeout).isNotNull();
                    assertThat(timeout.value()).isEqualTo("");
                })
                .verifyComplete();

        verify(configRepo).findByAgentName(agentName);
        verify(configRepo, never()).findByAgentName(GLOBAL_AGENT);
    }

    private static Map<String, String> fullConfigEntries(String agentName) {
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

    private static MemoryConfigEntity entity(String agentName, String key, String value) {
        MemoryConfigEntity e = new MemoryConfigEntity();
        e.setAgentName(agentName);
        e.setConfigKey(key);
        e.setConfigValue(value);
        return e;
    }
}
