package com.atm.intellimate.agent.runtime;

import java.util.List;

/**
 * Events emitted by the Agent Loop during execution.
 * Each variant represents a distinct phase of the agent's reasoning and action cycle.
 */
public sealed interface AgentEvent {

    /** Emitted at the start of each LLM turn. */
    record TurnStart(int turn, int maxTurns) implements AgentEvent {}

    /** A streaming text token from the LLM. */
    record TextChunk(String text) implements AgentEvent {}

    /** The LLM has decided to call a tool. */
    record ToolCall(
            String toolCallId,
            String name,
            String description,
            String arguments,
            int turn
    ) implements AgentEvent {}

    /** Result of a tool execution. */
    record ToolResult(
            String toolCallId,
            String name,
            String result,
            boolean success,
            int turn
    ) implements AgentEvent {}

    /** Agent Loop completed normally. */
    record Done(String fullText, int totalTurns) implements AgentEvent {}

    /** Agent Loop encountered an error. */
    record Error(String message) implements AgentEvent {}

    /** A tool call requires human approval before execution. */
    record ApprovalRequired(
            String toolCallId,
            String toolName,
            String arguments
    ) implements AgentEvent {}

    /** User's approval response for a pending tool call. */
    record ApprovalResponse(
            String toolCallId,
            boolean approved,
            String modifiedArguments
    ) implements AgentEvent {}

    // ───── Plan Mode events ─────

    record PlanStepInfo(int index, String title, String description) {}

    record PlanCreated(
            Long planId, String title, List<PlanStepInfo> steps
    ) implements AgentEvent {}

    record PlanAwaitingApproval(Long planId) implements AgentEvent {}

    record PlanStatusChanged(
            Long planId, String status
    ) implements AgentEvent {}

    record PlanStepStart(Long planId, int stepIndex, String title) implements AgentEvent {}

    record PlanStepDone(
            Long planId, int stepIndex, String status, String resultSummary
    ) implements AgentEvent {}

    record PlanAdjusted(
            Long planId, String adjustType,
            List<PlanStepInfo> currentSteps
    ) implements AgentEvent {}

    record PlanCompleted(Long planId, String status) implements AgentEvent {}

    // ───── Delegation events ─────

    record DelegationStart(
            String workerAgentName, String task, String delegationId
    ) implements AgentEvent {}

    record DelegationProgress(
            String workerAgentName, String delegationId, AgentEvent nestedEvent
    ) implements AgentEvent {}

    record DelegationResult(
            String workerAgentName, String delegationId,
            String result, boolean success, int turnsUsed, long durationMs
    ) implements AgentEvent {}

    // ───── Handoff events ─────

    record HandoffStart(
            String fromAgent, String toAgent, String reason, String contextSummary
    ) implements AgentEvent {}

    // ───── Parallel delegation events ─────

    record ParallelTask(String agentName, String task) {}

    record ParallelStart(
            String parallelGroupId, List<ParallelTask> tasks
    ) implements AgentEvent {}

    record ParallelProgress(
            String parallelGroupId, String agentName, AgentEvent nestedEvent
    ) implements AgentEvent {}

    record ParallelAgentResult(
            String agentName, String result, boolean success, long durationMs
    ) {}

    record ParallelResult(
            String parallelGroupId, List<ParallelAgentResult> results
    ) implements AgentEvent {}

    // ───── Memory events ─────

    record MemorySnapshot(
            int tokenBudget, int tokenUsed, int tokenEstimated, float usageRatio,
            int chunkCount, List<ChunkInfo> chunks
    ) implements AgentEvent {}

    record ChunkInfo(
            String id, String type, String category,
            float importance, int tokens, String contentPreview,
            String createdAt
    ) {}

    record ConsolidationTriggered(
            int chunksSelected, int tokensBefore, int tokensAfter,
            List<String> extractedFacts
    ) implements AgentEvent {}
}
