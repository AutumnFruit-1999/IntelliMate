package com.atm.intellimate.memory.retrieval;

import com.atm.intellimate.memory.model.MemoryChunk;
import java.time.Instant;
import java.time.ZoneId;

public record VectorSearchResult(
        Long mysqlId,
        String content,
        String memoryType,
        float importance,
        double similarity,
        Instant createdAt
) {
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
        return MemoryChunk.recalled(prefix + content, estimatedTokens, importance);
    }
}
