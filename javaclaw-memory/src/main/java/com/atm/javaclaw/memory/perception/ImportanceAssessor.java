package com.atm.javaclaw.memory.perception;

import com.atm.javaclaw.memory.model.ContentCategory;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Evaluates the importance of a piece of content using rule-based heuristics.
 * In Plan mode, importance is boosted/reduced by task alignment.
 */
public class ImportanceAssessor {

    private static final Pattern ERROR_PATTERN = Pattern.compile(
            "(?i)(exception|error|failed|failure|exit\\s*code\\s*[^0]|non-zero|stacktrace|traceback)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> CONFIRMATION_KEYWORDS = Set.of(
            "文件已保存", "文件已修改", "saved", "done", "success", "ok", "completed"
    );

    /**
     * Assess importance of tool output content.
     */
    public float assess(String content, ContentCategory category, Map<String, String> metadata) {
        if (content == null || content.isBlank()) {
            return 0.2f;
        }

        String exitCode = metadata != null ? metadata.get("exitCode") : null;
        if (exitCode != null && !"0".equals(exitCode)) {
            return 0.9f;
        }
        if (ERROR_PATTERN.matcher(content).find()) {
            return 0.9f;
        }

        String trimmed = content.trim();
        if (trimmed.length() < 30 && CONFIRMATION_KEYWORDS.stream()
                .anyMatch(kw -> trimmed.toLowerCase().contains(kw.toLowerCase()))) {
            return 0.2f;
        }

        if (category == ContentCategory.SEARCH_RESULT) {
            return 0.7f;
        }

        return 0.5f;
    }

    /**
     * Plan-mode importance: apply task_alignment_boost based on current step description.
     */
    public float assessWithPlanContext(String content, ContentCategory category,
                                       Map<String, String> metadata,
                                       String currentStepDesc,
                                       java.util.List<String> completedStepDescs,
                                       java.util.List<String> pendingStepDescs) {
        float base = assess(content, category, metadata);

        if (currentStepDesc == null || currentStepDesc.isBlank()) {
            return base;
        }

        if (hasKeywordOverlap(content, currentStepDesc)) {
            return Math.min(0.9f, base * 1.5f);
        }

        if (completedStepDescs != null) {
            for (String desc : completedStepDescs) {
                if (hasKeywordOverlap(content, desc)) {
                    return 0.3f;
                }
            }
        }

        if (pendingStepDescs != null) {
            for (String desc : pendingStepDescs) {
                if (hasKeywordOverlap(content, desc)) {
                    return 0.7f;
                }
            }
        }

        return 0.2f;
    }

    private boolean hasKeywordOverlap(String content, String description) {
        if (content == null || description == null) return false;
        String[] descWords = description.toLowerCase().split("[\\s,./\\-_]+");
        String lowerContent = content.toLowerCase();
        int matches = 0;
        int meaningfulWords = 0;
        for (String word : descWords) {
            if (word.length() < 3) continue;
            meaningfulWords++;
            if (lowerContent.contains(word)) {
                matches++;
            }
        }
        return meaningfulWords > 0 && (float) matches / meaningfulWords > 0.3f;
    }
}
