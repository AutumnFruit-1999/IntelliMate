package com.atm.intellimate.gateway.service;

import com.atm.intellimate.memory.model.MemoryEntry;
import com.atm.intellimate.memory.retrieval.VectorMemoryStore;
import com.atm.intellimate.memory.retrieval.VectorSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class QdrantVectorStoreImpl implements VectorMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStoreImpl.class);

    private final VectorStore vectorStore;

    public QdrantVectorStoreImpl(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    private static String toUUID(Long mysqlId) {
        return UUID.nameUUIDFromBytes(("mysql_" + mysqlId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Override
    public Mono<Void> store(MemoryEntry entry) {
        return Mono.fromRunnable(() -> {
            String docId = toUUID(entry.getId());
            String textToEmbed = entry.getEnrichedContent() != null && !entry.getEnrichedContent().isBlank()
                    ? entry.getEnrichedContent()
                    : entry.getContent();
            log.info("[向量存储] mysqlId={}, docId={}, user={}, agent={}, topic='{}', level={}, textLen={}",
                    entry.getId(), docId, entry.getUserId(), entry.getAgentId(),
                    entry.getTopic(), entry.getMemoryLevel(), textToEmbed.length());
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("mysql_id", entry.getId().intValue());
            metadata.put("user_id", entry.getUserId());
            metadata.put("agent_id", entry.getAgentId());
            metadata.put("memory_type", entry.getMemoryType());
            metadata.put("importance", (double) entry.getImportance());
            metadata.put("created_at", entry.getCreatedAt() != null ? entry.getCreatedAt().toString() : "");
            metadata.put("topic", entry.getTopic() != null ? entry.getTopic() : "");
            metadata.put("memory_level", entry.getMemoryLevel() != null ? entry.getMemoryLevel() : "detail");
            Document doc = new Document(docId, textToEmbed, metadata);
            vectorStore.add(List.of(doc));
            log.info("[向量存储] 写入成功, mysqlId={}", entry.getId());
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<List<VectorSearchResult>> search(String query, String userId, String agentId, int topK, float similarityThreshold) {
        log.info("[向量检索] query='{}', user={}, agent={}, topK={}, threshold={}",
                query, userId, agentId, topK, similarityThreshold);
        return Mono.fromCallable(() -> {
            FilterExpressionBuilder b = new FilterExpressionBuilder();
            Filter.Expression filter = b.and(
                    b.eq("user_id", userId),
                    b.eq("agent_id", agentId)
            ).build();

            SearchRequest request = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .similarityThreshold(similarityThreshold)
                    .filterExpression(filter)
                    .build();

            List<Document> docs = vectorStore.similaritySearch(request);
            log.info("[向量检索] Qdrant 返回 {} 条原始文档", docs.size());

            List<VectorSearchResult> results = docs.stream()
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
                        String topic = meta.getOrDefault("topic", "").toString();
                        String memoryLevel = meta.getOrDefault("memory_level", "detail").toString();
                        return new VectorSearchResult(mysqlId, content, memoryType, importance, similarity, createdAt, topic, memoryLevel);
                    })
                    .toList();

            results.forEach(r -> log.info("[向量检索]   >> mysqlId={}, similarity={}, topic='{}', level={}, content='{}'",
                    r.mysqlId(), String.format("%.4f", r.similarity()), r.topic(), r.memoryLevel(),
                    r.content().length() > 60 ? r.content().substring(0, 60) + "..." : r.content()));
            log.info("[向量检索] 最终返回 {} 条结果", results.size());

            return results;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> deleteById(Long mysqlId) {
        return Mono.fromRunnable(() -> {
            log.info("[向量删除] mysqlId={}, docId={}", mysqlId, toUUID(mysqlId));
            vectorStore.delete(List.of(toUUID(mysqlId)));
            log.info("[向量删除] 删除成功, mysqlId={}", mysqlId);
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
                    } catch (IllegalStateException e) {
                        return false;
                    } catch (Exception e) {
                        return false;
                    }
                })
                .timeout(Duration.ofSeconds(2))
                .onErrorReturn(false)
                .subscribeOn(Schedulers.boundedElastic());
    }
}
