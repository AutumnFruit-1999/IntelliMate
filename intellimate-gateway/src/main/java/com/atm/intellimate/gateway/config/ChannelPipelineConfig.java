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
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
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
                                 ChannelConfigService channelConfigService,
                                 SessionRegistry sessionRegistry) {
        channelsManager.setInboundHandler(envelope -> {
            String channelId = envelope.sessionKey().channelId();
            String contextType = envelope.sessionKey().contextType();
            channelMetrics.recordMessageReceived(channelId, contextType);
            Timer.Sample sample = channelMetrics.startProcessingTimer();

            Mono<String> replyMono = tryBindingCode(envelope, bindingCodeService, identityService, sessionRegistry)
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

                    Mono<String> replyMono = channelConfigService.getDefaultAgent(channelId)
                            .flatMap(optAgent -> messagePipeline.processInbound(
                                    effectiveEnvelope, optAgent.orElse(null), channelId));

                    if (DM_CONTEXT_TYPES.contains(contextType)
                            && !"webchat".equals(channelId)
                            && !"unified".equals(channelId)) {
                        return replyMono.flatMap(reply ->
                                identityService.listByUserId(userId)
                                        .any(id -> "webchat".equals(id.getChannelId()))
                                        .map(hasWebchat -> hasWebchat ? reply
                                                : reply + "\n\n💡 如需将此账号与 Web 端关联以实现消息同步，请在 Web 端「渠道管理 → 跨渠道身份绑定」中生成绑定码，然后发送给我。"));
                    }
                    return replyMono;
                });
    }

    private static java.util.Optional<Mono<String>> tryBindingCode(
            InboundEnvelope envelope,
            ChannelBindingCodeService bindingCodeService,
            ChannelIdentityService identityService,
            SessionRegistry sessionRegistry) {
        String text = envelope.text() != null ? envelope.text().trim() : "";
        String normalized = normalizeBindingInput(text);
        if (!BINDING_CODE_PATTERN.matcher(normalized).matches()) {
            return java.util.Optional.empty();
        }
        return bindingCodeService.lookup(normalized)
                .map(entry -> identityService.findBoundUserId(
                                envelope.sessionKey().channelId(), envelope.senderId())
                        .flatMap(existingUserId -> {
                            if (!existingUserId.isEmpty() && !existingUserId.equals(entry.userId())) {
                                return Mono.just("绑定失败：该账号已被其他 Web 用户绑定");
                            }
                            if (existingUserId.equals(entry.userId())) {
                                bindingCodeService.consume(normalized);
                                return Mono.just("已绑定，无需重复操作");
                            }
                            return identityService.bindIdentity(
                                            entry.userId(),
                                            envelope.sessionKey().channelId(),
                                            envelope.senderId(),
                                            envelope.senderName())
                                    .doOnSuccess(v -> {
                                        bindingCodeService.consume(normalized);
                                        identityService.listByUserId(entry.userId())
                                                .filter(id -> "webchat".equals(id.getChannelId()))
                                                .next()
                                                .subscribe(webchatId -> {
                                                    try {
                                                        Long dbUserId = Long.parseLong(webchatId.getExternalId());
                                                        sessionRegistry.pushToUser(dbUserId, "binding.success", Map.of(
                                                                "channelId", envelope.sessionKey().channelId(),
                                                                "externalName", envelope.senderName() != null ? envelope.senderName() : "",
                                                                "boundAt", Instant.now().toString()
                                                        ));
                                                    } catch (NumberFormatException ignored) {}
                                                });
                                    })
                                    .thenReturn("绑定成功！你的账号已与 Web 端关联，后续消息将自动同步。");
                        }));
    }

    private static String normalizeBindingInput(String text) {
        String s = text.strip();
        if (s.startsWith("bind ") || s.startsWith("bind\t")) {
            s = s.substring(5);
        } else if (s.startsWith("绑定 ") || s.startsWith("绑定\t")) {
            s = s.substring(3);
        } else if (s.startsWith("绑定")) {
            s = s.substring(2);
        }
        return s.replaceAll("\\s+", "").strip();
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
