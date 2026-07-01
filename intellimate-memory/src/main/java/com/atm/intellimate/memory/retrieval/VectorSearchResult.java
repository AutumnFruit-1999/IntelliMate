package com.atm.intellimate.memory.retrieval;

import com.atm.intellimate.memory.model.MemoryChunk;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

public record VectorSearchResult(
        Long mysqlId,
        String content,
        String memoryType,
        float importance,
        double similarity,
        Instant createdAt,
        String topic,
        String memoryLevel
) {
    public VectorSearchResult(Long mysqlId, String content, String memoryType,
                              float importance, double similarity, Instant createdAt) {
        this(mysqlId, content, memoryType, importance, similarity, createdAt, null, null);
    }

    public MemoryChunk toRecalledChunk(int estimatedTokens) {
        String typeLabel = switch (memoryType) {
            case "semantic" -> "知识";
            case "episodic" -> "事件";
            case "procedural" -> "流程";
            default -> memoryType;
        };
        String dateStr = createdAt != null
                ? createdAt.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                : "未知";
        String prefix = String.format("[历史记忆 | %s | %s | 相关度:%.2f] ",
                typeLabel, dateStr, similarity);
        Map<String, String> metadata = new HashMap<>();
        if (memoryLevel != null) {
            metadata.put("memory_level", memoryLevel);
        }
        return MemoryChunk.recalled(prefix + content, estimatedTokens, importance, mysqlId, metadata);
    }
}
