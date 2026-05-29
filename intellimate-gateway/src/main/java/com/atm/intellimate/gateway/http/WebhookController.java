package com.atm.intellimate.gateway.http;

import com.atm.intellimate.channel.api.ChannelAdapter;
import com.atm.intellimate.channel.api.model.WebhookRequest;
import com.atm.intellimate.channel.api.model.WebhookResponse;
import com.atm.intellimate.gateway.audit.AuditService;
import com.atm.intellimate.gateway.channel.ChannelsManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * HTTP webhook endpoint for channel callbacks (e.g. WeChat, Feishu verification).
 * Channels register their webhook URLs as /webhook/{channelId}.
 * Request handling is delegated to the channel adapter via {@link ChannelAdapter#handleWebhook}.
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
     * GET /webhook/{channelId} — URL verification (e.g. WeChat echostr).
     */
    @GetMapping("/{channelId}")
    public Mono<ResponseEntity<String>> handleVerification(
            @PathVariable String channelId,
            @RequestHeader(required = false) Map<String, String> headers,
            @RequestParam Map<String, String> params
    ) {
        log.info("Webhook verification: channelId={}, params={}", channelId, params);
        WebhookRequest request = new WebhookRequest(
                "GET",
                normalizeHeaders(headers),
                params,
                null,
                null
        );
        return dispatchWebhook(channelId, "webhook_verify", params.toString(), request);
    }

    /**
     * POST /webhook/{channelId} — JSON callback body.
     */
    @PostMapping(value = "/{channelId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> handleJsonCallback(
            @PathVariable String channelId,
            @RequestHeader(required = false) Map<String, String> headers,
            @RequestParam(required = false) Map<String, String> params,
            @RequestBody String body
    ) {
        log.info("Webhook JSON callback: channelId={}, bodyLength={}", channelId, body != null ? body.length() : 0);
        WebhookRequest request = new WebhookRequest(
                "POST",
                normalizeHeaders(headers),
                params != null ? params : Map.of(),
                body,
                MediaType.APPLICATION_JSON_VALUE
        );
        return dispatchWebhook(channelId, "webhook_callback", "json bodyLen=" + (body != null ? body.length() : 0), request);
    }

    /**
     * POST /webhook/{channelId} — XML callback body (e.g. WeChat).
     */
    @PostMapping(value = "/{channelId}", consumes = MediaType.APPLICATION_XML_VALUE)
    public Mono<ResponseEntity<String>> handleXmlCallback(
            @PathVariable String channelId,
            @RequestHeader(required = false) Map<String, String> headers,
            @RequestParam(required = false) Map<String, String> params,
            @RequestBody String body
    ) {
        log.info("Webhook XML callback: channelId={}, bodyLength={}", channelId, body != null ? body.length() : 0);
        WebhookRequest request = new WebhookRequest(
                "POST",
                normalizeHeaders(headers),
                params != null ? params : Map.of(),
                body,
                MediaType.APPLICATION_XML_VALUE
        );
        return dispatchWebhook(channelId, "webhook_xml_callback", "xml bodyLen=" + (body != null ? body.length() : 0), request);
    }

    private Mono<ResponseEntity<String>> dispatchWebhook(
            String channelId, String auditAction, String auditDetail, WebhookRequest request
    ) {
        ChannelAdapter adapter = channelsManager.getAdapter(channelId);
        if (adapter == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        return auditService.log(auditAction, channelId, null, auditDetail)
                .then(Mono.fromSupplier(() -> toResponseEntity(adapter.handleWebhook(request))));
    }

    private static ResponseEntity<String> toResponseEntity(WebhookResponse response) {
        MediaType mediaType = response.contentType() != null
                ? MediaType.parseMediaType(response.contentType())
                : MediaType.TEXT_PLAIN;
        return ResponseEntity.status(response.statusCode())
                .contentType(mediaType)
                .body(response.body());
    }

    private static Map<String, String> normalizeHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> normalized = new HashMap<>();
        headers.forEach((k, v) -> normalized.put(k.toLowerCase(), v));
        return normalized;
    }
}
