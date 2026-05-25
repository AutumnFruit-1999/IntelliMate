package com.atm.intellimate.gateway.dto;

import com.atm.intellimate.gateway.entity.AgentEntity;

import java.time.LocalDateTime;

public record AgentDTO(
        Long id,
        String name,
        String model,
        String modelDisplayName,
        String soulMd,
        String agentsMd,
        Integer maxTurns,
        Integer timeoutSeconds,
        String toolsEnabled,
        String mcpToolsEnabled,
        String skillsEnabled,
        String skillGroupsEnabled,
        Boolean canDelegate,
        String delegateAgents,
        String goal,
        String bridgeNode,
        Boolean hasSoul,
        Boolean hasAgents,
        Boolean isDefault,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static AgentDTO fromEntity(AgentEntity entity) {
        return new AgentDTO(
                entity.getId(),
                entity.getName(),
                entity.getModel(),
                null,
                entity.getSoulMd(),
                entity.getAgentsMd(),
                entity.getMaxTurns(),
                entity.getTimeoutSeconds(),
                entity.getToolsEnabled(),
                entity.getMcpToolsEnabled(),
                entity.getSkillsEnabled(),
                entity.getSkillGroupsEnabled(),
                entity.getCanDelegate() != null && entity.getCanDelegate() == 1,
                entity.getDelegateAgents(),
                entity.getGoal(),
                entity.getBridgeNode(),
                entity.getSoulMd() != null && !entity.getSoulMd().isBlank(),
                entity.getAgentsMd() != null && !entity.getAgentsMd().isBlank(),
                false,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public static AgentDTO fromEntitySummary(AgentEntity entity, String modelDisplayName) {
        return new AgentDTO(
                entity.getId(),
                entity.getName(),
                entity.getModel(),
                modelDisplayName,
                null,
                null,
                entity.getMaxTurns(),
                entity.getTimeoutSeconds(),
                entity.getToolsEnabled(),
                null,
                null,
                null,
                entity.getCanDelegate() != null && entity.getCanDelegate() == 1,
                null,
                entity.getGoal(),
                null,
                entity.getSoulMd() != null && !entity.getSoulMd().isBlank(),
                entity.getAgentsMd() != null && !entity.getAgentsMd().isBlank(),
                false,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public AgentDTO withModelDisplayName(String modelDisplayName) {
        return new AgentDTO(
                id, name, model, modelDisplayName, soulMd, agentsMd, maxTurns, timeoutSeconds,
                toolsEnabled, mcpToolsEnabled, skillsEnabled, skillGroupsEnabled, canDelegate,
                delegateAgents, goal, bridgeNode, hasSoul, hasAgents, isDefault, createdAt, updatedAt
        );
    }

    public AgentDTO asDefault() {
        return new AgentDTO(
                id, name, model, modelDisplayName, soulMd, agentsMd, maxTurns, timeoutSeconds,
                toolsEnabled, mcpToolsEnabled, skillsEnabled, skillGroupsEnabled, canDelegate,
                delegateAgents, goal, bridgeNode, hasSoul, hasAgents, true, createdAt, updatedAt
        );
    }
}
