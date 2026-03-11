package com.atm.javaclaw.agent.tools.mcp;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * Wraps a ToolCallback and prefixes its name to avoid collisions.
 * E.g. MCP server "filesystem" tool "readFile" becomes "mcp_filesystem_readFile".
 */
public class PrefixedToolCallback implements ToolCallback {

    private final ToolDefinition toolDefinition;
    private final ToolCallback delegate;

    public PrefixedToolCallback(String prefix, ToolCallback delegate) {
        this.delegate = delegate;
        ToolDefinition original = delegate.getToolDefinition();
        this.toolDefinition = ToolDefinition.builder()
                .name(prefix + original.name())
                .description(original.description())
                .inputSchema(original.inputSchema())
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }
}
