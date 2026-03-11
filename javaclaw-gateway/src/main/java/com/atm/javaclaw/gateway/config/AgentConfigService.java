package com.atm.javaclaw.gateway.config;

import com.atm.javaclaw.core.config.JavaClawProperties;
import com.atm.javaclaw.gateway.entity.AgentEntity;
import com.atm.javaclaw.gateway.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Resolves agent configuration from the database, falling back
 * to application.yml defaults if not found.
 */
@Service
public class AgentConfigService {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigService.class);

    private final AgentRepository agentRepository;
    private final JavaClawProperties properties;

    public AgentConfigService(AgentRepository agentRepository, JavaClawProperties properties) {
        this.agentRepository = agentRepository;
        this.properties = properties;
    }

    /**
     * Resolve the full agent config (agent properties + tool spec) for the given name.
     * Checks DB first, falls back to yml defaults with "full" tool access.
     */
    public Mono<ResolvedAgentConfig> resolve(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return Mono.just(new ResolvedAgentConfig(properties.getAgent(), null));
        }

        return agentRepository.findByName(agentName)
                .map(entity -> new ResolvedAgentConfig(toAgentConfig(entity), entity.getToolsEnabled()))
                .defaultIfEmpty(new ResolvedAgentConfig(properties.getAgent(), null))
                .doOnNext(cfg -> log.debug("Resolved agent config: name={}, model={}, tools={}",
                        cfg.agent().getName(), cfg.agent().getModel(), cfg.toolsEnabled()));
    }

    private JavaClawProperties.Agent toAgentConfig(AgentEntity entity) {
        JavaClawProperties.Agent defaults = properties.getAgent();
        JavaClawProperties.Agent config = new JavaClawProperties.Agent();
        config.setName(entity.getName());
        config.setModel(entity.getModel() != null ? entity.getModel() : defaults.getModel());
        config.setMaxTurns(entity.getMaxTurns() != null ? entity.getMaxTurns() : defaults.getMaxTurns());
        config.setTimeoutSeconds(entity.getTimeoutSeconds() != null ? entity.getTimeoutSeconds() : defaults.getTimeoutSeconds());
        config.setSystemPrompt(entity.getSystemPrompt() != null ? entity.getSystemPrompt() : defaults.getSystemPrompt());
        config.setSoulMd(entity.getSoulMd() != null ? entity.getSoulMd() : defaults.getSoulMd());
        config.setUserMd(entity.getUserMd() != null ? entity.getUserMd() : defaults.getUserMd());
        config.setAgentsMd(entity.getAgentsMd() != null ? entity.getAgentsMd() : defaults.getAgentsMd());
        return config;
    }
}
