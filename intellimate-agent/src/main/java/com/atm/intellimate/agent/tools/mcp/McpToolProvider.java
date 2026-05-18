package com.atm.intellimate.agent.tools.mcp;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

/**
 * SPI for providing tools discovered from MCP Servers.
 * The implementation lives in intellimate-gateway where DB + MCP Client access is available.
 */
public interface McpToolProvider {

    ToolCallback[] getAllCallbacks();

    /**
     * Returns a map of server name -> list of prefixed tool names,
     * used by ToolsEngine to build per-server groups.
     */
    Map<String, List<String>> getServerToolNames();
}
