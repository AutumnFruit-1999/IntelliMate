package com.atm.intellimate.gateway.heartbeat;

import com.atm.intellimate.agent.runtime.AgentEvent;
import com.atm.intellimate.agent.runtime.AgentRunRequest;
import com.atm.intellimate.agent.runtime.AgentRuntime;
import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.gateway.config.AgentConfigService;
import com.atm.intellimate.gateway.config.ResolvedAgentConfig;
import com.atm.intellimate.gateway.entity.AgentEntity;
import com.atm.intellimate.gateway.entity.HeartbeatConfigEntity;
import com.atm.intellimate.gateway.entity.HeartbeatLogEntity;
import com.atm.intellimate.gateway.entity.OfflineMessageEntity;
import com.atm.intellimate.gateway.repository.AgentRepository;
import com.atm.intellimate.gateway.repository.AgentTaskRepository;
import com.atm.intellimate.gateway.repository.HeartbeatLogRepository;
import com.atm.intellimate.gateway.repository.OfflineMessageRepository;
import com.atm.intellimate.gateway.service.ChatInjectionService;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("HeartbeatEngine")
class HeartbeatEngineTest {

    @Mock private HeartbeatLogRepository logRepo;
    @Mock private AgentTaskRepository taskRepo;
    @Mock private OfflineMessageRepository offlineMsgRepo;
    @Mock private SessionRegistry sessionRegistry;
    @Mock private HeartbeatContextBuilder contextBuilder;
    @Mock private AgentRuntime agentRuntime;
    @Mock private AgentConfigService agentConfigService;
    @Mock private AgentRepository agentRepository;
    @Mock private ChatInjectionService chatInjectionService;

    private HeartbeatEngine engine;

    @BeforeEach
    void setUp() {
        engine = new HeartbeatEngine(
                logRepo, taskRepo, offlineMsgRepo, sessionRegistry,
                contextBuilder, agentRuntime, agentConfigService, agentRepository,
                chatInjectionService);
    }

    /**
     * Builds a config whose wake/sleep window guarantees the WAKING state
     * regardless of when the test runs. We set wakeTime = now - 10min,
     * sleepTime = now - 2h (wrapping to produce a valid WAKING window).
     */
    private HeartbeatConfigEntity configForState(LifecycleState desired) {
        LocalTime now = LocalTime.now();
        HeartbeatConfigEntity config = new HeartbeatConfigEntity();
        config.setAgentId(1L);
        config.setEnabled(1);
        config.setTimezone("Asia/Shanghai");
        config.setHeartbeatIntervalMinutes(60);
        config.setPersonalityPrompt("你是一个温暖的伙伴");

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm");
        switch (desired) {
            case WAKING -> {
                // wakeTime = now - 10min, sleepTime = wakeTime - 3h (wraps around)
                LocalTime wake = now.minusMinutes(10);
                LocalTime sleep = wake.minusHours(3);
                config.setWakeTime(wake.format(fmt));
                config.setSleepTime(sleep.format(fmt));
            }
            case ACTIVE -> {
                // wakeTime = now - 3h, sleepTime = now + 5h
                LocalTime wake = now.minusHours(3);
                LocalTime sleep = now.plusHours(5);
                config.setWakeTime(wake.format(fmt));
                config.setSleepTime(sleep.format(fmt));
            }
            case WINDING_DOWN -> {
                // sleepTime = now + 30min, wakeTime = sleepTime - 20h
                LocalTime sleep = now.plusMinutes(30);
                LocalTime wake = sleep.minusHours(20);
                config.setWakeTime(wake.format(fmt));
                config.setSleepTime(sleep.format(fmt));
            }
            case SLEEPING -> {
                // wakeTime = now + 3h, sleepTime = now - 1h
                LocalTime wake = now.plusHours(3);
                LocalTime sleep = now.minusHours(1);
                config.setWakeTime(wake.format(fmt));
                config.setSleepTime(sleep.format(fmt));
            }
        }
        return config;
    }

    private void stubAgentResolution() {
        AgentEntity agentEntity = new AgentEntity();
        agentEntity.setId(1L);
        agentEntity.setName("小助手");
        when(agentRepository.findById(1L)).thenReturn(Mono.just(agentEntity));

        IntelliMateProperties.Agent agentProps = new IntelliMateProperties.Agent();
        agentProps.setName("小助手");
        ResolvedAgentConfig resolved = new ResolvedAgentConfig(agentProps, null, null, null, null);
        when(agentConfigService.resolve("小助手")).thenReturn(Mono.just(resolved));
    }

    @Test
    @DisplayName("LLM responds normally - should deliver LLM response")
    void processHeartbeat_llmRespondsNormally_shouldDeliverLlmResponse() {
        HeartbeatConfigEntity config = configForState(LifecycleState.WAKING);
        stubAgentResolution();

        when(logRepo.findTodayByAgentIdAndState(eq(1L), eq("WAKING"))).thenReturn(Mono.empty());
        when(taskRepo.findUpcomingTasks(eq(1L), any())).thenReturn(Flux.empty());
        when(contextBuilder.buildPrompt(eq("小助手"), eq(LifecycleState.WAKING), any(), any(), any()))
                .thenReturn("你是小助手...");

        String llmText = "早安！今天天气不错，祝你有愉快的一天！";
        when(agentRuntime.dispatch(any(AgentRunRequest.class)))
                .thenReturn(Flux.just(new AgentEvent.Done(llmText, 1)));

        when(logRepo.save(any(HeartbeatLogEntity.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(chatInjectionService.injectAgentMessage(eq("小助手"), eq(llmText),
                eq(ChatInjectionService.ProactiveSource.HEARTBEAT)))
                .thenReturn(Mono.just(1));
        when(sessionRegistry.pushToAgent(eq("小助手"), eq("heartbeat.message"), any()))
                .thenReturn(true);

        StepVerifier.create(engine.processHeartbeat(config))
                .verifyComplete();

        verify(agentRuntime).dispatch(any(AgentRunRequest.class));

        ArgumentCaptor<HeartbeatLogEntity> logCaptor = ArgumentCaptor.forClass(HeartbeatLogEntity.class);
        verify(logRepo).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getResponse()).isEqualTo(llmText);

        verify(sessionRegistry).pushToAgent(eq("小助手"), eq("heartbeat.message"), any());
        verify(chatInjectionService).injectAgentMessage(eq("小助手"), eq(llmText),
                eq(ChatInjectionService.ProactiveSource.HEARTBEAT));
    }

    @Test
    @DisplayName("LLM returns [SILENT] - should log but not deliver")
    void processHeartbeat_llmReturnsSilent_shouldLogButNotDeliver() {
        HeartbeatConfigEntity config = configForState(LifecycleState.WAKING);
        stubAgentResolution();

        when(logRepo.findTodayByAgentIdAndState(eq(1L), eq("WAKING"))).thenReturn(Mono.empty());
        when(taskRepo.findUpcomingTasks(eq(1L), any())).thenReturn(Flux.empty());
        when(contextBuilder.buildPrompt(any(), any(), any(), any(), any())).thenReturn("prompt");

        when(agentRuntime.dispatch(any(AgentRunRequest.class)))
                .thenReturn(Flux.just(new AgentEvent.Done("[SILENT]", 1)));

        when(logRepo.save(any(HeartbeatLogEntity.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(engine.processHeartbeat(config))
                .verifyComplete();

        verify(logRepo).save(any());
        verify(sessionRegistry, never()).pushToAgent(any(), any(), any());
        verify(chatInjectionService, never()).injectAgentMessage(any(), any(), any());
    }

    @Test
    @DisplayName("LLM times out - should fallback to placeholder")
    void processHeartbeat_llmTimesOut_shouldFallbackToPlaceholder() {
        HeartbeatConfigEntity config = configForState(LifecycleState.WAKING);
        stubAgentResolution();

        when(logRepo.findTodayByAgentIdAndState(eq(1L), eq("WAKING"))).thenReturn(Mono.empty());
        when(taskRepo.findUpcomingTasks(eq(1L), any())).thenReturn(Flux.empty());
        when(contextBuilder.buildPrompt(any(), any(), any(), any(), any())).thenReturn("prompt");

        when(agentRuntime.dispatch(any(AgentRunRequest.class)))
                .thenReturn(Flux.never());

        when(logRepo.save(any(HeartbeatLogEntity.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(chatInjectionService.injectAgentMessage(any(), any(), any()))
                .thenReturn(Mono.just(1));
        when(sessionRegistry.pushToAgent(any(), any(), any())).thenReturn(true);

        StepVerifier.create(engine.processHeartbeat(config))
                .verifyComplete();

        ArgumentCaptor<HeartbeatLogEntity> logCaptor = ArgumentCaptor.forClass(HeartbeatLogEntity.class);
        verify(logRepo).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getResponse()).contains("早上好");
    }

    @Test
    @DisplayName("Agent not found - should fallback to placeholder with Agent# name")
    void processHeartbeat_agentNotFound_shouldFallbackToPlaceholder() {
        HeartbeatConfigEntity config = configForState(LifecycleState.WAKING);

        when(agentRepository.findById(1L)).thenReturn(Mono.empty());
        when(logRepo.findTodayByAgentIdAndState(eq(1L), eq("WAKING"))).thenReturn(Mono.empty());
        when(taskRepo.findUpcomingTasks(eq(1L), any())).thenReturn(Flux.empty());
        when(contextBuilder.buildPrompt(eq("Agent#1"), eq(LifecycleState.WAKING), any(), any(), any()))
                .thenReturn("prompt");

        // agentName falls back to "Agent#1" → agentConfigService.resolve("Agent#1")
        // returns defaults, but LLM call happens (or may fail). 
        // Since resolve returns a default config, dispatch will be called.
        IntelliMateProperties.Agent defaultProps = new IntelliMateProperties.Agent();
        defaultProps.setName("Agent#1");
        ResolvedAgentConfig resolved = new ResolvedAgentConfig(defaultProps, null, null, null, null);
        when(agentConfigService.resolve("Agent#1")).thenReturn(Mono.just(resolved));

        when(agentRuntime.dispatch(any(AgentRunRequest.class)))
                .thenReturn(Flux.error(new RuntimeException("Agent not properly configured")));

        when(logRepo.save(any(HeartbeatLogEntity.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(chatInjectionService.injectAgentMessage(any(), any(), any()))
                .thenReturn(Mono.just(1));
        when(sessionRegistry.pushToAgent(any(), any(), any())).thenReturn(true);

        StepVerifier.create(engine.processHeartbeat(config))
                .verifyComplete();

        ArgumentCaptor<HeartbeatLogEntity> logCaptor = ArgumentCaptor.forClass(HeartbeatLogEntity.class);
        verify(logRepo).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getResponse()).contains("早上好");
    }

    @Test
    @DisplayName("SLEEPING state - should do nothing")
    void processHeartbeat_sleeping_shouldSkip() {
        HeartbeatConfigEntity config = configForState(LifecycleState.SLEEPING);

        StepVerifier.create(engine.processHeartbeat(config))
                .verifyComplete();

        verifyNoInteractions(agentRuntime, agentConfigService, logRepo, taskRepo);
    }

    @Test
    @DisplayName("LLM responds with streaming chunks - should concatenate")
    void processHeartbeat_streamingChunks_shouldConcatenate() {
        HeartbeatConfigEntity config = configForState(LifecycleState.WAKING);
        stubAgentResolution();

        when(logRepo.findTodayByAgentIdAndState(eq(1L), eq("WAKING"))).thenReturn(Mono.empty());
        when(taskRepo.findUpcomingTasks(eq(1L), any())).thenReturn(Flux.empty());
        when(contextBuilder.buildPrompt(any(), any(), any(), any(), any())).thenReturn("prompt");

        when(agentRuntime.dispatch(any(AgentRunRequest.class)))
                .thenReturn(Flux.just(
                        new AgentEvent.TextChunk("早安！"),
                        new AgentEvent.TextChunk("今天加油！"),
                        new AgentEvent.Done("早安！今天加油！", 1)
                ));

        when(logRepo.save(any(HeartbeatLogEntity.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(chatInjectionService.injectAgentMessage(any(), any(), any()))
                .thenReturn(Mono.just(1));
        when(sessionRegistry.pushToAgent(any(), any(), any())).thenReturn(true);

        StepVerifier.create(engine.processHeartbeat(config))
                .verifyComplete();

        ArgumentCaptor<HeartbeatLogEntity> logCaptor = ArgumentCaptor.forClass(HeartbeatLogEntity.class);
        verify(logRepo).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getResponse()).isEqualTo("早安！今天加油！");
    }

    @Test
    @DisplayName("Agent offline - should cache to offline_message")
    void processHeartbeat_agentOffline_shouldCacheMessage() {
        HeartbeatConfigEntity config = configForState(LifecycleState.WAKING);
        stubAgentResolution();

        when(logRepo.findTodayByAgentIdAndState(eq(1L), eq("WAKING"))).thenReturn(Mono.empty());
        when(taskRepo.findUpcomingTasks(eq(1L), any())).thenReturn(Flux.empty());
        when(contextBuilder.buildPrompt(any(), any(), any(), any(), any())).thenReturn("prompt");

        when(agentRuntime.dispatch(any(AgentRunRequest.class)))
                .thenReturn(Flux.just(new AgentEvent.Done("晚安", 1)));

        when(logRepo.save(any(HeartbeatLogEntity.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(chatInjectionService.injectAgentMessage(eq("小助手"), eq("晚安"),
                eq(ChatInjectionService.ProactiveSource.HEARTBEAT)))
                .thenReturn(Mono.just(1));
        when(sessionRegistry.pushToAgent(any(), any(), any())).thenReturn(false);
        when(offlineMsgRepo.save(any(OfflineMessageEntity.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(engine.processHeartbeat(config))
                .verifyComplete();

        ArgumentCaptor<OfflineMessageEntity> msgCaptor = ArgumentCaptor.forClass(OfflineMessageEntity.class);
        verify(offlineMsgRepo).save(msgCaptor.capture());
        assertThat(msgCaptor.getValue().getContent()).isEqualTo("晚安");
        assertThat(msgCaptor.getValue().getMessageType()).isEqualTo("heartbeat");
    }
}
