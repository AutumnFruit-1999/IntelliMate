package com.atm.intellimate.gateway.channel.feishu;

import com.atm.intellimate.channel.api.AbstractChannelAdapter;
import com.atm.intellimate.channel.api.model.WebhookRequest;
import com.atm.intellimate.channel.api.model.WebhookResponse;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.OutboundMessage;
import com.atm.intellimate.core.model.SessionKey;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Feishu (Lark) channel adapter — webhook inbound and REST API outbound.
 */
@Component
public class FeishuAdapter extends AbstractChannelAdapter {

    private static final String CHANNEL_ID = "feishu";
    private static final String BASE_URL = "https://open.feishu.cn/open-apis";
    private static final long REFRESH_MARGIN_MS = 5 * 60 * 1000L;

    private final ObjectMapper objectMapper;
    private volatile WebClient webClient;
    private volatile String tenantAccessToken;
    private volatile long tokenExpiresAtEpochMs;

    public FeishuAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String getChannelId() {
        return CHANNEL_ID;
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
        properties.putObject("appId").put("type", "string").put("description", "Feishu App ID");
        properties.putObject("appSecret").put("type", "string").put("description", "Feishu App Secret");
        properties.putObject("encryptKey").put("type", "string")
                .put("description", "Event encryption key (optional)");
        properties.putObject("verificationToken").put("type", "string")
                .put("description", "Webhook verification token (optional)");
        properties.putObject("defaultAgent").put("type", "string")
                .put("description", "Default agent ID for this channel");

        schema.putArray("required").add("appId").add("appSecret");
        return schema;
    }

    @Override
    protected Mono<Void> doConnect(Map<String, Object> config) {
        this.webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .build();
        return refreshToken();
    }

    @Override
    protected Mono<Void> doDisconnect() {
        tenantAccessToken = null;
        tokenExpiresAtEpochMs = 0;
        webClient = null;
        return Mono.empty();
    }

    @Override
    protected Mono<Void> doSend(OutboundMessage message) {
        SessionKey sessionKey = message.sessionKey();
        String receiveIdType = "group".equals(sessionKey.contextType()) ? "chat_id" : "open_id";
        String receiveId = sessionKey.contextId();

        return getValidToken()
                .flatMap(token -> {
                    String contentJson;
                    try {
                        contentJson = objectMapper.writeValueAsString(Map.of("text", message.text()));
                    } catch (JsonProcessingException e) {
                        return Mono.error(e);
                    }
                    Map<String, String> body = Map.of(
                            "receive_id", receiveId,
                            "msg_type", "text",
                            "content", contentJson
                    );
                    return webClient.post()
                            .uri(uriBuilder -> uriBuilder
                                    .path("/im/v1/messages")
                                    .queryParam("receive_id_type", receiveIdType)
                                    .build())
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .flatMap(response -> {
                                int code = response.path("code").asInt(-1);
                                if (code != 0) {
                                    return Mono.error(new RuntimeException(
                                            "Feishu send message failed: " + response.path("msg").asText()));
                                }
                                return Mono.empty();
                            });
                });
    }

    @Override
    protected InboundEnvelope parseInbound(WebhookRequest request) {
        try {
            return FeishuEventParser.parse(request);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Feishu webhook", e);
        }
    }

    @Override
    protected boolean isVerificationRequest(WebhookRequest request) {
        return FeishuEventParser.isChallenge(request);
    }

    @Override
    protected String handleVerification(WebhookRequest request) {
        String challenge = FeishuEventParser.extractChallenge(request);
        try {
            return objectMapper.writeValueAsString(Map.of("challenge", challenge));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to build challenge response", e);
        }
    }

    @Override
    public WebhookResponse handleWebhook(WebhookRequest request) {
        if (isVerificationRequest(request)) {
            String body = handleVerification(request);
            log.info("[{}] verification request handled", getChannelId());
            return new WebhookResponse(200, body, MediaType.APPLICATION_JSON_VALUE);
        }
        return super.handleWebhook(request);
    }

    @Override
    protected boolean verifySignature(WebhookRequest request) {
        String expected = configString("verificationToken");
        if (expected == null || expected.isBlank()) {
            return true;
        }
        if (request.body() == null || request.body().isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(request.body());
            String token = root.path("header").path("token").asText(null);
            return expected.equals(token);
        } catch (Exception e) {
            log.warn("[{}] failed to verify webhook token: {}", getChannelId(), e.getMessage());
            return false;
        }
    }

    private Mono<String> getValidToken() {
        long now = System.currentTimeMillis();
        if (tenantAccessToken != null && now < tokenExpiresAtEpochMs - REFRESH_MARGIN_MS) {
            return Mono.just(tenantAccessToken);
        }
        return refreshToken().thenReturn(tenantAccessToken);
    }

    private Mono<Void> refreshToken() {
        String appId = configString("appId");
        String appSecret = configString("appSecret");
        if (appId == null || appSecret == null) {
            return Mono.error(new IllegalArgumentException("appId and appSecret are required"));
        }

        Map<String, String> body = Map.of("app_id", appId, "app_secret", appSecret);
        return webClient.post()
                .uri("/auth/v3/tenant_access_token/internal")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .flatMap(response -> {
                    int code = response.path("code").asInt(-1);
                    if (code != 0) {
                        return Mono.error(new RuntimeException(
                                "Feishu token request failed: " + response.path("msg").asText()));
                    }
                    tenantAccessToken = response.path("tenant_access_token").asText();
                    int expireSeconds = response.path("expire").asInt(7200);
                    tokenExpiresAtEpochMs = System.currentTimeMillis() + expireSeconds * 1000L;
                    log.info("[{}] tenant_access_token refreshed, expires in {}s",
                            getChannelId(), expireSeconds);
                    return Mono.empty();
                });
    }

    private String configString(String key) {
        if (config == null) {
            return null;
        }
        Object value = config.get(key);
        return value != null ? value.toString() : null;
    }
}
