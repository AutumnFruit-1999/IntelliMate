package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.entity.ChannelGroupEntity;
import com.atm.intellimate.gateway.service.ChannelGroupService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@RestController
@RequestMapping("/api/channels/{channelId}/groups")
public class ChannelGroupController {

    private final ChannelGroupService groupService;

    public ChannelGroupController(ChannelGroupService groupService) {
        this.groupService = groupService;
    }

    @GetMapping
    public Flux<ChannelGroupEntity> listGroups(@PathVariable String channelId) {
        return groupService.listByChannel(channelId);
    }

    @PutMapping("/{groupId}/agent")
    public Mono<ChannelGroupEntity> bindAgent(
            @PathVariable String channelId,
            @PathVariable String groupId,
            @RequestBody Map<String, String> body) {
        String agentName = body.get("agentName");
        if (agentName == null || agentName.isBlank()) {
            return Mono.error(new IllegalArgumentException("agentName is required"));
        }
        return groupService.bindAgent(channelId, groupId, agentName);
    }

    @DeleteMapping("/{groupId}/agent")
    public Mono<ChannelGroupEntity> unbindAgent(
            @PathVariable String channelId,
            @PathVariable String groupId) {
        return groupService.unbindAgent(channelId, groupId);
    }
}
