package com.atm.intellimate.agent.tools;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Predefined tool profiles that define which tools are available per agent.
 */
public enum ToolProfile {

    FULL(Set.of("exec", "readFile", "writeFile", "editFile", "webSearch", "webFetch")),
    CODING(Set.of("exec", "readFile", "writeFile", "editFile")),
    MESSAGING(Set.of("webSearch", "webFetch")),
    MINIMAL(Set.of());

    private final Set<String> allowedTools;

    ToolProfile(Set<String> allowedTools) {
        this.allowedTools = allowedTools;
    }

    public Set<String> getAllowedTools() {
        return allowedTools;
    }

    /**
     * Resolve a profile from a string (case-insensitive), or null if not a preset name.
     */
    public static ToolProfile fromString(String name) {
        if (name == null) return null;
        try {
            return valueOf(name.toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
