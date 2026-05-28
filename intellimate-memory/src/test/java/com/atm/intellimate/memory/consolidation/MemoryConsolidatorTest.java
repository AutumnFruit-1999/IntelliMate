package com.atm.intellimate.memory.consolidation;

import com.atm.intellimate.memory.longterm.LongTermMemory;
import com.atm.intellimate.memory.model.ChunkType;
import com.atm.intellimate.memory.model.ExtractedFact;
import com.atm.intellimate.memory.model.MemoryChunk;
import com.atm.intellimate.memory.working.TokenEstimator;
import com.atm.intellimate.memory.working.WorkingMemory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MemoryConsolidatorTest {

    @Mock
    ChatModel primaryModel;
    @Mock
    ChatModel fallbackModel;
    @Mock
    LongTermMemory longTermMemory;
    @Mock
    TokenEstimator tokenEstimator;

    private static MemoryChunk system() {
        return MemoryChunk.system("system", 5);
    }

    /** Working memory with {@code n} user chunks; high budget so accept() never schedules async consolidation. */
    private WorkingMemory workingMemoryWithUserChunks(int n) {
        WorkingMemory wm = new WorkingMemory(10_000_000, 0.99f, 1.2f, null, system());
        for (int i = 0; i < n; i++) {
            wm.accept(MemoryChunk.user("msg-" + i, 50)).block();
        }
        return wm;
    }

    private static ChatResponse chatResponseWithText(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    private static Flux<ChatResponse> streamResponseWithText(String text) {
        return Flux.just(chatResponseWithText(text));
    }

    @Test
    @DisplayName("tryConsolidate_whenBelowThreshold_returnsNull")
    void tryConsolidate_whenBelowThreshold_returnsNull() {
        MemoryConsolidator consolidator = new MemoryConsolidator(
                primaryModel, fallbackModel, longTermMemory, tokenEstimator,
                512, 2, 5_000L);

        WorkingMemory wm = workingMemoryWithUserChunks(3);
        assertNull(consolidator.tryConsolidate(wm, "default"));
        verifyNoInteractions(primaryModel, fallbackModel);
    }

    @Test
    @DisplayName("tryConsolidate_success_returnsResult")
    void tryConsolidate_success_returnsResult() {
        when(tokenEstimator.estimate(anyString())).thenReturn(12);
        String json = """
                {"summary":"merged summary","facts":[{"type":"semantic","content":"remember this","importance":0.72}]}
                """;
        when(primaryModel.stream(any(Prompt.class))).thenReturn(streamResponseWithText(json));
        when(longTermMemory.store(any(ExtractedFact.class), anyString(), anyString())).thenReturn(Mono.empty());

        MemoryConsolidator consolidator = new MemoryConsolidator(
                primaryModel, fallbackModel, longTermMemory, tokenEstimator,
                512, 2, 5_000L);

        WorkingMemory wm = workingMemoryWithUserChunks(5);
        ConsolidationResult result = consolidator.tryConsolidate(wm, "default");

        assertNotNull(result);
        assertEquals(ChunkType.CONSOLIDATED, result.summaryChunk().type());
        assertEquals("merged summary", result.summaryChunk().content());
        assertEquals(1, result.facts().size());
        assertEquals("remember this", result.facts().getFirst().content());
        assertTrue(result.tokensBefore() > 0);
        assertTrue(result.tokensAfter() > 0);
        verify(longTermMemory).store(any(ExtractedFact.class), eq("default"), eq("default"));
    }

    @Test
    @DisplayName("tryConsolidate_llmFailure_withFallback_usesSecondModel")
    void tryConsolidate_llmFailure_withFallback_usesSecondModel() {
        when(tokenEstimator.estimate(anyString())).thenReturn(8);
        String json = "{\"summary\":\"from-fallback\",\"facts\":[]}";
        when(primaryModel.stream(any(Prompt.class)))
                .thenReturn(Flux.error(new RuntimeException("primary down")))
                .thenReturn(Flux.error(new RuntimeException("primary still down")));
        when(fallbackModel.stream(any(Prompt.class))).thenReturn(streamResponseWithText(json));

        MemoryConsolidator consolidator = new MemoryConsolidator(
                primaryModel, fallbackModel, longTermMemory, tokenEstimator,
                512, 2, 5_000L);

        WorkingMemory wm = workingMemoryWithUserChunks(5);
        ConsolidationResult result = consolidator.tryConsolidate(wm, "default");

        assertNotNull(result);
        assertEquals("from-fallback", result.summaryChunk().content());
        verify(primaryModel, times(2)).stream(any(Prompt.class));
        verify(fallbackModel, times(1)).stream(any(Prompt.class));
    }

    @Test
    @DisplayName("tryConsolidate_allFail_returnsNull")
    void tryConsolidate_allFail_returnsNull() {
        when(primaryModel.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("fail")));
        when(fallbackModel.stream(any(Prompt.class))).thenReturn(Flux.error(new RuntimeException("fail-fallback")));

        MemoryConsolidator consolidator = new MemoryConsolidator(
                primaryModel, fallbackModel, longTermMemory, tokenEstimator,
                512, 2, 5_000L);

        WorkingMemory wm = workingMemoryWithUserChunks(5);
        assertNull(consolidator.tryConsolidate(wm, "default"));
        verify(primaryModel, atLeastOnce()).stream(any(Prompt.class));
        verify(fallbackModel, atLeastOnce()).stream(any(Prompt.class));
    }

    @Test
    @DisplayName("tryConsolidate_timeout_returnsNull")
    void tryConsolidate_timeout_returnsNull() {
        when(primaryModel.stream(any(Prompt.class)))
                .thenReturn(Flux.just(chatResponseWithText("{\"summary\":\"late\",\"facts\":[]}"))
                        .delayElements(Duration.ofMillis(800)));

        MemoryConsolidator consolidator = new MemoryConsolidator(
                primaryModel, null, longTermMemory, tokenEstimator,
                512, 2, 50L);

        WorkingMemory wm = workingMemoryWithUserChunks(5);
        assertNull(consolidator.tryConsolidate(wm, "default"));
        verify(primaryModel, atLeast(1)).stream(any(Prompt.class));
    }
}
