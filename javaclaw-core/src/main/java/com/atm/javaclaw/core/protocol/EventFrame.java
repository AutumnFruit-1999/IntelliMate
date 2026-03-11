package com.atm.javaclaw.core.protocol;

public record EventFrame(
        String event,
        Object payload,
        Long seq
) implements GatewayFrame {

    @Override
    public String type() {
        return "event";
    }
}
