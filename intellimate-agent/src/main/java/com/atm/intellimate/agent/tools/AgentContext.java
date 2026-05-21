package com.atm.intellimate.agent.tools;

public final class AgentContext {

    private static final ThreadLocal<Long> AGENT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> AGENT_NAME = new ThreadLocal<>();

    private AgentContext() {}

    public static void set(Long agentId, String agentName) {
        AGENT_ID.set(agentId);
        AGENT_NAME.set(agentName);
    }

    public static Long getAgentId() {
        return AGENT_ID.get();
    }

    public static String getAgentName() {
        return AGENT_NAME.get();
    }

    public static void clear() {
        AGENT_ID.remove();
        AGENT_NAME.remove();
    }
}
