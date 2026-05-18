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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * In-memory simulation of {@link MemoryConfigRepository} to show hot reload and reset
 * without a database.
 */
@ExtendWith(MockitoExtension.class)
class MemoryConfigHotReloadDemo {

    @Mock
    MemoryConfigRepository configRepo;

    private final Map<String, MemoryConfigEntity> backing = new HashMap<>();

    private MemoryConfigService service;

    @BeforeEach
    void setUp() {
        backing.clear();
        for (var e : MemoryConfigService.getDefaults().entrySet()) {
            MemoryConfigEntity row = new MemoryConfigEntity();
            row.setConfigKey(e.getKey());
            row.setConfigValue(e.getValue());
            backing.put(e.getKey(), row);
        }

        when(configRepo.findAll()).thenAnswer(inv -> Flux.fromIterable(new ArrayList<>(backing.values())));
        when(configRepo.upsert(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            String value = inv.getArgument(1);
            String description = inv.getArgument(2);
            MemoryConfigEntity row = backing.getOrDefault(key, new MemoryConfigEntity());
            row.setConfigKey(key);
            row.setConfigValue(value);
            row.setDescription(description);
            backing.put(key, row);
            return Mono.just(1);
        });
        lenient().when(configRepo.deleteAll()).thenAnswer(inv -> {
            backing.clear();
            return Mono.empty();
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

    @Test
    @DisplayName("resetToDefaults_restoresOriginalValues")
    void resetToDefaults_restoresOriginalValues() {
        service.updateConfig(Map.of("working.token_budget", "99999")).block();
        assertEquals(99_999, service.resolve().block().tokenBudget());

        service.resetToDefaults().block();

        ResolvedMemoryConfig restored = service.resolve().block();
        assertEquals(128_000, restored.tokenBudget());
    }
}
