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
            String arguments
    ) implements AgentEvent {}

    /** Result of a tool execution. */
    record ToolResult(
            String toolCallId,
            String name,
            String result,
            boolean success
    ) implements AgentEvent {}

    /** Agent Loop completed normally. */
    record Done(String fullText, int totalTurns) implements AgentEvent {}

    /** Agent Loop encountered an error. */
    record Error(String message) implements AgentEvent {}
}
