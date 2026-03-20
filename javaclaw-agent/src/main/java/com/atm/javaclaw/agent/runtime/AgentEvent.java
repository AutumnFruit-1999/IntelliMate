package com.atm.javaclaw.agent.runtime;

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
}
