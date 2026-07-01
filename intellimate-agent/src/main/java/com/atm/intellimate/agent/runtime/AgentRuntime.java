package com.atm.intellimate.agent.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    private final RunQueueManager runQueueManager;
    private final AgentMemoryLifecycle agentMemoryLifecycle;
    private final AgentLoopExecutor agentLoopExecutor;

    private final ConcurrentMap<Long, ToolApprovalGate> sessionApprovalGates = new ConcurrentHashMap<>();
    private final Set<Long> pausedPlanIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, org.reactivestreams.Subscription> activeWsRuns = new ConcurrentHashMap<>();

    private static final ConcurrentMap<Long, AgentEvent.MemorySnapshot> latestSnapshots = new ConcurrentHashMap<>();

    public AgentRuntime(RunQueueManager runQueueManager,
                        AgentMemoryLifecycle agentMemoryLifecycle,
                        AgentLoopExecutor agentLoopExecutor) {
        this.runQueueManager = runQueueManager;
        this.agentMemoryLifecycle = agentMemoryLifecycle;
        this.agentLoopExecutor = agentLoopExecutor;
    }

    public Flux<AgentEvent> dispatch(AgentRunRequest request) {
        return runQueueManager.enqueue(request.sessionId(), () ->
                agentLoopExecutor.executeAgentLoop(request,
                        new AgentLoopCallbacks(pausedPlanIds, latestSnapshots, sessionApprovalGates, this::dispatch)));
    }

    public static AgentEvent.MemorySnapshot getLatestSnapshot(Long sessionId) {
        return latestSnapshots.get(sessionId);
    }

    /**
     * Called on WebSocket disconnect to flush deferred episodic memory for the session.
     * Only stores if chunks > 4 and no prior episodic was generated during this session.
     */
    public boolean flushDeferredEpisodicMemory(Long sessionId) {
        return agentMemoryLifecycle.flushDeferredEpisodicMemory(sessionId);
    }

    public void registerWsRun(String wsSessionId, org.reactivestreams.Subscription subscription) {
        activeWsRuns.put(wsSessionId, subscription);
    }

    public void unregisterWsRun(String wsSessionId) {
        activeWsRuns.remove(wsSessionId);
    }

    public void cancelByWsSession(String wsSessionId) {
        org.reactivestreams.Subscription sub = activeWsRuns.remove(wsSessionId);
        if (sub != null) {
            sub.cancel();
        }
    }

    public void signalPlanPaused(Long messageId) {
        if (messageId != null) {
            pausedPlanIds.add(messageId);
        }
    }

    public void clearPlanPaused(Long messageId) {
        if (messageId != null) {
            pausedPlanIds.remove(messageId);
        }
    }

    /**
     * Resolves a pending tool approval for a given session.
     * Called by MessagePipeline when the user responds to an approval request.
     */
    public void resolveApproval(Long sessionId, String toolCallId, boolean approved, String modifiedArguments) {
        ToolApprovalGate gate = sessionApprovalGates.get(sessionId);
        if (gate != null) {
            gate.resolve(toolCallId, approved, modifiedArguments);
        } else {
            log.warn("No approval gate found for sessionId={}, toolCallId={}", sessionId, toolCallId);
        }
    }
}
