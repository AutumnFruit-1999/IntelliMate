package com.atm.intellimate.gateway.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public record ChannelInfoDto(
        String channelId,
        String status,
        boolean enabled,
        Map<String, Object> config,
        JsonNode configSchema
) {}
