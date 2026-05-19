package com.atm.intellimate.gateway.bridge;

import com.atm.intellimate.agent.tools.bridge.BridgeToolProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class BridgeToolProviderImpl implements BridgeToolProvider {

    private final BridgeNodeRegistry registry;

    public BridgeToolProviderImpl(BridgeNodeRegistry registry) {
        this.registry = registry;
    }

    @Override
    public boolean isConnected(String bridgeNodeName) {
        return registry.isConnected(bridgeNodeName);
    }

    @Override
    public Set<String> getRegisteredTools(String bridgeNodeName) {
        return registry.getRegisteredTools(bridgeNodeName);
    }

    @Override
    public ToolCallback createBridgeCallback(String bridgeNodeName, ToolDefinition originalDefinition) {
        BridgeNodeSession session = registry.getSession(bridgeNodeName);
        if (session == null) {
            throw new IllegalStateException("Bridge node not connected: " + bridgeNodeName);
        }
        return new BridgeToolCallback(originalDefinition, session);
    }
}
