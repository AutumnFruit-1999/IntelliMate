package com.atm.intellimate.gateway.service;

import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.repository.TranscriptMessageRepository;
import com.atm.intellimate.gateway.session.SessionManager;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatInjectionServiceTest {

    @Mock private SessionRegistry sessionRegistry;
    @Mock private SessionManager sessionManager;
    @Mock private TranscriptMessageRepository transcriptRepo;

    private ChatInjectionService service;

    @BeforeEach
    void setUp() {
        IntelliMateProperties properties = new IntelliMateProperties();
        service = new ChatInjectionService(sessionRegistry, sessionManager, transcriptRepo, properties);
    }

    @Test
    void injectAgentMessage_persistsAndPushes() {
        when(sessionManager.findOrCreateProactiveSession("agent-a")).thenReturn(Mono.just(42L));
        when(sessionManager.appendMessage(eq(42L), any(TranscriptMessageEntity.class))).thenReturn(Mono.empty());
        when(sessionRegistry.pushToAllAgentSessions(eq("agent-a"), eq("agent.proactive"), any(Map.class))).thenReturn(2);

        StepVerifier.create(service.injectAgentMessage("agent-a", "Hello!", ChatInjectionService.ProactiveSource.HEARTBEAT))
                .expectNext(2)
                .verifyComplete();

        ArgumentCaptor<TranscriptMessageEntity> captor = ArgumentCaptor.forClass(TranscriptMessageEntity.class);
        verify(sessionManager).appendMessage(eq(42L), captor.capture());

        TranscriptMessageEntity saved = captor.getValue();
        assertThat(saved.getRole()).isEqualTo("assistant");
        assertThat(saved.getContent()).isEqualTo("Hello!");
        assertThat(saved.getMetadataJson()).contains("heartbeat");
    }

    @Test
    void injectAgentMessage_persistFails_stillPushes() {
        when(sessionManager.findOrCreateProactiveSession("agent-a")).thenReturn(Mono.error(new RuntimeException("DB down")));
        when(sessionRegistry.pushToAllAgentSessions(eq("agent-a"), eq("agent.proactive"), any(Map.class))).thenReturn(1);

        StepVerifier.create(service.injectAgentMessage("agent-a", "Hi", ChatInjectionService.ProactiveSource.SCHEDULED_JOB))
                .expectNext(1)
                .verifyComplete();
    }

    @Test
    void injectAgentMessage_noSessions_returnsZero() {
        when(sessionManager.findOrCreateProactiveSession("agent-a")).thenReturn(Mono.just(42L));
        when(sessionManager.appendMessage(eq(42L), any())).thenReturn(Mono.empty());
        when(sessionRegistry.pushToAllAgentSessions(eq("agent-a"), eq("agent.proactive"), any(Map.class))).thenReturn(0);

        StepVerifier.create(service.injectAgentMessage("agent-a", "Hi", ChatInjectionService.ProactiveSource.HEARTBEAT))
                .expectNext(0)
                .verifyComplete();
    }
}
