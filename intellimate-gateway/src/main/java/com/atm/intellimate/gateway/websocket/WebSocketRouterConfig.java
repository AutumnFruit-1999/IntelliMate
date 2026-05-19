package com.atm.intellimate.gateway.websocket;

import com.atm.intellimate.gateway.bridge.BridgeWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

import java.util.Map;

@Configuration
public class WebSocketRouterConfig {

    @Bean
    public HandlerMapping webSocketHandlerMapping(GatewayWebSocketHandler handler,
                                                  BridgeWebSocketHandler bridgeHandler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        mapping.setUrlMap(Map.of(
                "/ws", handler,
                "/api/bridge/connect", bridgeHandler
        ));
        mapping.setOrder(-1);

        CorsConfiguration cors = new CorsConfiguration();
        cors.addAllowedOriginPattern("*");
        cors.addAllowedHeader("*");
        cors.addAllowedMethod("*");
        cors.setAllowCredentials(true);
        mapping.setCorsConfigurations(Map.of("/ws", cors, "/api/bridge/connect", cors));

        return mapping;
    }

    @Bean
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        return new WebSocketHandlerAdapter();
    }
}
