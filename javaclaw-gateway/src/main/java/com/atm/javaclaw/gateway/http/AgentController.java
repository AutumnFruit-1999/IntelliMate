package com.atm.javaclaw.gateway.http;

import com.atm.javaclaw.core.config.JavaClawProperties;
import com.atm.javaclaw.gateway.entity.AgentEntity;
import com.atm.javaclaw.gateway.repository.AgentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentRepository agentRepository;
    private final JavaClawProperties properties;

    public AgentController(AgentRepository agentRepository, JavaClawProperties properties) {
        this.agentRepository = agentRepository;
        this.properties = properties;
    }

    @GetMapping("/agents")
    public Mono<List<Map<String, Object>>> listAgents() {
        Mono<List<Map<String, Object>>> dbAgents = agentRepository.findAllActive()
                .map(this::entityToSummaryDto)
                .collectList();

        return dbAgents.map(list -> {
            JavaClawProperties.Agent defaults = properties.getAgent();
            boolean hasDefault = list.stream().anyMatch(a -> defaults.getName().equals(a.get("name")));
            if (!hasDefault) {
                Map<String, Object> defaultAgent = new LinkedHashMap<>();
                defaultAgent.put("name", defaults.getName());
                defaultAgent.put("model", defaults.getModel());
                defaultAgent.put("hasSoul", defaults.getSoulMd() != null && !defaults.getSoulMd().isBlank());
                defaultAgent.put("hasUser", defaults.getUserMd() != null && !defaults.getUserMd().isBlank());
                defaultAgent.put("hasAgents", defaults.getAgentsMd() != null && !defaults.getAgentsMd().isBlank());
                defaultAgent.put("isDefault", true);
                list.addFirst(defaultAgent);
            }
            return list;
        });
    }

    @GetMapping("/agent/{name}")
    public Mono<Map<String, Object>> getAgent(@PathVariable String name) {
        return agentRepository.findByName(name)
                .map(this::entityToDto)
                .defaultIfEmpty(defaultDto(name));
    }

    @PostMapping("/agent")
    public Mono<Map<String, Object>> createAgent(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String model = body.getOrDefault("model", properties.getAgent().getModel());

        if (name == null || name.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required"));
        }

        return agentRepository.findByName(name)
                .flatMap(existing -> Mono.<Map<String, Object>>error(
                        new ResponseStatusException(HttpStatus.CONFLICT, "Agent already exists: " + name)))
                .switchIfEmpty(Mono.defer(() -> {
                    AgentEntity entity = new AgentEntity();
                    entity.setName(name);
                    entity.setModel(model);
                    entity.setMaxTurns(properties.getAgent().getMaxTurns());
                    entity.setTimeoutSeconds(properties.getAgent().getTimeoutSeconds());
                    entity.setDeleted(0);
                    entity.setCreatedAt(LocalDateTime.now());
                    entity.setUpdatedAt(LocalDateTime.now());

                    return agentRepository.save(entity)
                            .doOnNext(saved -> log.info("Created agent: name={}, id={}", saved.getName(), saved.getId()))
                            .map(this::entityToSummaryDto);
                }));
    }

    @PutMapping("/agent/{name}")
    public Mono<Map<String, Object>> updateAgent(@PathVariable String name,
                                                  @RequestBody Map<String, Object> body) {
        return agentRepository.findByName(name)
                .switchIfEmpty(Mono.defer(() -> createDefaultEntity(name)))
                .flatMap(entity -> {
                    if (body.containsKey("model")) entity.setModel((String) body.get("model"));
                    if (body.containsKey("systemPrompt")) entity.setSystemPrompt((String) body.get("systemPrompt"));
                    if (body.containsKey("maxTurns")) entity.setMaxTurns((Integer) body.get("maxTurns"));
                    if (body.containsKey("timeoutSeconds")) entity.setTimeoutSeconds((Integer) body.get("timeoutSeconds"));
                    if (body.containsKey("toolsEnabled")) {
                        Object val = body.get("toolsEnabled");
                        entity.setToolsEnabled(val instanceof String s ? s : (val != null ? val.toString() : null));
                    }
                    if (body.containsKey("mcpToolsEnabled")) {
                        Object val = body.get("mcpToolsEnabled");
                        entity.setMcpToolsEnabled(val instanceof String s ? s : (val != null ? val.toString() : null));
                    }
                    if (body.containsKey("skillsEnabled")) {
                        Object val = body.get("skillsEnabled");
                        entity.setSkillsEnabled(val instanceof String s ? s : (val != null ? val.toString() : null));
                    }
                    entity.setUpdatedAt(LocalDateTime.now());
                    return agentRepository.save(entity);
                })
                .map(saved -> Map.<String, Object>of("success", true));
    }

    private Mono<AgentEntity> createDefaultEntity(String name) {
        JavaClawProperties.Agent defaults = properties.getAgent();
        AgentEntity entity = new AgentEntity();
        entity.setName(name);
        entity.setModel(defaults.getModel());
        entity.setMaxTurns(defaults.getMaxTurns());
        entity.setTimeoutSeconds(defaults.getTimeoutSeconds());
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        log.info("Auto-creating agent record for update: name={}", name);
        return agentRepository.save(entity);
    }

    @PutMapping("/agent/{name}/context")
    public Mono<Map<String, Object>> updateContext(@PathVariable String name,
                                                    @RequestBody Map<String, String> body) {
        String soulMd = body.get("soulMd");
        String userMd = body.get("userMd");
        String agentsMd = body.get("agentsMd");

        log.info("Updating context for agent={}: soul={}chars, user={}chars, agents={}chars",
                name,
                soulMd != null ? soulMd.length() : 0,
                userMd != null ? userMd.length() : 0,
                agentsMd != null ? agentsMd.length() : 0);

        return agentRepository.updateContextByName(name, soulMd, userMd, agentsMd)
                .flatMap(rows -> {
                    if (rows > 0) {
                        return Mono.just(Map.<String, Object>of("success", true));
                    }
                    return createAndUpdateContext(name, soulMd, userMd, agentsMd);
                });
    }

    @DeleteMapping("/agent/{name}")
    public Mono<Map<String, Object>> deleteAgent(@PathVariable String name) {
        String defaultName = properties.getAgent().getName();
        if (defaultName.equals(name)) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot delete default agent"));
        }
        return agentRepository.softDeleteByName(name)
                .flatMap(rows -> {
                    if (rows == 0) {
                        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + name));
                    }
                    log.info("Deleted agent: name={}", name);
                    return Mono.just(Map.<String, Object>of("success", true));
                });
    }

    private Mono<Map<String, Object>> createAndUpdateContext(String name, String soulMd, String userMd, String agentsMd) {
        JavaClawProperties.Agent defaults = properties.getAgent();
        AgentEntity entity = new AgentEntity();
        entity.setName(name);
        entity.setModel(defaults.getModel());
        entity.setMaxTurns(defaults.getMaxTurns());
        entity.setTimeoutSeconds(defaults.getTimeoutSeconds());
        entity.setSoulMd(soulMd);
        entity.setUserMd(userMd);
        entity.setAgentsMd(agentsMd);
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        return agentRepository.save(entity)
                .doOnNext(saved -> log.info("Auto-created agent record: name={}, id={}", saved.getName(), saved.getId()))
                .thenReturn(Map.<String, Object>of("success", true));
    }

    private Map<String, Object> entityToSummaryDto(AgentEntity entity) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("name", entity.getName());
        dto.put("model", entity.getModel());
        dto.put("hasSoul", entity.getSoulMd() != null && !entity.getSoulMd().isBlank());
        dto.put("hasUser", entity.getUserMd() != null && !entity.getUserMd().isBlank());
        dto.put("hasAgents", entity.getAgentsMd() != null && !entity.getAgentsMd().isBlank());
        dto.put("toolsEnabled", entity.getToolsEnabled());
        dto.put("isDefault", false);
        return dto;
    }

    private Map<String, Object> entityToDto(AgentEntity entity) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("name", entity.getName());
        dto.put("model", entity.getModel());
        dto.put("soulMd", entity.getSoulMd());
        dto.put("userMd", entity.getUserMd());
        dto.put("agentsMd", entity.getAgentsMd());
        dto.put("toolsEnabled", entity.getToolsEnabled());
        dto.put("mcpToolsEnabled", entity.getMcpToolsEnabled());
        dto.put("skillsEnabled", entity.getSkillsEnabled());
        return dto;
    }

    private Map<String, Object> defaultDto(String name) {
        JavaClawProperties.Agent defaults = properties.getAgent();
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("name", name);
        dto.put("model", defaults.getModel());
        dto.put("soulMd", defaults.getSoulMd());
        dto.put("userMd", defaults.getUserMd());
        dto.put("agentsMd", defaults.getAgentsMd());
        dto.put("toolsEnabled", (String) null);
        dto.put("mcpToolsEnabled", (String) null);
        dto.put("skillsEnabled", (String) null);
        return dto;
    }
}
