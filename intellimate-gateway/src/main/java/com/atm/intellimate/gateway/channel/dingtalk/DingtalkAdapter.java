package com.atm.intellimate.gateway.channel.dingtalk;

import com.atm.intellimate.channel.api.AbstractChannelAdapter;
import com.atm.intellimate.channel.api.model.WebhookRequest;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.OutboundMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * DingTalk channel adapter — webhook inbound and Open API outbound.
 */
@Component
public class DingtalkAdapter extends AbstractChannelAdapter {

    private static final String CHANNEL_ID = "dingtalk";
    private static final String BASE_URL = "https://oapi.dingtalk.com";
    private static final String MODE_ENTERPRISE_APP = "enterprise_app";
    private static final long REFRESH_MARGIN_MS = 5 * 60 * 1000L;

    private final ObjectMapper objectMapper;
    private volatile WebClient webClient;
    private volatile String accessToken;
    private volatile long tokenExpiresAtEpochMs;

    public DingtalkAdapter(ObjectMapper objectMapper) {
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
        properties.putObject("appKey").put("type", "string").put("description", "DingTalk App Key");
        properties.putObject("appSecret").put("type", "string").put("description", "DingTalk App Secret");
        properties.putObject("signSecret").put("type", "string")
                .put("description", "Outgoing robot signature secret (optional)");
        properties.putObject("mode").put("type", "string")
                .put("description", "outgoing_robot or enterprise_app")
                .put("default", "outgoing_robot");
        properties.putObject("defaultAgent").put("type", "string")
                .put("description", "Default agent ID for this channel");

        schema.putArray("required").add("appKey").add("appSecret");
        return schema;
    }

    @Override
    protected Mono<Void> doConnect(Map<String, Object> config) {
        this.webClient = WebClient.builder()
                .baseUrl(BASE_URL)
                .build();
        if (MODE_ENTERPRISE_APP.equals(configString("mode"))) {
            return refreshToken();
        }
        return Mono.empty();
    }

    @Override
    protected Mono<Void> doDisconnect() {
        accessToken = null;
        tokenExpiresAtEpochMs = 0;
        webClient = null;
        return Mono.empty();
    }

    @Override
    protected Mono<Void> doSend(OutboundMessage message) {
        return getValidToken()
                .flatMap(token -> {
                    String appKey = configString("appKey");
                    String userId = message.sessionKey().contextId();
                    String msgParam;
                    try {
                        msgParam = objectMapper.writeValueAsString(Map.of("content", message.text()));
                    } catch (JsonProcessingException e) {
                        return Mono.error(e);
                    }
                    Map<String, Object> body = Map.of(
                            "robotCode", appKey,
                            "userIds", List.of(userId),
                            "msgKey", "sampleText",
                            "msgParam", msgParam
                    );
                    return webClient.post()
                            .uri("/v1.0/robot/oToMessages/batchSend")
                            .header("x-acs-dingtalk-access-token", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .doOnNext(response -> log.debug("[{}] send response: {}", getChannelId(), response))
                            .then();
                });
    }

    @Override
    protected InboundEnvelope parseInbound(WebhookRequest request) {
        try {
            return DingtalkEventParser.parse(request);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse DingTalk webhook", e);
        }
    }

    @Override
    protected boolean verifySignature(WebhookRequest request) {
        String signSecret = configString("signSecret");
        if (signSecret == null || signSecret.isBlank()) {
            return true;
        }
        String timestamp = headerValue(request, "timestamp");
        String sign = headerValue(request, "sign");
        return DingtalkSignature.verify(timestamp, sign, signSecret);
    }

    private Mono<String> getValidToken() {
        long now = System.currentTimeMillis();
        if (accessToken != null && now < tokenExpiresAtEpochMs - REFRESH_MARGIN_MS) {
            return Mono.just(accessToken);
        }
        return refreshToken().thenReturn(accessToken);
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
                    log.info("[{}] access_token refreshed, expires in {}s",
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

    private static String headerValue(WebhookRequest request, String name) {
        if (request.headers() == null) {
            return null;
        }
        String lower = name.toLowerCase();
        for (Map.Entry<String, String> entry : request.headers().entrySet()) {
            if (entry.getKey() != null && entry.getKey().toLowerCase().equals(lower)) {
                return entry.getValue();
            }
        }
        return request.getHeader(name);
    }
}
