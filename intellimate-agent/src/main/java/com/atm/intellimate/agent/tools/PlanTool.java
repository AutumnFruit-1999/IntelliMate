package com.atm.intellimate.agent.tools;

import com.atm.intellimate.agent.plan.PlanOperations;
import com.atm.intellimate.agent.plan.PlanOperations.StepInput;
import com.atm.intellimate.core.prompt.PromptLoader;
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
public class PlanTool implements ToolCallback, ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(PlanTool.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
            .configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true);

    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "action":{"type":"string","description":"操作类型：create（创建计划）、step_done（标记步骤完成）、complete（完成计划）"},\
            "title":{"type":"string","description":"计划标题（create 时必填）"},\
            "steps":{"type":"array","items":{"type":"object","properties":{\
            "title":{"type":"string","description":"步骤标题"},\
            "description":{"type":"string","description":"步骤描述，包含所有操作细节，核心部分着重说明"},\
            "verification":{"type":"string","description":"验证方式——完成后如何确认这一步是成功的"}\
            },"required":["title","description","verification"]},"description":"计划步骤数组（create 时必填）"},\
            "stepIndex":{"type":"integer","description":"步骤索引（step_done 时必填）"},\
            "resultSummary":{"type":"string","description":"步骤完成摘要（step_done 时可选）"},\
            "summary":{"type":"string","description":"计划完成总结（complete 时可选）"}\
            },"required":["action"]}""";

    private final ToolDefinition toolDefinition;
    private final PlanOperations planOperations;
    private final AgentSessionContext sessionContext;

    public PlanTool(PlanOperations planOperations, AgentSessionContext sessionContext) {
        this.planOperations = planOperations;
        this.sessionContext = sessionContext;
        this.toolDefinition = ToolDefinition.builder()
                .name("plan")
                .description(PromptLoader.load("prompts/plan-description.md"))
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
            String jsonPayload = extractJsonPayload(toolInput);
            JsonNode root = parseLenient(jsonPayload);
            String action = root.has("action") ? root.get("action").asText() : "";

            return switch (action) {
                case "create" -> handleCreate(root, sessionId);
                case "step_done" -> handleStepDone(root);
                case "complete" -> handleComplete(root);
                default -> "{\"error\": \"Unknown action: " + action + ". Supported: create, step_done, complete\"}";
            };
        } catch (Exception e) {
            log.error("Plan tool execution failed: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage().replace("\"", "'").replace("\n", " ") + "\"}";
        }
    }

    private String handleCreate(JsonNode root, long sessionId) throws Exception {
        String title = root.has("title") ? root.get("title").asText() : "";
        JsonNode stepsNode = root.get("steps");

        if (stepsNode == null || !stepsNode.isArray() || stepsNode.isEmpty()) {
            return "{\"error\": \"At least one step is required\"}";
        }

        List<StepInput> steps = new ArrayList<>();
        for (JsonNode stepNode : stepsNode) {
            String stepTitle = stepNode.has("title") ? stepNode.get("title").asText() : "";
            String stepDesc = stepNode.has("description") ? stepNode.get("description").asText() : "";
            String verification = stepNode.has("verification") ? stepNode.get("verification").asText() : "";
            steps.add(new StepInput(stepTitle, stepDesc, verification));
        }

        PlanOperations.PlanResult result = planOperations.createPlan(sessionId, title, steps).block();
        if (result == null) {
            return "{\"error\": \"Failed to create plan\"}";
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("messageId", result.messageId());
        response.put("status", result.status());
        response.put("message", result.message());
        return MAPPER.writeValueAsString(response);
    }

    private String handleStepDone(JsonNode root) throws Exception {
        if (!root.has("stepIndex")) {
            return "{\"error\": \"stepIndex is required for step_done action\"}";
        }
        long messageId = root.has("messageId") ? root.get("messageId").asLong() : 0;
        int stepIndex = root.get("stepIndex").asInt();
        String resultSummary = root.has("resultSummary") && !root.get("resultSummary").isNull()
                ? root.get("resultSummary").asText() : null;

        PlanOperations.PlanResult result = planOperations.updateStep(messageId, stepIndex, "completed", resultSummary).block();
        if (result == null) {
            return "{\"error\": \"Failed to update step\"}";
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("messageId", result.messageId());
        response.put("stepIndex", stepIndex);
        response.put("status", result.status());
        response.put("message", result.message());
        return MAPPER.writeValueAsString(response);
    }

    private String handleComplete(JsonNode root) throws Exception {
        long messageId = root.has("messageId") ? root.get("messageId").asLong() : 0;
        String summary = root.has("summary") && !root.get("summary").isNull()
                ? root.get("summary").asText() : null;

        PlanOperations.PlanResult result = planOperations.completePlan(messageId, summary).block();
        if (result == null) {
            return "{\"error\": \"Failed to complete plan\"}";
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("messageId", result.messageId());
        response.put("status", result.status());
        response.put("message", result.message());
        return MAPPER.writeValueAsString(response);
    }

    private static JsonNode parseLenient(String json) throws Exception {
        try {
            return LENIENT_MAPPER.readTree(json);
        } catch (Exception e) {
            return MAPPER.readTree(json);
        }
    }

    private static String extractJsonPayload(String raw) {
        if (raw == null) return "{}";
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNl = s.indexOf('\n');
            if (firstNl > 0) s = s.substring(firstNl + 1);
            int fence = s.lastIndexOf("```");
            if (fence >= 0) s = s.substring(0, fence).trim();
        }
        int start = s.indexOf('{');
        if (start < 0) return s;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) { escape = false; continue; }
            if (c == '\\' && inString) { escape = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return s.substring(start, i + 1); }
        }
        return s.substring(start);
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return new ToolCallback[]{this};
    }
}
