package com.atm.javaclaw.agent.tools;

import com.atm.javaclaw.agent.plan.PlanOperations;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implements ToolCallback directly (instead of @Tool) to avoid Spring AI's
 * MethodToolCallback double-encoding the already-serialized JSON return value.
 */
@Component
public class UpdatePlanTool implements ToolCallback, ToolCallbackProvider {

    private static final Logger log = LoggerFactory.getLogger(UpdatePlanTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DESCRIPTION =
            "Update an active execution plan. Supported actions:\n"
            + "- markStep: Mark a step as 'in_progress', 'completed', or 'failed'\n"
            + "- addStep: Insert a new step after a given index\n"
            + "- removeStep: Remove an unnecessary step\n"
            + "- completePlan: Mark the entire plan as completed (skips remaining steps)";

    private static final String INPUT_SCHEMA = """
            {"type":"object","properties":{\
            "planId":{"type":"integer","description":"The plan ID to update"},\
            "action":{"type":"string","description":"Action: markStep, addStep, removeStep, or completePlan"},\
            "stepIndex":{"type":"integer","description":"Step index (required for markStep, addStep, removeStep)"},\
            "status":{"type":"string","description":"New status for markStep (in_progress/completed/failed), or reason for removeStep"},\
            "resultSummary":{"type":"string","description":"Result summary for markStep(completed/failed) or completePlan"},\
            "title":{"type":"string","description":"Title for addStep"},\
            "description":{"type":"string","description":"Description for addStep"}\
            },"required":["planId","action"]}""";

    private final ToolDefinition toolDefinition;
    private final PlanOperations planOperations;

    public UpdatePlanTool(PlanOperations planOperations) {
        this.planOperations = planOperations;
        this.toolDefinition = ToolDefinition.builder()
                .name("updatePlan")
                .description(DESCRIPTION)
                .inputSchema(INPUT_SCHEMA)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        try {
            JsonNode root = MAPPER.readTree(toolInput);

            Long planId = root.has("planId") ? root.get("planId").asLong() : null;
            String action = root.has("action") ? root.get("action").asText() : null;
            Integer stepIndex = root.has("stepIndex") && !root.get("stepIndex").isNull()
                    ? root.get("stepIndex").asInt() : null;
            String status = root.has("status") && !root.get("status").isNull()
                    ? root.get("status").asText() : null;
            String resultSummary = root.has("resultSummary") && !root.get("resultSummary").isNull()
                    ? root.get("resultSummary").asText() : null;
            String title = root.has("title") && !root.get("title").isNull()
                    ? root.get("title").asText() : null;
            String description = root.has("description") && !root.get("description").isNull()
                    ? root.get("description").asText() : null;

            if (planId == null || action == null) {
                return "{\"error\": \"planId and action are required\"}";
            }

            log.info("updatePlan: planId={}, action={}, stepIndex={}", planId, action, stepIndex);

            return switch (action) {
                case "markStep" -> {
                    if (stepIndex == null || status == null) {
                        yield "{\"error\": \"markStep requires stepIndex and status\"}";
                    }
                    var result = planOperations.markStep(planId, stepIndex, status, resultSummary);
                    yield toJson(result.planId(), result.stepIndex(), result.status(), result.message());
                }
                case "addStep" -> {
                    if (stepIndex == null || title == null) {
                        yield "{\"error\": \"addStep requires stepIndex (afterIndex) and title\"}";
                    }
                    var result = planOperations.addStep(planId, stepIndex, title, description);
                    yield toJson(result.planId(), result.stepIndex(), result.status(), result.message());
                }
                case "removeStep" -> {
                    if (stepIndex == null) {
                        yield "{\"error\": \"removeStep requires stepIndex\"}";
                    }
                    var result = planOperations.removeStep(planId, stepIndex, resultSummary);
                    yield toJson(result.planId(), result.stepIndex(), result.status(), result.message());
                }
                case "completePlan" -> {
                    var result = planOperations.completePlan(planId, resultSummary);
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("planId", result.planId());
                    resp.put("status", result.status());
                    resp.put("message", result.message());
                    yield MAPPER.writeValueAsString(resp);
                }
                default -> "{\"error\": \"Unknown action: " + action + "\"}";
            };
        } catch (Exception e) {
            log.error("Failed to update plan: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage().replace("\"", "'").replace("\n", " ") + "\"}";
        }
    }

    private String toJson(Long planId, int stepIndex, String status, String message) throws Exception {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("planId", planId);
        resp.put("stepIndex", stepIndex);
        resp.put("status", status);
        resp.put("message", message);
        return MAPPER.writeValueAsString(resp);
    }

    @Override
    public ToolCallback[] getToolCallbacks() {
        return new ToolCallback[]{this};
    }
}
