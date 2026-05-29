package com.atm.intellimate.gateway.channel.wechat;

import com.atm.intellimate.channel.api.AbstractChannelAdapter;
import com.atm.intellimate.channel.api.model.WebhookRequest;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.SessionKey;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public abstract class AbstractWeChatAdapter extends AbstractChannelAdapter {

    protected WeChatCrypto crypto;
    protected WebClient webClient;
    protected String appId;
    protected String appSecret;
    protected String token;
    protected String encodingAesKey;
    protected volatile String accessToken;
    protected volatile long tokenExpiresAt;

    @Override
    protected Mono<Void> doConnect(Map<String, Object> config) {
        this.appId = configString(config, "appId");
        this.appSecret = configString(config, "appSecret");
        this.token = configString(config, "token");
        if (this.token == null) {
            this.token = "";
        }
        this.encodingAesKey = configString(config, "encodingAesKey");
        if (this.encodingAesKey == null) {
            this.encodingAesKey = "";
        }

        if (!encodingAesKey.isEmpty()) {
            this.crypto = new WeChatCrypto(encodingAesKey, appId, token);
        }

        this.webClient = WebClient.builder()
                .baseUrl(getBaseUrl())
                .build();

        return refreshAccessToken().then();
    }

    @Override
    protected Mono<Void> doDisconnect() {
        this.accessToken = null;
        this.tokenExpiresAt = 0;
        return Mono.empty();
    }

    @Override
    protected boolean isVerificationRequest(WebhookRequest request) {
        return "GET".equals(request.method()) && request.getQueryParam("echostr") != null;
    }

    @Override
    protected String handleVerification(WebhookRequest request) {
        return request.getQueryParam("echostr");
    }

    @Override
    protected boolean verifySignature(WebhookRequest request) {
        if (token == null || token.isEmpty()) {
            return true;
        }
        String signature = request.getQueryParam("signature");
        if (signature == null) {
            signature = request.getHeader("signature");
        }
        String timestamp = request.getQueryParam("timestamp");
        String nonce = request.getQueryParam("nonce");

        if (crypto != null) {
            return crypto.verifySignature(signature, timestamp, nonce);
        }
        return true;
    }

    @Override
    protected InboundEnvelope parseInbound(WebhookRequest request) {
        String xml = request.body();

        if (crypto != null && xml != null && xml.contains("<Encrypt>")) {
            Map<String, String> encryptedFields = WeChatXmlParser.parse(xml);
            String encrypted = encryptedFields.get("Encrypt");
            xml = crypto.decrypt(encrypted);
        }

        Map<String, String> fields = WeChatXmlParser.parse(xml);
        String msgType = fields.getOrDefault("MsgType", "text");

        if (!"text".equals(msgType)) {
            log.info("[{}] unsupported message type: {}", getChannelId(), msgType);
            return null;
        }

        String fromUser = fields.get("FromUserName");
        String content = fields.getOrDefault("Content", "");

        SessionKey sessionKey = new SessionKey(getChannelId(), "dm", fromUser);

        return new InboundEnvelope(
                sessionKey,
                fromUser,
                fromUser,
                content,
                List.of(),
                Instant.now(),
                request.body()
        );
    }

    protected Mono<String> ensureAccessToken() {
        if (accessToken != null && System.currentTimeMillis() < tokenExpiresAt - 300_000) {
            return Mono.just(accessToken);
        }
        return refreshAccessToken();
    }

    protected abstract String getBaseUrl();

    protected abstract Mono<String> refreshAccessToken();

    private static String configString(Map<String, Object> config, String key) {
        Object value = config.get(key);
        return value != null ? value.toString() : null;
    }
}
