package com.atm.intellimate.gateway.websocket;

import com.atm.intellimate.core.protocol.EventFrame;
import com.atm.intellimate.core.protocol.GatewayFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final AtomicLong seqGenerator = new AtomicLong(0);
    private final ConcurrentHashMap<String, Sinks.Many<GatewayFrame>> sessionSinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> agentSessions = new ConcurrentHashMap<>();

    public void register(String wsSessionId, Sinks.Many<GatewayFrame> sink) {
        sessionSinks.put(wsSessionId, sink);
        log.debug("Session registered: {}", wsSessionId);
    }

    public void bindAgent(String wsSessionId, String agentName) {
        agentSessions.put(agentName, wsSessionId);
        log.debug("Agent '{}' bound to session {}", agentName, wsSessionId);
    }

    public void unregister(String wsSessionId) {
        sessionSinks.remove(wsSessionId);
        agentSessions.entrySet().removeIf(e -> e.getValue().equals(wsSessionId));
        log.debug("Session unregistered: {}", wsSessionId);
    }

    public boolean isAgentOnline(String agentName) {
        String sid = agentSessions.get(agentName);
        return sid != null && sessionSinks.containsKey(sid);
    }

    public boolean pushToAgent(String agentName, String eventType, Map<String, Object> payload) {
        String sid = agentSessions.get(agentName);
        if (sid == null) return false;
        Sinks.Many<GatewayFrame> sink = sessionSinks.get(sid);
        if (sink == null) return false;
        EventFrame frame = new EventFrame(eventType, payload, seqGenerator.incrementAndGet());
        return sink.tryEmitNext(frame).isSuccess();
    }

    public void broadcast(String eventType, Map<String, Object> payload) {
        if (sessionSinks.isEmpty()) return;
        EventFrame frame = new EventFrame(eventType, payload, seqGenerator.incrementAndGet());
        sessionSinks.values().forEach(sink -> sink.tryEmitNext(frame));
    }
}
