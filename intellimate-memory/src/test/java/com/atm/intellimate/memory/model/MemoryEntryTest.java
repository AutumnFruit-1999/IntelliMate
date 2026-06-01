package com.atm.intellimate.memory.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MemoryEntry")
class MemoryEntryTest {

    @Test
    @DisplayName("toRecalledChunk with relevance score includes type and date prefix")
    void toRecalledChunk_withRelevanceScore_includesTypeAndDatePrefix() {
        MemoryEntry entry = new MemoryEntry("user1", "agent1", "semantic", "用户偏好简洁代码", 0.8f, 1L);
        entry.setCreatedAt(Instant.parse("2026-05-28T10:00:00Z"));
        MemoryChunk chunk = entry.toRecalledChunk(50, 0.82);
        assertTrue(chunk.content().startsWith("[历史记忆 | 知识 | 2026-05-28 | 相关度:0.82]"),
                "Should start with annotated prefix, got: " + chunk.content());
        assertTrue(chunk.content().contains("用户偏好简洁代码"));
    }

    @Test
    @DisplayName("toRecalledChunk with relevance for episodic type shows 事件 label")
    void toRecalledChunk_episodicType_showsEventLabel() {
        MemoryEntry entry = new MemoryEntry("user1", "agent1", "episodic", "讨论了数据库设计", 0.5f, 1L);
        entry.setCreatedAt(Instant.parse("2026-05-30T14:00:00Z"));
        MemoryChunk chunk = entry.toRecalledChunk(30, 0.65);
        assertTrue(chunk.content().contains("事件"), "Episodic should show 事件 label");
    }

    @Test
    @DisplayName("toRecalledChunk with relevance for procedural type shows 流程 label")
    void toRecalledChunk_proceduralType_showsProcessLabel() {
        MemoryEntry entry = new MemoryEntry("user1", "agent1", "procedural", "部署步骤", 0.6f, 1L);
        entry.setCreatedAt(Instant.parse("2026-05-29T08:00:00Z"));
        MemoryChunk chunk = entry.toRecalledChunk(20, 0.70);
        assertTrue(chunk.content().contains("流程"), "Procedural should show 流程 label");
    }

    @Test
    @DisplayName("Original toRecalledChunk without relevance still works")
    void toRecalledChunk_original_stillWorks() {
        MemoryEntry entry = new MemoryEntry("user1", "agent1", "semantic", "test", 0.5f, 1L);
        MemoryChunk chunk = entry.toRecalledChunk(10);
        assertEquals("test", chunk.content());
    }
}
