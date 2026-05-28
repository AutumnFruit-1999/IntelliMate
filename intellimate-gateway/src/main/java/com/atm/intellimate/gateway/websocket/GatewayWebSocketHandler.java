package com.atm.intellimate.gateway.websocket;

import com.atm.intellimate.core.protocol.EventFrame;
import com.atm.intellimate.core.protocol.GatewayFrame;
import com.atm.intellimate.core.protocol.RequestFrame;
import com.atm.intellimate.core.protocol.ResponseFrame;
import com.atm.intellimate.gateway.pipeline.MessagePipeline;
import com.atm.intellimate.gateway.security.SecurityService;
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
import reactor.util.context.Context;

import java.time.Duration;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class GatewayWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayWebSocketHandler.class);
    private static final Duration PING_INTERVAL = Duration.ofSeconds(30);
    private static final int MAX_MISSED_PONGS = 3;

    private final ProtocolCodec codec;
    private final SecurityService securityService;
    private final MessagePipeline messagePipeline;
    private final SessionRegistry sessionRegistry;
    private final AtomicLong seqGenerator = new AtomicLong(0);

    public GatewayWebSocketHandler(ProtocolCodec codec,
                                   SecurityService securityService,
                                   MessagePipeline messagePipeline,
                                   SessionRegistry sessionRegistry) {
        this.codec = codec;
        this.securityService = securityService;
        this.messagePipeline = messagePipeline;
        this.sessionRegistry = sessionRegistry;
    }

    @Override
    @NonNull
    public Mono<Void> handle(@NonNull WebSocketSession session) {
        String token = extractToken(session);
        if (!securityService.validateToken(token)) {
            log.warn("WebSocket connection rejected: invalid token");
            return session.close();
        }

        log.info("WebSocket connected: sessionId={}", session.getId());

        Sinks.Many<GatewayFrame> outSink = Sinks.many().unicast()
                .onBackpressureBuffer(Queues.<GatewayFrame>get(1024).get());
        AtomicInteger missedPongs = new AtomicInteger(0);

        sessionRegistry.register(session.getId(), outSink);

        outSink.tryEmitNext(new EventFrame(
                "session.welcome",
                Map.of("wsSessionId", session.getId()),
                seqGenerator.incrementAndGet()
        ));

        Disposable heartbeat = Flux.interval(PING_INTERVAL)
                .subscribe(tick -> {
                    if (missedPongs.incrementAndGet() > MAX_MISSED_PONGS) {
                        log.warn("Session {} missed {} pongs, closing", session.getId(), MAX_MISSED_PONGS);
                        outSink.tryEmitComplete();
                        return;
                    }
                    outSink.tryEmitNext(new EventFrame(
                            "ping", Map.of(), seqGenerator.incrementAndGet()
                    ));
                });

        Sinks.Many<GatewayFrame> frameSink = Sinks.many().unicast()
                .onBackpressureBuffer(Queues.<GatewayFrame>get(1024).get());

        Disposable receiver = session.receive()
                .subscribe(
                        msg -> {
                            try {
                                GatewayFrame frame = codec.decode(msg.getPayloadAsText());
                                if (frame instanceof EventFrame evt && "pong".equals(evt.event())) {
                                    missedPongs.set(0);
                                    log.trace("Pong received, counter reset");
                                    return;
                                }
                                frameSink.tryEmitNext(frame);
                            } catch (Exception e) {
                                log.error("Failed to decode frame: {}", msg.getPayloadAsText(), e);
                                frameSink.tryEmitError(e);
                            }
                        },
                        frameSink::tryEmitError,
                        frameSink::tryEmitComplete
                );

        Mono<Void> inbound = frameSink.asFlux()
                .concatMap(frame -> routeFrame(frame, session))
                .doOnNext(outSink::tryEmitNext)
                .doOnComplete(outSink::tryEmitComplete)
                .doOnError(e -> outSink.tryEmitError(e))
                .then();

        Flux<WebSocketMessage> outbound = outSink.asFlux()
                .map(frame -> session.textMessage(codec.encode(frame)));

        return Mono.when(inbound, session.send(outbound))
                .doFinally(signal -> {
                    receiver.dispose();
                    heartbeat.dispose();
                    sessionRegistry.unregister(session.getId());
                    messagePipeline.onWebSocketDisconnect(session.getId());
                    log.info("WebSocket disconnected: sessionId={}, signal={}", session.getId(), signal);
                });
    }

    private Flux<GatewayFrame> routeFrame(GatewayFrame frame, WebSocketSession session) {
        return switch (frame) {
            case RequestFrame req -> handleRequest(req, session);
            case EventFrame evt -> handleEvent(evt, session);
            case ResponseFrame resp -> Flux.empty();
        };
    }

    private Flux<GatewayFrame> handleRequest(RequestFrame request, WebSocketSession session) {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        log.debug("Handling request: method={}, id={}, traceId={}", request.method(), request.id(), traceId);
        return messagePipeline.processRequest(request, session.getId())
                .contextWrite(Context.of("traceId", traceId));
    }

    private Flux<GatewayFrame> handleEvent(EventFrame event, WebSocketSession session) {
        if ("agent.bind".equals(event.event())) {
            Object agentNameObj = null;
            if (event.payload() instanceof Map<?, ?> payload) {
                agentNameObj = payload.get("agentName");
            }
            if (agentNameObj instanceof String agentName && !agentName.isBlank()) {
                sessionRegistry.bindAgent(session.getId(), agentName);
                log.info("Agent bind: agent='{}', session={}", agentName, session.getId());
            }
            return Flux.empty();
        }
        log.debug("Unhandled client event: {}", event.event());
        return Flux.empty();
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
        var headers = session.getHandshakeInfo().getHeaders();
        String authHeader = headers.getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }
}
