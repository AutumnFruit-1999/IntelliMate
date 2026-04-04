package com.atm.javaclaw.agent.tools;

import org.springframework.stereotype.Component;

/**
 * ThreadLocal-based holder for the current agent session ID.
 * Set by AgentRuntime before executing the loop, used by tools (e.g. WritePlanTool)
 * that need to know which session they are operating in.
 */
@Component
public class AgentSessionContext {

    private static final ThreadLocal<Long> CURRENT_SESSION_ID = new ThreadLocal<>();

    public void set(Long sessionId) {
        CURRENT_SESSION_ID.set(sessionId);
    }

    public Long getCurrentSessionId() {
        return CURRENT_SESSION_ID.get();
    }

    public void clear() {
        CURRENT_SESSION_ID.remove();
    }
}
