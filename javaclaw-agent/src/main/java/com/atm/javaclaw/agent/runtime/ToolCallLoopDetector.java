package com.atm.javaclaw.agent.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

/**
 * Sliding-window detector for tool call loops.
 * Tracks recent tool call signatures (toolName::hash(arguments)) and reports
 * OK / WARN / TERMINATE based on repetition count within the window.
 *
 * Per-run lifecycle — create one instance per agent execution.
 */
public class ToolCallLoopDetector {

    private final int windowSize;
    private final int warnThreshold;
    private final int terminateThreshold;
    private final Set<String> excludedTools;
    private final Deque<String> recentSignatures;

    public ToolCallLoopDetector(int windowSize, int warnThreshold, int terminateThreshold,
                                Set<String> excludedTools) {
        this.windowSize = windowSize;
        this.warnThreshold = warnThreshold;
        this.terminateThreshold = terminateThreshold;
        this.excludedTools = excludedTools != null ? excludedTools : Set.of();
        this.recentSignatures = new ArrayDeque<>(windowSize);
    }

    public ToolCallLoopDetector() {
        this(8, 3, 5, Set.of());
    }

    public enum LoopStatus { OK, WARN, TERMINATE }

    public LoopStatus check(String toolName, String arguments) {
        if (excludedTools.contains(toolName)) {
            return LoopStatus.OK;
        }

        String signature = toolName + "::" + arguments.hashCode();

        if (recentSignatures.size() >= windowSize) {
            recentSignatures.pollFirst();
        }
        recentSignatures.addLast(signature);

        long count = recentSignatures.stream()
                .filter(s -> s.equals(signature))
                .count();

        if (count >= terminateThreshold) {
            return LoopStatus.TERMINATE;
        } else if (count >= warnThreshold) {
            return LoopStatus.WARN;
        }
        return LoopStatus.OK;
    }

    public void reset() {
        recentSignatures.clear();
    }
}
