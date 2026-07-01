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
import com.atm.intellimate.gateway.entity.SessionEntity;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.repository.SessionRepository;
import com.atm.intellimate.gateway.service.InlinePlanService;
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
    @Mock private InlinePlanService inlinePlanService;
    @Mock private SessionRegistry sessionRegistry;
    @Mock private SessionRepository sessionRepository;
    @Mock private com.atm.intellimate.gateway.channel.ChannelIdentityService channelIdentityService;
    @Mock private com.atm.intellimate.gateway.service.CrossChannelSyncService crossChannelSyncService;

    private MessagePipeline pipeline;
    private MessageConverter messageConverter;
    private AgentEventMapper agentEventMapper;

    @BeforeEach
    void setUp() {
        messageConverter = new MessageConverter(sessionManager, properties);
        agentEventMapper = new AgentEventMapper(agentConfigService, agentRuntime, properties);
        pipeline = new MessagePipeline(
                sessionManager, messageConverter, agentEventMapper, agentRuntime, properties,
                agentConfigService, commandHandler, auditService, inlinePlanService,
                sessionRegistry, sessionRepository, channelIdentityService, crossChannelSyncService);
    }

    private TranscriptMessageEntity makePlanMessage(Long id, Long sessionId, String status) {
        TranscriptMessageEntity msg = new TranscriptMessageEntity();
        msg.setId(id);
        msg.setSessionId(sessionId);
        msg.setRole("assistant");
        msg.setContent("Test Plan");
        msg.setMetadataJson("{\"type\":\"plan\",\"plan\":{\"status\":\"" + status + "\",\"steps\":[]}}");
        msg.setCreatedAt(LocalDateTime.now());
        return msg;
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
    void planApprove_approved_triggersAgentExecution() {
        Long messageId = 100L;
        Long sessionId = 200L;

        TranscriptMessageEntity planMsg = makePlanMessage(messageId, sessionId, "approved");
        SessionEntity session = makeSession(sessionId);

        when(inlinePlanService.updatePlanStatus(messageId, "approved")).thenReturn(Mono.empty());
        when(inlinePlanService.getPlanMessage(messageId)).thenReturn(Mono.just(planMsg));
        when(sessionRepository.findById(sessionId)).thenReturn(Mono.just(session));

        when(inlinePlanService.getActivePlan(sessionId)).thenReturn(Mono.just(planMsg));
        when(sessionManager.appendMessage(eq(sessionId), any(TranscriptMessageEntity.class)))
                .thenReturn(Mono.empty());
        when(auditService.log(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Mono.empty());
        when(crossChannelSyncService.syncToExternalChannels(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Mono.empty());

        IntelliMateProperties.Agent agentProps = mock(IntelliMateProperties.Agent.class);
        when(agentProps.getHistoryLimit()).thenReturn(20);
        when(properties.getAgent()).thenReturn(agentProps);

        when(sessionManager.getHistory(eq(sessionId), anyInt()))
                .thenReturn(Flux.empty());

        IntelliMateProperties.Agent agentConfig = mock(IntelliMateProperties.Agent.class);
        when(agentConfig.getName()).thenReturn("test-agent");
        ResolvedAgentConfig resolved = new ResolvedAgentConfig(
                agentConfig, null, null, null, null);
        when(agentConfigService.resolve("test-agent")).thenReturn(Mono.just(resolved));

        when(inlinePlanService.buildPlanContext(messageId)).thenReturn(Mono.just("## 当前计划"));

        when(agentRuntime.dispatch(any())).thenReturn(Flux.just(
                new AgentEvent.Done("Plan execution started", 1)));

        RequestFrame request = new RequestFrame("req-1", "plan.approve",
                Map.of("messageId", messageId, "approved", true));

        Flux<GatewayFrame> result = pipeline.processRequest(request, "ws-session-1");

        StepVerifier.create(result)
                .assertNext(frame -> {
                    assertThat(frame).isInstanceOf(EventFrame.class);
                    EventFrame evt = (EventFrame) frame;
                    assertThat(evt.event()).isEqualTo("plan.status_changed");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) evt.payload();
                    assertThat(payload.get("messageId")).isEqualTo(messageId);
                    assertThat(payload.get("status")).isEqualTo("approved");
                })
                .thenConsumeWhile(frame -> true)
                .verifyComplete();

        verify(inlinePlanService).updatePlanStatus(messageId, "approved");
        verify(inlinePlanService).getPlanMessage(messageId);
        verify(sessionRepository).findById(sessionId);
    }

    @Test
    void planApprove_rejected_cancelsPlan() {
        Long messageId = 100L;

        when(inlinePlanService.updatePlanStatus(messageId, "cancelled")).thenReturn(Mono.empty());

        RequestFrame request = new RequestFrame("req-2", "plan.approve",
                Map.of("messageId", messageId, "approved", false));

        Flux<GatewayFrame> result = pipeline.processRequest(request, "ws-session-2");

        StepVerifier.create(result)
                .assertNext(frame -> {
                    assertThat(frame).isInstanceOf(EventFrame.class);
                    EventFrame evt = (EventFrame) frame;
                    assertThat(evt.event()).isEqualTo("plan.status_changed");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) evt.payload();
                    assertThat(payload.get("messageId")).isEqualTo(messageId);
                    assertThat(payload.get("status")).isEqualTo("cancelled");
                })
                .assertNext(frame -> assertThat(frame).isInstanceOf(ResponseFrame.class))
                .verifyComplete();

        verify(inlinePlanService).updatePlanStatus(messageId, "cancelled");
        verify(inlinePlanService, never()).getPlanMessage(anyLong());
    }

    @Test
    void planResume_triggersAgentExecution() {
        Long messageId = 100L;
        Long sessionId = 200L;

        TranscriptMessageEntity planMsg = makePlanMessage(messageId, sessionId, "executing");
        SessionEntity session = makeSession(sessionId);

        when(inlinePlanService.updatePlanStatus(messageId, "executing")).thenReturn(Mono.empty());
        when(inlinePlanService.getPlanMessage(messageId)).thenReturn(Mono.just(planMsg));
        when(sessionRepository.findById(sessionId)).thenReturn(Mono.just(session));

        when(inlinePlanService.getActivePlan(sessionId)).thenReturn(Mono.just(planMsg));
        when(sessionManager.appendMessage(eq(sessionId), any(TranscriptMessageEntity.class)))
                .thenReturn(Mono.empty());
        when(auditService.log(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(Mono.empty());
        when(crossChannelSyncService.syncToExternalChannels(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(Mono.empty());

        IntelliMateProperties.Agent agentProps = mock(IntelliMateProperties.Agent.class);
        when(agentProps.getHistoryLimit()).thenReturn(20);
        when(properties.getAgent()).thenReturn(agentProps);
        when(sessionManager.getHistory(eq(sessionId), anyInt())).thenReturn(Flux.empty());

        IntelliMateProperties.Agent agentConfig = mock(IntelliMateProperties.Agent.class);
        ResolvedAgentConfig resolved = new ResolvedAgentConfig(agentConfig, null, null, null, null);
        when(agentConfigService.resolve("test-agent")).thenReturn(Mono.just(resolved));
        when(inlinePlanService.buildPlanContext(messageId)).thenReturn(Mono.just("## 当前计划"));
        when(agentRuntime.dispatch(any())).thenReturn(Flux.just(new AgentEvent.Done("done", 1)));

        RequestFrame request = new RequestFrame("req-3", "plan.resume",
                Map.of("messageId", messageId));

        Flux<GatewayFrame> result = pipeline.processRequest(request, "ws-session-3");

        StepVerifier.create(result)
                .assertNext(frame -> {
                    assertThat(frame).isInstanceOf(EventFrame.class);
                    EventFrame evt = (EventFrame) frame;
                    assertThat(evt.event()).isEqualTo("plan.status_changed");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> payload = (Map<String, Object>) evt.payload();
                    assertThat(payload.get("status")).isEqualTo("executing");
                })
                .thenConsumeWhile(frame -> true)
                .verifyComplete();

        verify(inlinePlanService).updatePlanStatus(messageId, "executing");
    }
}
