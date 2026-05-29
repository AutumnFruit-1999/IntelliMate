package com.atm.intellimate.channel.api.model;

import java.util.Map;

public record WebhookRequest(
        String method,
        Map<String, String> headers,
        Map<String, String> queryParams,
        String body,
        String contentType
) {
    public String getHeader(String name) {
        return headers != null ? headers.get(name.toLowerCase()) : null;
    }

    public String getQueryParam(String name) {
        return queryParams != null ? queryParams.get(name) : null;
    }
}
