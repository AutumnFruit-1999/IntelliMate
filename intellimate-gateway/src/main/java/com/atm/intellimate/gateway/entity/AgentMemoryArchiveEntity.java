package com.atm.intellimate.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("agent_memory_archive")
public class AgentMemoryArchiveEntity {

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

    @Column("archived_at")
    private LocalDateTime archivedAt;

    public static AgentMemoryArchiveEntity from(AgentMemoryEntity src) {
        AgentMemoryArchiveEntity e = new AgentMemoryArchiveEntity();
        e.setId(src.getId());
        e.setUserId(src.getUserId());
        e.setAgentId(src.getAgentId());
        e.setMemoryType(src.getMemoryType());
        e.setContent(src.getContent());
        e.setImportance(src.getImportance());
        e.setAccessCount(src.getAccessCount());
        e.setLastAccessedAt(src.getLastAccessedAt());
        e.setCreatedAt(src.getCreatedAt());
        e.setSourceSessionId(src.getSourceSessionId());
        e.setMetadataJson(src.getMetadataJson());
        e.setArchivedAt(LocalDateTime.now());
        return e;
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
    public LocalDateTime getArchivedAt() { return archivedAt; }
    public void setArchivedAt(LocalDateTime archivedAt) { this.archivedAt = archivedAt; }
}
