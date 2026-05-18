package com.atm.javaclaw.agent.runtime;

import com.atm.javaclaw.core.config.JavaClawProperties;
import reactor.core.publisher.Mono;

/**
 * SPI for resolving worker agent configuration during delegation.
 * Implemented by javaclaw-gateway (AgentConfigService-based impl).
 */
public interface DelegationResolver {

    /**
     * Resolve a worker agent's full run configuration.
     *
     * @param agentName    the worker agent name to resolve
     * @return resolved config, or empty if agent not found
     */
    Mono<ResolvedWorkerConfig> resolveWorker(String agentName);

    /**
     * Create a temporary session for a delegated worker agent.
     *
     * @param parentSessionId  the parent (supervisor) session ID
     * @param workerAgentName  the worker agent name
     * @param delegationId     unique delegation identifier
     * @return the new session ID
     */
    Mono<Long> createWorkerSession(Long parentSessionId, String workerAgentName, String delegationId);

    record ResolvedWorkerConfig(
            JavaClawProperties.Agent agent,
            String toolsEnabled,
            String mcpToolsEnabled,
            String skillsEnabled,
            String skillGroupsEnabled,
            boolean canDelegate,
            String delegateAgents
    ) {}
}
