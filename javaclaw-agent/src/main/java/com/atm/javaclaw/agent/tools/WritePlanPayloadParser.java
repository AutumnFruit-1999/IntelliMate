package com.atm.javaclaw.agent.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Normalizes model-produced tool arguments (markdown fences, Unicode quotes) and parses
 * writePlan payloads with a lenient JSON mapper for use by {@link WritePlanTool} fallbacks
 * and {@link com.atm.javaclaw.agent.runtime.AgentRuntime} plan event extraction.
 */
public final class WritePlanPayloadParser {

    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);

    public record StepRow(String title, String description) {}

    public record ParsedWritePlan(String title, List<StepRow> steps) {}

    private WritePlanPayloadParser() {}

    public static String normalizeUnicodeQuotes(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw == null ? "" : raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '\u201c', '\u201d' -> sb.append('"');
                case '\u2018', '\u2019' -> sb.append('\'');
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Models often wrap JSON in markdown fences or append prose; strip to the outermost JSON object.
     */
    static String extractJsonObjectPayload(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) {
                s = s.substring(firstNl + 1);
            }
            int fence = s.lastIndexOf("```");
            if (fence >= 0) {
                s = s.substring(0, fence).trim();
            }
        }
        int start = s.indexOf('{');
        if (start < 0) {
            return s;
        }
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return s.substring(start);
    }

    public static ParsedWritePlan parse(String raw) throws JsonProcessingException {
        String normalized = normalizeUnicodeQuotes(raw != null ? raw : "");
        String jsonPayload = extractJsonObjectPayload(normalized);
        JsonNode root = LENIENT_MAPPER.readTree(jsonPayload);
        String title = root.has("title") ? root.get("title").asText() : "";
        JsonNode stepsNode = root.get("steps");
        if (stepsNode == null || !stepsNode.isArray()) {
            return new ParsedWritePlan(title, List.of());
        }
        List<StepRow> steps = new ArrayList<>();
        for (JsonNode stepNode : stepsNode) {
            String st = stepNode.has("title") ? stepNode.get("title").asText() : "";
            String sd = stepNode.has("description") ? stepNode.get("description").asText() : "";
            steps.add(new StepRow(st, sd));
        }
        return new ParsedWritePlan(title, steps);
    }
}
