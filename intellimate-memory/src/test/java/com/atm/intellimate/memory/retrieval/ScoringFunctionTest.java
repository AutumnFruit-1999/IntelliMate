package com.atm.intellimate.memory.retrieval;

import com.atm.intellimate.memory.model.MemoryEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ScoringFunction")
class ScoringFunctionTest {

    private final ScoringFunction scorer = new ScoringFunction();

    private MemoryEntry createMemory(float importance, int accessCount, Instant lastAccessed) {
        MemoryEntry m = new MemoryEntry("user1", "default", "semantic", "test content", importance, null);
        m.setAccessCount(accessCount);
        m.setLastAccessedAt(lastAccessed);
        return m;
    }

    @Test
    @DisplayName("Recent high-importance memory scores high")
    void score_recentHighImportance_scoresHigh() {
        MemoryEntry m = createMemory(0.9f, 5, Instant.now());
        double score = scorer.computeRetrievalScore(m, 0.8, 0.1);
        assertTrue(score > 1.0, "Recent high-importance memory should score high, got " + score);
    }

    @Test
    @DisplayName("Old low-importance memory scores low")
    void score_oldLowImportance_scoresLow() {
        MemoryEntry m = createMemory(0.2f, 0, Instant.now().minus(30, ChronoUnit.DAYS));
        double score = scorer.computeRetrievalScore(m, 0.3, 0.1);
        assertTrue(score < 0.1, "Old low-importance memory should score low, got " + score);
    }

    @Test
    @DisplayName("Recency decay at 7 days with lambda=0.1 is approximately 50%")
    void recencyDecay_sevenDays_halfLife() {
        double decay = scorer.computeRecencyDecay(Instant.now().minus(7, ChronoUnit.DAYS), 0.1);
        assertTrue(decay > 0.45 && decay < 0.55,
                "7-day decay with lambda=0.1 should be ~0.5, got " + decay);
    }

    @Test
    @DisplayName("Access boost is logarithmic")
    void accessBoost_logarithmic() {
        MemoryEntry m0 = createMemory(0.5f, 0, Instant.now());
        MemoryEntry m9 = createMemory(0.5f, 9, Instant.now());

        double score0 = scorer.computeRetrievalScore(m0, 1.0, 0.1);
        double score9 = scorer.computeRetrievalScore(m9, 1.0, 0.1);
        assertTrue(score9 > score0, "Higher access count should produce higher score");
    }

    @Test
    @DisplayName("Null lastAccessed returns default decay of 0.5")
    void recencyDecay_nullLastAccessed() {
        double decay = scorer.computeRecencyDecay(null, 0.1);
        assertEquals(0.5, decay);
    }
}
