package com.atm.intellimate.core.model;

import java.util.List;

/**
 * Normalized outbound message to be delivered through a channel adapter.
 */
public record OutboundMessage(
        SessionKey sessionKey,
        String text,
        List<Attachment> attachments,
        String replyToMessageId
) {
}
