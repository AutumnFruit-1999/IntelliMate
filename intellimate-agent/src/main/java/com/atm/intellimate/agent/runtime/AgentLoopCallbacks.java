package com.atm.intellimate.agent.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import reactor.core.publisher.Flux;

record AgentLoopCallbacks(
        Set<Long> pausedPlanIds,
        ConcurrentMap<Long, AgentEvent.MemorySnapshot> latestSnapshots,
        ConcurrentMap<Long, ToolApprovalGate> sessionApprovalGates,
        Function<AgentRunRequest, Flux<AgentEvent>> dispatchFn
) {}
