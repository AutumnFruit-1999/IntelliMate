package com.atm.intellimate.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("bridge_node")
public class BridgeNodeEntity {

    @Id
    private Long id;
    private String name;
    private String tokenHash;
    private String status;
    private String registeredTools;
    private LocalDateTime lastConnectedAt;
    private LocalDateTime lastHeartbeatAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRegisteredTools() { return registeredTools; }
    public void setRegisteredTools(String registeredTools) { this.registeredTools = registeredTools; }
    public LocalDateTime getLastConnectedAt() { return lastConnectedAt; }
    public void setLastConnectedAt(LocalDateTime lastConnectedAt) { this.lastConnectedAt = lastConnectedAt; }
    public LocalDateTime getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(LocalDateTime lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
