package com.atm.intellimate.memory.working;

/**
 * Estimates token counts from text using CJK-aware heuristics and an optional
 * per-message format overhead. A {@link #calibrationFactor} smooths estimates;
 * {@link #adjustCalibration(double)} can optionally tune heuristics from
 * observed usage when callers choose to apply it.
 */
public class TokenEstimator {

    /** Approximate extra tokens per Spring AI chat message (role markers, etc.). */
    public static final int MESSAGE_FORMAT_OVERHEAD_TOKENS = 4;

    private static final double CJK_CHARS_PER_TOKEN = 1.5;
    private static final double ASCII_CHARS_PER_TOKEN = 4.0;
    private static final double STRUCTURED_ASCII_CHARS_PER_TOKEN = 3.0;

    private volatile double calibrationFactor = 1.0;

    /**
     * Raw content estimate (no per-message overhead). Used for summaries,
     * retrieved memory text, and other non-chat-wrapper strings.
     */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double base = rawBaseTokens(text);
        return Math.max(1, (int) Math.ceil(base * calibrationFactor));
    }

    /**
     * Estimate for a single chat message body (adds {@link #MESSAGE_FORMAT_OVERHEAD_TOKENS}).
     */
    public int estimateForMessage(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double base = rawBaseTokens(text) + MESSAGE_FORMAT_OVERHEAD_TOKENS;
        return Math.max(1, (int) Math.ceil(base * calibrationFactor));
    }

    /**
     * Estimate for a tool-interaction chunk, which expands to two messages (assistant tool call + tool response).
     */
    public int estimateForToolInteraction(String text) {
        String safe = text != null ? text : "";
        double base = rawBaseTokens(safe) + 2 * MESSAGE_FORMAT_OVERHEAD_TOKENS;
        if (safe.isEmpty()) {
            return Math.max(1, (int) Math.ceil(base * calibrationFactor));
        }
        return Math.max(1, (int) Math.ceil(base * calibrationFactor));
    }

    /**
     * Estimate for structured content (JSON, code) which has higher token density
     * due to punctuation-heavy syntax ({, }, :, ", etc.).
     */
    public int estimateForStructuredContent(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjkChars = 0;
        int asciiChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCJK(c)) {
                cjkChars++;
            } else {
                asciiChars++;
            }
        }
        double base = cjkChars / CJK_CHARS_PER_TOKEN + asciiChars / STRUCTURED_ASCII_CHARS_PER_TOKEN;
        return Math.max(1, (int) Math.ceil(base * calibrationFactor));
    }

    public void adjustCalibration(double actualVsEstimatedRatio) {
        synchronized (this) {
            this.calibrationFactor = this.calibrationFactor * 0.7 + actualVsEstimatedRatio * 0.3;
        }
    }

    public double getCalibrationFactor() {
        return calibrationFactor;
    }

    private static double rawBaseTokens(String text) {
        int cjkChars = 0;
        int asciiChars = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCJK(c)) {
                cjkChars++;
            } else {
                asciiChars++;
            }
        }
        return cjkChars / CJK_CHARS_PER_TOKEN + asciiChars / ASCII_CHARS_PER_TOKEN;
    }

    private static boolean isCJK(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA;
    }
}
