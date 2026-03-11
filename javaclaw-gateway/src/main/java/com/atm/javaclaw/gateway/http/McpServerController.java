package com.atm.javaclaw.gateway.http;

import com.atm.javaclaw.agent.tools.ToolsEngine;
import com.atm.javaclaw.gateway.entity.McpServerEntity;
import com.atm.javaclaw.gateway.repository.McpServerRepository;
import com.atm.javaclaw.gateway.service.McpToolProviderImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/mcp-servers")
public class McpServerController {

    private static final Logger log = LoggerFactory.getLogger(McpServerController.class);
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]{0,63}$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpServerRepository repository;
    private final McpToolProviderImpl mcpToolProvider;
    private final ToolsEngine toolsEngine;

    public McpServerController(McpServerRepository repository,
                               McpToolProviderImpl mcpToolProvider,
                               ToolsEngine toolsEngine) {
        this.repository = repository;
        this.mcpToolProvider = mcpToolProvider;
        this.toolsEngine = toolsEngine;
    }

    @GetMapping
    public Mono<List<McpServerEntity>> listAll() {
        return repository.findAll().collectList();
    }

    @GetMapping("/{id}")
    public Mono<McpServerEntity> getById(@PathVariable Long id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @PostMapping
    public Mono<McpServerEntity> create(@RequestBody Map<String, Object> body) {
        return Mono.defer(() -> {
            String name = (String) body.get("name");
            if (name == null || !NAME_PATTERN.matcher(name).matches()) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "服务名称必须以字母开头，只能包含字母、数字、下划线和短横线，长度 1~64 个字符"));
            }

            return repository.findByName(name)
                    .flatMap(existing -> Mono.<McpServerEntity>error(
                            new ResponseStatusException(HttpStatus.CONFLICT, "服务名称已存在: " + name)))
                    .switchIfEmpty(Mono.defer(() -> {
                        McpServerEntity entity = new McpServerEntity();
                        entity.setName(name);
                        entity.setServerUrl((String) body.get("serverUrl"));
                        entity.setTransportType((String) body.getOrDefault("transportType", "SSE"));
                        entity.setAuthConfig(toJsonString(body.get("authConfig")));
                        entity.setAgentName((String) body.get("agentName"));
                        entity.setEnabled(1);
                        entity.setCreatedAt(LocalDateTime.now());
                        entity.setUpdatedAt(LocalDateTime.now());
                        return repository.save(entity);
                    }))
                    .flatMap(this::tryConnectAndUpdate);
        });
    }

    @PutMapping("/{id}")
    public Mono<McpServerEntity> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(entity -> {
                    String oldName = entity.getName();
                    if (body.containsKey("name")) {
                        String name = (String) body.get("name");
                        if (!NAME_PATTERN.matcher(name).matches()) {
                            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "服务名称必须以字母开头，只能包含字母、数字、下划线和短横线，长度 1~64 个字符"));
                        }
                        entity.setName(name);
                    }
                    if (body.containsKey("serverUrl")) entity.setServerUrl((String) body.get("serverUrl"));
                    if (body.containsKey("transportType")) entity.setTransportType((String) body.get("transportType"));
                    if (body.containsKey("authConfig")) entity.setAuthConfig(toJsonString(body.get("authConfig")));
                    if (body.containsKey("agentName")) entity.setAgentName((String) body.get("agentName"));
                    if (body.containsKey("enabled")) {
                        Object val = body.get("enabled");
                        entity.setEnabled(val instanceof Boolean b ? (b ? 1 : 0) : ((Number) val).intValue());
                    }
                    entity.setUpdatedAt(LocalDateTime.now());

                    mcpToolProvider.disconnectServer(oldName);

                    return repository.save(entity)
                            .flatMap(saved -> {
                                if (saved.getEnabled() == 1) {
                                    return tryConnectAndUpdate(saved);
                                }
                                toolsEngine.refresh();
                                return Mono.just(saved);
                            });
                });
    }

    @DeleteMapping("/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable Long id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(entity -> repository.delete(entity).thenReturn(entity))
                .doOnNext(entity -> {
                    mcpToolProvider.disconnectServer(entity.getName());
                    toolsEngine.refresh();
                })
                .map(entity -> Map.<String, Object>of("success", true, "deletedName", entity.getName()));
    }

    @PostMapping("/{id}/test")
    public Mono<Map<String, Object>> test(@PathVariable Long id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(entity -> doTestConnection(entity));
    }

    /**
     * Test connection with config from request body (no DB record needed).
     * Allows testing before saving.
     */
    @PostMapping("/test-config")
    public Mono<Map<String, Object>> testConfig(@RequestBody Map<String, Object> body) {
        return Mono.defer(() -> {
            McpServerEntity entity = new McpServerEntity();
            entity.setName((String) body.getOrDefault("name", "test"));
            entity.setServerUrl((String) body.get("serverUrl"));
            entity.setTransportType((String) body.getOrDefault("transportType", "SSE"));
            entity.setAuthConfig(toJsonString(body.get("authConfig")));
            return doTestConnection(entity);
        });
    }

    private Mono<Map<String, Object>> doTestConnection(McpServerEntity entity) {
        return Mono.fromCallable(() -> {
            var tools = mcpToolProvider.testConnection(entity);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("serverName", entity.getName());
            result.put("toolsDiscovered", tools);
            return result;
        }).subscribeOn(Schedulers.boundedElastic())
        .onErrorResume(e -> {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("success", false);
            err.put("serverName", entity.getName());
            err.put("error", e.getMessage());
            err.put("toolsDiscovered", List.of());
            return Mono.just(err);
        });
    }

    /**
     * Try to connect to the MCP server. If connection fails, mark as disconnected
     * (clear toolsDiscovered) and return the entity -- never propagate the error.
     */
    private Mono<McpServerEntity> tryConnectAndUpdate(McpServerEntity entity) {
        return Mono.fromCallable(() -> {
            try {
                mcpToolProvider.connectServerSync(entity);
                toolsEngine.refresh();

                String toolsJson = MAPPER.writeValueAsString(
                        mcpToolProvider.getServerToolNames().getOrDefault(entity.getName(), List.of()));
                entity.setToolsDiscovered(toolsJson);
                entity.setLastConnectedAt(LocalDateTime.now());
                log.info("MCP server '{}' connected successfully", entity.getName());
            } catch (Exception e) {
                log.warn("MCP server '{}' 连接失败（已保存配置，状态为断开）: {}", entity.getName(), e.getMessage());
                entity.setToolsDiscovered(null);
                entity.setLastConnectedAt(null);
                toolsEngine.refresh();
            }
            return entity;
        }).subscribeOn(Schedulers.boundedElastic())
        .flatMap(e -> repository.save(e));
    }

    private String toJsonString(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
