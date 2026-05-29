package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.channel.ChannelBindingCodeService;
import com.atm.intellimate.gateway.channel.ChannelIdentityService;
import com.atm.intellimate.gateway.entity.ChannelIdentityEntity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

@Tag(name = "Channel Binding", description = "跨渠道账号绑定 API")
@RestController
@RequestMapping("/api/channel-binding")
public class ChannelBindingController {

    private final ChannelBindingCodeService bindingCodeService;
    private final ChannelIdentityService identityService;

    public ChannelBindingController(ChannelBindingCodeService bindingCodeService,
                                    ChannelIdentityService identityService) {
        this.bindingCodeService = bindingCodeService;
        this.identityService = identityService;
    }

    @Operation(summary = "生成 6 位绑定码")
    @PostMapping("/generate-code")
    public Mono<Map<String, Object>> generateCode(@RequestBody Map<String, String> body) {
        String userId = body != null ? body.get("userId") : null;
        if (userId == null || userId.isBlank()) {
            return Mono.error(new IllegalArgumentException("userId is required"));
        }
        return Mono.fromCallable(() -> bindingCodeService.generateCode(userId))
                .map(generated -> Map.<String, Object>of(
                        "code", generated.code(),
                        "expiresIn", generated.expiresIn()
                ));
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
