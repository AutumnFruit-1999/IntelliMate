package com.atm.intellimate.core.model;

/**
 * Unique key identifying a conversation session.
 *
 * @param channelId  the channel adapter identifier (e.g. "wechat", "feishu")
 * @param contextType the conversation context type: "dm", "group", or "channel"
 * @param contextId  the unique identifier within the channel (e.g. group ID, user ID)
 */
public record SessionKey(
        String channelId,
        String contextType,
        String contextId
) {
    public String toCompositeKey() {
        return channelId + ":" + contextType + ":" + contextId;
    }
}
