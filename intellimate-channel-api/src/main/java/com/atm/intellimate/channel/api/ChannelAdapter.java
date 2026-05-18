package com.atm.intellimate.channel.api;

import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.OutboundMessage;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Consumer;

/**
 * SPI interface for channel adapters (WeChat, Feishu, DingTalk, etc.).
 * Each adapter translates between a specific IM protocol and IntelliMate's
 * normalized InboundEnvelope / OutboundMessage models.
 */
public interface ChannelAdapter {

    /**
     * @return unique identifier for this channel (e.g. "wechat", "feishu")
     */
    String getChannelId();

    /**
     * Connect to the channel using the provided configuration.
     */
    Mono<Void> connect(Map<String, Object> config);

    /**
     * Gracefully disconnect from the channel.
     */
    Mono<Void> disconnect();

    /**
     * Send an outbound message through this channel.
     */
    Mono<Void> send(OutboundMessage message);

    /**
     * Register a callback for inbound messages from this channel.
     */
    void onMessage(Consumer<InboundEnvelope> handler);

    /**
     * @return the Java class representing this channel's specific config schema
     */
    Class<?> getConfigSchemaClass();

    /**
     * @return current connection status
     */
    ChannelStatus getStatus();

    /**
     * @return true if the adapter is currently connected and healthy
     */
    default boolean isConnected() {
        return getStatus() == ChannelStatus.CONNECTED;
    }
}
