package com.atm.intellimate.gateway.config;

import com.atm.intellimate.core.model.InboundEnvelope;
import com.atm.intellimate.core.model.OutboundMessage;
import com.atm.intellimate.core.model.SessionKey;
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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Wires external channel inbound messages through the agent pipeline
 * and sends replies back via the appropriate channel adapter.
 *
 * Cross-channel unified sessions: for DM messages, the external identity
 * is resolved to a unified IntelliMate userId via {@link ChannelIdentityService},
 * and the session key is transformed to {@code ("unified", "dm", userId)} so
 * that the same user shares one conversation across all channels.
 */
@Configuration
public class ChannelPipelineConfig {

    private static final Logger log = LoggerFactory.getLogger(ChannelPipelineConfig.class);
    private static final Pattern BINDING_CODE_PATTERN = Pattern.compile("^\\d{6}$");
    private static final Set<String> DM_CONTEXT_TYPES = Set.of("dm", "p2p");

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
                    .orElseGet(() -> resolveIdentityAndProcess(
                            envelope, identityService, channelConfigService, messagePipeline));

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
    }

    /**
     * Resolves the external identity to a unified userId, transforms the
     * session key for DM messages, and dispatches to the message pipeline.
     */
    private static Mono<String> resolveIdentityAndProcess(
            InboundEnvelope envelope,
            ChannelIdentityService identityService,
            ChannelConfigService channelConfigService,
            MessagePipeline messagePipeline) {

        String channelId = envelope.sessionKey().channelId();
        String contextType = envelope.sessionKey().contextType();

        return identityService.resolveUserId(channelId, envelope.senderId(), envelope.senderName())
                .flatMap(userId -> {
                    InboundEnvelope effectiveEnvelope;
                    if (DM_CONTEXT_TYPES.contains(contextType)) {
                        SessionKey unifiedKey = new SessionKey("unified", "dm", userId);
                        effectiveEnvelope = new InboundEnvelope(
                                unifiedKey,
                                envelope.senderId(),
                                envelope.senderName(),
                                envelope.text(),
                                envelope.attachments(),
                                envelope.timestamp(),
                                envelope.rawPayload()
                        );
                    } else {
                        effectiveEnvelope = envelope;
                    }

                    return channelConfigService.getDefaultAgent(channelId)
                            .flatMap(optAgent -> messagePipeline.processInbound(
                                    effectiveEnvelope, optAgent.orElse(null), channelId));
                });
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
