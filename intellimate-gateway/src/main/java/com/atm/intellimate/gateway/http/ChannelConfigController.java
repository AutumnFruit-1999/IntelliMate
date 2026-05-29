package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.dto.ChannelInfoDto;
import com.atm.intellimate.gateway.service.ChannelConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Tag(name = "Channel", description = "渠道配置管理 API")
@RestController
@RequestMapping("/api/channels")
public class ChannelConfigController {

    private final ChannelConfigService channelConfigService;

    public ChannelConfigController(ChannelConfigService channelConfigService) {
        this.channelConfigService = channelConfigService;
    }

    @Operation(summary = "列出所有渠道配置")
    @GetMapping
    public Flux<ChannelInfoDto> listChannels() {
        return channelConfigService.listChannels();
    }

    @Operation(summary = "获取单个渠道配置")
    @GetMapping("/{channelId}")
    public Mono<ChannelInfoDto> getChannel(@PathVariable String channelId) {
        return channelConfigService.getChannel(channelId);
    }

    @Operation(summary = "创建渠道配置")
    @PostMapping
    public Mono<Map<String, Object>> createChannel(@RequestBody Map<String, Object> body) {
        String channelId = (String) body.get("channelId");
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) body.get("config");
        return channelConfigService.createChannel(channelId, enabled, config)
                .map(entity -> Map.of("channelId", entity.getChannelId(), "id", entity.getId()));
    }

    @Operation(summary = "更新渠道配置")
    @PutMapping("/{channelId}")
    public Mono<Map<String, Object>> updateChannel(
            @PathVariable String channelId,
            @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.TRUE.equals(body.get("enabled"));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) body.get("config");
        return channelConfigService.updateChannel(channelId, enabled, config)
                .map(entity -> Map.of("channelId", entity.getChannelId(), "status", "updated"));
    }

    @Operation(summary = "软删除渠道配置")
    @DeleteMapping("/{channelId}")
    public Mono<Void> deleteChannel(@PathVariable String channelId) {
        return channelConfigService.deleteChannel(channelId);
    }

    @Operation(summary = "连接渠道")
    @PostMapping("/{channelId}/connect")
    public Mono<Map<String, String>> connect(@PathVariable String channelId) {
        return channelConfigService.connectChannel(channelId)
                .thenReturn(Map.of("status", "connected"));
    }

    @Operation(summary = "断开渠道")
    @PostMapping("/{channelId}/disconnect")
    public Mono<Map<String, String>> disconnect(@PathVariable String channelId) {
        return channelConfigService.disconnectChannel(channelId)
                .thenReturn(Map.of("status", "disconnected"));
    }
}
