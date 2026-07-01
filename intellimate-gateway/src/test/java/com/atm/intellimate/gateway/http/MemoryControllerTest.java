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

    @Mock
    private com.atm.intellimate.gateway.repository.SessionRepository sessionRepository;

    @Mock
    private com.atm.intellimate.gateway.repository.TranscriptMessageRepository transcriptRepository;

    @BeforeEach
    void setUp() {
        MemoryController controller = new MemoryController(
                configService, longTermMemory, agentMemoryRepository, agentMemoryArchiveRepository,
                sessionRepository, transcriptRepository, null);
        client = WebTestClient.bindToController(controller)
                .controllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/memory/config returns grouped config")
    void getConfig_returnsGroupedConfig() {
        when(configService.resolveGroupedForAgent("_global_")).thenReturn(Mono.just(Map.of(
                "working.token_budget", new MemoryConfigService.ConfigItem("128000", "Token budget", "number"),
                "vector.enabled", new MemoryConfigService.ConfigItem("true", "Enable vector DB", "boolean"),
                "retrieval.strategy", new MemoryConfigService.ConfigItem("hybrid", "Retrieval strategy", "string")
        )));

        client.get().uri("/api/memory/config")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.working.token_budget.value").isEqualTo("128000")
                .jsonPath("$.data.working.token_budget.type").isEqualTo("number")
                .jsonPath("$.data.vector.enabled.value").isEqualTo("true")
                .jsonPath("$.data.retrieval.strategy.value").isEqualTo("hybrid");
    }

    @Test
    @DisplayName("PUT /api/memory/config updates and returns success")
    void updateConfig_returnsSuccess() {
        when(configService.updateConfigForAgent(anyString(), anyMap())).thenReturn(Mono.empty());

        client.put().uri("/api/memory/config")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("working.token_budget", "64000"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.success").isEqualTo("true");

        verify(configService).updateConfigForAgent(eq("_global_"), anyMap());
    }

    @Test
    @DisplayName("DELETE /api/memory/config deletes agent config")
    void deleteConfig_returnsSuccess() {
        when(configService.deleteConfigForAgent("GroupChat")).thenReturn(Mono.empty());

        client.delete().uri("/api/memory/config?agentName=GroupChat")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.success").isEqualTo("true");

        verify(configService).deleteConfigForAgent("GroupChat");
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
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data[0].id").isEqualTo(1)
                .jsonPath("$.data[0].content").isEqualTo("User prefers dark mode")
                .jsonPath("$.data[0].memoryType").isEqualTo("episodic")
                .jsonPath("$.data[0].agentId").isEqualTo("default")
                .jsonPath("$.data[0].importance").isEqualTo(0.8);
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
                .jsonPath("$.data[0].agentId").isEqualTo("alpha")
                .jsonPath("$.data[0].content").isEqualTo("Agent-specific");

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
                .jsonPath("$.data[0].memoryType").isEqualTo("semantic");
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
                .jsonPath("$.success").isEqualTo(true)
                .jsonPath("$.data.success").isEqualTo("true");

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
                .jsonPath("$.data.episodicCount").isEqualTo(5)
                .jsonPath("$.data.semanticCount").isEqualTo(10)
                .jsonPath("$.data.proceduralCount").isEqualTo(3)
                .jsonPath("$.data.totalCount").isEqualTo(18);

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
                .jsonPath("$.data.totalCount").isEqualTo(1);

        verify(longTermMemory).getStats("default", "beta");
    }

    @Test
    @DisplayName("GET /api/memory/long-term returns v3 fields (enrichedContent, keywords, topic, memoryLevel, sourceMemoryIds)")
    void getLongTermMemories_returnsV3Fields() {
        AgentMemoryEntity entity = memoryEntity(5L, "default", "semantic",
                "用户助手名称设定为张三", 0.9f, 1);
        entity.setEnrichedContent("用户在对话开始时明确指示助手名称为'张三'，助手确认接受该设定。");
        entity.setKeywords("张三 助手名称 设定");
        entity.setTopic("助手身份设定");
        entity.setMemoryLevel("detail");
        entity.setSourceMemoryIds("[10,20]");

        when(agentMemoryRepository.findByUserIdAndAgentId("default", "default"))
                .thenReturn(Flux.just(entity));

        client.get().uri("/api/memory/long-term?userId=default")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].enrichedContent").isEqualTo("用户在对话开始时明确指示助手名称为'张三'，助手确认接受该设定。")
                .jsonPath("$.data[0].keywords").isEqualTo("张三 助手名称 设定")
                .jsonPath("$.data[0].topic").isEqualTo("助手身份设定")
                .jsonPath("$.data[0].memoryLevel").isEqualTo("detail")
                .jsonPath("$.data[0].sourceMemoryIds[0]").isEqualTo(10)
                .jsonPath("$.data[0].sourceMemoryIds[1]").isEqualTo(20);
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
                .jsonPath("$.data.id").isEqualTo(42)
                .jsonPath("$.data.content").isEqualTo("detail");
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
                .jsonPath("$.data[0].id").isEqualTo(10)
                .jsonPath("$.data[0].archivedAt").exists()
                .jsonPath("$.data[0].content").isEqualTo("cold memory");

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
                .jsonPath("$.data.tokenBudget").isEqualTo(128000)
                .jsonPath("$.data.tokenUsed").isEqualTo(5000)
                .jsonPath("$.data.tokenEstimated").isEqualTo(4800)
                .jsonPath("$.data.usageRatio").isEqualTo(0.04)
                .jsonPath("$.data.chunkCount").isEqualTo(1)
                .jsonPath("$.data.chunks[0].id").isEqualTo("c1")
                .jsonPath("$.data.chunks[0].type").isEqualTo("USER")
                .jsonPath("$.data.chunks[0].importance").isEqualTo(0.5)
                .jsonPath("$.data.chunks[0].tokens").isEqualTo(100);
    }

    @Test
    @DisplayName("GET /api/memory/working/{sessionId} returns message when no snapshot")
    void getWorkingMemory_noSnapshot() {
        client.get().uri("/api/memory/working/99999")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.message").isEqualTo("No active working memory for session");
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
