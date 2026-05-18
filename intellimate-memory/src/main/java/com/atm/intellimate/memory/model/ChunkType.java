package com.atm.intellimate.memory.model;

/**
 * Where this chunk originated from.
 */
public enum ChunkType {
    USER,
    ASSISTANT,
    TOOL_INTERACTION,
    SYSTEM,
    CONSOLIDATED,
    RECALLED
}
