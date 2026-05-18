package com.atm.intellimate.core.model;

import java.time.Instant;
import java.util.List;

/**
 * Normalized inbound message envelope from any channel adapter.
 * This is the unified internal representation of an incoming message,
 * decoupled from channel-specific formats.
 */
public record InboundEnvelope(
        SessionKey sessionKey,
        String senderId,
        String senderName,
        String text,
        List<Attachment> attachments,
        Instant timestamp,
        String rawPayload
) {
}
