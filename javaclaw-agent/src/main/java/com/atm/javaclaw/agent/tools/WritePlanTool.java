package com.atm.javaclaw.agent.tools;

import com.atm.javaclaw.agent.plan.PlanOperations;
import com.atm.javaclaw.agent.plan.PlanOperations.StepInput;
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
            "title":{"type":"string","description":"A concise title summarizing the overall goal"},\
            "steps":{"type":"array","items":{"type":"object","properties":{\
            "title":{"type":"string","description":"Step title"},\
            "description":{"type":"string","description":"Step description"}\
            },"required":["title","description"]},"description":"Array of plan steps"}\
            },"required":["title","steps"]}""";

    private final ToolDefinition toolDefinition;
    private final PlanOperations planOperations;
    private final AgentSessionContext sessionContext;

    public WritePlanTool(PlanOperations planOperations, AgentSessionContext sessionContext) {
        this.planOperations = planOperations;
        this.sessionContext = sessionContext;
        this.toolDefinition = ToolDefinition.builder()
                .name("writePlan")
                .description("Create a structured execution plan with numbered steps. "
                        + "Use this when the user's task is complex and requires multiple distinct steps. "
                        + "The plan will be presented to the user for approval before execution begins. "
                        + "Each step should be a clear, actionable unit of work. "
                        + "IMPORTANT: Pass ONLY a single valid JSON object as arguments (no markdown fences, no prose before/after). "
                        + "Escape double quotes inside string values; use \\n for newlines in descriptions.")
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
            JsonNode root = LENIENT_MAPPER.readTree(jsonPayload);
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


    /**
     * Models often wrap JSON in markdown fences or append Chinese prose; strip to the outermost JSON object.
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

    @Override
    public ToolCallback[] getToolCallbacks() {
        return new ToolCallback[]{this};
    }
}
