package com.atm.javaclaw.memory.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MemoryChunk")
class MemoryChunkTest {

    @Test
    @DisplayName("Factory: system chunk has SYSTEM type and high importance")
    void system_createsSystemChunk() {
        MemoryChunk chunk = MemoryChunk.system("You are an assistant.", 10);
        assertEquals(ChunkType.SYSTEM, chunk.type());
        assertEquals(1.0f, chunk.importance());
        assertEquals(10, chunk.estimatedTokens());
    }

    @Test
    @DisplayName("Factory: user chunk")
    void user_createsUserChunk() {
        MemoryChunk chunk = MemoryChunk.user("Fix the bug", 5);
        assertEquals(ChunkType.USER, chunk.type());
        assertEquals(0.8f, chunk.importance());
    }

    @Test
    @DisplayName("Factory: assistant chunk")
    void assistant_createsAssistantChunk() {
        MemoryChunk chunk = MemoryChunk.assistant("I'll help you fix it.", 8);
        assertEquals(ChunkType.ASSISTANT, chunk.type());
        assertEquals(0.5f, chunk.importance());
    }

    @Test
    @DisplayName("Factory: tool interaction bundles call and response")
    void toolInteraction_createsChunkWithMetadata() {
        MemoryChunk chunk = MemoryChunk.toolInteraction(
                "file content here", ContentCategory.CODE, 0.7f, 100,
                Map.of("toolCallId", "tc-1", "toolName", "readFile", "arguments", "{\"path\":\"/src/Main.java\"}"));
        assertEquals(ChunkType.TOOL_INTERACTION, chunk.type());
        assertEquals(ContentCategory.CODE, chunk.category());
        assertEquals("tc-1", chunk.metadata().get("toolCallId"));
    }

    @Test
    @DisplayName("toMessages: USER returns single UserMessage")
    void toMessages_user_returnsSingleMessage() {
        MemoryChunk chunk = MemoryChunk.user("hello", 2);
        List<Message> messages = chunk.toMessages();
        assertEquals(1, messages.size());
        assertEquals("hello", messages.get(0).getText());
    }

    @Test
    @DisplayName("toMessages: TOOL_INTERACTION returns AssistantMessage + ToolResponseMessage pair")
    void toMessages_toolInteraction_returnsTwoMessages() {
        MemoryChunk chunk = MemoryChunk.toolInteraction(
                "tool output", ContentCategory.TEXT, 0.5f, 20,
                Map.of("toolCallId", "tc-1", "toolName", "exec", "arguments", "{}"));
        List<Message> messages = chunk.toMessages();
        assertEquals(2, messages.size());
    }

    @Test
    @DisplayName("toMessages: SYSTEM returns SystemMessage")
    void toMessages_system_returnsSystemMessage() {
        MemoryChunk chunk = MemoryChunk.system("system prompt", 5);
        List<Message> messages = chunk.toMessages();
        assertEquals(1, messages.size());
        assertEquals("system prompt", messages.get(0).getText());
    }

    @Test
    @DisplayName("toMessages: CONSOLIDATED returns single AssistantMessage")
    void toMessages_consolidated_returnsSingleMessage() {
        MemoryChunk chunk = MemoryChunk.consolidated("summary of prior actions", 15, 0.6f);
        List<Message> messages = chunk.toMessages();
        assertEquals(1, messages.size());
    }
}
