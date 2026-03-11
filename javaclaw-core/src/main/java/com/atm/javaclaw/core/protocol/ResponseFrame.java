package com.atm.javaclaw.core.protocol;

public record ResponseFrame(
        String id,
        boolean ok,
        Object payload,
        Object error
) implements GatewayFrame {

    @Override
    public String type() {
        return "response";
    }

    public static ResponseFrame success(String id, Object payload) {
        return new ResponseFrame(id, true, payload, null);
    }

    public static ResponseFrame failure(String id, Object error) {
        return new ResponseFrame(id, false, null, error);
    }
}
