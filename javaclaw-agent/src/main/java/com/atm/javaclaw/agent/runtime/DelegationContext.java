package com.atm.javaclaw.agent.runtime;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks delegation state across recursive agent invocations.
 * Passed explicitly through AgentRunRequest — no ThreadLocal.
 */
public record DelegationContext(
        Long parentSessionId,
        String parentAgentName,
        int nestingDepth,
        int maxNestingDepth,
        int maxParallel,
        int maxDelegations,
        AtomicInteger delegationCount
) {
    public static final int DEFAULT_MAX_NESTING_DEPTH = 2;
    public static final int DEFAULT_MAX_PARALLEL = 4;
    public static final int DEFAULT_MAX_DELEGATIONS = 10;

    public static DelegationContext root(Long sessionId, String agentName) {
        return new DelegationContext(
                sessionId, agentName,
                0, DEFAULT_MAX_NESTING_DEPTH,
                DEFAULT_MAX_PARALLEL, DEFAULT_MAX_DELEGATIONS,
                new AtomicInteger(0));
    }

    public DelegationContext incrementDepth() {
        return new DelegationContext(
                parentSessionId, parentAgentName,
                nestingDepth + 1, maxNestingDepth,
                maxParallel, maxDelegations,
                delegationCount);
    }

    public boolean canDelegate() {
        return nestingDepth < maxNestingDepth
                && delegationCount.get() < maxDelegations;
    }

    public int incrementAndGetCount() {
        return delegationCount.incrementAndGet();
    }
}
