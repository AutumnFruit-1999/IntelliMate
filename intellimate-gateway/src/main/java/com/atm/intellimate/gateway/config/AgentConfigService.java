package com.atm.intellimate.gateway.config;

import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.gateway.entity.AgentEntity;
import com.atm.intellimate.gateway.repository.AgentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Resolves agent configuration from the database, falling back
 * to application.yml defaults if not found.
 */
@Service
public class AgentConfigService {

    private static final Logger log = LoggerFactory.getLogger(AgentConfigService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentRepository agentRepository;
    private final IntelliMateProperties properties;

    public AgentConfigService(AgentRepository agentRepository, IntelliMateProperties properties) {
        this.agentRepository = agentRepository;
        this.properties = properties;
    }

    /**
     * Resolve the full agent config (agent properties + tool spec) for the given name.
     * Checks DB first, falls back to yml defaults with "full" tool access.
     */
    public Mono<ResolvedAgentConfig> resolve(String agentName) {
        if (agentName == null || agentName.isBlank()) {
            return Mono.just(new ResolvedAgentConfig(properties.getAgent(), null, null, null, null));
        }

        return agentRepository.findByName(agentName)
                .flatMap(entity -> {
                    boolean canDelegate = entity.getCanDelegate() != null && entity.getCanDelegate() == 1;
                    IntelliMateProperties.Agent agentConfig = toAgentConfig(entity);

                    if (canDelegate && entity.getDelegateAgents() != null) {
                        return buildDelegateAgentInfo(entity.getDelegateAgents())
                                .map(delegateInfo -> {
                                    agentConfig.setCanDelegate(true);
                                    agentConfig.setDelegateAgents(entity.getDelegateAgents());
                                    String existingAgentsMd = agentConfig.getAgentsMd();
                                    String combined = (existingAgentsMd != null ? existingAgentsMd + "\n\n" : "") + delegateInfo;
                                    agentConfig.setAgentsMd(combined);
                                    return new ResolvedAgentConfig(
                                            agentConfig,
                                            entity.getToolsEnabled(),
                                            entity.getMcpToolsEnabled(),
                                            entity.getSkillsEnabled(),
                                            entity.getSkillGroupsEnabled(),
                                            true, entity.getDelegateAgents(), entity.getGoal(),
                                            entity.getBridgeNode());
                                });
                    }

                    return Mono.just(new ResolvedAgentConfig(
                            agentConfig,
                            entity.getToolsEnabled(),
                            entity.getMcpToolsEnabled(),
                            entity.getSkillsEnabled(),
                            entity.getSkillGroupsEnabled(),
                            canDelegate, entity.getDelegateAgents(), entity.getGoal(),
                            entity.getBridgeNode()));
                })
                .defaultIfEmpty(new ResolvedAgentConfig(properties.getAgent(), null, null, null, null))
                .doOnNext(cfg -> log.debug("Resolved agent config: name={}, model={}, tools={}, mcpTools={}, skills={}, skillGroups={}, canDelegate={}",
                        cfg.agent().getName(), cfg.agent().getModel(), cfg.toolsEnabled(), cfg.mcpToolsEnabled(),
                        cfg.skillsEnabled(), cfg.skillGroupsEnabled(), cfg.canDelegate()));
    }

    private Mono<String> buildDelegateAgentInfo(String delegateAgentsJson) {
        List<String> names;
        try {
            names = MAPPER.readValue(delegateAgentsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse delegateAgents JSON: {}", delegateAgentsJson);
            return Mono.just("");
        }
        if (names.isEmpty()) return Mono.just("");

        return agentRepository.findAllActive()
                .filter(a -> names.contains(a.getName()))
                .collectList()
                .map(agents -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("## Available Delegate Agents\n\n");
                    sb.append("You can delegate tasks to the following specialist agents:\n\n");
                    for (AgentEntity worker : agents) {
                        sb.append("- **").append(worker.getName()).append("**");
                        if (worker.getGoal() != null && !worker.getGoal().isBlank()) {
                            sb.append(": ").append(worker.getGoal());
                        }
                        sb.append('\n');
                    }
                    for (String name : names) {
                        if (agents.stream().noneMatch(a -> a.getName().equals(name))) {
                            sb.append("- **").append(name).append("** (not found)\n");
                        }
                    }
                    sb.append("\nUse `delegateAgent` to assign a sub-task to one agent.\n");
                    sb.append("Use `delegateAgentsParallel` to run multiple agents concurrently.\n");
                    sb.append("Use `handoffToAgent` to transfer full control to another agent.\n");
                    return sb.toString();
                });
    }

    private IntelliMateProperties.Agent toAgentConfig(AgentEntity entity) {
        IntelliMateProperties.Agent defaults = properties.getAgent();
        IntelliMateProperties.Agent config = new IntelliMateProperties.Agent();
        config.setName(entity.getName());
        config.setModel(entity.getModel() != null ? entity.getModel() : defaults.getModel());
        config.setMaxTurns(entity.getMaxTurns() != null ? entity.getMaxTurns() : defaults.getMaxTurns());
        config.setTimeoutSeconds(entity.getTimeoutSeconds() != null ? entity.getTimeoutSeconds() : defaults.getTimeoutSeconds());
        config.setSystemPrompt(entity.getSystemPrompt() != null ? entity.getSystemPrompt() : defaults.getSystemPrompt());
        config.setSoulMd(entity.getSoulMd() != null ? entity.getSoulMd() : defaults.getSoulMd());
        config.setUserMd(entity.getUserMd() != null ? entity.getUserMd() : defaults.getUserMd());
        config.setAgentsMd(entity.getAgentsMd() != null ? entity.getAgentsMd() : defaults.getAgentsMd());
        config.setGoal(entity.getGoal());
        return config;
    }
}
