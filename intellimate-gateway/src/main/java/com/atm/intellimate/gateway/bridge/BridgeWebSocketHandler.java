package com.atm.intellimate.gateway.bridge;

import com.atm.intellimate.gateway.repository.BridgeNodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;

@Component
public class BridgeWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(BridgeWebSocketHandler.class);
    private static final Duration PING_INTERVAL = Duration.ofSeconds(30);

    private final BridgeNodeRegistry registry;
    private final BridgeNodeRepository repository;
    private final ObjectMapper objectMapper;

    public BridgeWebSocketHandler(BridgeNodeRegistry registry,
                                  BridgeNodeRepository repository,
                                  ObjectMapper objectMapper) {
        this.registry = registry;
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @NonNull
    public Mono<Void> handle(@NonNull WebSocketSession session) {
        String token = extractToken(session);
        if (token == null || token.isBlank()) {
            log.warn("Bridge connection rejected: no token provided");
            return session.close();
        }

        String tokenHash = sha256(token);

        Sinks.Many<String> outSink = Sinks.many().unicast()
                .onBackpressureBuffer(Queues.<String>get(256).get());

        Disposable heartbeat = Flux.interval(PING_INTERVAL)
                .subscribe(tick -> {
                    BridgeNodeSession nodeSession = registry.getSessionByWebSocketId(session.getId());
                    if (nodeSession != null) {
                        nodeSession.sendPing();
                    }
                });

        Mono<Void> inbound = session.receive()
                .map(WebSocketMessage::getPayloadAsText)
                .concatMap(text -> handleIncoming(text, session, outSink, tokenHash))
                .then();

        Flux<WebSocketMessage> outbound = outSink.asFlux()
                .map(session::textMessage);

        return Mono.when(inbound, session.send(outbound))
                .doFinally(signal -> {
                    heartbeat.dispose();
                    cleanupSession(session);
                });
    }

    private Mono<Void> handleIncoming(String text, WebSocketSession wsSession,
                                      Sinks.Many<String> outSink, String tokenHash) {
        try {
            BridgeProtocol.Message msg = objectMapper.readValue(text, BridgeProtocol.Message.class);

            return switch (msg) {
                case BridgeProtocol.Register reg -> handleRegister(reg, wsSession, outSink, tokenHash);
                case BridgeProtocol.ToolResult result -> {
                    BridgeNodeSession nodeSession = registry.getSessionByWebSocketId(wsSession.getId());
                    if (nodeSession != null) {
                        nodeSession.onToolResult(result);
                    }
                    yield Mono.empty();
                }
                case BridgeProtocol.Pong pong -> {
                    BridgeNodeSession nodeSession = registry.getSessionByWebSocketId(wsSession.getId());
                    if (nodeSession != null) {
                        nodeSession.setLastHeartbeat(Instant.now());
                        repository.updateHeartbeat(nodeSession.getNodeName()).subscribe();
                    }
                    yield Mono.empty();
                }
                default -> Mono.empty();
            };
        } catch (Exception e) {
            log.error("Failed to parse bridge message: {}", text, e);
            return Mono.empty();
        }
    }

    private Mono<Void> handleRegister(BridgeProtocol.Register reg, WebSocketSession wsSession,
                                      Sinks.Many<String> outSink, String tokenHash) {
        return repository.findByName(reg.name())
                .switchIfEmpty(Mono.error(new RuntimeException("Unknown bridge node: " + reg.name())))
                .flatMap(entity -> {
                    if (!entity.getTokenHash().equals(tokenHash)) {
                        log.warn("Bridge token mismatch for node: {}", reg.name());
                        return sendError(outSink, "Authentication failed")
                                .then(Mono.fromRunnable(wsSession::close))
                                .then();
                    }

                    BridgeNodeSession nodeSession = new BridgeNodeSession(
                            reg.name(), wsSession, outSink, objectMapper);
                    Set<String> tools = reg.tools() != null ? Set.copyOf(reg.tools()) : Set.of();
                    nodeSession.setRegisteredTools(tools);
                    registry.register(reg.name(), nodeSession);

                    return repository.updateStatus(reg.name(), "CONNECTED")
                            .then(repository.updateRegisteredTools(reg.name(), toJson(reg.tools())))
                            .then(sendRegistered(outSink, entity.getId().toString()));
                })
                .onErrorResume(e -> {
                    log.error("Bridge registration failed: {}", e.getMessage());
                    return sendError(outSink, e.getMessage());
                });
    }

    private Mono<Void> sendRegistered(Sinks.Many<String> outSink, String nodeId) {
        try {
            outSink.tryEmitNext(objectMapper.writeValueAsString(
                    new BridgeProtocol.Registered(nodeId)));
        } catch (Exception e) {
            log.error("Failed to send registered message", e);
        }
        return Mono.empty();
    }

    private Mono<Void> sendError(Sinks.Many<String> outSink, String message) {
        try {
            outSink.tryEmitNext(objectMapper.writeValueAsString(
                    new BridgeProtocol.ErrorMsg(message)));
        } catch (Exception e) {
            log.error("Failed to send error message", e);
        }
        return Mono.empty();
    }

    private void cleanupSession(WebSocketSession wsSession) {
        BridgeNodeSession nodeSession = registry.getSessionByWebSocketId(wsSession.getId());
        if (nodeSession != null) {
            String nodeName = nodeSession.getNodeName();
            registry.unregister(nodeName);
            repository.updateStatus(nodeName, "DISCONNECTED").subscribe();
        }
    }

    private String extractToken(WebSocketSession session) {
        var uri = session.getHandshakeInfo().getUri();
        var query = uri.getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("token=")) {
                    return param.substring(6);
                }
            }
        }
        return null;
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj != null ? obj : java.util.List.of());
        } catch (Exception e) {
            return "[]";
        }
    }
}
