package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.channel.ChannelBindingCodeService;
import com.atm.intellimate.gateway.channel.ChannelIdentityService;
import com.atm.intellimate.gateway.entity.ChannelIdentityEntity;
import com.atm.intellimate.gateway.security.JwtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Channel Binding", description = "跨渠道账号绑定 API")
@RestController
@RequestMapping("/api/channel-binding")
public class ChannelBindingController {

    private static final Logger log = LoggerFactory.getLogger(ChannelBindingController.class);

    private final ChannelBindingCodeService bindingCodeService;
    private final ChannelIdentityService identityService;
    private final JwtService jwtService;

    public ChannelBindingController(ChannelBindingCodeService bindingCodeService,
                                    ChannelIdentityService identityService,
                                    JwtService jwtService) {
        this.bindingCodeService = bindingCodeService;
        this.identityService = identityService;
        this.jwtService = jwtService;
    }

    @Operation(summary = "生成 6 位绑定码，优先从 JWT 解析当前用户身份")
    @PostMapping("/generate-code")
    public Mono<Map<String, Object>> generateCode(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestBody(required = false) Map<String, String> body) {

        return resolveUnifiedUserId(authHeader, body)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("userId is required")))
                .map(bindingCodeService::generateCode)
                .map(generated -> Map.<String, Object>of(
                        "code", generated.code(),
                        "expiresIn", generated.expiresIn()
                ));
    }

    private Mono<String> resolveUnifiedUserId(String authHeader, Map<String, String> body) {
        String token = extractBearerToken(authHeader);
        if (token != null) {
            return jwtService.validateToken(token)
                    .map(claims -> identityService.resolveUserId(
                            "webchat", String.valueOf(claims.userId()), null))
                    .orElseGet(() -> fallbackFromBody(body));
        }
        return fallbackFromBody(body);
    }

    private Mono<String> fallbackFromBody(Map<String, String> body) {
        String userId = body != null ? body.get("userId") : null;
        if (userId == null || userId.isBlank()) {
            return Mono.empty();
        }
        return Mono.just(userId);
    }

    private String extractBearerToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7).trim();
        }
        return null;
    }

    @Operation(summary = "列出用户已绑定的渠道身份")
    @GetMapping("/identities/{userId}")
    public Flux<Map<String, Object>> listIdentities(@PathVariable String userId) {
        return identityService.listByUserId(userId)
                .map(this::toIdentityDto);
    }

    @Operation(summary = "解绑渠道身份")
    @DeleteMapping("/identities/{identityId}")
    public Mono<Void> unbindIdentity(@PathVariable Long identityId) {
        return identityService.unbind(identityId);
    }

    private Map<String, Object> toIdentityDto(ChannelIdentityEntity entity) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", entity.getId());
        dto.put("userId", entity.getUserId());
        dto.put("channelId", entity.getChannelId());
        dto.put("externalId", entity.getExternalId());
        dto.put("externalName", entity.getExternalName());
        if (entity.getBoundAt() != null) {
            dto.put("boundAt", entity.getBoundAt().toString());
        }
        return dto;
    }

}
