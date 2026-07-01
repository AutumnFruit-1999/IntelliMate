package com.atm.intellimate.memory.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * A long-term memory entry (episodic / semantic / procedural).
 */
public class MemoryEntry {

    private Long id;
    private String userId;
    private String agentId;
    private String memoryType;
    private String content;
    private float importance;
    private int accessCount;
    private Instant lastAccessedAt;
    private Instant createdAt;
    private Long sourceSessionId;
    private String metadataJson;
    private String enrichedContent;
    private String topic;
    private String keywords;
    private String memoryLevel = "detail";
    private String sourceMemoryIds;

    public MemoryEntry() {}

    public MemoryEntry(String userId, String agentId, String memoryType, String content,
                       float importance, Long sourceSessionId) {
        this.userId = userId;
        this.agentId = agentId;
        this.memoryType = memoryType;
        this.content = content;
        this.importance = importance;
        this.accessCount = 0;
        this.createdAt = Instant.now();
        this.sourceSessionId = sourceSessionId;
    }

    public static MemoryEntry v3(String userId, String agentId, String memoryType, String content,
                               float importance, Long sourceSessionId,
                               String enrichedContent, String topic, String keywords,
                               String memoryLevel, String sourceMemoryIds) {
        MemoryEntry entry = new MemoryEntry(userId, agentId, memoryType, content, importance, sourceSessionId);
        entry.setEnrichedContent(enrichedContent);
        entry.setTopic(topic);
        entry.setKeywords(keywords);
        entry.setMemoryLevel(memoryLevel != null ? memoryLevel : "detail");
        entry.setSourceMemoryIds(sourceMemoryIds);
        return entry;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public String getMemoryType() { return memoryType; }
    public void setMemoryType(String memoryType) { this.memoryType = memoryType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public float getImportance() { return importance; }
    public void setImportance(float importance) { this.importance = importance; }
    public int getAccessCount() { return accessCount; }
    public void setAccessCount(int accessCount) { this.accessCount = accessCount; }
    public Instant getLastAccessedAt() { return lastAccessedAt; }
    public void setLastAccessedAt(Instant lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Long getSourceSessionId() { return sourceSessionId; }
    public void setSourceSessionId(Long sourceSessionId) { this.sourceSessionId = sourceSessionId; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public String getEnrichedContent() { return enrichedContent; }
    public void setEnrichedContent(String enrichedContent) { this.enrichedContent = enrichedContent; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }
    public String getMemoryLevel() { return memoryLevel; }
    public void setMemoryLevel(String memoryLevel) { this.memoryLevel = memoryLevel; }
    public String getSourceMemoryIds() { return sourceMemoryIds; }
    public void setSourceMemoryIds(String sourceMemoryIds) { this.sourceMemoryIds = sourceMemoryIds; }

    public MemoryChunk toRecalledChunk(int estimatedTokens) {
        return MemoryChunk.recalled(content, estimatedTokens, importance, id, recalledMetadata());
    }

    public MemoryChunk toRecalledChunk(int estimatedTokens, double relevanceScore) {
        String typeLabel = switch (memoryType) {
            case "semantic" -> "知识";
            case "episodic" -> "事件";
            case "procedural" -> "流程";
            default -> memoryType;
        };
        String dateStr = createdAt != null
                ? createdAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
                : "未知";
        String relevanceStr = relevanceScore < 0 ? "N/A" : String.format("%.2f", relevanceScore);
        String prefix = String.format("[历史记忆 | %s | %s | 相关度:%s] ",
                typeLabel, dateStr, relevanceStr);
        return MemoryChunk.recalled(prefix + content, estimatedTokens, importance, id, recalledMetadata());
    }

    private Map<String, String> recalledMetadata() {
        Map<String, String> meta = new HashMap<>();
        if (memoryLevel != null) {
            meta.put("memory_level", memoryLevel);
        }
        return meta;
    }
}
