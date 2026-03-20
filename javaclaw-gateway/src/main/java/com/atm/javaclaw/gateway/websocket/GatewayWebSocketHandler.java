package com.atm.javaclaw.gateway.websocket;

import com.atm.javaclaw.core.protocol.EventFrame;
import com.atm.javaclaw.core.protocol.GatewayFrame;
import com.atm.javaclaw.core.protocol.RequestFrame;
import com.atm.javaclaw.core.protocol.ResponseFrame;
import com.atm.javaclaw.gateway.pipeline.MessagePipeline;
import com.atm.javaclaw.gateway.security.SecurityService;
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

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class GatewayWebSocketHandler implements WebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayWebSocketHandler.class);
    private static final Duration PING_INTERVAL = Duration.ofSeconds(30);
    private static final int MAX_MISSED_PONGS = 30;

    private final ProtocolCodec codec;
    private final SecurityService securityService;
    private final MessagePipeline messagePipeline;
    private final AtomicLong seqGenerator = new AtomicLong(0);

    public GatewayWebSocketHandler(ProtocolCodec codec,
                                   SecurityService securityService,
                                   MessagePipeline messagePipeline) {
        this.codec = codec;
        this.securityService = securityService;
        this.messagePipeline = messagePipeline;
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

        Sinks.Many<GatewayFrame> outSink = Sinks.many().unicast().onBackpressureBuffer();
        AtomicInteger missedPongs = new AtomicInteger(0);

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

        Mono<Void> inbound = session.receive()
                .map(msg -> codec.decode(msg.getPayloadAsText()))
                .concatMap(frame -> routeFrame(frame, session, missedPongs))
                .doOnNext(outSink::tryEmitNext)
                .doOnComplete(outSink::tryEmitComplete)
                .doOnError(e -> outSink.tryEmitError(e))
                .then();

        Flux<WebSocketMessage> outbound = outSink.asFlux()
                .map(frame -> session.textMessage(codec.encode(frame)));

        return Mono.when(inbound, session.send(outbound))
                .doFinally(signal -> {
                    heartbeat.dispose();
                    log.info("WebSocket disconnected: sessionId={}, signal={}", session.getId(), signal);
                });
    }

    private Flux<GatewayFrame> routeFrame(GatewayFrame frame, WebSocketSession session, AtomicInteger missedPongs) {
        return switch (frame) {
            case RequestFrame req -> handleRequest(req, session);
            case EventFrame evt -> handleEvent(evt, missedPongs);
            case ResponseFrame resp -> Flux.empty();
        };
    }

    private Flux<GatewayFrame> handleRequest(RequestFrame request, WebSocketSession session) {
        log.debug("Handling request: method={}, id={}", request.method(), request.id());
        return messagePipeline.processRequest(request, session.getId());
    }

    private Flux<GatewayFrame> handleEvent(EventFrame event, AtomicInteger missedPongs) {
        return switch (event.event()) {
            case "pong" -> {
                missedPongs.set(0);
                log.trace("Pong received, counter reset");
                yield Flux.empty();
            }
            default -> {
                log.debug("Unhandled client event: {}", event.event());
                yield Flux.empty();
            }
        };
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
