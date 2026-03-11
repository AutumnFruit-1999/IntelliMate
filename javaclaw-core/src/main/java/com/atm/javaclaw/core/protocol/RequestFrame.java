package com.atm.javaclaw.core.protocol;

public record RequestFrame(
        String id,
        String method,
        Object params
) implements GatewayFrame {

    @Override
    public String type() {
        return "request";
    }
}
