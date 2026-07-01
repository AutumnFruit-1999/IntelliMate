package com.atm.intellimate.gateway.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BridgeNodeRegistry {

    private static final Logger log = LoggerFactory.getLogger(BridgeNodeRegistry.class);

    private final Map<String, BridgeNodeSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, String> wsSessionToNode = new ConcurrentHashMap<>();

    public void register(String nodeName, BridgeNodeSession session) {
        BridgeNodeSession prev = sessions.put(nodeName, session);
        if (prev != null) {
            log.warn("Replacing existing bridge session for node: {}", nodeName);
            wsSessionToNode.remove(prev.getWebSocketSessionId());
            prev.close();
        }
        wsSessionToNode.put(session.getWebSocketSessionId(), nodeName);
    }

    public void unregister(String nodeName) {
        BridgeNodeSession session = sessions.remove(nodeName);
        if (session != null) {
            wsSessionToNode.remove(session.getWebSocketSessionId());
            session.close();
        }
    }

    public BridgeNodeSession getSession(String nodeName) {
        return sessions.get(nodeName);
    }

    public BridgeNodeSession getSessionByWebSocketId(String webSocketSessionId) {
        String nodeName = wsSessionToNode.get(webSocketSessionId);
        return nodeName != null ? sessions.get(nodeName) : null;
    }

    public boolean isConnected(String nodeName) {
        return sessions.containsKey(nodeName);
    }

    public Set<String> getRegisteredTools(String nodeName) {
        BridgeNodeSession session = sessions.get(nodeName);
        return session != null ? session.getRegisteredTools() : Set.of();
    }

    public Collection<BridgeNodeSession> getAllSessions() {
        return sessions.values();
    }
}
