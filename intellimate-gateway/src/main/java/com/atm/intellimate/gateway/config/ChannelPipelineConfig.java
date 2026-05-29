package com.atm.intellimate.gateway.config;

import com.atm.intellimate.core.model.OutboundMessage;
import com.atm.intellimate.gateway.channel.ChannelsManager;
import com.atm.intellimate.gateway.pipeline.MessagePipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

/**
 * Wires external channel inbound messages through the agent pipeline
 * and sends replies back via the appropriate channel adapter.
 */
@Configuration
public class ChannelPipelineConfig {

    private static final Logger log = LoggerFactory.getLogger(ChannelPipelineConfig.class);

    public ChannelPipelineConfig(ChannelsManager channelsManager, MessagePipeline messagePipeline) {
        channelsManager.setInboundHandler(envelope ->
                messagePipeline.processInbound(envelope)
                        .flatMap(replyText -> {
                            OutboundMessage outbound = new OutboundMessage(
                                    envelope.sessionKey(),
                                    replyText,
                                    Collections.emptyList(),
                                    null
                            );
                            return channelsManager.sendOutbound(outbound);
                        })
                        .subscribe(
                                null,
                                err -> log.error("Inbound pipeline error for {}: {}",
                                        envelope.sessionKey(), err.getMessage(), err)
                        )
        );
        log.info("Channel inbound pipeline wired");
    }
}
