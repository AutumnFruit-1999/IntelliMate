package com.atm.javaclaw.agent.tools;

import com.atm.javaclaw.agent.plan.PlanOperations;
import com.atm.javaclaw.agent.plan.PlanOperations.StepInput;
import com.atm.javaclaw.core.prompt.PromptLoader;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class WritePlanTool implements ToolCallback, ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(WritePlanTool.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);

    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "title":{"type":"string","description":"简洁概括整体目标的计划标题"},\
            "steps":{"type":"array","items":{"type":"object","properties":{\
            "title":{"type":"string","description":"步骤标题"},\
            "description":{"type":"string","description":"步骤描述，包含具体操作和预期产出"}\
            },"required":["title","description"]},"description":"计划步骤数组"}\
            },"required":["title","steps"]}""";

    private final ToolDefinition toolDefinition;
    private final PlanOperations planOperations;
    private final AgentSessionContext sessionContext;

    public WritePlanTool(PlanOperations planOperations, AgentSessionContext sessionContext) {
        this.planOperations = planOperations;
        this.sessionContext = sessionContext;
        this.toolDefinition = ToolDefinition.builder()
                .name("writePlan")
                .description(PromptLoader.load("prompts/write-plan-description.md"))
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        Long sessionId = sessionContext.getCurrentSessionId();
        if (sessionId == null) {
            return "{\"error\": \"No active session context\"}";
        }

        try {
            String jsonPayload = extractJsonObjectPayload(toolInput);
            JsonNode root = parseJsonLenient(jsonPayload);
            String title = root.has("title") ? root.get("title").asText() : "";
            JsonNode stepsNode = root.get("steps");

            if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
                return "{\"error\": \"At least one step is required\"}";
            }

            List<StepInput> steps = new ArrayList<>();
            for (JsonNode stepNode : stepsNode) {
                String stepTitle = stepNode.has("title") ? stepNode.get("title").asText() : "";
                String stepDesc = stepNode.has("description") ? stepNode.get("description").asText() : "";
                steps.add(new StepInput(stepTitle, stepDesc));
            }

            log.info("Creating plan '{}' with {} steps for session {}", title, steps.size(), sessionId);

            PlanOperations.PlanResult result = planOperations.createPlan(sessionId, title, steps);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("planId", result.planId());
            response.put("status", result.status());
            response.put("message", result.message());
            String json = MAPPER.writeValueAsString(response);
            log.debug("writePlan result: {}", json);
            return json;
        } catch (Exception e) {
            log.error("Failed to create plan: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage().replace("\"", "'").replace("\n", " ") + "\"}";
        }
    }

    public static JsonNode parseJsonLenient(String json) throws Exception {
        try {
            return LENIENT_MAPPER.readTree(json);
        } catch (Exception firstError) {
            log.debug("Initial JSON parse failed, attempting repair: {}", firstError.getMessage());
            String repaired = repairUnquotedValues(json);
            if (!repaired.equals(json)) {
                try {
                    JsonNode result = LENIENT_MAPPER.readTree(repaired);
                    log.info("JSON repair succeeded");
                    return result;
                } catch (Exception secondError) {
                    log.debug("Repaired JSON also failed: {}", secondError.getMessage());
                }
            }

            JsonNode fallback = tryRegexFallback(json);
            if (fallback != null) {
                log.info("Regex fallback succeeded for plan JSON");
                return fallback;
            }
            throw firstError;
        }
    }

    /**
     * Last-resort extraction: use regex to pull title and steps from malformed JSON.
     */
    private static JsonNode tryRegexFallback(String json) {
        try {
            java.util.regex.Pattern titlePat = java.util.regex.Pattern.compile(
                    "\"title\"\\s*:\\s*\"([^\"]+)\"");
            java.util.regex.Pattern stepPat = java.util.regex.Pattern.compile(
                    "\\{\\s*\"title\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"description\"\\s*:\\s*\"?([^\"\\}]+)\"?\\s*\\}");

            java.util.regex.Matcher titleMatcher = titlePat.matcher(json);
            String title = "";
            if (titleMatcher.find()) {
                title = titleMatcher.group(1);
            }

            com.fasterxml.jackson.databind.node.ObjectNode root = MAPPER.createObjectNode();
            root.put("title", title);
            com.fasterxml.jackson.databind.node.ArrayNode stepsArr = root.putArray("steps");

            java.util.regex.Matcher stepMatcher = stepPat.matcher(json);
            int lastEnd = 0;
            while (stepMatcher.find(lastEnd)) {
                com.fasterxml.jackson.databind.node.ObjectNode step = MAPPER.createObjectNode();
                step.put("title", stepMatcher.group(1));
                step.put("description", stepMatcher.group(2).trim());
                stepsArr.add(step);
                lastEnd = stepMatcher.end();
            }

            if (stepsArr.isEmpty()) return null;
            log.debug("Regex fallback extracted title='{}' with {} steps", title, stepsArr.size());
            return root;
        } catch (Exception e) {
            log.debug("Regex fallback failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Attempts to wrap unquoted string values in double quotes.
     * Handles the common case where the model outputs:
     *   "description": 使用 mkdir 创建目录
     * instead of:
     *   "description": "使用 mkdir 创建目录"
     * Also correctly tracks single-quoted strings to avoid corrupting them.
     */
    public static String repairUnquotedValues(String json) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = json.length();
        char stringDelim = 0;
        boolean escape = false;

        while (i < len) {
            char c = json.charAt(i);

            if (escape) {
                sb.append(c);
                escape = false;
                i++;
                continue;
            }
            if (c == '\\' && stringDelim != 0) {
                sb.append(c);
                escape = true;
                i++;
                continue;
            }
            if (stringDelim != 0 && c == stringDelim) {
                stringDelim = 0;
                sb.append(c);
                i++;
                continue;
            }
            if (stringDelim == 0 && (c == '"' || c == '\'')) {
                stringDelim = c;
                sb.append(c);
                i++;
                continue;
            }
            if (stringDelim != 0) {
                sb.append(c);
                i++;
                continue;
            }

            if (c == ':') {
                sb.append(c);
                i++;
                while (i < len && (json.charAt(i) == ' ' || json.charAt(i) == '\t')) {
                    sb.append(json.charAt(i));
                    i++;
                }
                if (i < len) {
                    char next = json.charAt(i);
                    if (next != '"' && next != '\'' && next != '{' && next != '['
                            && next != 't' && next != 'f' && next != 'n' && next != '-'
                            && !Character.isDigit(next)) {
                        int valueEnd = findUnquotedValueEnd(json, i);
                        String rawValue = json.substring(i, valueEnd).trim();
                        rawValue = rawValue.replace("\\", "\\\\").replace("\"", "\\\"")
                                .replace("\n", "\\n").replace("\r", "\\r");
                        sb.append('"').append(rawValue).append('"');
                        i = valueEnd;
                    }
                }
                continue;
            }

            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static int findUnquotedValueEnd(String json, int start) {
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') {
                if (depth == 0) return i;
                depth--;
            } else if ((c == ',' || c == '\n') && depth == 0) {
                return i;
            }
        }
        return json.length();
    }


    /**
     * Models often wrap JSON in markdown fences or append Chinese prose; strip to the outermost JSON object.
     */
    public static String extractJsonObjectPayload(String raw) {
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

    @Override
    public ToolCallback[] getToolCallbacks() {
        return new ToolCallback[]{this};
    }
}
