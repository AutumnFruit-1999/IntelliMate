package com.atm.javaclaw.memory.model;

/**
 * What kind of data is inside the chunk — guides consolidation strategies.
 */
public enum ContentCategory {
    CODE,
    TEXT,
    COMMAND_OUTPUT,
    SEARCH_RESULT,
    STRUCTURED_DATA
}
