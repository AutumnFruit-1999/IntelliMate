package com.atm.intellimate.memory.retrieval;

import com.atm.intellimate.memory.model.MemoryChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HybridMemoryRetrieval")
class HybridMemoryRetrievalTest {

    @Test
    @DisplayName("hybrid mode merges vector and keyword results")
    void retrieve_hybridMode_mergesVectorAndKeywordResults() {
        // Setup mocks
        VectorMemoryStore vectorStore = mock(VectorMemoryStore.class);
        MemoryRetrieval keywordRetrieval = mock(MemoryRetrieval.class);

        when(vectorStore.isAvailable()).thenReturn(Mono.just(true));
        when(vectorStore.search(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(Mono.just(List.of(
                        new VectorSearchResult(1L, "vector result 1", "semantic", 0.8f, 0.9, Instant.now()),
                        new VectorSearchResult(2L, "vector result 2", "episodic", 0.6f, 0.7, Instant.now())
                )));
        when(keywordRetrieval.retrieve(anyString(), anyString(), anyString(), anyInt(), anyDouble()))
                .thenReturn(Mono.just(List.of(
                        MemoryChunk.recalled("keyword result 1", 50, 0.7f),
                        MemoryChunk.recalled("keyword result 2", 30, 0.5f)
                )));

        HybridMemoryRetrieval hybrid = new HybridMemoryRetrieval(
                keywordRetrieval, vectorStore, 0.6f, 0.4f);

        StepVerifier.create(hybrid.retrieve("test query", "user1", "agent1",
                        2048, 0.1, HybridMemoryRetrieval.Strategy.HYBRID))
                .assertNext(chunks -> {
                    assertThat(chunks).isNotEmpty();
                    assertThat(chunks.size()).isLessThanOrEqualTo(4);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("falls back to keyword when vector unavailable")
    void retrieve_vectorUnavailable_fallsBackToKeyword() {
        VectorMemoryStore vectorStore = mock(VectorMemoryStore.class);
        MemoryRetrieval keywordRetrieval = mock(MemoryRetrieval.class);

        when(vectorStore.isAvailable()).thenReturn(Mono.just(false));
        when(keywordRetrieval.retrieve(anyString(), anyString(), anyString(), anyInt(), anyDouble()))
                .thenReturn(Mono.just(List.of(
                        MemoryChunk.recalled("keyword only", 30, 0.5f)
                )));

        HybridMemoryRetrieval hybrid = new HybridMemoryRetrieval(
                keywordRetrieval, vectorStore, 0.6f, 0.4f);

        StepVerifier.create(hybrid.retrieve("test query", "user1", "agent1",
                        2048, 0.1, HybridMemoryRetrieval.Strategy.HYBRID))
                .assertNext(chunks -> {
                    assertThat(chunks).hasSize(1);
                    assertThat(chunks.get(0).content()).isEqualTo("keyword only");
                })
                .verifyComplete();
    }
}
