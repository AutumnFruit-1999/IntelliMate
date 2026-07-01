package com.atm.intellimate.gateway.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

public class BridgeToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(BridgeToolCallback.class);

    private final ToolDefinition definition;
    private final BridgeNodeSession nodeSession;

    public BridgeToolCallback(ToolDefinition definition, BridgeNodeSession nodeSession) {
        this.definition = definition;
        this.nodeSession = nodeSession;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return definition;
    }

    @Override
    public String call(String toolInput) {
        String toolName = definition.name();

        BridgeProtocol.ToolResult result = nodeSession.callTool(toolName, toolInput);

        if (result.success()) {
            return result.result() != null ? result.result() : "";
        } else {
            String error = result.error() != null ? result.error() : "Unknown bridge error";
            log.warn("Bridge tool error: node={}, tool={}, error={}",
                    nodeSession.getNodeName(), toolName, error);
            return "Error (bridge): " + error;
        }
    }
}
