package com.atm.javaclaw.agent.tools;

import com.atm.javaclaw.agent.plan.PlanOperations;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class UpdatePlanTool {

    private static final Logger log = LoggerFactory.getLogger(UpdatePlanTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PlanOperations planOperations;

    public UpdatePlanTool(PlanOperations planOperations) {
        this.planOperations = planOperations;
    }

    @Tool(description = "Update an active execution plan. Supported actions:\n"
            + "- markStep: Mark a step as 'in_progress', 'completed', or 'failed'\n"
            + "- addStep: Insert a new step after a given index\n"
            + "- removeStep: Remove an unnecessary step\n"
            + "- completePlan: Mark the entire plan as completed (skips remaining steps)")
    public String updatePlan(
            @ToolParam(description = "The plan ID to update") Long planId,
            @ToolParam(description = "Action: markStep, addStep, removeStep, or completePlan") String action,
            @ToolParam(description = "Step index (required for markStep, addStep, removeStep)", required = false) Integer stepIndex,
            @ToolParam(description = "New status for markStep (in_progress/completed/failed), or reason for removeStep", required = false) String status,
            @ToolParam(description = "Result summary for markStep(completed/failed) or completePlan", required = false) String resultSummary,
            @ToolParam(description = "Title for addStep", required = false) String title,
            @ToolParam(description = "Description for addStep", required = false) String description
    ) {
        try {
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
}
