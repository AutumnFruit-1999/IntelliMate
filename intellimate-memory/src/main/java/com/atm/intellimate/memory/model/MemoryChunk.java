package com.atm.intellimate.memory.model;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A semantically meaningful unit of information in working memory.
 * Tool call + tool response are bundled into a single TOOL_INTERACTION chunk.
 */
public record MemoryChunk(
        String id,
        ChunkType type,
        String content,
        ContentCategory category,
        float importance,
        int estimatedTokens,
        long originalSize,
        Instant createdAt,
        Map<String, String> metadata,
        Long sourceId
) {

    public MemoryChunk(String id, ChunkType type, String content, ContentCategory category,
                       float importance, int estimatedTokens, long originalSize,
                       Instant createdAt, Map<String, String> metadata) {
        this(id, type, content, category, importance, estimatedTokens, originalSize,
                createdAt, metadata, null);
    }

    public static MemoryChunk system(String content, int estimatedTokens) {
        return new MemoryChunk(
                UUID.randomUUID().toString(), ChunkType.SYSTEM, content,
                ContentCategory.TEXT, 1.0f, estimatedTokens, content.length(),
                Instant.now(), Map.of()
        );
    }

    public static MemoryChunk user(String content, int estimatedTokens) {
        return new MemoryChunk(
                UUID.randomUUID().toString(), ChunkType.USER, content,
                ContentCategory.TEXT, 0.8f, estimatedTokens, content.length(),
                Instant.now(), Map.of()
        );
    }

    public static MemoryChunk assistant(String content, int estimatedTokens) {
        return new MemoryChunk(
                UUID.randomUUID().toString(), ChunkType.ASSISTANT, content,
                ContentCategory.TEXT, 0.5f, estimatedTokens, content.length(),
                Instant.now(), Map.of()
        );
    }

    public static MemoryChunk toolInteraction(String content, ContentCategory category,
                                               float importance, int estimatedTokens,
                                               Map<String, String> metadata) {
        return new MemoryChunk(
                UUID.randomUUID().toString(), ChunkType.TOOL_INTERACTION, content,
                category, importance, estimatedTokens, content.length(),
                Instant.now(), metadata
        );
    }

    public static MemoryChunk consolidated(String summary, int estimatedTokens, float importance) {
        return new MemoryChunk(
                UUID.randomUUID().toString(), ChunkType.CONSOLIDATED, summary,
                ContentCategory.TEXT, importance, estimatedTokens, summary.length(),
                Instant.now(), Map.of()
        );
    }

    public static MemoryChunk recalled(String content, int estimatedTokens, float importance) {
        return recalled(content, estimatedTokens, importance, null, Map.of());
    }

    public static MemoryChunk recalled(String content, int estimatedTokens, float importance,
                                       Long sourceId, Map<String, String> metadata) {
        return new MemoryChunk(
                UUID.randomUUID().toString(), ChunkType.RECALLED, content,
                ContentCategory.TEXT, importance, estimatedTokens, content.length(),
                Instant.now(), metadata != null ? metadata : Map.of(), sourceId
        );
    }

    /**
     * Converts this chunk back into Spring AI Message(s) for LLM input.
     */
    public List<Message> toMessages() {
        return switch (type) {
            case SYSTEM -> List.of(new SystemMessage(content));
            case USER -> List.of(new UserMessage(content));
            case ASSISTANT, CONSOLIDATED -> List.of(new AssistantMessage(content));
            case RECALLED -> List.of(new SystemMessage(content));
            case TOOL_INTERACTION -> {
                List<Message> msgs = new ArrayList<>(2);
                String toolCallId = metadata.getOrDefault("toolCallId", id);
                String toolName = metadata.getOrDefault("toolName", "unknown");
                String args = metadata.getOrDefault("arguments", "{}");
                var call = new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                        toolCallId, "function", toolName, args);
                msgs.add(new AssistantMessage("", Map.of(), List.of(call)));
                msgs.add(new ToolResponseMessage(
                        List.of(new ToolResponseMessage.ToolResponse(toolCallId, toolName, content)),
                        Map.of()
                ));
                yield Collections.unmodifiableList(msgs);
            }
        };
    }
}
