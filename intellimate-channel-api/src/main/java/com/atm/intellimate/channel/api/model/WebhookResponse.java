package com.atm.intellimate.channel.api.model;

public record WebhookResponse(
        int statusCode,
        String body,
        String contentType
) {
    public static WebhookResponse ok() {
        return new WebhookResponse(200, "{\"status\":\"ok\"}", "application/json");
    }

    public static WebhookResponse ok(String body) {
        return new WebhookResponse(200, body, "text/plain");
    }

    public static WebhookResponse unauthorized() {
        return new WebhookResponse(401, "{\"error\":\"unauthorized\"}", "application/json");
    }

    public static WebhookResponse notFound() {
        return new WebhookResponse(404, "{\"error\":\"not found\"}", "application/json");
    }
}
