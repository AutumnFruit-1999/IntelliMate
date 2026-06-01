package com.atm.intellimate.gateway.service;

import com.atm.intellimate.memory.model.MemoryEntry;
import com.atm.intellimate.memory.retrieval.VectorMemoryStore;
import com.atm.intellimate.memory.retrieval.VectorSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class QdrantVectorStoreImpl implements VectorMemoryStore {
    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStoreImpl.class);

    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;

    public QdrantVectorStoreImpl(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public Mono<Void> store(MemoryEntry entry) {
        return Mono.fromRunnable(() -> {
            String docId = "mysql_" + entry.getId();
            Map<String, Object> metadata = Map.of(
                    "mysql_id", entry.getId(),
                    "user_id", entry.getUserId(),
                    "agent_id", entry.getAgentId(),
                    "memory_type", entry.getMemoryType(),
                    "importance", entry.getImportance(),
                    "created_at", entry.getCreatedAt() != null ? entry.getCreatedAt().toString() : ""
            );
            Document doc = new Document(docId, entry.getContent(), metadata);
            vectorStore.add(List.of(doc));
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<List<VectorSearchResult>> search(String query, String userId, String agentId, int topK) {
        return Mono.fromCallable(() -> {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            Filter.Expression filter = b.and(
                    b.eq("user_id", userId),
                    b.eq("agent_id", agentId)
            ).build();

            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .filterExpression(filter)
                    .build();

            List<Document> docs = vectorStore.similaritySearch(request);
            return docs.stream()
                    .map(doc -> {
                        Map<String, Object> meta = doc.getMetadata();
                        Long mysqlId = meta.containsKey("mysql_id")
                                ? ((Number) meta.get("mysql_id")).longValue()
                                : null;
                        String content = doc.getText();
                        String memoryType = (String) meta.getOrDefault("memory_type", "semantic");
                        float importance = meta.containsKey("importance")
                                ? ((Number) meta.get("importance")).floatValue()
                                : 0.5f;
                        double similarity = doc.getScore() != null ? doc.getScore() : 0.0;
                        String createdAtStr = (String) meta.getOrDefault("created_at", "");
                        Instant createdAt = createdAtStr.isEmpty() ? null : Instant.parse(createdAtStr);
                        return new VectorSearchResult(mysqlId, content, memoryType, importance, similarity, createdAt);
                    })
                    .toList();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> deleteById(Long mysqlId) {
        return Mono.fromRunnable(() -> {
            String docId = "mysql_" + mysqlId;
            vectorStore.delete(List.of(docId));
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Boolean> isAvailable() {
        return Mono.fromCallable(() -> {
                    try {
                        SearchRequest request = SearchRequest.builder()
                                .query("health check")
                                .topK(1)
                                .build();
                        vectorStore.similaritySearch(request);
                        return true;
                    } catch (Exception e) {
                        log.debug("Vector store not available: {}", e.getMessage());
                        return false;
                    }
                })
                .timeout(Duration.ofSeconds(2))
                .onErrorReturn(false)
                .subscribeOn(Schedulers.boundedElastic());
    }
}
