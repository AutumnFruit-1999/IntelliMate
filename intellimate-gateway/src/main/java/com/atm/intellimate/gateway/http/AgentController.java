package com.atm.intellimate.gateway.http;

import com.atm.intellimate.agent.model.ChatModelRegistry;
import com.atm.intellimate.agent.model.ModelConfig;
import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.core.exception.ErrorCode;
import com.atm.intellimate.core.exception.IntelliMateException;
import com.atm.intellimate.gateway.dto.AgentDTO;
import com.atm.intellimate.gateway.dto.ApiResponse;
import com.atm.intellimate.gateway.entity.AgentEntity;
import com.atm.intellimate.gateway.repository.AgentRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Tag(name = "Agent", description = "Agent 管理 API")
@RestController
@RequestMapping("/api")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentRepository agentRepository;
    private final IntelliMateProperties properties;
    private final ChatModelRegistry chatModelRegistry;

    public AgentController(AgentRepository agentRepository, IntelliMateProperties properties,
                           ChatModelRegistry chatModelRegistry) {
        this.agentRepository = agentRepository;
        this.properties = properties;
        this.chatModelRegistry = chatModelRegistry;
    }

    @Operation(summary = "获取所有 Agent 列表")
    @GetMapping("/agents")
    public Mono<ApiResponse<List<AgentDTO>>> listAgents() {
        return agentRepository.findAllActive()
                .map(entity -> AgentDTO.fromEntitySummary(entity, resolveModelDisplayName(entity.getModel())))
                .collectList()
                .map(list -> {
                    IntelliMateProperties.Agent defaults = properties.getAgent();
                    boolean hasDefault = list.stream().anyMatch(a -> defaults.getName().equals(a.name()));
                    List<AgentDTO> result = new ArrayList<>(list);
                    if (!hasDefault) {
                        AgentDTO defaultAgent = new AgentDTO(
                                null,
                                defaults.getName(),
                                defaults.getModel(),
                                resolveModelDisplayName(defaults.getModel()),
                                null,
                                null,
                                defaults.getMaxTurns(),
                                defaults.getTimeoutSeconds(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                defaults.getSoulMd() != null && !defaults.getSoulMd().isBlank(),
                                defaults.getAgentsMd() != null && !defaults.getAgentsMd().isBlank(),
                                true,
                                null,
                                null
                        );
                        result.addFirst(defaultAgent);
                    }
                    return ApiResponse.ok(result);
                });
    }

    @GetMapping("/agent/{name}")
    public Mono<ApiResponse<AgentDTO>> getAgent(@PathVariable String name) {
        return agentRepository.findByName(name)
                .map(entity -> AgentDTO.fromEntity(entity)
                        .withModelDisplayName(resolveModelDisplayName(entity.getModel())))
                .map(ApiResponse::ok)
                .defaultIfEmpty(ApiResponse.ok(defaultDto(name)));
    }

    @Operation(summary = "创建 Agent")
    @PostMapping("/agent")
    public Mono<ApiResponse<AgentDTO>> createAgent(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String model = body.getOrDefault("model", properties.getAgent().getModel());

        if (name == null || name.isBlank()) {
            return Mono.error(new IntelliMateException(ErrorCode.VALIDATION_FAILED, "name is required"));
        }

        return agentRepository.findByName(name)
                .flatMap(existing -> Mono.<AgentEntity>error(
                        new IntelliMateException(ErrorCode.AGENT_NAME_CONFLICT, "Agent already exists: " + name)))
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
                            .doOnNext(saved -> log.info("Created agent: name={}, id={}", saved.getName(), saved.getId()));
                }))
                .map(entity -> AgentDTO.fromEntitySummary(entity, resolveModelDisplayName(entity.getModel())))
                .map(ApiResponse::ok);
    }

    @PutMapping("/agent/{name}")
    public Mono<ApiResponse<Map<String, Object>>> updateAgent(@PathVariable String name,
                                                  @RequestBody Map<String, Object> body) {
        return agentRepository.findByName(name)
                .switchIfEmpty(Mono.defer(() -> createDefaultEntity(name)))
                .flatMap(entity -> {
                    if (body.containsKey("model")) entity.setModel((String) body.get("model"));
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
                    if (body.containsKey("skillGroupsEnabled")) {
                        Object val = body.get("skillGroupsEnabled");
                        entity.setSkillGroupsEnabled(val instanceof String s ? s : (val != null ? val.toString() : null));
                    }
                    if (body.containsKey("canDelegate")) {
                        Object val = body.get("canDelegate");
                        if (val instanceof Boolean b) {
                            entity.setCanDelegate(b ? 1 : 0);
                        } else if (val instanceof Number n) {
                            entity.setCanDelegate(n.intValue());
                        }
                    }
                    if (body.containsKey("delegateAgents")) {
                        Object val = body.get("delegateAgents");
                        entity.setDelegateAgents(val instanceof String s ? s : (val != null ? val.toString() : null));
                    }
                    if (body.containsKey("goal")) {
                        Object val = body.get("goal");
                        entity.setGoal(val instanceof String s ? s : (val != null ? val.toString() : null));
                    }
                    if (body.containsKey("bridgeNode")) {
                        Object val = body.get("bridgeNode");
                        entity.setBridgeNode(val instanceof String s ? s : (val != null ? val.toString() : null));
                    }
                    entity.setUpdatedAt(LocalDateTime.now());
                    return agentRepository.save(entity);
                })
                .map(saved -> ApiResponse.ok(Map.<String, Object>of("success", true)));
    }

    private Mono<AgentEntity> createDefaultEntity(String name) {
        IntelliMateProperties.Agent defaults = properties.getAgent();
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
    public Mono<ApiResponse<Map<String, Object>>> updateContext(@PathVariable String name,
                                                    @RequestBody Map<String, String> body) {
        String soulMd = body.get("soulMd");
        String agentsMd = body.get("agentsMd");

        log.info("Updating context for agent={}: soul={}chars, agents={}chars",
                name,
                soulMd != null ? soulMd.length() : 0,
                agentsMd != null ? agentsMd.length() : 0);

        return agentRepository.updateContextByName(name, soulMd, agentsMd)
                .flatMap(rows -> {
                    if (rows > 0) {
                        return Mono.just(ApiResponse.ok(Map.<String, Object>of("success", true)));
                    }
                    return createAndUpdateContext(name, soulMd, agentsMd);
                });
    }

    @DeleteMapping("/agent/{name}")
    public Mono<ApiResponse<Map<String, Object>>> deleteAgent(@PathVariable String name) {
        String defaultName = properties.getAgent().getName();
        if (defaultName.equals(name)) {
            return Mono.error(new IntelliMateException(ErrorCode.VALIDATION_FAILED, "Cannot delete default agent"));
        }
        return agentRepository.softDeleteByName(name)
                .flatMap(rows -> {
                    if (rows == 0) {
                        return Mono.error(new IntelliMateException(ErrorCode.AGENT_NOT_FOUND, "Agent not found: " + name));
                    }
                    log.info("Deleted agent: name={}", name);
                    return Mono.just(ApiResponse.ok(Map.<String, Object>of("success", true)));
                });
    }

    private Mono<ApiResponse<Map<String, Object>>> createAndUpdateContext(String name, String soulMd, String agentsMd) {
        IntelliMateProperties.Agent defaults = properties.getAgent();
        AgentEntity entity = new AgentEntity();
        entity.setName(name);
        entity.setModel(defaults.getModel());
        entity.setMaxTurns(defaults.getMaxTurns());
        entity.setTimeoutSeconds(defaults.getTimeoutSeconds());
        entity.setSoulMd(soulMd);
        entity.setAgentsMd(agentsMd);
        entity.setDeleted(0);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        return agentRepository.save(entity)
                .doOnNext(saved -> log.info("Auto-created agent record: name={}, id={}", saved.getName(), saved.getId()))
                .thenReturn(ApiResponse.ok(Map.<String, Object>of("success", true)));
    }

    private String resolveModelDisplayName(String modelRef) {
        if (modelRef == null || modelRef.isBlank()) return "";
        try {
            Long definitionId = Long.parseLong(modelRef);
            ModelConfig mc = chatModelRegistry.getDefinition(definitionId);
            return mc != null ? mc.displayName() : modelRef;
        } catch (NumberFormatException e) {
            return modelRef;
        }
    }

    private AgentDTO defaultDto(String name) {
        IntelliMateProperties.Agent defaults = properties.getAgent();
        return new AgentDTO(
                null,
                name,
                defaults.getModel(),
                null,
                defaults.getSoulMd(),
                defaults.getAgentsMd(),
                defaults.getMaxTurns(),
                defaults.getTimeoutSeconds(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                defaults.getSoulMd() != null && !defaults.getSoulMd().isBlank(),
                defaults.getAgentsMd() != null && !defaults.getAgentsMd().isBlank(),
                false,
                null,
                null
        );
    }
}
