package com.atm.intellimate.gateway.pipeline;

import com.atm.intellimate.agent.runtime.AgentEvent;
import com.atm.intellimate.agent.runtime.AgentRuntime;
import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.core.protocol.EventFrame;
import com.atm.intellimate.core.protocol.GatewayFrame;
import com.atm.intellimate.core.protocol.RequestFrame;
import com.atm.intellimate.core.protocol.ResponseFrame;
import com.atm.intellimate.gateway.audit.AuditService;
import com.atm.intellimate.gateway.config.AgentConfigService;
import com.atm.intellimate.gateway.config.ResolvedAgentConfig;
import com.atm.intellimate.gateway.entity.PlanEntity;
import com.atm.intellimate.gateway.entity.SessionEntity;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.repository.SessionRepository;
import com.atm.intellimate.gateway.service.PlanService;
import com.atm.intellimate.gateway.session.SessionManager;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessagePipelineApproveExecuteTest {

    @Mock private SessionManager sessionManager;
    @Mock private AgentRuntime agentRuntime;
    @Mock private IntelliMateProperties properties;
    @Mock private AgentConfigService agentConfigService;
    @Mock private CommandHandler commandHandler;
    @Mock private AuditService auditService;
    @Mock private PlanService planService;
    @Mock private SessionRegistry sessionRegistry;
    @Mock private SessionRepository sessionRepository;

    private MessagePipeline pipeline;
    private MessageConverter messageConverter;
    private AgentEventMapper agentEventMapper;
    private PlanExecutionOrchestrator planExecutionOrchestrator;

    @BeforeEach
    void setUp() {
        messageConverter = new MessageConverter(sessionManager, properties);
        planExecutionOrchestrator = new PlanExecutionOrchestrator(planService, null, null, properties);
        agentEventMapper = new AgentEventMapper(agentConfigService, agentRuntime, properties, planExecutionOrchestrator);
        PlanRequestHandler planRequestHandler = new PlanRequestHandler(planService, agentRuntime, sessionRepository);
        pipeline = new MessagePipeline(
                sessionManager, messageConverter, agentEventMapper, agentRuntime, properties,
                agentConfigService, commandHandler, auditService, planRequestHandler,
                planService, planExecutionOrchestrator, sessionRegistry, sessionRepository);
    }

    private PlanEntity makePlan(Long id, Long sessionId, String status) {
        PlanEntity p = new PlanEntity();
        p.setId(id);
        p.setSessionId(sessionId);
        p.setStatus(status);
        p.setTitle("Test Plan");
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        return p;
    }

    private SessionEntity makeSession(Long id) {
        SessionEntity s = new SessionEntity();
        s.setId(id);
        s.setAgentName("test-agent");
        s.setContextId("user-1");
        s.setChannelId("webchat");
        s.setContextType("dm");
        return s;
    }

    @Test
    void approveAndExecute_emitsStatusChangedAndTriggersAgent() {
        Long planId = 100L;
        Long sessionId = 200L;

        PlanEntity approved = makePlan(planId, sessionId, "approved");
        PlanEntity executing = makePlan(planId, sessionId, "executing");
        SessionEntity session = makeSession(sessionId);

        when(planService.approvePlan(planId, true, null)).thenReturn(Mono.just(approved));
        when(planService.resumePlan(planId)).thenReturn(Mono.just(executing));
        when(sessionRepository.findById(sessionId)).thenReturn(Mono.just(session));

        when(planService.getActivePlan(sessionId)).thenReturn(Mono.just(executing));
        when(sessionManager.appendMessage(eq(sessionId), any(TranscriptMessageEntity.class)))
                .thenReturn(Mono.empty());
        when(auditService.log(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Mono.empty());

        IntelliMateProperties.Agent agentProps = mock(IntelliMateProperties.Agent.class);
        when(agentProps.getHistoryLimit()).thenReturn(20);
        when(properties.getAgent()).thenReturn(agentProps);

        when(sessionManager.getPlanHistory(eq(sessionId), eq(planId), anyInt()))
                .thenReturn(Flux.empty());

        IntelliMateProperties.Agent agentConfig = mock(IntelliMateProperties.Agent.class);
        when(agentConfig.getName()).thenReturn("test-agent");
        ResolvedAgentConfig resolved = new ResolvedAgentConfig(
                agentConfig, null, null, null, null);
        when(agentConfigService.resolve("test-agent")).thenReturn(Mono.just(resolved));

        when(planService.getSteps(planId)).thenReturn(Flux.empty());

        when(agentRuntime.dispatch(any())).thenReturn(Flux.just(
                new AgentEvent.Done("Plan execution started", 1)));

        when(planService.getActivePlan(sessionId)).thenReturn(Mono.just(executing));
        when(planService.getPlanById(planId)).thenReturn(Mono.just(executing));

        RequestFrame request = new RequestFrame("req-1", "plan.approve_and_execute",
                Map.of("planId", planId));

        Flux<GatewayFrame> result = pipeline.processRequest(request, "ws-session-1");

        StepVerifier.create(result)
                .assertNext(frame -> {
                    assertThat(frame).isInstanceOf(EventFrame.class);
                    EventFrame evt = (EventFrame) frame;
                    assertThat(evt.event()).isEqualTo("plan.status_changed");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) evt.payload();
                    assertThat(payload.get("planId")).isEqualTo(planId);
                    assertThat(payload.get("status")).isEqualTo("executing");
                })
                .thenConsumeWhile(frame -> true)
                .verifyComplete();

        verify(planService).approvePlan(planId, true, null);
        verify(planService).resumePlan(planId);
        verify(sessionRepository).findById(sessionId);
    }

    @Test
    void approveAndExecute_approveFails_returnsErrorWithCurrentStatus() {
        Long planId = 100L;

        PlanEntity draftPlan = makePlan(planId, 200L, "draft");

        when(planService.approvePlan(planId, true, null))
                .thenReturn(Mono.error(new IllegalStateException("Plan is not in draft status")));
        when(planService.getPlanById(planId)).thenReturn(Mono.just(draftPlan));

        RequestFrame request = new RequestFrame("req-2", "plan.approve_and_execute",
                Map.of("planId", planId));

        Flux<GatewayFrame> result = pipeline.processRequest(request, "ws-session-2");

        StepVerifier.create(result)
                .assertNext(frame -> {
                    assertThat(frame).isInstanceOf(ResponseFrame.class);
                    ResponseFrame resp = (ResponseFrame) frame;
                    assertThat(resp.ok()).isFalse();
                    assertThat(resp.error().toString()).contains("Plan is not in draft status");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) resp.payload();
                    assertThat(payload).isNotNull();
                    assertThat(payload.get("currentStatus")).isEqualTo("draft");
                })
                .verifyComplete();

        verify(planService).approvePlan(planId, true, null);
        verify(planService, never()).resumePlan(anyLong());
    }

    @Test
    void approveAndExecute_approveSucceeds_resumeFails_returnsApprovedStatus() {
        Long planId = 100L;
        Long sessionId = 200L;

        PlanEntity approved = makePlan(planId, sessionId, "approved");

        when(planService.approvePlan(planId, true, null)).thenReturn(Mono.just(approved));
        when(planService.resumePlan(planId))
                .thenReturn(Mono.error(new RuntimeException("Resume failed")));
        when(planService.getPlanById(planId)).thenReturn(Mono.just(approved));

        RequestFrame request = new RequestFrame("req-3", "plan.approve_and_execute",
                Map.of("planId", planId));

        Flux<GatewayFrame> result = pipeline.processRequest(request, "ws-session-3");

        StepVerifier.create(result)
                .assertNext(frame -> {
                    assertThat(frame).isInstanceOf(ResponseFrame.class);
                    ResponseFrame resp = (ResponseFrame) frame;
                    assertThat(resp.ok()).isFalse();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) resp.payload();
                    assertThat(payload).isNotNull();
                    assertThat(payload.get("currentStatus")).isEqualTo("approved");
                })
                .verifyComplete();

        verify(planService).approvePlan(planId, true, null);
        verify(planService).resumePlan(planId);
    }
}
