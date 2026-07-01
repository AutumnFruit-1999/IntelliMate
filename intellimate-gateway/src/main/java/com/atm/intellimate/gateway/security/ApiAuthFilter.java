package com.atm.intellimate.gateway.security;

import com.atm.intellimate.core.config.IntelliMateProperties;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(1)
public class ApiAuthFilter implements WebFilter {

    private final IntelliMateProperties properties;
    private final JwtService jwtService;

    public ApiAuthFilter(IntelliMateProperties properties, JwtService jwtService) {
        this.properties = properties;
        this.jwtService = jwtService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api")) {
            return chain.filter(exchange);
        }

        if (path.startsWith("/api/auth/")) {
            return chain.filter(exchange);
        }

        String configuredToken = properties.getSecurity().getAuthToken();
        if (configuredToken == null || configuredToken.isBlank()) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        String token = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            token = authHeader.substring(7);
        }
        if (token == null) {
            token = exchange.getRequest().getQueryParams().getFirst("token");
        }

        if (token != null && configuredToken.equals(token)) {
            return chain.filter(exchange);
        }

        if (token != null && jwtService.validateToken(token).isPresent()) {
            return chain.filter(exchange);
        }

        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        return exchange.getResponse().setComplete();
    }
}
