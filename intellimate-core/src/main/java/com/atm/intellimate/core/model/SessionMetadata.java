package com.atm.intellimate.core.model;

/**
 * Metadata attached to a session upon creation or update.
 */
public record SessionMetadata(
        String agentName,
        String senderName,
        String channelId,
        String contextType,
        String contextId
) {
}
