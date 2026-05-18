package com.atm.intellimate.gateway.channel.webchat;

import com.atm.intellimate.channel.api.ChannelAdapter;
import com.atm.intellimate.channel.api.ChannelStatus;
import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.OutboundMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Consumer;

/**
 * WebChat adapter — a passthrough channel that operates directly
 * over the Gateway WebSocket connection. Messages from the WebSocket
 * handler are treated as WebChat channel messages.
 *
 * This adapter is always "connected" since it piggybacks on the
 * Gateway's own WebSocket server rather than connecting to an external service.
 */
@Component
public class WebChatAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(WebChatAdapter.class);
    private static final String CHANNEL_ID = "webchat";

    private volatile ChannelStatus status = ChannelStatus.DISCONNECTED;
    private volatile Consumer<InboundEnvelope> messageHandler;

    @Override
    public String getChannelId() {
        return CHANNEL_ID;
    }

    @Override
    public Mono<Void> connect(Map<String, Object> config) {
        status = ChannelStatus.CONNECTED;
        log.info("WebChat adapter connected (passthrough mode)");
        return Mono.empty();
    }

    @Override
    public Mono<Void> disconnect() {
        status = ChannelStatus.DISCONNECTED;
        log.info("WebChat adapter disconnected");
        return Mono.empty();
    }

    @Override
    public Mono<Void> send(OutboundMessage message) {
        // WebChat outbound is handled directly by the WebSocket handler,
        // not through this adapter's send(). This is a no-op for webchat.
        log.debug("WebChat send called (no-op, handled by WS handler): sessionKey={}", message.sessionKey());
        return Mono.empty();
    }

    @Override
    public void onMessage(Consumer<InboundEnvelope> handler) {
        this.messageHandler = handler;
    }

    /**
     * Called by the WebSocket handler to inject a message into the channel system.
     */
    public void deliverInbound(InboundEnvelope envelope) {
        if (messageHandler != null) {
            messageHandler.accept(envelope);
        }
    }

    @Override
    public Class<?> getConfigSchemaClass() {
        return Map.class;
    }

    @Override
    public ChannelStatus getStatus() {
        return status;
    }
}
