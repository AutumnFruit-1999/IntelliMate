package com.atm.intellimate.core.protocol;

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
