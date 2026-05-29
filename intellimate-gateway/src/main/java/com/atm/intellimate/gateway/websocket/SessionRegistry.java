package com.atm.intellimate.gateway.websocket;

import com.atm.intellimate.core.protocol.EventFrame;
import com.atm.intellimate.core.protocol.GatewayFrame;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class SessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(SessionRegistry.class);

    private final AtomicLong seqGenerator = new AtomicLong(0);
    private final ConcurrentHashMap<String, Sinks.Many<GatewayFrame>> sessionSinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> agentSessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> sessionUserIds = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;

    public SessionRegistry(@Autowired(required = false) MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        if (meterRegistry != null) {
            meterRegistry.gauge("ws.connections.active", sessionSinks, ConcurrentMap::size);
        }
    }

    public void register(String wsSessionId, Sinks.Many<GatewayFrame> sink) {
        sessionSinks.put(wsSessionId, sink);
        if (meterRegistry != null) {
            meterRegistry.counter("ws.connections.opened").increment();
        }
        log.debug("Session registered: {}", wsSessionId);
    }

    public void bindUserId(String wsSessionId, Long userId) {
        sessionUserIds.put(wsSessionId, userId);
        log.debug("User {} bound to session {}", userId, wsSessionId);
    }

    public Long getUserId(String wsSessionId) {
        return sessionUserIds.get(wsSessionId);
    }

    public void bindAgent(String wsSessionId, String agentName) {
        agentSessions
                .computeIfAbsent(agentName, k -> ConcurrentHashMap.newKeySet())
                .add(wsSessionId);
        log.debug("Agent '{}' bound to session {}", agentName, wsSessionId);
    }

    public void unregister(String wsSessionId) {
        sessionSinks.remove(wsSessionId);
        sessionUserIds.remove(wsSessionId);
        agentSessions.values().forEach(set -> set.remove(wsSessionId));
        agentSessions.entrySet().removeIf(e -> e.getValue().isEmpty());
        if (meterRegistry != null) {
            meterRegistry.counter("ws.connections.closed").increment();
        }
        log.debug("Session unregistered: {}", wsSessionId);
    }

    public boolean isAgentOnline(String agentName) {
        Set<String> sids = agentSessions.get(agentName);
        if (sids == null || sids.isEmpty()) return false;
        return sids.stream().anyMatch(sessionSinks::containsKey);
    }

    public boolean pushToAgent(String agentName, String eventType, Map<String, Object> payload) {
        return pushToAllAgentSessions(agentName, eventType, payload) > 0;
    }

    public int pushToAllAgentSessions(String agentName, String eventType, Map<String, Object> payload) {
        Set<String> sids = agentSessions.get(agentName);
        if (sids == null || sids.isEmpty()) return 0;

        EventFrame frame = new EventFrame(eventType, payload, seqGenerator.incrementAndGet());
        int delivered = 0;
        Iterator<String> iter = sids.iterator();
        while (iter.hasNext()) {
            String sid = iter.next();
            Sinks.Many<GatewayFrame> sink = sessionSinks.get(sid);
            if (sink == null) {
                iter.remove();
                continue;
            }
            if (sink.tryEmitNext(frame).isSuccess()) {
                delivered++;
            }
        }
        return delivered;
    }

    public boolean pushToSession(String wsSessionId, String eventType, Map<String, Object> payload) {
        Sinks.Many<GatewayFrame> sink = sessionSinks.get(wsSessionId);
        if (sink == null) return false;
        EventFrame frame = new EventFrame(eventType, payload, seqGenerator.incrementAndGet());
        return sink.tryEmitNext(frame).isSuccess();
    }

    public int pushToUser(Long userId, String eventType, Map<String, Object> payload) {
        if (userId == null) return 0;
        EventFrame frame = new EventFrame(eventType, payload, seqGenerator.incrementAndGet());
        int delivered = 0;
        for (Map.Entry<String, Long> entry : sessionUserIds.entrySet()) {
            if (userId.equals(entry.getValue())) {
                Sinks.Many<GatewayFrame> sink = sessionSinks.get(entry.getKey());
                if (sink != null && sink.tryEmitNext(frame).isSuccess()) {
                    delivered++;
                }
            }
        }
        return delivered;
    }

    public void broadcast(String eventType, Map<String, Object> payload) {
        if (sessionSinks.isEmpty()) return;
        EventFrame frame = new EventFrame(eventType, payload, seqGenerator.incrementAndGet());
        sessionSinks.values().forEach(sink -> sink.tryEmitNext(frame));
    }
}
