package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.entity.AgentMemoryArchiveEntity;
import com.atm.intellimate.gateway.entity.AgentMemoryEntity;
import com.atm.intellimate.gateway.repository.AgentMemoryArchiveRepository;
import com.atm.intellimate.gateway.repository.AgentMemoryRepository;
import com.atm.intellimate.gateway.service.MemoryConfigService;
import com.atm.intellimate.memory.longterm.LongTermMemory;
import com.atm.intellimate.agent.runtime.AgentEvent;
import com.atm.intellimate.agent.runtime.AgentRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryController")
class MemoryControllerTest {

    private WebTestClient client;

    @Mock
    private MemoryConfigService configService;

    @Mock
    private LongTermMemory longTermMemory;

    @Mock
    private AgentMemoryRepository agentMemoryRepository;

    @Mock
    private AgentMemoryArchiveRepository agentMemoryArchiveRepository;

    @BeforeEach
    void setUp() {
        MemoryController controller = new MemoryController(
                configService, longTermMemory, agentMemoryRepository, agentMemoryArchiveRepository);
        client = WebTestClient.bindToController(controller).build();
    }

    @Test
    @DisplayName("GET /api/memory/config returns grouped config")
    void getConfig_returnsGroupedConfig() {
        when(configService.resolveGrouped()).thenReturn(Mono.just(Map.of(
                "working.token_budget", new MemoryConfigService.ConfigItem("128000", "128000", "Token budget", "number")
        )));

        client.get().uri("/api/memory/config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.working.token_budget.value").isEqualTo("128000")
                .jsonPath("$.working.token_budget.type").isEqualTo("number");
    }

    @Test
    @DisplayName("PUT /api/memory/config updates and returns success")
    void updateConfig_returnsSuccess() {
        when(configService.updateConfig(anyMap())).thenReturn(Mono.empty());

        client.put().uri("/api/memory/config")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("working.token_budget", "64000"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo("true");

        verify(configService).updateConfig(anyMap());
    }

    @Test
    @DisplayName("POST /api/memory/config/reset resets to defaults")
    void resetConfig_returnsSuccess() {
        when(configService.resetToDefaults()).thenReturn(Mono.empty());

        client.post().uri("/api/memory/config/reset")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo("true");

        verify(configService).resetToDefaults();
    }

    @Test
    @DisplayName("GET /api/memory/long-term returns memory list")
    void getLongTermMemories_returnsList() {
        AgentMemoryEntity entity = memoryEntity(1L, "default", "episodic",
                "User prefers dark mode", 0.8f, 3);

        when(agentMemoryRepository.findByUserIdAndAgentId("default", "default"))
                .thenReturn(Flux.just(entity));

        client.get().uri("/api/memory/long-term?userId=default")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(1)
                .jsonPath("$[0].content").isEqualTo("User prefers dark mode")
                .jsonPath("$[0].memoryType").isEqualTo("episodic")
                .jsonPath("$[0].agentId").isEqualTo("default")
                .jsonPath("$[0].importance").isEqualTo(0.8);
    }

    @Test
    @DisplayName("GET /api/memory/long-term filters by agentId")
    void getLongTermMemories_withAgentId() {
        AgentMemoryEntity entity = memoryEntity(3L, "default", "episodic", "Agent-specific", 0.5f, 0);
        entity.setAgentId("alpha");

        when(agentMemoryRepository.findByUserIdAndAgentId("default", "alpha"))
                .thenReturn(Flux.just(entity));

        client.get().uri("/api/memory/long-term?userId=default&agentId=alpha")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].agentId").isEqualTo("alpha")
                .jsonPath("$[0].content").isEqualTo("Agent-specific");

        verify(agentMemoryRepository).findByUserIdAndAgentId("default", "alpha");
    }

    @Test
    @DisplayName("GET /api/memory/long-term with type filter")
    void getLongTermMemories_withTypeFilter() {
        AgentMemoryEntity entity = memoryEntity(2L, "default", "semantic",
                "Java records are immutable", 0.7f, 1);

        when(agentMemoryRepository.findByUserIdAndAgentIdAndMemoryType("default", "default", "semantic"))
                .thenReturn(Flux.just(entity));

        client.get().uri("/api/memory/long-term?userId=default&type=semantic")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].memoryType").isEqualTo("semantic");
    }

    @Test
    @DisplayName("DELETE /api/memory/long-term/{id} deletes existing memory via LongTermMemory")
    void deleteLongTermMemory_existingId_returnsSuccess() {
        when(agentMemoryRepository.existsById(1L)).thenReturn(Mono.just(true));
        when(longTermMemory.deleteById(1L)).thenReturn(Mono.empty());

        client.delete().uri("/api/memory/long-term/1")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo("true");

        verify(longTermMemory).deleteById(1L);
    }

    @Test
    @DisplayName("DELETE /api/memory/long-term/{id} returns 404 for missing")
    void deleteLongTermMemory_missingId_returns404() {
        when(agentMemoryRepository.existsById(999L)).thenReturn(Mono.just(false));

        client.delete().uri("/api/memory/long-term/999")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("GET /api/memory/stats returns memory statistics")
    void getStats_returnsStatistics() {
        when(longTermMemory.getStats("default", "default"))
                .thenReturn(Mono.just(new LongTermMemory.MemoryStats(5, 10, 3, 18)));

        client.get().uri("/api/memory/stats?userId=default")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.episodicCount").isEqualTo(5)
                .jsonPath("$.semanticCount").isEqualTo(10)
                .jsonPath("$.proceduralCount").isEqualTo(3)
                .jsonPath("$.totalCount").isEqualTo(18);

        verify(longTermMemory).getStats("default", "default");
    }

    @Test
    @DisplayName("GET /api/memory/stats passes agentId to LongTermMemory")
    void getStats_withAgentId() {
        when(longTermMemory.getStats("default", "beta"))
                .thenReturn(Mono.just(new LongTermMemory.MemoryStats(1, 0, 0, 1)));

        client.get().uri("/api/memory/stats?userId=default&agentId=beta")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.totalCount").isEqualTo(1);

        verify(longTermMemory).getStats("default", "beta");
    }

    @Test
    @DisplayName("GET /api/memory/long-term/{id} returns one memory")
    void getLongTermMemoryById_found() {
        AgentMemoryEntity entity = memoryEntity(42L, "default", "episodic", "detail", 0.5f, 0);
        when(agentMemoryRepository.findById(42L)).thenReturn(Mono.just(entity));

        client.get().uri("/api/memory/long-term/42")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(42)
                .jsonPath("$.content").isEqualTo("detail");
    }

    @Test
    @DisplayName("GET /api/memory/long-term/{id} returns 404 when missing")
    void getLongTermMemoryById_missing() {
        when(agentMemoryRepository.findById(999L)).thenReturn(Mono.empty());

        client.get().uri("/api/memory/long-term/999")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    @DisplayName("GET /api/memory/archive lists archived rows")
    void getArchive_returnsList() {
        AgentMemoryArchiveEntity archived = new AgentMemoryArchiveEntity();
        archived.setId(10L);
        archived.setUserId("default");
        archived.setAgentId("default");
        archived.setMemoryType("episodic");
        archived.setContent("cold memory");
        archived.setImportance(0.2f);
        archived.setAccessCount(0);
        archived.setCreatedAt(LocalDateTime.parse("2024-01-01T12:00:00"));
        archived.setArchivedAt(LocalDateTime.parse("2024-06-01T00:00:00"));

        when(agentMemoryArchiveRepository.findByUserIdAndAgentId("default", "default"))
                .thenReturn(Flux.just(archived));

        client.get().uri("/api/memory/archive?userId=default&agentId=default")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].id").isEqualTo(10)
                .jsonPath("$[0].archivedAt").exists()
                .jsonPath("$[0].content").isEqualTo("cold memory");

        verify(agentMemoryArchiveRepository).findByUserIdAndAgentId("default", "default");
    }

    @AfterEach
    void cleanupSnapshots() throws Exception {
        getSnapshotMap().remove(12345L);
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<Long, AgentEvent.MemorySnapshot> getSnapshotMap() throws Exception {
        Field f = AgentRuntime.class.getDeclaredField("latestSnapshots");
        f.setAccessible(true);
        return (ConcurrentMap<Long, AgentEvent.MemorySnapshot>) f.get(null);
    }

    @Test
    @DisplayName("GET /api/memory/working/{sessionId} returns snapshot when present")
    void getWorkingMemory_withSnapshot() throws Exception {
        var chunk = new AgentEvent.ChunkInfo("c1", "USER", "GENERAL", 0.5f, 100, "hello", "2026-01-01T00:00:00");
        var snap = new AgentEvent.MemorySnapshot(128000, 5000, 4800, 0.04f, 1, List.of(chunk));
        getSnapshotMap().put(12345L, snap);

        client.get().uri("/api/memory/working/12345")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.tokenBudget").isEqualTo(128000)
                .jsonPath("$.tokenUsed").isEqualTo(5000)
                .jsonPath("$.tokenEstimated").isEqualTo(4800)
                .jsonPath("$.usageRatio").isEqualTo(0.04)
                .jsonPath("$.chunkCount").isEqualTo(1)
                .jsonPath("$.chunks[0].id").isEqualTo("c1")
                .jsonPath("$.chunks[0].type").isEqualTo("USER")
                .jsonPath("$.chunks[0].importance").isEqualTo(0.5)
                .jsonPath("$.chunks[0].tokens").isEqualTo(100);
    }

    @Test
    @DisplayName("GET /api/memory/working/{sessionId} returns message when no snapshot")
    void getWorkingMemory_noSnapshot() {
        client.get().uri("/api/memory/working/99999")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.message").isEqualTo("No active working memory for session");
    }

    private static AgentMemoryEntity memoryEntity(Long id, String userId, String type,
                                                    String content, float importance, int accessCount) {
        AgentMemoryEntity e = new AgentMemoryEntity();
        e.setId(id);
        e.setUserId(userId);
        e.setAgentId("default");
        e.setMemoryType(type);
        e.setContent(content);
        e.setImportance(importance);
        e.setAccessCount(accessCount);
        e.setCreatedAt(LocalDateTime.now());
        e.setLastAccessedAt(LocalDateTime.now());
        return e;
    }
}
