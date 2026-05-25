package com.atm.intellimate.gateway.dto;

import java.time.Instant;
import java.util.Map;

public record ApiError(
    String code,
    String message,
    Instant timestamp,
    Map<String, Object> details
) {
    public ApiError(String code, String message) {
        this(code, message, Instant.now(), null);
    }

    public ApiError(String code, String message, Map<String, Object> details) {
        this(code, message, Instant.now(), details);
    }
}
