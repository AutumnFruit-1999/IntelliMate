package com.atm.intellimate.gateway.service;

import com.atm.intellimate.agent.runtime.DelegationResolver;
import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.core.model.SessionKey;
import com.atm.intellimate.core.model.SessionMetadata;
import com.atm.intellimate.gateway.config.AgentConfigService;
import com.atm.intellimate.gateway.config.ResolvedAgentConfig;
import com.atm.intellimate.gateway.session.SessionManager;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class DelegationResolverImpl implements DelegationResolver {

    private final AgentConfigService agentConfigService;
    private final SessionManager sessionManager;

    public DelegationResolverImpl(AgentConfigService agentConfigService,
                                  SessionManager sessionManager) {
        this.agentConfigService = agentConfigService;
        this.sessionManager = sessionManager;
    }

    @Override
    public Mono<ResolvedWorkerConfig> resolveWorker(String agentName) {
        return agentConfigService.resolve(agentName)
                .filter(cfg -> cfg.agent().getName() != null)
                .map(this::toWorkerConfig);
    }

    @Override
    public Mono<Long> createWorkerSession(Long parentSessionId, String workerAgentName, String delegationId) {
        String contextId = "delegation::" + parentSessionId + "::" + workerAgentName + "::" + delegationId;
        SessionKey key = new SessionKey("workflow", "delegation", contextId);
        SessionMetadata metadata = new SessionMetadata(workerAgentName, null, "workflow", "delegation", contextId);

        return sessionManager.getOrCreate(key, metadata)
                .map(session -> session.getId());
    }

    private ResolvedWorkerConfig toWorkerConfig(ResolvedAgentConfig cfg) {
        return new ResolvedWorkerConfig(
                cfg.agent(),
                cfg.toolsEnabled(),
                cfg.mcpToolsEnabled(),
                cfg.skillsEnabled(),
                cfg.skillGroupsEnabled(),
                cfg.canDelegate(),
                cfg.delegateAgents());
    }
}
