package com.atm.intellimate.gateway.service;

import com.atm.intellimate.agent.tools.ToolsEngine;
import com.atm.intellimate.agent.tools.mcp.McpToolProvider;
import com.atm.intellimate.agent.tools.mcp.PrefixedToolCallback;
import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.gateway.entity.McpServerEntity;
import com.atm.intellimate.gateway.repository.McpServerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class McpToolProviderImpl implements McpToolProvider {

    private static final Logger log = LoggerFactory.getLogger(McpToolProviderImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final McpServerRepository repository;
    private final ToolsEngine toolsEngine;
    private final IntelliMateProperties properties;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    private final Map<String, ToolCallback[]> mcpToolCallbacks = new ConcurrentHashMap<>();

    public McpToolProviderImpl(McpServerRepository repository,
                               @Lazy ToolsEngine toolsEngine,
                               IntelliMateProperties properties) {
        this.repository = repository;
        this.toolsEngine = toolsEngine;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            List<McpServerEntity> servers = repository.findAll()
                    .collectList()
                    .subscribeOn(Schedulers.boundedElastic())
                    .block();

            log.debug("MCP onApplicationReady: loading {} server(s) from repository", servers.size());

            for (McpServerEntity server : servers) {
                try {
                    connectServerSync(server);
                } catch (Exception e) {
                    // 连接失败，禁用该服务器
                    server.setEnabled(0);
                    server.setUpdatedAt(java.time.LocalDateTime.now());
                    repository.save(server).block();
                    log.warn("Failed to connect MCP server '{}' on startup: {}",
                            server.getName(), e.getMessage());
                }
            }

            toolsEngine.refresh();
            int toolCount = getAllCallbacks().length;
            log.debug("MCP onApplicationReady: initialized {} connected server(s), {} tool callback(s)",
                    clients.size(), toolCount);
            log.info("MCP tool provider initialized: {} server(s), {} tool(s)",
                    clients.size(), toolCount);
        } catch (Exception e) {
            log.warn("Failed to initialize MCP tool provider: {}", e.getMessage());
        }
    }

    @Override
    public ToolCallback[] getAllCallbacks() {
        log.debug("getAllCallbacks: mcpToolCallbacks size={}, server names={}",
                mcpToolCallbacks.size(), mcpToolCallbacks.keySet());

        // 如果 Map 为空，可能是初始化未完成，尝试强制加载
        if (mcpToolCallbacks.isEmpty() && !clients.isEmpty()) {
            log.warn("mcpToolCallbacks is empty but clients is not, forcing reconnection...");
            repository.findAll()
                    .filter(server -> server.getEnabled() != null && server.getEnabled() == 1)
                    .doOnNext(server -> {
                        try {
                            connectServerSync(server);
                        } catch (Exception e) {
                            log.warn("Failed to reconnect MCP server '{}': {}", server.getName(), e.getMessage());
                        }
                    })
                    .subscribe();
        }

        return mcpToolCallbacks.values().stream()
                .flatMap(Arrays::stream)
                .toArray(ToolCallback[]::new);
    }

    @Override
    public Map<String, List<String>> getServerToolNames() {
        Map<String, List<String>> result = new ConcurrentHashMap<>();
        mcpToolCallbacks.forEach((serverName, callbacks) -> {
            List<String> names = Arrays.stream(callbacks)
                    .map(cb -> cb.getToolDefinition().name())
                    .toList();
            result.put(serverName, names);
        });
        return result;
    }

    public void connectServerSync(McpServerEntity server) {
        try {
            disconnectServer(server.getName());

            McpSyncClient client = createSyncClient(server);
            client.initialize();
            clients.put(server.getName(), client);

            SyncMcpToolCallbackProvider provider = new SyncMcpToolCallbackProvider(List.of(client));
            ToolCallback[] callbacks = provider.getToolCallbacks();
            ToolCallback[] prefixed = prefixToolNames(server.getName(), callbacks);
            mcpToolCallbacks.put(server.getName(), prefixed);


            log.info("Connected MCP server '{}': {} tool(s) discovered", server.getName(), callbacks.length);
        } catch (Exception e) {
            log.debug("connectServerSync failed for server '{}': {}", server.getName(), e.getMessage(), e);
            throw e;
        }
    }

    public void disconnectServer(String serverName) {
        McpSyncClient existing = clients.remove(serverName);
        mcpToolCallbacks.remove(serverName);
        if (existing != null) {
            try {
                existing.closeGracefully();
            } catch (Exception e) {
                log.warn("Error closing MCP client '{}': {}", serverName, e.getMessage());
            }
        }
    }

    /**
     * Test connection to an MCP server without persisting.
     * Returns the list of discovered tools.
     */
    public List<McpDiscoveredTool> testConnection(McpServerEntity server) {
        McpSyncClient client = createSyncClient(server);
        try {
            client.initialize();
            McpSchema.ListToolsResult result = client.listTools();
            return result.tools().stream()
                    .map(t -> new McpDiscoveredTool(t.name(), t.description()))
                    .toList();
        } finally {
            try {
                client.closeGracefully();
            } catch (Exception e) {
                log.debug("Error closing test MCP client: {}", e.getMessage());
            }
        }
    }

    private Duration resolveRequestTimeout(McpServerEntity server) {
        if (server.getRequestTimeoutSeconds() != null && server.getRequestTimeoutSeconds() > 0) {
            return Duration.ofSeconds(server.getRequestTimeoutSeconds());
        }
        return Duration.ofSeconds(properties.getAgent().getMcpRequestTimeoutSeconds());
    }

    private McpSyncClient createSyncClient(McpServerEntity server) {
        Duration requestTimeout = resolveRequestTimeout(server);
        log.debug("Creating MCP client for '{}' with request timeout: {}s",
                server.getName(), requestTimeout.toSeconds());

        return switch (server.getTransportType()) {
            case "SSE" -> {
                WebClient.Builder wcBuilder = WebClient.builder().baseUrl(server.getServerUrl());
                Map<String, String> headers = parseHeaders(server.getAuthConfig());
                if (!headers.isEmpty()) {
                    wcBuilder.defaultHeaders(h -> headers.forEach(h::set));
                }
                yield McpClient.sync(new WebFluxSseClientTransport(wcBuilder))
                        .requestTimeout(requestTimeout)
                        .build();
            }
            case "STREAMABLE_HTTP" -> {
                Map<String, String> shHeaders = parseHeaders(server.getAuthConfig());
                java.net.URI fullUri = java.net.URI.create(server.getServerUrl());
                String baseUrl = fullUri.getScheme() + "://" + fullUri.getAuthority();
                String path = fullUri.getPath();

                var shBuilder = HttpClientStreamableHttpTransport.builder(baseUrl);
                if (path != null && !path.isEmpty() && !path.equals("/")) {
                    shBuilder.endpoint(path);
                }
                if (!shHeaders.isEmpty()) {
                    shBuilder.customizeRequest(req -> shHeaders.forEach(req::header));
                }
                shBuilder.connectTimeout(requestTimeout);
                yield McpClient.sync(shBuilder.build())
                        .requestTimeout(requestTimeout)
                        .build();
            }
            case "STDIO" -> {
                StdioConfig config = parseStdioConfig(server.getServerUrl());
                io.modelcontextprotocol.client.transport.ServerParameters.Builder paramsBuilder =
                        io.modelcontextprotocol.client.transport.ServerParameters.builder(config.command())
                                .args(config.args());
                if (config.env() != null && !config.env().isEmpty()) {
                    paramsBuilder.env(config.env());
                }
                yield McpClient.sync(new StdioClientTransport(paramsBuilder.build()))
                        .requestTimeout(requestTimeout)
                        .build();
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported transport type: " + server.getTransportType());
        };
    }

    private ToolCallback[] prefixToolNames(String serverName, ToolCallback[] callbacks) {
        String prefix = "mcp_" + serverName.replaceAll("[^a-zA-Z0-9]", "_") + "_";
        return Arrays.stream(callbacks)
                .map(cb -> (ToolCallback) new PrefixedToolCallback(prefix, cb))
                .toArray(ToolCallback[]::new);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseHeaders(String authConfigJson) {
        if (authConfigJson == null || authConfigJson.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(authConfigJson, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            log.warn("Failed to parse authConfig as headers map: {}", e.getMessage());
            return Map.of();
        }
    }

    private StdioConfig parseStdioConfig(String serverUrl) {
        try {
            Map<String, Object> map = MAPPER.readValue(serverUrl, new TypeReference<>() {
            });
            String command = (String) map.get("command");
            @SuppressWarnings("unchecked")
            List<String> args = (List<String>) map.getOrDefault("args", List.of());
            @SuppressWarnings("unchecked")
            Map<String, String> env = (Map<String, String>) map.getOrDefault("env", Map.of());
            return new StdioConfig(command, args, env);
        } catch (Exception e) {
            throw new IllegalArgumentException("STDIO 配置 JSON 格式错误: " + e.getMessage(), e);
        }
    }

    public record McpDiscoveredTool(String name, String description) {
    }

    private record StdioConfig(String command, List<String> args, Map<String, String> env) {
    }
}
