package com.atm.intellimate.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("agent_memory")
public class AgentMemoryEntity {

    @Id
    private Long id;

    @Column("user_id")
    private String userId;

    @Column("agent_id")
    private String agentId;

    @Column("memory_type")
    private String memoryType;

    private String content;
    private Float importance;

    @Column("access_count")
    private Integer accessCount;

    @Column("last_accessed_at")
    private LocalDateTime lastAccessedAt;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("source_session_id")
    private Long sourceSessionId;

    @Column("metadata_json")
    private String metadataJson;

    @Column("keywords")
    private String keywords;

    @Column("topic")
    private String topic;

    @Column("memory_level")
    private String memoryLevel;

    @Column("source_memory_ids")
    private String sourceMemoryIds;

    @Column("enriched_content")
    private String enrichedContent;

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
    public Float getImportance() { return importance; }
    public void setImportance(Float importance) { this.importance = importance; }
    public Integer getAccessCount() { return accessCount; }
    public void setAccessCount(Integer accessCount) { this.accessCount = accessCount; }
    public LocalDateTime getLastAccessedAt() { return lastAccessedAt; }
    public void setLastAccessedAt(LocalDateTime lastAccessedAt) { this.lastAccessedAt = lastAccessedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public Long getSourceSessionId() { return sourceSessionId; }
    public void setSourceSessionId(Long sourceSessionId) { this.sourceSessionId = sourceSessionId; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getMemoryLevel() { return memoryLevel; }
    public void setMemoryLevel(String memoryLevel) { this.memoryLevel = memoryLevel; }
    public String getSourceMemoryIds() { return sourceMemoryIds; }
    public void setSourceMemoryIds(String sourceMemoryIds) { this.sourceMemoryIds = sourceMemoryIds; }
    public String getEnrichedContent() { return enrichedContent; }
    public void setEnrichedContent(String enrichedContent) { this.enrichedContent = enrichedContent; }
}
