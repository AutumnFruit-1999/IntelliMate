package com.atm.intellimate.gateway.channel.wechat;

import com.atm.intellimate.channel.api.model.MessageType;
import com.atm.intellimate.core.model.OutboundMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;

@Component
public class WeChatOfficialAdapter extends AbstractWeChatAdapter {

    private static final String CHANNEL_ID = "wechat";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getChannelId() {
        return CHANNEL_ID;
    }

    @Override
    public Class<?> getConfigSchemaClass() {
        return Map.class;
    }

    @Override
    protected String getBaseUrl() {
        return "https://api.weixin.qq.com";
    }

    @Override
    protected Mono<String> refreshAccessToken() {
        return webClient.get()
                .uri("/cgi-bin/token?grant_type=client_credential&appid={appId}&secret={secret}",
                        appId, appSecret)
                .retrieve()
                .bodyToMono(String.class)
                .map(resp -> {
                    try {
                        JsonNode node = objectMapper.readTree(resp);
                        if (node.has("access_token")) {
                            this.accessToken = node.get("access_token").asText();
                            int expires = node.path("expires_in").asInt(7200);
                            this.tokenExpiresAt = System.currentTimeMillis() + expires * 1000L;
                            log.info("[{}] access_token refreshed, expires in {}s",
                                    getChannelId(), expires);
                            return this.accessToken;
                        }
                        throw new RuntimeException("WeChat token error: " + resp);
                    } catch (RuntimeException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to refresh WeChat token", e);
                    }
                });
    }

    @Override
    protected Mono<Void> doSend(OutboundMessage message) {
        return ensureAccessToken()
                .flatMap(token -> {
                    ObjectNode body = objectMapper.createObjectNode();
                    body.put("touser", message.sessionKey().contextId());
                    body.put("msgtype", "text");
                    body.putObject("text").put("content", message.text());

                    return webClient.post()
                            .uri(uriBuilder -> uriBuilder
                                    .path("/cgi-bin/message/custom/send")
                                    .queryParam("access_token", token)
                                    .build())
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .retrieve()
                            .bodyToMono(String.class)
                            .doOnNext(resp -> log.debug("[{}] send response: {}", getChannelId(), resp))
                            .then();
                });
    }

    @Override
    public Set<MessageType> supportedMessageTypes() {
        return Set.of(MessageType.TEXT);
    }

    @Override
    public JsonNode getConfigSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        props.putObject("appId").put("type", "string").put("title", "App ID");
        props.putObject("appSecret").put("type", "string").put("title", "App Secret");
        props.putObject("token").put("type", "string").put("title", "Token（URL 验证用）");
        props.putObject("encodingAesKey").put("type", "string")
                .put("title", "Encoding AES Key（消息加解密）");
        props.putObject("defaultAgent").put("type", "string").put("title", "默认 Agent");
        schema.putArray("required").add("appId").add("appSecret");
        return schema;
    }
}
