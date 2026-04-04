package com.atm.javaclaw.agent.runtime;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Compresses old ToolResponseMessage contents to reclaim context window space.
 *
 * Only ToolResponseMessages outside the "recent window" are truncated.
 * SystemMessage, UserMessage, AssistantMessage are never modified.
 *
 * Per-run lifecycle — create one instance per agent execution.
 */
public class ContextCondenser {

    private static final int DEFAULT_KEEP_RECENT = 20;
    private static final int DEFAULT_SUMMARY_LENGTH = 200;
    private static final int DEFAULT_MIN_TURNS_BETWEEN = 5;

    private final int keepRecentMessages;
    private final int summaryLength;
    private final int minTurnsBetween;
    private int lastCondensedAtTurn = -100;

    public ContextCondenser() {
        this(DEFAULT_KEEP_RECENT, DEFAULT_SUMMARY_LENGTH, DEFAULT_MIN_TURNS_BETWEEN);
    }

    public ContextCondenser(int keepRecentMessages, int summaryLength, int minTurnsBetween) {
        this.keepRecentMessages = keepRecentMessages;
        this.summaryLength = summaryLength;
        this.minTurnsBetween = minTurnsBetween;
    }

    /**
     * Checks whether condensation should trigger.
     *
     * @return true if token usage exceeds threshold AND enough turns have passed
     */
    public boolean shouldCondense(int currentTurn, ContextWindowTracker tracker) {
        if (!tracker.isNearLimit(0.70)) {
            return false;
        }
        return currentTurn - lastCondensedAtTurn >= minTurnsBetween;
    }

    /**
     * Condenses old ToolResponseMessages in the history by truncating their content.
     * Returns a new list — does not mutate the input.
     */
    public List<Message> condense(List<Message> history, int currentTurn) {
        this.lastCondensedAtTurn = currentTurn;

        if (history.size() <= keepRecentMessages) {
            return new ArrayList<>(history);
        }

        List<Message> condensed = new ArrayList<>(history.size());
        int cutoff = history.size() - keepRecentMessages;

        for (int i = 0; i < history.size(); i++) {
            Message msg = history.get(i);

            if (i < cutoff && msg instanceof ToolResponseMessage trm) {
                List<ToolResponseMessage.ToolResponse> originalResponses = trm.getResponses();
                List<ToolResponseMessage.ToolResponse> condensedResponses = new ArrayList<>();
                for (ToolResponseMessage.ToolResponse resp : originalResponses) {
                    String content = resp.responseData();
                    if (content != null && content.length() > summaryLength) {
                        content = content.substring(0, summaryLength)
                                + "... [condensed: " + resp.responseData().length() + " -> " + summaryLength + " chars]";
                    }
                    condensedResponses.add(new ToolResponseMessage.ToolResponse(
                            resp.id(), resp.name(), content));
                }
                condensed.add(new ToolResponseMessage(condensedResponses));
            } else {
                condensed.add(msg);
            }
        }
        return condensed;
    }
}
