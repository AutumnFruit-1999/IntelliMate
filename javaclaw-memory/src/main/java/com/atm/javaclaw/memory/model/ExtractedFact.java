package com.atm.javaclaw.memory.model;

/**
 * A fact extracted during memory consolidation, destined for long-term memory.
 */
public record ExtractedFact(
        String type,
        String content,
        float importance
) {
}
