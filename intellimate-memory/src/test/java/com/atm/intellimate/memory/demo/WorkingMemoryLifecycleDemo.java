package com.atm.intellimate.memory.demo;

import com.atm.intellimate.memory.consolidation.ConsolidationResult;
import com.atm.intellimate.memory.consolidation.MemoryConsolidator;
import com.atm.intellimate.memory.model.ContentCategory;
import com.atm.intellimate.memory.model.ExtractedFact;
import com.atm.intellimate.memory.model.MemoryChunk;
import com.atm.intellimate.memory.working.WorkingMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Demo: 工作记忆完整生命周期")
class WorkingMemoryLifecycleDemo {

    @Test
    @DisplayName("模拟 Bug 修复对话: 工具输出 -> 容量不足 -> 巩固 -> 继续")
    void bugFixScenario() {
        MemoryConsolidator mockConsolidator = new MemoryConsolidator(null, null, null, null, 1024, 2, 5000) {
            @Override
            public ConsolidationResult tryConsolidate(WorkingMemory wm, String agentId) {
                List<MemoryChunk> allChunks = wm.getChunks();
                List<MemoryChunk> toConsolidate = new ArrayList<>();
                for (int i = 0; i < allChunks.size() - 1; i++) {
                    toConsolidate.add(allChunks.get(i));
                }
                if (toConsolidate.isEmpty()) return null;

                MemoryChunk summary = MemoryChunk.consolidated(
                        "用户正在修复 AuthService NPE。已读取 AuthService.java 和 UserRepository.java，发现空指针在第42行。", 50, 0.6f);
                int tokensBefore = wm.estimateTokenUsage();
                wm.replaceWithConsolidated(toConsolidate, summary);
                int tokensAfter = wm.estimateTokenUsage();
                return new ConsolidationResult(summary, List.of(
                        new ExtractedFact("semantic", "AuthService.java 第42行有 NPE", 0.8f)
                ), toConsolidate.size(), tokensBefore, tokensAfter);
            }
        };

        MemoryChunk systemChunk = MemoryChunk.system("You are a helpful assistant.", 500);
        WorkingMemory wm = new WorkingMemory(2000, 0.75f, 1.1f, mockConsolidator, systemChunk);

        // 1. User message
        wm.accept(MemoryChunk.user("修复 AuthService NPE", 50));
        assertEquals(550, wm.estimateTokenUsage());

        // 2. Tool: read AuthService.java
        wm.accept(MemoryChunk.toolInteraction(
                "public class AuthService { ... line 42: user.getName() ... }",
                ContentCategory.CODE, 0.5f, 800,
                Map.of("toolName", "readFile", "toolCallId", "tc1", "arguments", "{}")));
        float usageAfterFirstRead = wm.usageRatio();
        assertTrue(usageAfterFirstRead < 0.75f, "Should be below threshold: " + usageAfterFirstRead);

        // 3. Tool: read UserRepository.java (pushes over threshold, triggers consolidation)
        Mono<ConsolidationResult> consolidation = wm.accept(MemoryChunk.toolInteraction(
                "public interface UserRepository extends JpaRepository<User, Long> { ... }",
                ContentCategory.CODE, 0.5f, 600,
                Map.of("toolName", "readFile", "toolCallId", "tc2", "arguments", "{}")));

        // Wait for async consolidation to complete
        consolidation.block(Duration.ofSeconds(5));

        assertTrue(wm.estimateTokenUsage() < 1950,
                "After consolidation, usage should drop. Actual: " + wm.estimateTokenUsage());

        // 4. Continue adding more chunks
        wm.accept(MemoryChunk.toolInteraction(
                "File saved", ContentCategory.TEXT, 0.2f, 10,
                Map.of("toolName", "editFile", "toolCallId", "tc3", "arguments", "{}")));

        // 5. Build LLM input
        List<Message> messages = wm.buildLLMInputSync();
        assertFalse(messages.isEmpty(), "Should produce non-empty message list");
        assertEquals("You are a helpful assistant.", messages.get(0).getText());

        // 6. Verify snapshot
        WorkingMemory.MemorySnapshot snapshot = wm.getSnapshot();
        assertEquals(2000, snapshot.tokenBudget());
        assertTrue(snapshot.chunkCount() > 0);
    }
}
