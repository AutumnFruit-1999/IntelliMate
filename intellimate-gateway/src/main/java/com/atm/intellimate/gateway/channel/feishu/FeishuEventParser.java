package com.atm.intellimate.gateway.channel.feishu;

import com.atm.intellimate.channel.api.model.WebhookRequest;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.SessionKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/**
 * Parses Feishu (Lark) webhook event callbacks into normalized {@link InboundEnvelope}s.
 */
public final class FeishuEventParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHANNEL_ID = "feishu";
    private static final String EVENT_MESSAGE_RECEIVE = "im.message.receive_v1";

    private FeishuEventParser() {
    }

    public static boolean isChallenge(WebhookRequest request) {
        if (request.body() == null || request.body().isBlank()) {
            return false;
        }
        try {
            JsonNode root = MAPPER.readTree(request.body());
            return root.hasNonNull("challenge");
        } catch (Exception e) {
            return false;
        }
    }

    public static String extractChallenge(WebhookRequest request) {
        try {
            JsonNode root = MAPPER.readTree(request.body());
            return root.get("challenge").asText();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid Feishu challenge request", e);
        }
    }

    public static InboundEnvelope parse(WebhookRequest request) throws Exception {
        if (request.body() == null || request.body().isBlank()) {
            return null;
        }

        JsonNode root = MAPPER.readTree(request.body());
        if (root.hasNonNull("challenge")) {
            return null;
        }

        JsonNode header = root.get("header");
        if (header == null) {
            return null;
        }

        String eventType = textOrNull(header.get("event_type"));
        if (!EVENT_MESSAGE_RECEIVE.equals(eventType)) {
            return null;
        }

        JsonNode event = root.get("event");
        if (event == null) {
            return null;
        }

        String senderId = textOrNull(event.path("sender").path("sender_id").get("open_id"));
        if (senderId == null) {
            return null;
        }

        JsonNode message = event.get("message");
        if (message == null) {
            return null;
        }

        String feishuChatType = textOrNull(message.get("chat_type"));
        String contextType = "group".equals(feishuChatType) ? "group" : "dm";
        String contextId = "group".equals(contextType)
                ? textOrNull(message.get("chat_id"))
                : senderId;
        if (contextId == null) {
            return null;
        }

        String text = extractText(textOrNull(message.get("content")));
        Instant timestamp = parseCreateTime(textOrNull(header.get("create_time")));
        SessionKey sessionKey = new SessionKey(CHANNEL_ID, contextType, contextId);

        return new InboundEnvelope(
                sessionKey,
                senderId,
                null,
                text,
                List.of(),
                timestamp,
                request.body()
        );
    }

    private static String extractText(String contentJson) throws Exception {
        if (contentJson == null || contentJson.isBlank()) {
            return "";
        }
        JsonNode content = MAPPER.readTree(contentJson);
        return content.path("text").asText("");
    }

    private static Instant parseCreateTime(String createTime) {
        if (createTime == null || createTime.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(createTime));
        } catch (NumberFormatException e) {
            return Instant.now();
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.asText(null);
        return (text == null || text.isBlank()) ? null : text;
    }
}
