package com.atm.intellimate.gateway.channel.dingtalk;

import com.atm.intellimate.channel.api.ChannelAdapter;
import com.atm.intellimate.channel.api.ChannelStatus;
import com.atm.intellimate.channel.api.model.MessageType;
import com.atm.intellimate.channel.api.model.WebhookRequest;
import com.atm.intellimate.channel.api.model.WebhookResponse;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.OutboundMessage;
import com.atm.intellimate.core.model.SessionKey;
import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.DingTalkStreamTopics;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * DingTalk Stream mode adapter — uses WebSocket long connection via DingTalk SDK.
 * No public webhook URL required.
 */
@Component
public class DingtalkStreamAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(DingtalkStreamAdapter.class);
    private static final String CHANNEL_ID = "dingtalk-stream";
    private static final String API_BASE = "https://api.dingtalk.com";
    private static final long REFRESH_MARGIN_MS = 5 * 60 * 1000L;

    private final ObjectMapper objectMapper;
    private volatile ChannelStatus status = ChannelStatus.DISCONNECTED;
    private volatile Map<String, Object> config;
    private volatile Consumer<InboundEnvelope> messageHandler;
    private volatile OpenDingTalkClient streamClient;
    private volatile WebClient webClient;
    private volatile String accessToken;
    private volatile long tokenExpiresAtEpochMs;

    public DingtalkStreamAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getChannelId() {
        return CHANNEL_ID;
    }

    @Override
    public Mono<Void> connect(Map<String, Object> config) {
        this.config = config;
        String clientId = configString("appKey");
        String clientSecret = configString("appSecret");
        if (clientId == null || clientSecret == null) {
            return Mono.error(new IllegalArgumentException("appKey and appSecret are required for Stream mode"));
        }

        return Mono.fromRunnable(() -> {
            try {
                this.webClient = WebClient.builder().baseUrl(API_BASE).build();

                OpenDingTalkCallbackListener<ChatbotMessage, ?> botListener = message -> {
                    handleBotMessage(message);
                    return null;
                };
                this.streamClient = OpenDingTalkStreamClientBuilder.custom()
                        .credential(new AuthClientCredential(clientId, clientSecret))
                        .registerCallbackListener(DingTalkStreamTopics.BOT_MESSAGE_TOPIC, botListener)
                        .build();
                streamClient.start();
                status = ChannelStatus.CONNECTED;
                log.info("[{}] Stream client connected", CHANNEL_ID);
            } catch (Exception e) {
                status = ChannelStatus.ERROR;
                throw new RuntimeException("Failed to start DingTalk Stream client", e);
            }
        });
    }

    @Override
    public Mono<Void> disconnect() {
        return Mono.fromRunnable(() -> {
            if (streamClient != null) {
                try {
                    streamClient.stop();
                } catch (Exception e) {
                    log.warn("[{}] Error stopping stream client: {}", CHANNEL_ID, e.getMessage());
                }
                streamClient = null;
            }
            accessToken = null;
            tokenExpiresAtEpochMs = 0;
            webClient = null;
            status = ChannelStatus.DISCONNECTED;
            log.info("[{}] Stream client disconnected", CHANNEL_ID);
        });
    }

    @Override
    public Mono<Void> send(OutboundMessage message) {
        return getValidToken()
                .flatMap(token -> {
                    String appKey = configString("appKey");
                    String contextType = message.sessionKey().contextType();
                    String contextId = message.sessionKey().contextId();
                    String msgParam;
                    try {
                        msgParam = objectMapper.writeValueAsString(Map.of("content", message.text()));
                    } catch (JsonProcessingException e) {
                        return Mono.error(e);
                    }

                    String uri;
                    Map<String, Object> body;
                    if ("group".equals(contextType)) {
                        uri = "/v1.0/robot/groupMessages/send";
                        body = Map.of(
                                "robotCode", appKey,
                                "openConversationId", contextId,
                                "msgKey", "sampleText",
                                "msgParam", msgParam
                        );
                    } else {
                        uri = "/v1.0/robot/oToMessages/batchSend";
                        body = Map.of(
                                "robotCode", appKey,
                                "userIds", List.of(contextId),
                                "msgKey", "sampleText",
                                "msgParam", msgParam
                        );
                    }

                    return webClient.post()
                            .uri(uri)
                            .header("x-acs-dingtalk-access-token", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .doOnNext(resp -> log.debug("[{}] send response ({}): {}", CHANNEL_ID, contextType, resp))
                            .then();
                });
    }

    @Override
    public void onMessage(Consumer<InboundEnvelope> handler) {
        this.messageHandler = handler;
    }

    @Override
    public ChannelStatus getStatus() {
        return status;
    }

    @Override
    public boolean isConnected() {
        return status == ChannelStatus.CONNECTED;
    }

    @Override
    public Set<MessageType> supportedMessageTypes() {
        return Set.of(MessageType.TEXT);
    }

    @Override
    public WebhookResponse handleWebhook(WebhookRequest request) {
        return new WebhookResponse(404, "Stream mode does not use webhooks", "text/plain");
    }

    @Override
    public Class<?> getConfigSchemaClass() {
        return Map.class;
    }

    @Override
    public JsonNode getConfigSchema() {
        var factory = JsonNodeFactory.instance;
        var schema = factory.objectNode();
        schema.put("$schema", "http://json-schema.org/draft-07/schema#");
        schema.put("type", "object");

        var properties = schema.putObject("properties");
        properties.putObject("appKey").put("type", "string").put("description", "DingTalk App Key (ClientId)");
        properties.putObject("appSecret").put("type", "string").put("description", "DingTalk App Secret (ClientSecret)");
        properties.putObject("defaultAgent").put("type", "string")
                .put("description", "Default agent ID for this channel");

        schema.putArray("required").add("appKey").add("appSecret");
        return schema;
    }

    private void handleBotMessage(ChatbotMessage message) {
        try {
            String senderId = message.getSenderStaffId();
            if (senderId == null || senderId.isBlank()) {
                senderId = message.getSenderId();
            }
            String senderName = message.getSenderNick();
            String text = "";
            if (message.getText() != null && message.getText().getContent() != null) {
                text = message.getText().getContent().trim();
            }
            String chatType = message.getConversationType();
            String contextType = "1".equals(chatType) ? "dm" : "group";
            String contextId = "1".equals(chatType) ? senderId : message.getConversationId();

            InboundEnvelope envelope = new InboundEnvelope(
                    new SessionKey(CHANNEL_ID, contextType, contextId),
                    senderId,
                    senderName,
                    text,
                    List.of(),
                    Instant.now(),
                    null
            );

            log.info("[{}] Stream message received: sender={}, text length={}",
                    CHANNEL_ID, senderId, text.length());

            if (messageHandler != null) {
                messageHandler.accept(envelope);
            }
        } catch (Exception e) {
            log.error("[{}] Failed to process stream message", CHANNEL_ID, e);
        }
    }

    private Mono<String> getValidToken() {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < tokenExpiresAtEpochMs - REFRESH_MARGIN_MS) {
            return Mono.just(accessToken);
        }
        return refreshToken().thenReturn("").flatMap(x -> {
            if (accessToken == null) {
                return Mono.error(new RuntimeException("Failed to get access token"));
            }
            return Mono.just(accessToken);
        });
    }

    private Mono<Void> refreshToken() {
        String appKey = configString("appKey");
        String appSecret = configString("appSecret");
        if (appKey == null || appSecret == null) {
            return Mono.error(new IllegalArgumentException("appKey and appSecret are required"));
        }
        Map<String, String> body = Map.of("appKey", appKey, "appSecret", appSecret);
        return webClient.post()
                .uri("/v1.0/oauth2/accessToken")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMap(response -> {
                    if (!response.has("accessToken")) {
                        return Mono.error(new RuntimeException(
                                "DingTalk token request failed: " + response));
                    }
                    accessToken = response.path("accessToken").asText();
                    int expireSeconds = response.path("expireIn").asInt(7200);
                    tokenExpiresAtEpochMs = System.currentTimeMillis() + expireSeconds * 1000L;
                    log.info("[{}] access_token refreshed, expires in {}s", CHANNEL_ID, expireSeconds);
                    return Mono.empty();
                });
    }

    private String configString(String key) {
        if (config == null) return null;
        Object value = config.get(key);
        return value != null ? value.toString() : null;
    }
}
