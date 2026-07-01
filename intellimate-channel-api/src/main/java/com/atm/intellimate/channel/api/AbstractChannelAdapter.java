package com.atm.intellimate.channel.api;

import com.atm.intellimate.channel.api.model.WebhookRequest;
import com.atm.intellimate.channel.api.model.WebhookResponse;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Consumer;

public abstract class AbstractChannelAdapter implements ChannelAdapter {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected volatile ChannelStatus status = ChannelStatus.DISCONNECTED;
    protected volatile Map<String, Object> config;
    protected volatile Consumer<InboundEnvelope> inboundHandler;

    protected abstract Mono<Void> doConnect(Map<String, Object> config);
    protected abstract Mono<Void> doDisconnect();
    protected abstract Mono<Void> doSend(OutboundMessage message);
    protected abstract InboundEnvelope parseInbound(WebhookRequest request);
    protected abstract boolean verifySignature(WebhookRequest request);

    protected boolean isVerificationRequest(WebhookRequest request) {
        return false;
    }

    protected String handleVerification(WebhookRequest request) {
        return "";
    }

    @Override
    public final Mono<Void> connect(Map<String, Object> config) {
        this.config = config;
        this.status = ChannelStatus.CONNECTING;
        return doConnect(config)
                .doOnSuccess(v -> this.status = ChannelStatus.CONNECTED)
                .doOnError(e -> {
                    this.status = ChannelStatus.ERROR;
                    log.error("[{}] connect failed: {}", getChannelId(), e.getMessage(), e);
                });
    }

    @Override
    public final Mono<Void> disconnect() {
        return doDisconnect()
                .doFinally(s -> this.status = ChannelStatus.DISCONNECTED);
    }

    @Override
    public final Mono<Void> send(OutboundMessage message) {
        if (!isConnected()) {
            return Mono.error(new IllegalStateException(
                    "Channel " + getChannelId() + " is not connected"));
        }
        return doSend(message)
                .doOnError(e -> log.error("[{}] send failed: {}", getChannelId(), e.getMessage(), e));
    }

    @Override
    public void onMessage(Consumer<InboundEnvelope> handler) {
        this.inboundHandler = handler;
    }

    @Override
    public ChannelStatus getStatus() {
        return status;
    }

    @Override
    public WebhookResponse handleWebhook(WebhookRequest request) {
        if (isVerificationRequest(request)) {
            String challenge = handleVerification(request);
            return WebhookResponse.ok(challenge);
        }

        if (!verifySignature(request)) {
            log.warn("[{}] signature verification failed", getChannelId());
            return WebhookResponse.unauthorized();
        }

        try {
            InboundEnvelope envelope = parseInbound(request);
            if (envelope != null && inboundHandler != null) {
                inboundHandler.accept(envelope);
            }
        } catch (Exception e) {
            log.error("[{}] failed to parse inbound message: {}", getChannelId(), e.getMessage(), e);
        }

        return WebhookResponse.ok();
    }
}
