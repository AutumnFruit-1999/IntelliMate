package com.atm.intellimate.gateway.service;

import com.atm.intellimate.memory.model.MemoryEntry;
import com.atm.intellimate.memory.retrieval.VectorSearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("QdrantVectorStoreImpl")
class QdrantVectorStoreImplTest {

    @Mock
    private VectorStore vectorStore;

    private QdrantVectorStoreImpl qdrantStore;

    @BeforeEach
    void setUp() {
        qdrantStore = new QdrantVectorStoreImpl(vectorStore);
    }

    @Test
    @DisplayName("store converts MemoryEntry to Document and adds to VectorStore")
    void store_convertsEntryToDocument() {
        MemoryEntry entry = buildEntry(1L, "user1", "agent1", "semantic", "用户喜欢深色模式", 0.8f);

        StepVerifier.create(qdrantStore.store(entry))
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(captor.capture());

        List<Document> docs = captor.getValue();
        assertThat(docs).hasSize(1);
        Document doc = docs.get(0);
        assertThat(doc.getText()).isEqualTo("用户喜欢深色模式");
        assertThat(doc.getMetadata()).containsEntry("user_id", "user1");
        assertThat(doc.getMetadata()).containsEntry("agent_id", "agent1");
        assertThat(doc.getMetadata()).containsEntry("memory_type", "semantic");
        assertThat(((Number) doc.getMetadata().get("importance")).floatValue()).isEqualTo(0.8f);
    }

    @Test
    @DisplayName("search returns VectorSearchResult list from VectorStore results")
    void search_returnsResults() {
        Instant now = Instant.now();
        Document doc = Document.builder()
                .id("doc-id")
                .text("记忆内容")
                .metadata(Map.of(
                        "mysql_id", 42,
                        "user_id", "user1",
                        "agent_id", "agent1",
                        "memory_type", "episodic",
                        "importance", 0.7,
                        "created_at", now.toString()
                ))
                .score(0.85)
                .build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc));

        StepVerifier.create(qdrantStore.search("查询", "user1", "agent1", 10, 0.35f))
                .assertNext(results -> {
                    assertThat(results).hasSize(1);
                    VectorSearchResult r = results.get(0);
                    assertThat(r.mysqlId()).isEqualTo(42L);
                    assertThat(r.content()).isEqualTo("记忆内容");
                    assertThat(r.memoryType()).isEqualTo("episodic");
                    assertThat(r.importance()).isEqualTo(0.7f);
                    assertThat(r.similarity()).isEqualTo(0.85);
                    assertThat(r.createdAt()).isEqualTo(now);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("search returns empty list when no results")
    void search_returnsEmptyWhenNoResults() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());

        StepVerifier.create(qdrantStore.search("无关查询", "user1", "agent1", 10, 0.35f))
                .assertNext(results -> assertThat(results).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("search handles missing metadata fields gracefully")
    void search_handlesMissingMetadata() {
        Document doc = Document.builder()
                .id("doc-id")
                .text("内容")
                .metadata(Map.of())
                .score(0.5)
                .build();

        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc));

        StepVerifier.create(qdrantStore.search("查询", "user1", "agent1", 5, 0.35f))
                .assertNext(results -> {
                    assertThat(results).hasSize(1);
                    VectorSearchResult r = results.get(0);
                    assertThat(r.mysqlId()).isNull();
                    assertThat(r.memoryType()).isEqualTo("semantic");
                    assertThat(r.importance()).isEqualTo(0.5f);
                    assertThat(r.createdAt()).isNull();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("deleteById removes document by deterministic UUID")
    void deleteById_removesDocument() {
        StepVerifier.create(qdrantStore.deleteById(99L))
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).delete(captor.capture());
        assertThat(captor.getValue()).hasSize(1);
        assertThat(captor.getValue().get(0)).isNotBlank();
    }

    @Test
    @DisplayName("deleteById generates same UUID for same mysqlId")
    void deleteById_deterministicUUID() {
        StepVerifier.create(qdrantStore.deleteById(1L)).verifyComplete();
        StepVerifier.create(qdrantStore.deleteById(1L)).verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(2)).delete(captor.capture());

        String uuid1 = captor.getAllValues().get(0).get(0);
        String uuid2 = captor.getAllValues().get(1).get(0);
        assertThat(uuid1).isEqualTo(uuid2);
    }

    @Test
    @DisplayName("isAvailable returns true when health check succeeds")
    void isAvailable_returnsTrueOnSuccess() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of());

        StepVerifier.create(qdrantStore.isAvailable())
                .assertNext(available -> assertThat(available).isTrue())
                .verifyComplete();
    }

    @Test
    @DisplayName("isAvailable returns false on IllegalStateException")
    void isAvailable_returnsFalseOnIllegalState() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new IllegalStateException("not ready"));

        StepVerifier.create(qdrantStore.isAvailable())
                .assertNext(available -> assertThat(available).isFalse())
                .verifyComplete();
    }

    @Test
    @DisplayName("isAvailable returns false on generic exception")
    void isAvailable_returnsFalseOnGenericException() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenThrow(new RuntimeException("connection refused"));

        StepVerifier.create(qdrantStore.isAvailable())
                .assertNext(available -> assertThat(available).isFalse())
                .verifyComplete();
    }

    @Test
    @DisplayName("store and search use consistent UUID mapping")
    void storeAndSearch_consistentUUIDMapping() {
        MemoryEntry entry = buildEntry(7L, "user1", "agent1", "procedural", "流程记忆", 0.6f);

        StepVerifier.create(qdrantStore.store(entry))
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> addCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).add(addCaptor.capture());
        String storedDocId = addCaptor.getValue().get(0).getId();

        StepVerifier.create(qdrantStore.deleteById(7L))
                .verifyComplete();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> deleteCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore).delete(deleteCaptor.capture());
        String deletedDocId = deleteCaptor.getValue().get(0);

        assertThat(storedDocId).isEqualTo(deletedDocId);
    }

    private static MemoryEntry buildEntry(Long id, String userId, String agentId,
                                           String memoryType, String content, float importance) {
        MemoryEntry entry = new MemoryEntry();
        entry.setId(id);
        entry.setUserId(userId);
        entry.setAgentId(agentId);
        entry.setMemoryType(memoryType);
        entry.setContent(content);
        entry.setImportance(importance);
        entry.setCreatedAt(Instant.now());
        return entry;
    }
}
