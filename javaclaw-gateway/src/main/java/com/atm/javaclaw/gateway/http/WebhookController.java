package com.atm.javaclaw.gateway.http;

import com.atm.javaclaw.gateway.audit.AuditService;
import com.atm.javaclaw.gateway.channel.ChannelsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * HTTP webhook endpoint for channel callbacks (e.g. WeChat, Feishu verification).
 * Channels can register their webhook URLs as /webhook/{channelId}.
 */
@RestController
@RequestMapping("/webhook")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final ChannelsManager channelsManager;
    private final AuditService auditService;

    public WebhookController(ChannelsManager channelsManager, AuditService auditService) {
        this.channelsManager = channelsManager;
        this.auditService = auditService;
    }

    /**
     * GET /webhook/{channelId} — used for channel verification (e.g. WeChat echostr).
     */
    @GetMapping("/{channelId}")
    public Mono<ResponseEntity<String>> handleVerification(
            @PathVariable String channelId,
            @RequestParam Map<String, String> params
    ) {
        log.info("Webhook verification: channelId={}, params={}", channelId, params);

        return auditService.log("webhook_verify", channelId, null, params.toString())
                .then(Mono.fromSupplier(() -> {
                    var adapter = channelsManager.getAdapter(channelId);
                    if (adapter == null) {
                        return ResponseEntity.notFound().<String>build();
                    }
                    String echoStr = params.get("echostr");
                    if (echoStr != null) {
                        return ResponseEntity.ok(echoStr);
                    }
                    return ResponseEntity.ok("ok");
                }));
    }

    /**
     * POST /webhook/{channelId} — receives inbound messages from channels.
     */
    @PostMapping(value = "/{channelId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Map<String, Object>>> handleCallback(
            @PathVariable String channelId,
            @RequestBody Map<String, Object> body
    ) {
        log.info("Webhook callback: channelId={}, body keys={}", channelId, body.keySet());

        var adapter = channelsManager.getAdapter(channelId);
        if (adapter == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }

        return auditService.log("webhook_callback", channelId, null, "keys=" + body.keySet())
                .thenReturn(ResponseEntity.ok(Map.of("status", (Object) "received", "channelId", channelId)));
    }

    /**
     * POST /webhook/{channelId}/xml — receives XML callbacks (e.g. WeChat).
     */
    @PostMapping(value = "/{channelId}", consumes = MediaType.APPLICATION_XML_VALUE)
    public Mono<ResponseEntity<String>> handleXmlCallback(
            @PathVariable String channelId,
            @RequestBody String body
    ) {
        log.info("Webhook XML callback: channelId={}, bodyLength={}", channelId, body.length());

        var adapter = channelsManager.getAdapter(channelId);
        if (adapter == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }

        return auditService.log("webhook_xml_callback", channelId, null, "bodyLen=" + body.length())
                .thenReturn(ResponseEntity.ok("success"));
    }
}
