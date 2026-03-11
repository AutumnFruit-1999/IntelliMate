package com.atm.javaclaw.agent.tools.dynamic;

import java.util.Map;

public record HttpExecutionConfig(
        String url,
        String method,
        Map<String, String> headers,
        String bodyTemplate,
        String responseExtract
) {}
