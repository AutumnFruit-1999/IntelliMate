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
                MemoryConfigService.getDefaults().entrySet().stream()
                        .map(e -> entity(e.getKey(), e.getValue()))
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
                    assertThat(config.embeddingModel()).isEqualTo("text-embedding-v3");
                    assertThat(config.retrievalStrategy()).isEqualTo("hybrid");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("resolveGrouped() returns config items with metadata")
    void resolveGrouped_containsAllKeysWithMetadata() {
        when(configRepo.findByAgentName(GLOBAL_AGENT)).thenReturn(Flux.fromIterable(
                MemoryConfigService.getDefaults().entrySet().stream()
                        .map(e -> entity(e.getKey(), e.getValue()))
                        .toList()
        ));

        StepVerifier.create(service.resolveGrouped())
                .assertNext(items -> {
                    assertThat(items).hasSize(MemoryConfigService.getDefaults().size());
                    var tokenBudget = items.get("working.token_budget");
                    assertThat(tokenBudget).isNotNull();
                    assertThat(tokenBudget.value()).isEqualTo("128000");
                    assertThat(tokenBudget.defaultValue()).isEqualTo("128000");
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
    @DisplayName("resetToDefaults() deletes agent config then re-inserts defaults")
    void resetToDefaults_deletesAndReinserts() {
        when(configRepo.deleteByAgentName(GLOBAL_AGENT)).thenReturn(Mono.just(1));
        when(configRepo.upsertForAgent(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(1));

        StepVerifier.create(service.resetToDefaults())
                .verifyComplete();

        verify(configRepo).deleteByAgentName(GLOBAL_AGENT);
        verify(configRepo, times(MemoryConfigService.getDefaults().size()))
                .upsertForAgent(eq(GLOBAL_AGENT), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("getDefaults() returns all 29 config keys")
    void getDefaults_returnsExpectedSize() {
        assertThat(MemoryConfigService.getDefaults()).hasSize(29);
        assertThat(MemoryConfigService.getDefaults()).containsKey("working.token_budget");
        assertThat(MemoryConfigService.getDefaults()).containsKey("long_term.enabled");
        assertThat(MemoryConfigService.getDefaults()).containsKey("vector.enabled");
        assertThat(MemoryConfigService.getDefaults()).containsKey("retrieval.strategy");
    }

    private static MemoryConfigEntity entity(String key, String value) {
        MemoryConfigEntity e = new MemoryConfigEntity();
        e.setAgentName(GLOBAL_AGENT);
        e.setConfigKey(key);
        e.setConfigValue(value);
        return e;
    }
}
