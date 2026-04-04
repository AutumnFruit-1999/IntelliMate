package com.atm.javaclaw.agent.runtime;

import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Tracks estimated token usage against the model's context window limit.
 *
 * Strategy: prefer real token counts from the API (updated after each LLM call),
 * fall back to character-based estimation for incremental updates between calls.
 *
 * Per-run lifecycle — create one instance per agent execution.
 */
public class ContextWindowTracker {

    private static final double CHARS_PER_TOKEN_FALLBACK = 3.5;

    private final int maxContextTokens;
    private int lastKnownTotalTokens = 0;
    private int estimatedAdditionalChars = 0;

    public ContextWindowTracker(int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    /** Called after each LLM response with real token usage from the API. */
    public void updateFromApiUsage(long totalTokens) {
        this.lastKnownTotalTokens = (int) totalTokens;
        this.estimatedAdditionalChars = 0;
    }

    /** Called when tool results are added to history between LLM calls. */
    public void addToolResultChars(int charCount) {
        this.estimatedAdditionalChars += charCount;
    }

    public int estimatedTotalTokens() {
        return lastKnownTotalTokens
                + (int) (estimatedAdditionalChars / CHARS_PER_TOKEN_FALLBACK);
    }

    public int remainingTokens() {
        return maxContextTokens - estimatedTotalTokens();
    }

    public boolean isNearLimit(double threshold) {
        return estimatedTotalTokens() > maxContextTokens * threshold;
    }

    public boolean isOverLimit() {
        return estimatedTotalTokens() > maxContextTokens;
    }

    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    /**
     * Recalculate from the current history (used after context condensation).
     * Falls back to character estimation since we don't have API usage data
     * for the condensed history.
     */
    public void recalculate(List<Message> history) {
        int totalChars = 0;
        for (Message msg : history) {
            if (msg.getText() != null) {
                totalChars += msg.getText().length();
            }
        }
        this.lastKnownTotalTokens = (int) (totalChars / CHARS_PER_TOKEN_FALLBACK);
        this.estimatedAdditionalChars = 0;
    }
}
