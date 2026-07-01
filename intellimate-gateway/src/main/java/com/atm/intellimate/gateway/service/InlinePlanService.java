package com.atm.intellimate.gateway.service;

import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.repository.TranscriptMessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class InlinePlanService {

    private static final Logger log = LoggerFactory.getLogger(InlinePlanService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> TERMINAL_STATUSES = Set.of("completed", "cancelled", "failed");

    private final TranscriptMessageRepository messageRepository;

    public InlinePlanService(TranscriptMessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    public Mono<TranscriptMessageEntity> createPlanMessage(long sessionId, String title, List<Map<String, Object>> steps) {
        return getActivePlan(sessionId)
                .flatMap(existing -> Mono.<TranscriptMessageEntity>error(
                        new IllegalStateException("Session already has an active plan (messageId=" + existing.getId() + ")")))
                .switchIfEmpty(Mono.defer(() -> {
                    Map<String, Object> planData = new LinkedHashMap<>();
                    planData.put("status", "draft");
                    planData.put("steps", steps);
                    planData.put("completionSummary", null);

                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("type", "plan");
                    metadata.put("plan", planData);

                    TranscriptMessageEntity entity = new TranscriptMessageEntity();
                    entity.setSessionId(sessionId);
                    entity.setRole("assistant");
                    entity.setContent(title);
                    entity.setCreatedAt(LocalDateTime.now());
                    try {
                        entity.setMetadataJson(MAPPER.writeValueAsString(metadata));
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                    return messageRepository.save(entity);
                }));
    }

    public Mono<Void> updateStepStatus(long messageId, int stepIndex, String status, String resultSummary) {
        return messageRepository.findById(messageId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Plan message not found: " + messageId)))
                .flatMap(msg -> {
                    try {
                        Map<String, Object> metadata = parseMetadata(msg.getMetadataJson());
                        @SuppressWarnings("unchecked")
                        Map<String, Object> plan = (Map<String, Object>) metadata.get("plan");
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> steps = (List<Map<String, Object>>) plan.get("steps");

                        if (stepIndex < 0 || stepIndex >= steps.size()) {
                            return Mono.error(new IllegalArgumentException("Invalid step index: " + stepIndex));
                        }

                        Map<String, Object> step = steps.get(stepIndex);
                        step.put("status", status);
                        if (resultSummary != null) {
                            step.put("resultSummary", resultSummary);
                        }

                        String planStatus = (String) plan.get("status");
                        if ("in_progress".equals(status) && ("approved".equals(planStatus) || "draft".equals(planStatus))) {
                            plan.put("status", "executing");
                        }

                        msg.setMetadataJson(MAPPER.writeValueAsString(metadata));
                        return messageRepository.save(msg).then();
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                });
    }

    public Mono<Void> updatePlanStatus(long messageId, String newStatus) {
        return messageRepository.findById(messageId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Plan message not found: " + messageId)))
                .flatMap(msg -> {
                    try {
                        Map<String, Object> metadata = parseMetadata(msg.getMetadataJson());
                        @SuppressWarnings("unchecked")
                        Map<String, Object> plan = (Map<String, Object>) metadata.get("plan");
                        String currentStatus = (String) plan.get("status");

                        if (TERMINAL_STATUSES.contains(currentStatus)) {
                            return Mono.error(new IllegalStateException(
                                    "Cannot change status from terminal state: " + currentStatus));
                        }

                        plan.put("status", newStatus);

                        if ("cancelled".equals(newStatus)) {
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> steps = (List<Map<String, Object>>) plan.get("steps");
                            for (Map<String, Object> step : steps) {
                                String stepStatus = (String) step.get("status");
                                if ("pending".equals(stepStatus) || "in_progress".equals(stepStatus)) {
                                    step.put("status", "skipped");
                                }
                            }
                        }

                        msg.setMetadataJson(MAPPER.writeValueAsString(metadata));
                        return messageRepository.save(msg).then();
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                });
    }

    public Mono<Void> completePlan(long messageId, String summary) {
        return messageRepository.findById(messageId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Plan message not found: " + messageId)))
                .flatMap(msg -> {
                    try {
                        Map<String, Object> metadata = parseMetadata(msg.getMetadataJson());
                        @SuppressWarnings("unchecked")
                        Map<String, Object> plan = (Map<String, Object>) metadata.get("plan");

                        plan.put("status", "completed");
                        plan.put("completionSummary", summary);

                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> steps = (List<Map<String, Object>>) plan.get("steps");
                        for (Map<String, Object> step : steps) {
                            String stepStatus = (String) step.get("status");
                            if ("pending".equals(stepStatus) || "in_progress".equals(stepStatus)) {
                                step.put("status", "skipped");
                            }
                        }

                        msg.setMetadataJson(MAPPER.writeValueAsString(metadata));
                        return messageRepository.save(msg).then();
                    } catch (Exception e) {
                        return Mono.error(e);
                    }
                });
    }

    public Mono<TranscriptMessageEntity> getActivePlan(long sessionId) {
        return messageRepository.findActivePlanBySessionId(sessionId);
    }

    public Mono<TranscriptMessageEntity> getPlanMessage(long messageId) {
        return messageRepository.findById(messageId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Plan message not found: " + messageId)));
    }

    public Mono<Map<String, Object>> getPlanData(long messageId) {
        return messageRepository.findById(messageId)
                .map(msg -> {
                    try {
                        return parseMetadata(msg.getMetadataJson());
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse plan metadata", e);
                    }
                });
    }

    public Mono<String> getPlanStatus(long messageId) {
        return getPlanData(messageId)
                .map(metadata -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> plan = (Map<String, Object>) metadata.get("plan");
                    return (String) plan.get("status");
                });
    }

    /**
     * Build a plan execution context string for injection into the agent system prompt.
     */
    public Mono<String> buildPlanContext(long messageId) {
        return messageRepository.findById(messageId)
                .map(msg -> {
                    try {
                        Map<String, Object> metadata = parseMetadata(msg.getMetadataJson());
                        @SuppressWarnings("unchecked")
                        Map<String, Object> plan = (Map<String, Object>) metadata.get("plan");
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> steps = (List<Map<String, Object>>) plan.get("steps");

                        String title = msg.getContent();
                        int totalCount = steps.size();
                        int completedCount = 0;
                        Map<String, Object> nextStep = null;

                        for (Map<String, Object> step : steps) {
                            String status = (String) step.get("status");
                            if ("completed".equals(status)) completedCount++;
                            if (nextStep == null && ("pending".equals(status) || "in_progress".equals(status))) {
                                nextStep = step;
                            }
                        }

                        StringBuilder sb = new StringBuilder();
                        sb.append("## 当前计划\n\n");
                        sb.append("标题：").append(title).append('\n');
                        sb.append("进度：第 ").append(completedCount).append('/').append(totalCount).append(" 步\n");

                        if (nextStep != null) {
                            sb.append("下一步：").append(nextStep.get("title"))
                                    .append(" - ").append(nextStep.get("description")).append('\n');
                            Object verification = nextStep.get("verification");
                            if (verification != null && !verification.toString().isBlank()) {
                                sb.append("验证条件：").append(verification).append('\n');
                            }
                            sb.append("\n请继续执行下一步。完成实施后，执行验证条件确认结果正确，验证通过后调用 plan({ action: \"step_done\", stepIndex: ")
                                    .append(nextStep.get("index")).append(" })。\n");
                        } else {
                            sb.append("\n所有步骤已完成。请调用 plan({ action: \"complete\" })。\n");
                        }

                        return sb.toString();
                    } catch (Exception e) {
                        log.warn("Failed to build plan context for messageId={}: {}", messageId, e.getMessage());
                        return "";
                    }
                })
                .defaultIfEmpty("");
    }

    private Map<String, Object> parseMetadata(String json) throws Exception {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    }
}
