package com.atm.intellimate.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("agent")
public class AgentEntity {

    @Id
    private Long id;

    private String name;
    private String model;
    private String systemPrompt;
    private String soulMd;
    private String userMd;
    private String agentsMd;
    private Integer maxTurns;
    private Integer timeoutSeconds;
    private String toolsEnabled;
    private String mcpToolsEnabled;
    private String skillsEnabled;
    private String skillGroupsEnabled;
    private Integer canDelegate;
    private String delegateAgents;
    private String goal;
    private String bridgeNode;
    private String configJson;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public String getSoulMd() { return soulMd; }
    public void setSoulMd(String soulMd) { this.soulMd = soulMd; }
    public String getUserMd() { return userMd; }
    public void setUserMd(String userMd) { this.userMd = userMd; }
    public String getAgentsMd() { return agentsMd; }
    public void setAgentsMd(String agentsMd) { this.agentsMd = agentsMd; }
    public Integer getMaxTurns() { return maxTurns; }
    public void setMaxTurns(Integer maxTurns) { this.maxTurns = maxTurns; }
    public Integer getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Integer timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public String getToolsEnabled() { return toolsEnabled; }
    public void setToolsEnabled(String toolsEnabled) { this.toolsEnabled = toolsEnabled; }
    public String getMcpToolsEnabled() { return mcpToolsEnabled; }
    public void setMcpToolsEnabled(String mcpToolsEnabled) { this.mcpToolsEnabled = mcpToolsEnabled; }
    public String getSkillsEnabled() { return skillsEnabled; }
    public void setSkillsEnabled(String skillsEnabled) { this.skillsEnabled = skillsEnabled; }
    public String getSkillGroupsEnabled() { return skillGroupsEnabled; }
    public void setSkillGroupsEnabled(String skillGroupsEnabled) { this.skillGroupsEnabled = skillGroupsEnabled; }
    public Integer getCanDelegate() { return canDelegate; }
    public void setCanDelegate(Integer canDelegate) { this.canDelegate = canDelegate; }
    public String getDelegateAgents() { return delegateAgents; }
    public void setDelegateAgents(String delegateAgents) { this.delegateAgents = delegateAgents; }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public String getBridgeNode() { return bridgeNode; }
    public void setBridgeNode(String bridgeNode) { this.bridgeNode = bridgeNode; }
    public String getConfigJson() { return configJson; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }
}
