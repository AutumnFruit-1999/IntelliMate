package com.atm.intellimate.agent.tools.bridge;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.Set;

public interface BridgeToolProvider {

    boolean isConnected(String bridgeNodeName);

    Set<String> getRegisteredTools(String bridgeNodeName);

    ToolCallback createBridgeCallback(String bridgeNodeName, ToolDefinition originalDefinition);
}
