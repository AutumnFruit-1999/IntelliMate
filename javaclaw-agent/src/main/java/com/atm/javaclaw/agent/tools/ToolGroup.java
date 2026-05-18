package com.atm.javaclaw.agent.tools;

import java.util.Set;

/**
 * Logical grouping of tools for UI display and batch selection.
 * Mirrors OpenClaw's {@code group:*} concept.
 */
public enum ToolGroup {

    FS("文件系统", Set.of("readFile", "writeFile", "editFile")),
    RUNTIME("运行时", Set.of("exec")),
    WEB("网络", Set.of("webSearch", "webFetch")),
    SKILLS("Skills", Set.of("getSkillContent", "listSkillsByGroup")),
    DELEGATION("委派协作", Set.of("delegateAgent", "handoffToAgent", "delegateAgentsParallel"));

    private final String displayName;
    private final Set<String> toolNames;

    ToolGroup(String displayName, Set<String> toolNames) {
        this.displayName = displayName;
        this.toolNames = toolNames;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Set<String> getToolNames() {
        return toolNames;
    }

    /** Find the group a tool belongs to, or null if ungrouped. */
    public static ToolGroup groupOf(String toolName) {
        for (ToolGroup g : values()) {
            if (g.toolNames.contains(toolName)) return g;
        }
        return null;
    }
}
