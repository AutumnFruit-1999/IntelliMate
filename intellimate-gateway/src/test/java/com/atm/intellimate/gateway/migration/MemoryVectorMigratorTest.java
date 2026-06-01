package com.atm.intellimate.gateway.migration;

import com.atm.intellimate.gateway.entity.AgentMemoryEntity;
import com.atm.intellimate.gateway.repository.AgentMemoryRepository;
import com.atm.intellimate.memory.model.MemoryEntry;
import com.atm.intellimate.memory.retrieval.VectorMemoryStore;
import com.atm.intellimate.memory.retrieval.VectorSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemoryVectorMigrator")
class MemoryVectorMigratorTest {

    @Mock
    private VectorMemoryStore vectorStore;

    @Mock
    private AgentMemoryRepository memoryRepository;

    private MemoryVectorMigrator migrator;

    @BeforeEach
    void setUp() {
        migrator = new MemoryVectorMigrator(vectorStore, memoryRepository);
    }

    @Test
    @DisplayName("skips migration when vector store is unavailable")
    void migrate_skipsWhenVectorStoreUnavailable() {
        when(vectorStore.isAvailable()).thenReturn(Mono.just(false));

        StepVerifier.create(migrator.migrate())
                .assertNext(stats -> {
                    assertThat(stats.migrated()).isZero();
                    assertThat(stats.skipped()).isZero();
                    assertThat(stats.failed()).isZero();
                    assertThat(stats.total()).isZero();
                })
                .verifyComplete();

        verify(memoryRepository, never()).findAll();
        verify(vectorStore, never()).store(any());
    }

    @Test
    @DisplayName("stores memories not yet present in vector store")
    void migrate_storesMissingMemories() {
        AgentMemoryEntity entity = memoryEntity(1L, "user prefers dark mode");

        when(vectorStore.isAvailable()).thenReturn(Mono.just(true));
        when(memoryRepository.findAll()).thenReturn(Flux.just(entity));
        when(vectorStore.search(anyString(), eq("user1"), eq("agent1"), eq(10)))
                .thenReturn(Mono.just(List.of()));
        when(vectorStore.store(any(MemoryEntry.class))).thenReturn(Mono.empty());

        StepVerifier.create(migrator.migrate())
                .assertNext(stats -> {
                    assertThat(stats.migrated()).isEqualTo(1);
                    assertThat(stats.skipped()).isZero();
                    assertThat(stats.failed()).isZero();
                    assertThat(stats.total()).isEqualTo(1);
                })
                .verifyComplete();

        ArgumentCaptor<MemoryEntry> captor = ArgumentCaptor.forClass(MemoryEntry.class);
        verify(vectorStore).store(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo(1L);
        assertThat(captor.getValue().getContent()).isEqualTo("user prefers dark mode");
    }

    @Test
    @DisplayName("skips memories already indexed by mysql_id")
    void migrate_skipsExistingMemories() {
        AgentMemoryEntity entity = memoryEntity(2L, "already indexed");

        when(vectorStore.isAvailable()).thenReturn(Mono.just(true));
        when(memoryRepository.findAll()).thenReturn(Flux.just(entity));
        when(vectorStore.search(eq("already indexed"), eq("user1"), eq("agent1"), eq(10)))
                .thenReturn(Mono.just(List.of(
                        new VectorSearchResult(2L, "already indexed", "semantic", 0.8f, 0.99, Instant.now())
                )));

        StepVerifier.create(migrator.migrate())
                .assertNext(stats -> {
                    assertThat(stats.migrated()).isZero();
                    assertThat(stats.skipped()).isEqualTo(1);
                    assertThat(stats.failed()).isZero();
                    assertThat(stats.total()).isEqualTo(1);
                })
                .verifyComplete();

        verify(vectorStore, never()).store(any());
    }

    @Test
    @DisplayName("counts failures without aborting remaining migrations")
    void migrate_continuesAfterSingleFailure() {
        AgentMemoryEntity ok = memoryEntity(3L, "ok memory");
        AgentMemoryEntity bad = memoryEntity(4L, "bad memory");

        when(vectorStore.isAvailable()).thenReturn(Mono.just(true));
        when(memoryRepository.findAll()).thenReturn(Flux.just(ok, bad));
        when(vectorStore.search(anyString(), eq("user1"), eq("agent1"), eq(10)))
                .thenReturn(Mono.just(List.of()));
        when(vectorStore.store(any(MemoryEntry.class))).thenAnswer(invocation -> {
            MemoryEntry entry = invocation.getArgument(0);
            if (entry.getId() == 4L) {
                return Mono.error(new RuntimeException("embedding failed"));
            }
            return Mono.empty();
        });

        StepVerifier.create(migrator.migrate())
                .assertNext(stats -> {
                    assertThat(stats.migrated()).isEqualTo(1);
                    assertThat(stats.skipped()).isZero();
                    assertThat(stats.failed()).isEqualTo(1);
                    assertThat(stats.total()).isEqualTo(2);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("returns zero stats when no memories exist")
    void migrate_handlesEmptyRepository() {
        when(vectorStore.isAvailable()).thenReturn(Mono.just(true));
        when(memoryRepository.findAll()).thenReturn(Flux.empty());

        StepVerifier.create(migrator.migrate())
                .assertNext(stats -> {
                    assertThat(stats.migrated()).isZero();
                    assertThat(stats.skipped()).isZero();
                    assertThat(stats.failed()).isZero();
                    assertThat(stats.total()).isZero();
                })
                .verifyComplete();

        verify(vectorStore, never()).store(any());
    }

    private static AgentMemoryEntity memoryEntity(long id, String content) {
        AgentMemoryEntity entity = new AgentMemoryEntity();
        entity.setId(id);
        entity.setUserId("user1");
        entity.setAgentId("agent1");
        entity.setMemoryType("semantic");
        entity.setContent(content);
        entity.setImportance(0.8f);
        entity.setAccessCount(0);
        entity.setCreatedAt(LocalDateTime.now());
        return entity;
    }
}
