package com.atm.javaclaw.agent.skills;

import java.util.Set;

/**
 * ThreadLocal holder for the current agent's allowed skill group names.
 * Set by AgentRuntime before execution, read by ListSkillsByGroupTool for filtering.
 */
public final class SkillGroupContext {

    private static final ThreadLocal<Set<String>> ALLOWED_GROUPS = new ThreadLocal<>();

    private SkillGroupContext() {}

    /**
     * Set allowed groups for the current agent execution.
     * @param groups null means no restriction (show all), empty set means none allowed
     */
    public static void set(Set<String> groups) {
        ALLOWED_GROUPS.set(groups);
    }

    /**
     * Get allowed groups. Returns null if unrestricted (full access).
     */
    public static Set<String> get() {
        return ALLOWED_GROUPS.get();
    }

    public static void clear() {
        ALLOWED_GROUPS.remove();
    }
}
