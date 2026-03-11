package com.atm.javaclaw.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Registry of all available tools. Provides tool callbacks to the AgentRuntime
 * for injection into ChatClient calls, with optional per-agent filtering.
 */
@Component
public class ToolsEngine {

    private static final Logger log = LoggerFactory.getLogger(ToolsEngine.class);

    private final ToolCallback[] allToolCallbacks;

    public ToolsEngine(List<ToolCallbackProvider> providers) {
        this.allToolCallbacks = providers.stream()
                .flatMap(p -> java.util.Arrays.stream(p.getToolCallbacks()))
                .toArray(ToolCallback[]::new);
        log.info("ToolsEngine initialized with {} tool(s): {}", allToolCallbacks.length,
                Arrays.stream(allToolCallbacks).map(cb -> cb.getToolDefinition().name()).toList());
    }

    /**
     * Get all tool callbacks (no filtering).
     */
    public ToolCallback[] getToolCallbacks() {
        return allToolCallbacks;
    }

    /**
     * Get tool callbacks filtered by a tools_enabled specification.
     * The spec can be:
     *   - null or "full" → all tools
     *   - a profile name ("coding", "messaging", "minimal") → preset filter
     *   - a JSON array of tool names → explicit filter
     */
    public ToolCallback[] getToolCallbacksFor(String toolsEnabledSpec) {
        if (toolsEnabledSpec == null || toolsEnabledSpec.isBlank() || "full".equalsIgnoreCase(toolsEnabledSpec.trim())) {
            return allToolCallbacks;
        }

        ToolProfile profile = ToolProfile.fromString(toolsEnabledSpec.trim());
        if (profile != null) {
            return filterByNames(profile.getAllowedTools());
        }

        // Treat as JSON array: ["exec","readFile"]
        Set<String> names = parseToolNames(toolsEnabledSpec);
        if (names.isEmpty()) {
            return allToolCallbacks;
        }
        return filterByNames(names);
    }

    private ToolCallback[] filterByNames(Set<String> allowedNames) {
        if (allowedNames.isEmpty()) {
            return new ToolCallback[0];
        }
        return Arrays.stream(allToolCallbacks)
                .filter(cb -> allowedNames.contains(cb.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
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
