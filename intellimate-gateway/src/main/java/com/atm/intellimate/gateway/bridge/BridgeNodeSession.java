package com.atm.intellimate.gateway.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class BridgeNodeSession {

    private static final Logger log = LoggerFactory.getLogger(BridgeNodeSession.class);
    private static final long DEFAULT_TOOL_TIMEOUT_SECONDS = 120;

    private final String nodeName;
    private final String webSocketSessionId;
    private final Sinks.Many<String> outSink;
    private final ObjectMapper objectMapper;
    private volatile Set<String> registeredTools = Set.of();
    private volatile Instant connectedAt = Instant.now();
    private volatile Instant lastHeartbeat = Instant.now();

    private final Map<String, CompletableFuture<BridgeProtocol.ToolResult>> pendingCalls = new ConcurrentHashMap<>();

    public BridgeNodeSession(String nodeName, WebSocketSession wsSession,
                             Sinks.Many<String> outSink, ObjectMapper objectMapper) {
        this.nodeName = nodeName;
        this.webSocketSessionId = wsSession.getId();
        this.outSink = outSink;
        this.objectMapper = objectMapper;
    }

    public String getNodeName() { return nodeName; }
    public String getWebSocketSessionId() { return webSocketSessionId; }
    public Set<String> getRegisteredTools() { return registeredTools; }
    public void setRegisteredTools(Set<String> tools) { this.registeredTools = tools; }
    public Instant getConnectedAt() { return connectedAt; }
    public Instant getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Instant lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }

    public BridgeProtocol.ToolResult callTool(String toolName, String argsJson) {
        String requestId = java.util.UUID.randomUUID().toString();
        CompletableFuture<BridgeProtocol.ToolResult> future = new CompletableFuture<>();
        pendingCalls.put(requestId, future);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.readValue(argsJson, Map.class);
            BridgeProtocol.ToolCall call = new BridgeProtocol.ToolCall(requestId, toolName, args);
            String json = objectMapper.writeValueAsString(call);
            outSink.tryEmitNext(json);

            return future.get(DEFAULT_TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingCalls.remove(requestId);
            log.error("Bridge tool call failed: node={}, tool={}", nodeName, toolName, e);
            return new BridgeProtocol.ToolResult(requestId, false, null,
                    "Bridge call error: " + e.getMessage(), null);
        }
    }

    public void onToolResult(BridgeProtocol.ToolResult result) {
        CompletableFuture<BridgeProtocol.ToolResult> future = pendingCalls.remove(result.id());
        if (future != null) {
            future.complete(result);
        } else {
            log.warn("Received tool result for unknown request: id={}", result.id());
        }
    }

    public void sendPing() {
        try {
            String json = objectMapper.writeValueAsString(new BridgeProtocol.Ping());
            outSink.tryEmitNext(json);
        } catch (Exception e) {
            log.error("Failed to send ping to bridge node: {}", nodeName, e);
        }
    }

    public void close() {
        pendingCalls.values().forEach(f -> f.completeExceptionally(
                new RuntimeException("Bridge node disconnected")));
        pendingCalls.clear();
    }
}
