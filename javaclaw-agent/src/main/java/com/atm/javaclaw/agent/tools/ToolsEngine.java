package com.atm.javaclaw.agent.tools;

import com.atm.javaclaw.agent.tools.dynamic.DynamicToolProvider;
import com.atm.javaclaw.agent.tools.mcp.McpToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Composite registry of all available tools (builtin + dynamic + MCP).
 * Provides tool callbacks to the AgentRuntime with optional per-agent filtering.
 */
@Component
public class ToolsEngine {

    private static final Logger log = LoggerFactory.getLogger(ToolsEngine.class);

    private final ToolCallback[] builtinCallbacks;
    private final DynamicToolProvider dynamicToolProvider;
    private final McpToolProvider mcpToolProvider;
    private volatile ToolCallback[] allToolCallbacks;

    public ToolsEngine(List<ToolCallbackProvider> providers,
                       @Autowired(required = false) DynamicToolProvider dynamicToolProvider,
                       @Autowired(required = false) McpToolProvider mcpToolProvider) {
        this.builtinCallbacks = providers.stream()
                .flatMap(p -> Arrays.stream(p.getToolCallbacks()))
                .toArray(ToolCallback[]::new);
        this.dynamicToolProvider = dynamicToolProvider;
        this.mcpToolProvider = mcpToolProvider;
        refresh();
    }

    /**
     * Rebuild the unified callback registry by merging builtin + dynamic + MCP tools.
     * Called after custom tool CRUD or MCP server connection changes.
     */
    public synchronized void refresh() {
        List<ToolCallback> all = new ArrayList<>(Arrays.asList(builtinCallbacks));
        int dynamicCount = 0;
        int mcpCount = 0;

        if (dynamicToolProvider != null) {
            List<ToolCallback> dynamic = dynamicToolProvider.getDynamicCallbacks();
            all.addAll(dynamic);
            dynamicCount = dynamic.size();
        }

        if (mcpToolProvider != null) {
            ToolCallback[] mcpCallbacks = mcpToolProvider.getAllCallbacks();
            all.addAll(Arrays.asList(mcpCallbacks));
            mcpCount = mcpCallbacks.length;
        }

        this.allToolCallbacks = all.toArray(new ToolCallback[0]);

        log.info("ToolsEngine refreshed: {} builtin + {} dynamic + {} mcp = {} total",
                builtinCallbacks.length, dynamicCount, mcpCount, allToolCallbacks.length);

        if (log.isDebugEnabled()) {
            log.debug("Registered tools: {}",
                    Arrays.stream(allToolCallbacks).map(cb -> cb.getToolDefinition().name()).toList());
        }
    }

    public ToolCallback[] getToolCallbacks() {
        return allToolCallbacks;
    }

    /**
     * Filter builtin + custom + MCP tools together (legacy, treats all tools uniformly).
     */
    public ToolCallback[] getToolCallbacksFor(String toolsEnabledSpec) {
        return getToolCallbacksFor(toolsEnabledSpec, null);
    }

    /**
     * Filter builtin/custom tools by {@code toolsEnabledSpec} and MCP tools by {@code mcpToolsEnabledSpec} independently.
     *
     * @param toolsEnabledSpec     builtin+custom filter: null/"full" → all, profile name, or JSON array
     * @param mcpToolsEnabledSpec  MCP filter: null → none, "full" → all, JSON array → by name
     */
    public ToolCallback[] getToolCallbacksFor(String toolsEnabledSpec, String mcpToolsEnabledSpec) {
        ToolCallback[] builtinCustom = getBuiltinCustomCallbacksFor(toolsEnabledSpec);
        ToolCallback[] mcp = getMcpCallbacksFor(mcpToolsEnabledSpec);
        if (mcp.length == 0) {
            return builtinCustom;
        }
        ToolCallback[] merged = new ToolCallback[builtinCustom.length + mcp.length];
        System.arraycopy(builtinCustom, 0, merged, 0, builtinCustom.length);
        System.arraycopy(mcp, 0, merged, builtinCustom.length, mcp.length);
        return merged;
    }

    private ToolCallback[] getBuiltinCustomCallbacksFor(String toolsEnabledSpec) {
        ToolCallback[] source = getBuiltinCustomCallbacks();
        if (toolsEnabledSpec == null || toolsEnabledSpec.isBlank() || "full".equalsIgnoreCase(toolsEnabledSpec.trim())) {
            return source;
        }

        ToolProfile profile = ToolProfile.fromString(toolsEnabledSpec.trim());
        if (profile != null) {
            return filterFromSource(source, profile.getAllowedTools());
        }

        Set<String> names = parseToolNames(toolsEnabledSpec);
        if (names.isEmpty()) {
            return source;
        }
        return filterFromSource(source, names);
    }

    private ToolCallback[] getMcpCallbacksFor(String mcpToolsEnabledSpec) {
        if (mcpToolProvider == null) {
            return new ToolCallback[0];
        }
        ToolCallback[] allMcp = mcpToolProvider.getAllCallbacks();
        if (allMcp.length == 0) {
            return allMcp;
        }
        if (mcpToolsEnabledSpec == null || mcpToolsEnabledSpec.isBlank()) {
            return new ToolCallback[0];
        }
        if ("full".equalsIgnoreCase(mcpToolsEnabledSpec.trim())) {
            return allMcp;
        }
        Set<String> names = parseToolNames(mcpToolsEnabledSpec);
        if (names.isEmpty()) {
            return new ToolCallback[0];
        }
        return filterFromSource(allMcp, names);
    }

    private ToolCallback[] getBuiltinCustomCallbacks() {
        List<ToolCallback> result = new ArrayList<>(Arrays.asList(builtinCallbacks));
        if (dynamicToolProvider != null) {
            result.addAll(dynamicToolProvider.getDynamicCallbacks());
        }
        return result.toArray(new ToolCallback[0]);
    }

    private ToolCallback[] filterFromSource(ToolCallback[] source, Set<String> allowedNames) {
        if (allowedNames.isEmpty()) {
            return new ToolCallback[0];
        }
        return Arrays.stream(source)
                .filter(cb -> allowedNames.contains(cb.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }

    public List<Map<String, String>> getToolMetadata() {
        return Arrays.stream(allToolCallbacks).map(cb -> {
            String name = cb.getToolDefinition().name();
            String desc = cb.getToolDefinition().description();
            String source = detectSource(name);
            ToolGroup group = ToolGroup.groupOf(name);

            String groupName;
            String groupDisplayName;
            if (group != null) {
                groupName = group.name();
                groupDisplayName = group.getDisplayName();
            } else if ("mcp".equals(source)) {
                String serverName = extractMcpServerName(name);
                groupName = "MCP:" + serverName;
                groupDisplayName = "MCP: " + serverName;
            } else if ("custom".equals(source)) {
                groupName = "CUSTOM";
                groupDisplayName = "自定义工具";
            } else {
                groupName = "";
                groupDisplayName = "";
            }

            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("name", name);
            meta.put("description", desc != null ? desc : "");
            meta.put("source", source);
            meta.put("group", groupName);
            meta.put("groupDisplayName", groupDisplayName);
            return meta;
        }).toList();
    }

    /**
     * Return all groups (builtin enum + CUSTOM + MCP per-server).
     */
    public List<Map<String, Object>> getAllGroups() {
        List<Map<String, Object>> groups = new ArrayList<>();

        for (ToolGroup g : ToolGroup.values()) {
            groups.add(Map.of(
                    "name", g.name(),
                    "displayName", g.getDisplayName(),
                    "tools", List.copyOf(g.getToolNames()),
                    "source", "builtin"
            ));
        }

        if (dynamicToolProvider != null) {
            List<String> customToolNames = dynamicToolProvider.getDynamicCallbacks().stream()
                    .map(cb -> cb.getToolDefinition().name())
                    .toList();
            if (!customToolNames.isEmpty()) {
                groups.add(Map.of(
                        "name", "CUSTOM",
                        "displayName", "自定义工具",
                        "tools", customToolNames,
                        "source", "custom"
                ));
            }
        }

        if (mcpToolProvider != null) {
            mcpToolProvider.getServerToolNames().forEach((serverName, toolNames) -> {
                if (!toolNames.isEmpty()) {
                    groups.add(Map.of(
                            "name", "MCP:" + serverName,
                            "displayName", "MCP: " + serverName,
                            "tools", toolNames,
                            "source", "mcp"
                    ));
                }
            });
        }

        return groups;
    }

    public int getBuiltinCount() {
        return builtinCallbacks.length;
    }

    public int getDynamicCount() {
        return dynamicToolProvider != null ? dynamicToolProvider.getDynamicCallbacks().size() : 0;
    }

    public int getMcpCount() {
        return mcpToolProvider != null ? mcpToolProvider.getAllCallbacks().length : 0;
    }

    public ToolCallback getCallbackByName(String toolName) {
        return Arrays.stream(allToolCallbacks)
                .filter(cb -> cb.getToolDefinition().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + toolName));
    }

    private String detectSource(String toolName) {
        if (toolName.startsWith("mcp_")) {
            return "mcp";
        }
        Set<String> builtinNames = Set.of(Arrays.stream(builtinCallbacks)
                .map(cb -> cb.getToolDefinition().name())
                .toArray(String[]::new));
        return builtinNames.contains(toolName) ? "builtin" : "custom";
    }

    private String extractMcpServerName(String toolName) {
        if (!toolName.startsWith("mcp_")) return "";
        String rest = toolName.substring(4);
        int idx = rest.indexOf('_');
        return idx > 0 ? rest.substring(0, idx) : rest;
    }


    private Set<String> parseToolNames(String json) {
        try {
            String cleaned = json.replaceAll("[\\[\\]\"\\s]", "");
            if (cleaned.isEmpty()) return Set.of();
            return Set.of(cleaned.split(","));
        } catch (Exception e) {
            log.warn("Failed to parse tool names from: {}", json, e);
            return Set.of();
        }
    }
}
