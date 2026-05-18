package com.atm.intellimate.gateway.http;

import com.atm.intellimate.agent.tools.ToolsEngine;
import com.atm.intellimate.agent.tools.dynamic.DynamicToolProvider;
import com.atm.intellimate.agent.tools.dynamic.DynamicToolDefinition;
import com.atm.intellimate.agent.tools.dynamic.HttpToolCallback;
import com.atm.intellimate.gateway.entity.ToolDefinitionEntity;
import com.atm.intellimate.gateway.repository.ToolDefinitionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/tool-definitions")
public class ToolDefinitionController {

    private static final Logger log = LoggerFactory.getLogger(ToolDefinitionController.class);
    private static final Pattern TOOL_NAME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]{1,63}$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolDefinitionRepository repository;
    private final DynamicToolProvider dynamicToolProvider;
    private final ToolsEngine toolsEngine;
    private final WebClient testWebClient;

    public ToolDefinitionController(ToolDefinitionRepository repository,
                                    DynamicToolProvider dynamicToolProvider,
                                    ToolsEngine toolsEngine) {
        this.repository = repository;
        this.dynamicToolProvider = dynamicToolProvider;
        this.toolsEngine = toolsEngine;
        this.testWebClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }

    @GetMapping
    public Mono<List<ToolDefinitionEntity>> listAll() {
        return repository.findAll().collectList();
    }

    @GetMapping("/{id}")
    public Mono<ToolDefinitionEntity> getById(@PathVariable Long id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping
    public Mono<ToolDefinitionEntity> create(@RequestBody Map<String, Object> body) {
        return Mono.defer(() -> {
            String name = (String) body.get("name");
            if (name == null || !TOOL_NAME_PATTERN.matcher(name).matches()) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "工具名称必须以字母开头，只能包含字母、数字、下划线和短横线，长度 2~64 个字符"));
            }

            return repository.findByName(name)
                    .flatMap(existing -> Mono.<ToolDefinitionEntity>error(
                            new ResponseStatusException(HttpStatus.CONFLICT, "工具名称已存在: " + name)))
                    .switchIfEmpty(Mono.defer(() -> {
                        ToolDefinitionEntity entity = new ToolDefinitionEntity();
                        entity.setName(name);
                        entity.setType((String) body.getOrDefault("type", "HTTP_API"));
                        entity.setDescription((String) body.get("description"));
                        entity.setParametersSchema(toJsonString(body.get("parametersSchema")));
                        entity.setExecutionConfig(toJsonString(body.get("executionConfig")));
                        entity.setTimeoutSeconds(body.containsKey("timeoutSeconds")
                                ? ((Number) body.get("timeoutSeconds")).intValue() : 30);
                        entity.setGroupName((String) body.getOrDefault("groupName", "CUSTOM"));
                        entity.setAgentName((String) body.get("agentName"));
                        entity.setEnabled(1);
                        entity.setCreatedAt(LocalDateTime.now());
                        entity.setUpdatedAt(LocalDateTime.now());
                        return repository.save(entity);
                    }))
                    .flatMap(saved -> reloadAndReturn(saved));
        });
    }

    @PutMapping("/{id}")
    public Mono<ToolDefinitionEntity> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(entity -> {
                    if (body.containsKey("name")) {
                        String name = (String) body.get("name");
                        if (!TOOL_NAME_PATTERN.matcher(name).matches()) {
                            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "工具名称必须以字母开头，只能包含字母、数字、下划线和短横线，长度 2~64 个字符"));
                        }
                        entity.setName(name);
                    }
                    if (body.containsKey("type")) entity.setType((String) body.get("type"));
                    if (body.containsKey("description")) entity.setDescription((String) body.get("description"));
                    if (body.containsKey("parametersSchema")) entity.setParametersSchema(toJsonString(body.get("parametersSchema")));
                    if (body.containsKey("executionConfig")) entity.setExecutionConfig(toJsonString(body.get("executionConfig")));
                    if (body.containsKey("timeoutSeconds")) entity.setTimeoutSeconds(((Number) body.get("timeoutSeconds")).intValue());
                    if (body.containsKey("groupName")) entity.setGroupName((String) body.get("groupName"));
                    if (body.containsKey("agentName")) entity.setAgentName((String) body.get("agentName"));
                    if (body.containsKey("enabled")) {
                        Object val = body.get("enabled");
                        entity.setEnabled(val instanceof Boolean b ? (b ? 1 : 0) : ((Number) val).intValue());
                    }
                    entity.setUpdatedAt(LocalDateTime.now());
                    return repository.save(entity);
                })
                .flatMap(saved -> reloadAndReturn(saved));
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable Long id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(entity -> repository.delete(entity).thenReturn(entity))
                .flatMap(deleted -> dynamicToolProvider.reload()
                        .doOnSuccess(v -> toolsEngine.refresh())
                        .thenReturn(Map.<String, Object>of("success", true, "deletedName", deleted.getName())));
    }

    @PostMapping("/{id}/test")
    public Mono<Map<String, Object>> test(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(entity -> {
                    long startMs = System.currentTimeMillis();
                    try {
                        DynamicToolDefinition def = new DynamicToolDefinition(
                                entity.getId(), entity.getName(), entity.getType(),
                                entity.getDescription(), entity.getParametersSchema(),
                                entity.getExecutionConfig(),
                                entity.getTimeoutSeconds() != null ? entity.getTimeoutSeconds() : 30,
                                entity.getGroupName(), entity.getAgentName()
                        );

                        @SuppressWarnings("unchecked")
                        Map<String, Object> args = (Map<String, Object>) body.getOrDefault("arguments", Map.of());
                        String argsJson = MAPPER.writeValueAsString(args);

                        HttpToolCallback callback = new HttpToolCallback(def, testWebClient);
                        String result = callback.call(argsJson);
                        long durationMs = System.currentTimeMillis() - startMs;

                        Map<String, Object> resp = new LinkedHashMap<>();
                        resp.put("success", true);
                        resp.put("result", result);
                        resp.put("durationMs", durationMs);
                        return Mono.just(resp);
                    } catch (Exception e) {
                        long durationMs = System.currentTimeMillis() - startMs;
                        Map<String, Object> resp = new LinkedHashMap<>();
                        resp.put("success", false);
                        resp.put("error", e.getMessage());
                        resp.put("durationMs", durationMs);
                        return Mono.just(resp);
                    }
                });
    }

    private Mono<ToolDefinitionEntity> reloadAndReturn(ToolDefinitionEntity saved) {
        return dynamicToolProvider.reload()
                .doOnSuccess(v -> toolsEngine.refresh())
                .thenReturn(saved);
    }

    private String toJsonString(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialize to JSON: {}", e.getMessage());
            return obj.toString();
        }
    }
}
