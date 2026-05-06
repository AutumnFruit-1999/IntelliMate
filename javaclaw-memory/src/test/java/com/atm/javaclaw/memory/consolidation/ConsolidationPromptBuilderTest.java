package com.atm.javaclaw.memory.consolidation;

import com.atm.javaclaw.memory.model.ContentCategory;
import com.atm.javaclaw.memory.model.MemoryChunk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ConsolidationPromptBuilder")
class ConsolidationPromptBuilderTest {

    private final ConsolidationPromptBuilder builder = new ConsolidationPromptBuilder();

    @Test
    @DisplayName("Prompt includes all chunk contents")
    void build_includesAllChunkContents() {
        List<MemoryChunk> chunks = List.of(
                MemoryChunk.user("First user message", 10),
                MemoryChunk.toolInteraction("Tool output here", ContentCategory.CODE, 0.5f, 20, Map.of()),
                MemoryChunk.assistant("Assistant response", 10)
        );
        String prompt = builder.build(chunks, 512);
        assertTrue(prompt.contains("First user message"));
        assertTrue(prompt.contains("Tool output here"));
        assertTrue(prompt.contains("Assistant response"));
    }

    @Test
    @DisplayName("Prompt specifies max summary tokens")
    void build_respectsMaxSummaryTokens() {
        List<MemoryChunk> chunks = List.of(MemoryChunk.user("test", 5));
        String prompt = builder.build(chunks, 512);
        assertTrue(prompt.contains("512"));
    }

    @Test
    @DisplayName("Prompt requests JSON output format")
    void build_outputFormatIsValidJson() {
        List<MemoryChunk> chunks = List.of(MemoryChunk.user("test", 5));
        String prompt = builder.build(chunks, 512);
        assertTrue(prompt.contains("\"summary\""));
        assertTrue(prompt.contains("\"facts\""));
    }
}
