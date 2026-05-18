package com.atm.javaclaw.gateway.config;

import com.atm.javaclaw.core.config.JavaClawProperties;

/**
 * Holds the resolved agent configuration plus tool filtering specs.
 */
public record ResolvedAgentConfig(
        JavaClawProperties.Agent agent,
        String toolsEnabled,
        String mcpToolsEnabled,
        String skillsEnabled,
        String skillGroupsEnabled,
        boolean canDelegate,
        String delegateAgents,
        String goal
) {
    public ResolvedAgentConfig(JavaClawProperties.Agent agent,
                               String toolsEnabled, String mcpToolsEnabled,
                               String skillsEnabled, String skillGroupsEnabled) {
        this(agent, toolsEnabled, mcpToolsEnabled, skillsEnabled, skillGroupsEnabled,
                false, null, null);
    }
}
