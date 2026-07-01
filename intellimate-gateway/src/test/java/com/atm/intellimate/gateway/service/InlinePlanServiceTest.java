package com.atm.intellimate.gateway.service;

import com.atm.intellimate.agent.tools.AgentSessionContext;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.repository.TranscriptMessageRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InlinePlanService")
class InlinePlanServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long SESSION_ID = 42L;
    private static final long MESSAGE_ID = 100L;

    @Mock
    private TranscriptMessageRepository messageRepository;

    private InlinePlanService service;

    @BeforeEach
    void setUp() {
        service = new InlinePlanService(messageRepository);
    }

    @Test
    @DisplayName("createPlanMessage stores draft plan metadata with steps")
    void createPlanMessage_storesDraftPlanWithSteps() throws Exception {
        List<Map<String, Object>> steps = sampleSteps();
        when(messageRepository.findActivePlanBySessionId(SESSION_ID)).thenReturn(Mono.empty());
        when(messageRepository.save(any(TranscriptMessageEntity.class))).thenAnswer(invocation -> {
            TranscriptMessageEntity entity = invocation.getArgument(0);
            entity.setId(MESSAGE_ID);
            return Mono.just(entity);
        });

        StepVerifier.create(service.createPlanMessage(SESSION_ID, "My Plan", steps))
                .assertNext(entity -> {
                    assertThat(entity.getId()).isEqualTo(MESSAGE_ID);
                    assertThat(entity.getSessionId()).isEqualTo(SESSION_ID);
                    assertThat(entity.getRole()).isEqualTo("assistant");
                    assertThat(entity.getContent()).isEqualTo("My Plan");
                    assertThat(entity.getMetadataJson()).isNotBlank();
                })
                .verifyComplete();

        ArgumentCaptor<TranscriptMessageEntity> captor = ArgumentCaptor.forClass(TranscriptMessageEntity.class);
        verify(messageRepository).save(captor.capture());

        Map<String, Object> metadata = parseMetadata(captor.getValue().getMetadataJson());
        assertThat(metadata.get("type")).isEqualTo("plan");

        @SuppressWarnings("unchecked")
        Map<String, Object> plan = (Map<String, Object>) metadata.get("plan");
        assertThat(plan.get("status")).isEqualTo("draft");
        assertThat(plan.get("completionSummary")).isNull();
        assertThat(plan.get("steps")).isEqualTo(steps);
    }

    @Test
    @DisplayName("createPlanMessage rejects when session already has active plan")
    void createPlanMessage_rejectsWhenActivePlanExists() {
        TranscriptMessageEntity existing = planMessage(MESSAGE_ID, SESSION_ID, "executing", sampleSteps(), null);
        when(messageRepository.findActivePlanBySessionId(SESSION_ID)).thenReturn(Mono.just(existing));

        StepVerifier.create(service.createPlanMessage(SESSION_ID, "Another Plan", sampleSteps()))
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(IllegalStateException.class);
                    assertThat(error.getMessage()).contains("active plan");
                    assertThat(error.getMessage()).contains(String.valueOf(MESSAGE_ID));
                })
                .verify();
    }

    @Test
    @DisplayName("updateStepStatus updates step status in metadata")
    void updateStepStatus_updatesStepInMetadata() throws Exception {
        List<Map<String, Object>> steps = sampleSteps();
        TranscriptMessageEntity message = planMessage(MESSAGE_ID, SESSION_ID, "executing", steps, null);
        when(messageRepository.findById(MESSAGE_ID)).thenReturn(Mono.just(message));
        when(messageRepository.save(any(TranscriptMessageEntity.class))).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.updateStepStatus(MESSAGE_ID, 0, "completed", "Done"))
                .verifyComplete();

        ArgumentCaptor<TranscriptMessageEntity> captor = ArgumentCaptor.forClass(TranscriptMessageEntity.class);
        verify(messageRepository).save(captor.capture());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> savedSteps = planSteps(captor.getValue().getMetadataJson());
        assertThat(savedSteps.get(0).get("status")).isEqualTo("completed");
        assertThat(savedSteps.get(0).get("resultSummary")).isEqualTo("Done");
    }

    @Test
    @DisplayName("updateStepStatus transitions plan to executing when first step starts")
    void updateStepStatus_autoTransitionsToExecutingOnFirstStepStart() throws Exception {
        List<Map<String, Object>> steps = sampleSteps();
        TranscriptMessageEntity message = planMessage(MESSAGE_ID, SESSION_ID, "approved", steps, null);
        when(messageRepository.findById(MESSAGE_ID)).thenReturn(Mono.just(message));
        when(messageRepository.save(any(TranscriptMessageEntity.class))).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.updateStepStatus(MESSAGE_ID, 0, "in_progress", null))
                .verifyComplete();

        ArgumentCaptor<TranscriptMessageEntity> captor = ArgumentCaptor.forClass(TranscriptMessageEntity.class);
        verify(messageRepository).save(captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> plan = planData(captor.getValue().getMetadataJson());
        assertThat(plan.get("status")).isEqualTo("executing");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> savedSteps = (List<Map<String, Object>>) plan.get("steps");
        assertThat(savedSteps.get(0).get("status")).isEqualTo("in_progress");
    }

    @Test
    @DisplayName("updatePlanStatus allows draft to approved transition")
    void updatePlanStatus_draftToApproved() throws Exception {
        verifyStatusTransition("draft", "approved", "approved");
    }

    @Test
    @DisplayName("updatePlanStatus allows executing to paused transition")
    void updatePlanStatus_executingToPaused() throws Exception {
        verifyStatusTransition("executing", "paused", "paused");
    }

    @Test
    @DisplayName("updatePlanStatus allows paused to executing transition")
    void updatePlanStatus_pausedToExecuting() throws Exception {
        verifyStatusTransition("paused", "executing", "executing");
    }

    @Test
    @DisplayName("updatePlanStatus rejects transitions from terminal states")
    void updatePlanStatus_rejectsTerminalTransitions() {
        for (String terminalStatus : List.of("completed", "cancelled", "failed")) {
            TranscriptMessageEntity message = planMessage(MESSAGE_ID, SESSION_ID, terminalStatus, sampleSteps(), null);
            when(messageRepository.findById(MESSAGE_ID)).thenReturn(Mono.just(message));

            StepVerifier.create(service.updatePlanStatus(MESSAGE_ID, "executing"))
                    .expectErrorSatisfies(error -> {
                        assertThat(error).isInstanceOf(IllegalStateException.class);
                        assertThat(error.getMessage()).contains("terminal state");
                        assertThat(error.getMessage()).contains(terminalStatus);
                    })
                    .verify();
        }
    }

    @Test
    @DisplayName("updatePlanStatus to cancelled skips pending and in_progress steps")
    void updatePlanStatus_cancelSkipsRemainingSteps() throws Exception {
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step(0, "Step 1", "Desc 1", "pending"));
        steps.add(step(1, "Step 2", "Desc 2", "in_progress"));
        steps.add(step(2, "Step 3", "Desc 3", "completed"));
        TranscriptMessageEntity message = planMessage(MESSAGE_ID, SESSION_ID, "executing", steps, null);
        when(messageRepository.findById(MESSAGE_ID)).thenReturn(Mono.just(message));
        when(messageRepository.save(any(TranscriptMessageEntity.class))).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.updatePlanStatus(MESSAGE_ID, "cancelled"))
                .verifyComplete();

        ArgumentCaptor<TranscriptMessageEntity> captor = ArgumentCaptor.forClass(TranscriptMessageEntity.class);
        verify(messageRepository).save(captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> plan = planData(captor.getValue().getMetadataJson());
        assertThat(plan.get("status")).isEqualTo("cancelled");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> savedSteps = (List<Map<String, Object>>) plan.get("steps");
        assertThat(savedSteps.get(0).get("status")).isEqualTo("skipped");
        assertThat(savedSteps.get(1).get("status")).isEqualTo("skipped");
        assertThat(savedSteps.get(2).get("status")).isEqualTo("completed");
    }

    @Test
    @DisplayName("completePlan sets completed status, summary, and skips remaining steps")
    void completePlan_marksCompletedAndSkipsRemainingSteps() throws Exception {
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step(0, "Step 1", "Desc 1", "completed"));
        steps.add(step(1, "Step 2", "Desc 2", "pending"));
        steps.add(step(2, "Step 3", "Desc 3", "in_progress"));
        TranscriptMessageEntity message = planMessage(MESSAGE_ID, SESSION_ID, "executing", steps, null);
        when(messageRepository.findById(MESSAGE_ID)).thenReturn(Mono.just(message));
        when(messageRepository.save(any(TranscriptMessageEntity.class))).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.completePlan(MESSAGE_ID, "All done"))
                .verifyComplete();

        ArgumentCaptor<TranscriptMessageEntity> captor = ArgumentCaptor.forClass(TranscriptMessageEntity.class);
        verify(messageRepository).save(captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> plan = planData(captor.getValue().getMetadataJson());
        assertThat(plan.get("status")).isEqualTo("completed");
        assertThat(plan.get("completionSummary")).isEqualTo("All done");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> savedSteps = (List<Map<String, Object>>) plan.get("steps");
        assertThat(savedSteps.get(0).get("status")).isEqualTo("completed");
        assertThat(savedSteps.get(1).get("status")).isEqualTo("skipped");
        assertThat(savedSteps.get(2).get("status")).isEqualTo("skipped");
    }

    @Test
    @DisplayName("buildPlanContext includes title, progress, and next step")
    void buildPlanContext_includesTitleProgressAndNextStep() {
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step(0, "First", "Do first thing", "completed"));
        Map<String, Object> next = step(1, "Second", "Do second thing", "pending");
        next.put("verification", "Check output");
        steps.add(next);
        TranscriptMessageEntity message = planMessage(MESSAGE_ID, SESSION_ID, "executing", steps, null);
        message.setContent("Build Feature");
        when(messageRepository.findById(MESSAGE_ID)).thenReturn(Mono.just(message));

        StepVerifier.create(service.buildPlanContext(MESSAGE_ID))
                .assertNext(context -> {
                    assertThat(context).contains("## 当前计划");
                    assertThat(context).contains("标题：Build Feature");
                    assertThat(context).contains("进度：第 1/2 步");
                    assertThat(context).contains("下一步：Second - Do second thing");
                    assertThat(context).contains("验证条件：Check output");
                    assertThat(context).contains("stepIndex: 1");
                })
                .verifyComplete();
    }

    @Nested
    @DisplayName("isPausedOrCancelled via InlinePlanOperationsImpl")
    class IsPausedOrCancelledTests {

        @Mock
        private AgentSessionContext sessionContext;

        private InlinePlanOperationsImpl operations;

        @BeforeEach
        void setUpOperations() {
            operations = new InlinePlanOperationsImpl(service, sessionContext);
        }

        @Test
        @DisplayName("returns true for paused plan")
        void returnsTrueForPaused() {
            TranscriptMessageEntity message = planMessage(MESSAGE_ID, SESSION_ID, "paused", sampleSteps(), null);
            when(messageRepository.findById(MESSAGE_ID)).thenReturn(Mono.just(message));

            StepVerifier.create(operations.isPausedOrCancelled(MESSAGE_ID))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("returns true for cancelled plan")
        void returnsTrueForCancelled() {
            TranscriptMessageEntity message = planMessage(MESSAGE_ID, SESSION_ID, "cancelled", sampleSteps(), null);
            when(messageRepository.findById(MESSAGE_ID)).thenReturn(Mono.just(message));

            StepVerifier.create(operations.isPausedOrCancelled(MESSAGE_ID))
                    .expectNext(true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("returns false for executing plan")
        void returnsFalseForExecuting() {
            TranscriptMessageEntity message = planMessage(MESSAGE_ID, SESSION_ID, "executing", sampleSteps(), null);
            when(messageRepository.findById(MESSAGE_ID)).thenReturn(Mono.just(message));

            StepVerifier.create(operations.isPausedOrCancelled(MESSAGE_ID))
                    .expectNext(false)
                    .verifyComplete();
        }

        @Test
        @DisplayName("returns false when plan message not found")
        void returnsFalseWhenNotFound() {
            when(messageRepository.findById(MESSAGE_ID)).thenReturn(Mono.empty());

            StepVerifier.create(operations.isPausedOrCancelled(MESSAGE_ID))
                    .expectNext(false)
                    .verifyComplete();
        }
    }

    private void verifyStatusTransition(String fromStatus, String toStatus, String expectedStatus) throws Exception {
        TranscriptMessageEntity message = planMessage(MESSAGE_ID, SESSION_ID, fromStatus, sampleSteps(), null);
        when(messageRepository.findById(MESSAGE_ID)).thenReturn(Mono.just(message));
        when(messageRepository.save(any(TranscriptMessageEntity.class))).thenAnswer(invocation ->
                Mono.just(invocation.getArgument(0)));

        StepVerifier.create(service.updatePlanStatus(MESSAGE_ID, toStatus))
                .verifyComplete();

        ArgumentCaptor<TranscriptMessageEntity> captor = ArgumentCaptor.forClass(TranscriptMessageEntity.class);
        verify(messageRepository).save(captor.capture());
        assertThat(planData(captor.getValue().getMetadataJson()).get("status")).isEqualTo(expectedStatus);
    }

    private static List<Map<String, Object>> sampleSteps() {
        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step(0, "Step 1", "First step", "pending"));
        steps.add(step(1, "Step 2", "Second step", "pending"));
        return steps;
    }

    private static Map<String, Object> step(int index, String title, String description, String status) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("index", index);
        step.put("title", title);
        step.put("description", description);
        step.put("verification", "");
        step.put("status", status);
        step.put("resultSummary", null);
        return step;
    }

    private static TranscriptMessageEntity planMessage(long messageId, long sessionId, String status,
                                                       List<Map<String, Object>> steps, String completionSummary) {
        TranscriptMessageEntity message = new TranscriptMessageEntity();
        message.setId(messageId);
        message.setSessionId(sessionId);
        message.setRole("assistant");
        message.setContent("Test Plan");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("status", status);
        plan.put("steps", steps);
        plan.put("completionSummary", completionSummary);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("type", "plan");
        metadata.put("plan", plan);
        try {
            message.setMetadataJson(MAPPER.writeValueAsString(metadata));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return message;
    }

    private static Map<String, Object> parseMetadata(String json) throws Exception {
        return MAPPER.readValue(json, new TypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> planData(String json) throws Exception {
        return (Map<String, Object>) parseMetadata(json).get("plan");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> planSteps(String json) throws Exception {
        return (List<Map<String, Object>>) planData(json).get("steps");
    }
}
