package com.atm.intellimate.gateway.channel.dingtalk;

import com.atm.intellimate.channel.api.model.WebhookRequest;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.SessionKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/**
 * Parses DingTalk webhook payloads (Outgoing robot and enterprise event subscription)
 * into normalized {@link InboundEnvelope}s.
 */
public final class DingtalkEventParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CHANNEL_ID = "dingtalk";
    private static final String EVENT_CHAT_SEND_MESSAGE = "chat_send_message";

    private DingtalkEventParser() {
    }

    public static InboundEnvelope parse(WebhookRequest request) throws Exception {
        if (request.body() == null || request.body().isBlank()) {
            return null;
        }

        JsonNode root = MAPPER.readTree(request.body());
        if (isOutgoingRobotMessage(root)) {
            return parseOutgoingRobot(request, root);
        }
        if (isEventSubscription(root)) {
            return parseEventSubscription(request, root);
        }
        return null;
    }

    private static boolean isOutgoingRobotMessage(JsonNode root) {
        return root.has("msgtype") && root.has("senderStaffId");
    }

    private static boolean isEventSubscription(JsonNode root) {
        return root.has("EventType") || root.has("eventType");
    }

    private static InboundEnvelope parseOutgoingRobot(WebhookRequest request, JsonNode root) {
        String senderId = textOrNull(root.get("senderStaffId"));
        if (senderId == null) {
            return null;
        }

        String senderName = root.path("senderNick").asText("");
        String conversationId = textOrNull(root.get("conversationId"));
        String conversationType = root.path("conversationType").asText("1");
        String text = stripBotMention(root.path("text").path("content").asText("").trim());

        String contextType = "1".equals(conversationType) ? "dm" : "group";
        String contextId = "dm".equals(contextType) ? senderId : conversationId;
        if (contextId == null) {
            return null;
        }

        SessionKey sessionKey = new SessionKey(CHANNEL_ID, contextType, contextId);
        return new InboundEnvelope(
                sessionKey,
                senderId,
                senderName,
                text,
                List.of(),
                Instant.now(),
                request.body()
        );
    }

    private static InboundEnvelope parseEventSubscription(WebhookRequest request, JsonNode root) {
        String eventType = root.has("EventType")
                ? root.path("EventType").asText()
                : root.path("eventType").asText();

        if (!EVENT_CHAT_SEND_MESSAGE.equals(eventType) && !"message".equals(eventType)) {
            return null;
        }

        String senderId = textOrNull(root.has("SenderId") ? root.get("SenderId") : root.get("senderId"));
        if (senderId == null) {
            return null;
        }

        String senderName = root.has("SenderNick")
                ? root.path("SenderNick").asText("")
                : root.path("senderNick").asText("");
        String conversationId = textOrNull(
                root.has("ConversationId") ? root.get("ConversationId") : root.get("conversationId"));

        String text = stripBotMention(
                root.has("Text")
                        ? root.path("Text").path("content").asText("").trim()
                        : root.path("text").path("content").asText("").trim());

        String contextType = conversationId != null && conversationId.startsWith("cid") ? "group" : "dm";
        String contextId = "dm".equals(contextType) ? senderId : conversationId;
        if (contextId == null) {
            return null;
        }

        SessionKey sessionKey = new SessionKey(CHANNEL_ID, contextType, contextId);
        return new InboundEnvelope(
                sessionKey,
                senderId,
                senderName,
                text,
                List.of(),
                Instant.now(),
                request.body()
        );
    }

    private static String stripBotMention(String text) {
        if (text.startsWith("@")) {
            int spaceIdx = text.indexOf(' ');
            if (spaceIdx > 0) {
                return text.substring(spaceIdx + 1).trim();
            }
        }
        return text;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        String text = node.asText(null);
        return (text == null || text.isBlank()) ? null : text;
    }
}
