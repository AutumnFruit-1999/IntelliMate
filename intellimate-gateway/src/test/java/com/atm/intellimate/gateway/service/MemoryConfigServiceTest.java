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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryConfigService")
class MemoryConfigServiceTest {

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
        when(configRepo.findAll()).thenReturn(Flux.fromIterable(
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
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("resolveGrouped() returns config items with metadata")
    void resolveGrouped_containsAllKeysWithMetadata() {
        when(configRepo.findAll()).thenReturn(Flux.fromIterable(
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
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("updateConfig() calls upsert for each entry")
    void updateConfig_upsertsEachEntry() {
        when(configRepo.upsert(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(1));

        StepVerifier.create(service.updateConfig(Map.of(
                        "working.token_budget", "64000",
                        "long_term.enabled", "true"
                )))
                .verifyComplete();

        verify(configRepo).upsert(eq("working.token_budget"), eq("64000"), anyString());
        verify(configRepo).upsert(eq("long_term.enabled"), eq("true"), anyString());
    }

    @Test
    @DisplayName("resetToDefaults() deletes all then re-inserts defaults")
    void resetToDefaults_deletesAndReinserts() {
        when(configRepo.deleteAll()).thenReturn(Mono.empty());
        when(configRepo.upsert(anyString(), anyString(), anyString()))
                .thenReturn(Mono.just(1));

        StepVerifier.create(service.resetToDefaults())
                .verifyComplete();

        verify(configRepo).deleteAll();
        verify(configRepo, times(MemoryConfigService.getDefaults().size()))
                .upsert(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("getDefaults() returns all 14 config keys")
    void getDefaults_returnsExpectedSize() {
        assertThat(MemoryConfigService.getDefaults()).hasSize(14);
        assertThat(MemoryConfigService.getDefaults()).containsKey("working.token_budget");
        assertThat(MemoryConfigService.getDefaults()).containsKey("long_term.enabled");
    }

    private static MemoryConfigEntity entity(String key, String value) {
        MemoryConfigEntity e = new MemoryConfigEntity();
        e.setConfigKey(key);
        e.setConfigValue(value);
        return e;
    }
}
