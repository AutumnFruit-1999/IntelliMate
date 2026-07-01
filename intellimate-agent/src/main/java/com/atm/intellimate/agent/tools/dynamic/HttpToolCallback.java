package com.atm.intellimate.agent.tools.dynamic;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(HttpToolCallback.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{env:([^}]+)}");
    private static final Pattern PARAM_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final ToolDefinition toolDefinition;
    private final HttpExecutionConfig config;
    private final WebClient webClient;
    private final int timeoutSeconds;

    public HttpToolCallback(DynamicToolDefinition def, WebClient webClient) {
        this.config = parseConfig(def.executionConfig());
        this.toolDefinition = buildToolDefinition(def);
        this.webClient = webClient;
        this.timeoutSeconds = def.timeoutSeconds();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        try {
            Map<String, Object> args = parseArguments(toolInput);

            String url = resolveTemplate(config.url(), args);
            String body = resolveTemplate(config.bodyTemplate(), args);
            Map<String, String> headers = resolveHeaders(config.headers(), args);
            HttpMethod method = HttpMethod.valueOf(config.method().toUpperCase());

            WebClient.RequestBodySpec spec = webClient.method(method).uri(url);
            headers.forEach(spec::header);

            String response;
            if (body != null && !body.isBlank() && (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH)) {
                response = spec.bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .block();
            } else {
                response = spec.retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .block();
            }

            if (config.responseExtract() != null && !config.responseExtract().isBlank() && response != null) {
                try {
                    Object extracted = JsonPath.read(response, config.responseExtract());
                    return extracted instanceof String s ? s : MAPPER.writeValueAsString(extracted);
                } catch (Exception e) {
                    log.warn("JSONPath extraction failed for {}: {}", config.responseExtract(), e.getMessage());
                }
            }

            return response != null ? response : "";
        } catch (Exception e) {
            log.error("HttpToolCallback [{}] execution failed: {}", toolDefinition.name(), e.getMessage(), e);
            return "ERROR: " + e.getMessage();
        }
    }

    private Map<String, Object> parseArguments(String toolInput) {
        if (toolInput == null || toolInput.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(toolInput, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse tool input as JSON: {}", e.getMessage());
            return Map.of();
        }
    }

    private String resolveTemplate(String template, Map<String, Object> args) {
        if (template == null) return null;
        String result = template;
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            result = result.replace("${" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        }
        result = ENV_PATTERN.matcher(result).replaceAll(m -> {
            String envVal = System.getenv(m.group(1));
            return envVal != null ? Matcher.quoteReplacement(envVal) : "";
        });
        return result;
    }

    private Map<String, String> resolveHeaders(Map<String, String> headers, Map<String, Object> args) {
        if (headers == null || headers.isEmpty()) return Map.of();
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            resolved.put(entry.getKey(), resolveTemplate(entry.getValue(), args));
        }
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private HttpExecutionConfig parseConfig(String json) {
        try {
            Map<String, Object> map = MAPPER.readValue(json, new TypeReference<>() {});
            return new HttpExecutionConfig(
                    (String) map.get("url"),
                    (String) map.getOrDefault("method", "GET"),
                    map.containsKey("headers") ? (Map<String, String>) map.get("headers") : Map.of(),
                    (String) map.get("bodyTemplate"),
                    (String) map.get("responseExtract")
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid HTTP execution config: " + e.getMessage(), e);
        }
    }

    private ToolDefinition buildToolDefinition(DynamicToolDefinition def) {
        String schema = def.parametersSchema();
        if (schema == null || schema.isBlank()) {
            schema = "{\"type\":\"object\",\"properties\":{}}";
        }
        return ToolDefinition.builder()
                .name(def.name())
                .description(def.description() != null ? def.description() : def.name())
                .inputSchema(schema)
                .build();
    }
}
