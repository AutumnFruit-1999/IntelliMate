package com.atm.intellimate.agent.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Gates tool execution for tools that require human approval.
 * When a tool call needs approval, a Sinks.One is created and the caller
 * suspends until the user responds via WebSocket.
 *
 * Per-run lifecycle. The approval sink map is keyed by toolCallId.
 */
public class ToolApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalGate.class);

    private final Set<String> approvalRequiredTools;
    private final ConcurrentMap<String, Sinks.One<ApprovalDecision>> pendingApprovals = new ConcurrentHashMap<>();

    public record ApprovalDecision(boolean approved, String modifiedArguments) {}

    public ToolApprovalGate(Set<String> approvalRequiredTools) {
        this.approvalRequiredTools = approvalRequiredTools != null ? approvalRequiredTools : Set.of();
    }

    /** Returns true if the given tool requires human approval before execution. */
    public boolean requiresApproval(String toolName) {
        return approvalRequiredTools.contains(toolName);
    }

    /**
     * Creates a pending approval and returns a Mono that completes when the user responds.
     * The caller should emit an ApprovalRequired AgentEvent before subscribing.
     */
    public Mono<ApprovalDecision> requestApproval(String toolCallId) {
        Sinks.One<ApprovalDecision> sink = Sinks.one();
        pendingApprovals.put(toolCallId, sink);
        return sink.asMono();
    }

    /**
     * Called when the user responds to an approval request (via WebSocket).
     * Resumes the suspended tool execution.
     */
    public void resolve(String toolCallId, boolean approved, String modifiedArguments) {
        Sinks.One<ApprovalDecision> sink = pendingApprovals.remove(toolCallId);
        if (sink != null) {
            sink.tryEmitValue(new ApprovalDecision(approved, modifiedArguments));
        } else {
            log.warn("No pending approval found for toolCallId={}", toolCallId);
        }
    }

    public boolean hasPendingApprovals() {
        return !pendingApprovals.isEmpty();
    }
}
