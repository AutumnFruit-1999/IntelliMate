package com.atm.javaclaw.gateway.config;

import com.atm.javaclaw.core.config.JavaClawProperties;

/**
 * Holds the resolved agent configuration plus tool filtering spec.
 */
public record ResolvedAgentConfig(
        JavaClawProperties.Agent agent,
        String toolsEnabled
) {
}
