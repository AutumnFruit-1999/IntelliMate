package com.atm.intellimate.agent.runtime;

import com.atm.intellimate.agent.plan.PlanOperations;
import com.atm.intellimate.agent.tools.WritePlanTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PlanEventExtractor {

    private static final Logger log = LoggerFactory.getLogger(PlanEventExtractor.class);

    private final ObjectMapper objectMapper;
    private final PlanOperations planOperations;

    public PlanEventExtractor(ObjectMapper objectMapper,
                              @Autowired(required = false) PlanOperations planOperations) {
        this.objectMapper = objectMapper;
        this.planOperations = planOperations;
    }

    /**
     * Filters plan events that were already emitted by the auto-tracker to avoid
     * duplicates when the LLM also calls updatePlan(markStep, in_progress).
     */
    public static List<AgentEvent> filterDuplicatePlanEvents(List<AgentEvent> events, PlanStepTracker tracker) {
        List<AgentEvent> filtered = new ArrayList<>(events.size());
        for (AgentEvent evt : events) {
            if (evt instanceof AgentEvent.PlanStepStart pss) {
                if (tracker.isAutoStartedStep(pss.stepIndex())) {
                    log.debug("Filtering duplicate PlanStepStart for auto-started step {}", pss.stepIndex());
                    continue;
                }
                tracker.onStepStart(pss.stepIndex());
            } else if (evt instanceof AgentEvent.PlanStepDone psd) {
                tracker.onStepDone(psd.stepIndex());
            } else if (evt instanceof AgentEvent.PlanCompleted) {
                tracker.onPlanCompleted();
            }
            filtered.add(evt);
        }
        return filtered;
    }

    /**
     * After a writePlan/updatePlan tool execution succeeds, parse the JSON result
     * and arguments to produce the corresponding Plan* AgentEvents that the
     * frontend needs for real-time UI updates.
     */
    public List<AgentEvent> extractPlanEvents(String toolName, String arguments, String result) {
        if (!"writePlan".equals(toolName) && !"updatePlan".equals(toolName)) {
            return List.of();
        }
        if (arguments == null || result == null) {
            log.warn("extractPlanEvents: null arguments or result for tool={}", toolName);
            return List.of();
        }
        log.debug("extractPlanEvents: tool={}, resultLen={}, argsLen={}, result={}",
                toolName, result.length(), arguments.length(),
                result.substring(0, Math.min(result.length(), 500)));
        try {
            JsonNode resultNode = objectMapper.readTree(result);
            // Defense against double-encoded JSON (e.g. from @Tool methods whose
            // String return value gets re-serialized by Spring AI's MethodToolCallback).
            if (resultNode.isTextual()) {
                log.debug("extractPlanEvents: result is a JSON string (double-encoded), unwrapping");
                resultNode = objectMapper.readTree(resultNode.asText());
            }
            if (resultNode.has("error")) {
                log.debug("extractPlanEvents: result contains 'error' key, skipping");
                return List.of();
            }
            String resultStatus = resultNode.has("status") ? resultNode.get("status").asText() : "";
            if ("error".equals(resultStatus)) {
                log.warn("extractPlanEvents: tool {} returned error status: {}",
                        toolName, resultNode.has("message") ? resultNode.get("message").asText() : "unknown");
                return List.of();
            }

            List<AgentEvent> events;
            if ("writePlan".equals(toolName)) {
                events = extractWritePlanEvents(arguments, resultNode);
            } else {
                events = extractUpdatePlanEvents(arguments, resultNode);
            }
            log.info("extractPlanEvents: emitting {} plan event(s) for {}", events.size(), toolName);
            return events;
        } catch (Exception e) {
            log.warn("Failed to extract plan events from {} result: {}", toolName, e.getMessage(), e);
            return List.of();
        }
    }

    private List<AgentEvent> extractWritePlanEvents(String arguments, JsonNode resultNode) throws Exception {
        log.debug("extractWritePlanEvents: resultNode={}", resultNode);
        JsonNode planIdNode = resultNode.get("planId");
        if (planIdNode == null) planIdNode = resultNode.get("plan_id");
        if (planIdNode == null) planIdNode = resultNode.get("id");
        if (planIdNode == null || planIdNode.isNull()) {
            log.warn("extractWritePlanEvents: no planId found in result: {}", resultNode);
            return List.of();
        }
        Long planId = planIdNode.asLong();

        String cleanArgs = WritePlanTool.extractJsonObjectPayload(arguments);
        JsonNode argsNode = WritePlanTool.parseJsonLenient(cleanArgs);
        String title = argsNode.has("title") ? argsNode.get("title").asText() : "";
        JsonNode stepsArray = argsNode.has("steps") ? argsNode.get("steps") : objectMapper.createArrayNode();
        log.debug("extractWritePlanEvents: title={}, steps count={}", title, stepsArray.size());

        List<AgentEvent.PlanStepInfo> steps = new ArrayList<>();
        for (int i = 0; i < stepsArray.size(); i++) {
            JsonNode step = stepsArray.get(i);
            steps.add(new AgentEvent.PlanStepInfo(
                    i + 1,
                    step.has("title") ? step.get("title").asText() : "",
                    step.has("description") ? step.get("description").asText() : ""
            ));
        }
        log.info("extractWritePlanEvents: PlanCreated planId={}, title='{}', steps={}", planId, title, steps.size());

        return List.of(
                new AgentEvent.PlanCreated(planId, title, steps),
                new AgentEvent.PlanAwaitingApproval(planId)
        );
    }

    private List<AgentEvent> extractUpdatePlanEvents(String arguments, JsonNode resultNode) throws Exception {
        String cleanArgs = WritePlanTool.extractJsonObjectPayload(arguments);
        JsonNode argsNode = WritePlanTool.parseJsonLenient(cleanArgs);
        JsonNode planIdNode = argsNode.get("planId");
        if (planIdNode == null) planIdNode = argsNode.get("plan_id");
        JsonNode actionNode = argsNode.get("action");
        if (planIdNode == null || planIdNode.isNull() || actionNode == null || actionNode.isNull()) {
            log.warn("extractUpdatePlanEvents: missing planId or action in args: {}",
                    arguments.substring(0, Math.min(arguments.length(), 500)));
            return List.of();
        }
        Long planId = planIdNode.asLong();
        String action = actionNode.asText();
        log.debug("extractUpdatePlanEvents: planId={}, action={}", planId, action);

        return switch (action) {
            case "markStep" -> {
                JsonNode stepIndexNode = argsNode.get("stepIndex");
                JsonNode statusNode = argsNode.get("status");
                if (stepIndexNode == null || statusNode == null) {
                    log.warn("extractUpdatePlanEvents: markStep missing stepIndex or status");
                    yield List.of();
                }
                int stepIndex = stepIndexNode.asInt();
                String status = statusNode.asText();
                if ("in_progress".equals(status)) {
                    yield java.util.List.<AgentEvent>of(
                            new AgentEvent.PlanStatusChanged(planId, "executing"),
                            new AgentEvent.PlanStepStart(planId, stepIndex, ""));
                } else {
                    String summary = "";
                    if (argsNode.has("resultSummary") && !argsNode.get("resultSummary").isNull()) {
                        summary = argsNode.get("resultSummary").asText();
                    }
                    yield List.of((AgentEvent) new AgentEvent.PlanStepDone(planId, stepIndex, status, summary));
                }
            }
            case "completePlan" -> List.of((AgentEvent) new AgentEvent.PlanCompleted(planId, "completed"));
            case "addStep", "removeStep" -> {
                List<AgentEvent.PlanStepInfo> currentSteps = List.of();
                if (planOperations != null) {
                    try {
                        currentSteps = planOperations.getSteps(planId).stream()
                                .map(s -> new AgentEvent.PlanStepInfo(s.index(), s.title(), s.description()))
                                .toList();
                    } catch (Exception e) {
                        log.warn("Failed to load steps for PlanAdjusted event: {}", e.getMessage());
                    }
                }
                yield List.of((AgentEvent) new AgentEvent.PlanAdjusted(planId, action, currentSteps));
            }
            default -> List.of();
        };
    }
}
