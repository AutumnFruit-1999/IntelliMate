package com.atm.intellimate.gateway.config;

import com.atm.intellimate.core.config.IntelliMateProperties;

/**
 * Holds the resolved agent configuration plus tool filtering specs.
 */
public record ResolvedAgentConfig(
        IntelliMateProperties.Agent agent,
        String toolsEnabled,
        String mcpToolsEnabled,
        String skillsEnabled,
        String skillGroupsEnabled,
        boolean canDelegate,
        String delegateAgents,
        String goal
) {
    public ResolvedAgentConfig(IntelliMateProperties.Agent agent,
                               String toolsEnabled, String mcpToolsEnabled,
                               String skillsEnabled, String skillGroupsEnabled) {
        this(agent, toolsEnabled, mcpToolsEnabled, skillsEnabled, skillGroupsEnabled,
                false, null, null);
    }
}
