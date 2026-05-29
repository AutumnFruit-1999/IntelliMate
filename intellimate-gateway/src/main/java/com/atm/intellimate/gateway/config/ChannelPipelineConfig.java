package com.atm.intellimate.gateway.config;

import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.OutboundMessage;
import com.atm.intellimate.gateway.channel.ChannelBindingCodeService;
import com.atm.intellimate.gateway.channel.ChannelIdentityService;
import com.atm.intellimate.gateway.channel.ChannelMetrics;
import com.atm.intellimate.gateway.channel.ChannelsManager;
import com.atm.intellimate.gateway.pipeline.MessagePipeline;
import com.atm.intellimate.gateway.service.ChannelConfigService;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.regex.Pattern;

/**
 * Wires external channel inbound messages through the agent pipeline
 * and sends replies back via the appropriate channel adapter.
 */
@Configuration
public class ChannelPipelineConfig {

    private static final Logger log = LoggerFactory.getLogger(ChannelPipelineConfig.class);
    private static final Pattern BINDING_CODE_PATTERN = Pattern.compile("^\\d{6}$");

    public ChannelPipelineConfig(ChannelsManager channelsManager,
                                 MessagePipeline messagePipeline,
                                 ChannelMetrics channelMetrics,
                                 ChannelBindingCodeService bindingCodeService,
                                 ChannelIdentityService identityService,
                                 ChannelConfigService channelConfigService) {
        channelsManager.setInboundHandler(envelope -> {
            String channelId = envelope.sessionKey().channelId();
            String contextType = envelope.sessionKey().contextType();
            channelMetrics.recordMessageReceived(channelId, contextType);
            Timer.Sample sample = channelMetrics.startProcessingTimer();

            Mono<String> replyMono = tryBindingCode(envelope, bindingCodeService, identityService)
                    .orElseGet(() -> channelConfigService.getDefaultAgent(channelId)
                            .flatMap(optAgent -> messagePipeline.processInbound(
                                    envelope, optAgent.orElse(null))));

            replyMono
                    .flatMap(replyText -> {
                        OutboundMessage outbound = new OutboundMessage(
                                envelope.sessionKey(),
                                replyText,
                                Collections.emptyList(),
                                null
                        );
                        return channelsManager.sendOutbound(outbound)
                                .doOnSuccess(v -> channelMetrics.recordMessageSent(channelId, true))
                                .doOnError(e -> channelMetrics.recordMessageSent(channelId, false))
                                .thenReturn(replyText);
                    })
                    .doFinally(signal -> channelMetrics.stopProcessingTimer(sample, channelId))
                    .doOnError(err -> {
                        channelMetrics.recordError(channelId, classifyError(err));
                        log.error("Inbound pipeline error for {}: {}",
                                envelope.sessionKey(), err.getMessage(), err);
                    })
                    .subscribe(
                            null,
                            err -> { /* logged in doOnError */ }
                    );
        });
        log.info("Channel inbound pipeline wired");
    }

    private static java.util.Optional<Mono<String>> tryBindingCode(
            InboundEnvelope envelope,
            ChannelBindingCodeService bindingCodeService,
            ChannelIdentityService identityService) {
        String text = envelope.text() != null ? envelope.text().trim() : "";
        if (!BINDING_CODE_PATTERN.matcher(text).matches()) {
            return java.util.Optional.empty();
        }
        return bindingCodeService.lookup(text)
                .map(entry -> identityService.bindIdentity(
                                entry.userId(),
                                envelope.sessionKey().channelId(),
                                envelope.senderId(),
                                envelope.senderName())
                        .doOnSuccess(v -> bindingCodeService.consume(text))
                        .thenReturn("绑定成功"));
    }

    private static String classifyError(Throwable err) {
        if (err == null) {
            return "unknown";
        }
        String simple = err.getClass().getSimpleName();
        if (err.getCause() != null && err.getCause().getClass() != err.getClass()) {
            return simple + ":" + err.getCause().getClass().getSimpleName();
        }
        return simple;
    }

}
