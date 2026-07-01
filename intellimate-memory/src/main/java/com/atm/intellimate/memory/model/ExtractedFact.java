package com.atm.intellimate.memory.model;

import java.util.List;

/**
 * A fact extracted during memory consolidation, destined for long-term memory.
 */
public record ExtractedFact(
        String topic,
        List<String> keywords,
        String content,
        String enriched,
        float importance
) {
    private static final List<String> MEMORY_TYPES = List.of("episodic", "semantic", "procedural");

    /**
     * Backward-compatible factory for pre-v3 call sites that passed memory type as the first argument.
     */
    public static ExtractedFact legacy(String type, String content, float importance) {
        String memoryType = MEMORY_TYPES.contains(type) ? type : "semantic";
        return new ExtractedFact("", List.of(), content, memoryType, importance);
    }

    /**
     * @deprecated Pre-v3 memory type (episodic/semantic/procedural); retained until store layer is migrated.
     */
    @Deprecated
    public String type() {
        if (enriched != null && MEMORY_TYPES.contains(enriched)) {
            return enriched;
        }
        return "semantic";
    }
}
