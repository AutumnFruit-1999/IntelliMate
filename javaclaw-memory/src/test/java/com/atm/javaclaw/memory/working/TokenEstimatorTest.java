package com.atm.javaclaw.memory.working;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TokenEstimator")
class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    @DisplayName("ASCII text uses ~4 chars per token (plus calibration)")
    void estimate_asciiUsesWiderCharsPerToken() {
        String ascii = "abcdefghijklmnopqrst"; // 20 letters
        int tokens = estimator.estimate(ascii);
        assertEquals(5, tokens, "20 / 4 = 5 base tokens");
    }

    @Test
    @DisplayName("CJK text uses ~1.5 chars per token so more tokens than ASCII of same length")
    void estimate_cjkHigherTokenDensity() {
        String cjk = "一二三四五六七八九十"; // 10 ideographs
        String ascii = "abcdefghij"; // 10 ASCII

        int cjkTokens = estimator.estimate(cjk);
        int asciiTokens = estimator.estimate(ascii);

        assertTrue(cjkTokens > asciiTokens,
                "CJK should yield more tokens than same-length ASCII; cjk=" + cjkTokens + " ascii=" + asciiTokens);
        assertEquals(7, cjkTokens, "10 / 1.5 -> ceil(6.66) = 7");
        assertEquals(3, asciiTokens, "10 / 4 -> ceil(2.5) = 3");
    }

    @Test
    @DisplayName("Mixed CJK and ASCII blends both ratios")
    void estimate_mixedCjkAndAscii() {
        String mixed = "hello世界"; // 5 ASCII + 2 CJK
        int tokens = estimator.estimate(mixed);
        double expected = 5 / 4.0 + 2 / 1.5;
        assertEquals((int) Math.ceil(expected), tokens);
    }

    @Test
    @DisplayName("estimateForMessage adds format overhead on top of content")
    void estimateForMessage_addsOverhead() {
        String text = "hello";
        int contentOnly = estimator.estimate(text);
        int withMessage = estimator.estimateForMessage(text);
        assertTrue(withMessage > contentOnly);
    }

    @Test
    @DisplayName("calibration smooths toward observed API ratio")
    void adjustCalibration_movesEstimateTowardObservedRatio() {
        TokenEstimator e = new TokenEstimator();
        String sample = "hello world";
        int before = e.estimate(sample);
        e.adjustCalibration(2.0);
        int after = e.estimate(sample);
        assertTrue(after > before, "Higher calibration should increase token estimate");
        assertEquals(1.3, e.getCalibrationFactor(), 1e-9,
                "1.0 * 0.7 + 2.0 * 0.3 = 1.3");
    }

    @Test
    @DisplayName("Short English text estimation")
    void estimateTokens_shortText() {
        int tokens = estimator.estimateForMessage("hello world");
        assertTrue(tokens >= 3 && tokens <= 8, "Expected modest token count, got " + tokens);
    }

    @Test
    @DisplayName("Empty string returns 0")
    void estimateTokens_emptyString() {
        assertEquals(0, estimator.estimate(""));
        assertEquals(0, estimator.estimate(null));
        assertEquals(0, estimator.estimateForMessage(""));
    }

    @Test
    @DisplayName("Chinese text estimation yields more tokens per character than old flat heuristic")
    void estimateTokens_chineseText() {
        String chinese = "这是一段中文测试文本，用于验证 Token 估算";
        int tokens = estimator.estimate(chinese);
        assertTrue(tokens > 10, "Chinese-heavy string should map to many tokens, got " + tokens);
    }

    @Test
    @DisplayName("Code content estimation")
    void estimateTokens_codeContent() {
        String code = """
                public class HelloWorld {
                    public static void main(String[] args) {
                        System.out.println("Hello, World!");
                    }
                }
                """;
        int tokens = estimator.estimate(code);
        assertTrue(tokens > 20, "Java code should produce a meaningful token count, got " + tokens);
    }

    @Test
    @DisplayName("Single character returns at least 1")
    void estimateTokens_singleChar() {
        assertEquals(1, estimator.estimate("a"));
    }
}
