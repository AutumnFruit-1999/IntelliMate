package com.atm.javaclaw.gateway.websocket;

import com.atm.javaclaw.core.protocol.GatewayFrame;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Encodes/decodes GatewayFrame to/from JSON text for WebSocket transport.
 */
@Component
public class ProtocolCodec {

    private static final Logger log = LoggerFactory.getLogger(ProtocolCodec.class);

    private final ObjectMapper objectMapper;

    public ProtocolCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GatewayFrame decode(String json) {
        try {
            return objectMapper.readValue(json, GatewayFrame.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to decode gateway frame: {}", json, e);
            throw new IllegalArgumentException("Invalid gateway frame JSON", e);
        }
    }

    public String encode(GatewayFrame frame) {
        try {
            return objectMapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            log.error("Failed to encode gateway frame: {}", frame, e);
            throw new IllegalStateException("Failed to serialize gateway frame", e);
        }
    }
}
