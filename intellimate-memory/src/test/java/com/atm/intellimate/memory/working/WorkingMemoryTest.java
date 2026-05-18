package com.atm.intellimate.memory.working;

import com.atm.intellimate.memory.model.ChunkType;
import com.atm.intellimate.memory.model.ContentCategory;
import com.atm.intellimate.memory.model.MemoryChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("WorkingMemory")
class WorkingMemoryTest {

    private WorkingMemory createWM(int budget, float threshold) {
        MemoryChunk system = MemoryChunk.system("system prompt", 100);
        return new WorkingMemory(budget, threshold, 1.1f, null, system);
    }

    @Test
    @DisplayName("accept within budget adds chunk normally")
    void accept_withinBudget_addsChunk() {
        WorkingMemory wm = createWM(1000, 0.75f);
        wm.accept(MemoryChunk.user("hello", 50));
        assertEquals(1, wm.getChunkCount());
        assertEquals(150, wm.estimateTokenUsage());
    }

    @Test
    @DisplayName("usage ratio calculates correctly")
    void usageRatio_calculatesCorrectly() {
        WorkingMemory wm = createWM(1000, 0.75f);
        wm.accept(MemoryChunk.user("hello", 250));
        assertEquals(0.35f, wm.usageRatio(), 0.01f);
    }

    @Test
    @DisplayName("estimateTokenUsage sums system + all chunks")
    void estimateTokenUsage_sumsAllChunks() {
        WorkingMemory wm = createWM(1000, 0.75f);
        wm.accept(MemoryChunk.user("q", 50));
        wm.accept(MemoryChunk.toolInteraction("result", ContentCategory.TEXT, 0.5f, 200, Map.of()));
        assertEquals(350, wm.estimateTokenUsage());
    }

    @Test
    @DisplayName("getConsolidationCandidates excludes recent chunks")
    void getConsolidationCandidates_excludesRecentChunks() {
        WorkingMemory wm = createWM(10000, 0.75f);
        for (int i = 0; i < 10; i++) {
            wm.accept(MemoryChunk.user("msg" + i, 100));
        }
        List<MemoryChunk> candidates = wm.getConsolidationCandidates(3);
        assertFalse(candidates.isEmpty());
        assertTrue(candidates.size() <= 5);
    }

    @Test
    @DisplayName("replaceWithConsolidated reduces token usage")
    void replaceWithConsolidated_reducesTokens() {
        WorkingMemory wm = createWM(5000, 0.75f);
        MemoryChunk c1 = MemoryChunk.user("chunk1", 200);
        MemoryChunk c2 = MemoryChunk.user("chunk2", 200);
        MemoryChunk c3 = MemoryChunk.user("chunk3", 200);
        wm.accept(c1);
        wm.accept(c2);
        wm.accept(c3);
        int before = wm.estimateTokenUsage();

        MemoryChunk consolidated = MemoryChunk.consolidated("summary", 50, 0.5f);
        wm.replaceWithConsolidated(List.of(c1, c2, c3), consolidated);

        assertTrue(wm.estimateTokenUsage() < before);
        assertEquals(1, wm.getChunkCount());
    }

    @Test
    @DisplayName("buildLLMInputSync produces system-first ordering")
    void buildLLMInput_systemFirst() {
        WorkingMemory wm = createWM(5000, 0.75f);
        wm.accept(MemoryChunk.user("hello", 10));
        List<Message> messages = wm.buildLLMInputSync();
        assertFalse(messages.isEmpty());
        assertEquals("system prompt", messages.get(0).getText());
    }

    @Test
    @DisplayName("getSnapshot returns correct state")
    void getSnapshot_returnsCurrentState() {
        WorkingMemory wm = createWM(1000, 0.75f);
        wm.accept(MemoryChunk.user("test", 50));
        WorkingMemory.MemorySnapshot snapshot = wm.getSnapshot();
        assertEquals(1000, snapshot.tokenBudget());
        assertEquals(150, snapshot.tokenUsed());
        assertEquals(150, snapshot.tokenEstimated());
        assertEquals(1, snapshot.chunkCount());
    }

    @Test
    @DisplayName("getTokenUsage uses API baseline plus incremental estimate")
    void getTokenUsage_apiPlusIncremental() {
        WorkingMemory wm = createWM(100_000, 0.75f);
        wm.accept(MemoryChunk.user("hello", 50));
        assertEquals(150, wm.getTokenUsage());
        wm.setActualTokenUsage(5000);
        assertEquals(5000, wm.getTokenUsage());
        wm.addIncrementalTokens(40);
        assertEquals(5040, wm.getTokenUsage());
        wm.setActualTokenUsage(5200);
        assertEquals(5200, wm.getTokenUsage());
    }
}
