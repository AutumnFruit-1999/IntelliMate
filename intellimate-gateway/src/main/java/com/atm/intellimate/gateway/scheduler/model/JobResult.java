package com.atm.intellimate.gateway.scheduler.model;

import java.util.Map;

public record JobResult(
    boolean success,
    String message,
    Map<String, Object> metrics
) {
    public static JobResult ok(String msg) {
        return new JobResult(true, msg, Map.of());
    }

    public static JobResult ok(String msg, Map<String, Object> metrics) {
        return new JobResult(true, msg, metrics);
    }

    public static JobResult fail(String msg) {
        return new JobResult(false, msg, Map.of());
    }

    public static JobResult fail(String msg, Map<String, Object> metrics) {
        return new JobResult(false, msg, metrics);
    }
}
