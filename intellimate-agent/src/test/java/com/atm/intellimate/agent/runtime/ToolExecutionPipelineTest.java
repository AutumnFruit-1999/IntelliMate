package com.atm.intellimate.agent.runtime;

import com.atm.intellimate.agent.tools.AgentSessionContext;
import com.atm.intellimate.agent.tools.ToolsEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ToolExecutionPipelineTest {

    @Mock ToolsEngine toolsEngine;
    @Mock AgentSessionContext agentSessionContext;

    @Test
    void executeSingleTool_loopTerminate_returnsErrorResult() {
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                toolsEngine, agentSessionContext, null, null, new ObjectMapper(), null);

        ToolCallLoopDetector detector = new ToolCallLoopDetector(5, 3, 5, Set.of());
        for (int i = 0; i < 6; i++) {
            detector.check("readFile", "{\"path\":\"/tmp/a\"}");
        }

        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall("tc1", "tool", "readFile", "{\"path\":\"/tmp/a\"}");

        ToolExecutionResult result = pipeline.executeSingleTool(
                tc, "agent1", null, 1L, null,
                detector, new ToolResultCache(),
                Duration.ofSeconds(30), Set.of(), Map.of()
        ).block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isFalse();
        assertThat(result.result()).contains("循环调用");
    }

    @Test
    void executeSingleTool_cacheHit_returnsCachedResult() {
        ToolExecutionPipeline pipeline = new ToolExecutionPipeline(
                toolsEngine, agentSessionContext, null, null, new ObjectMapper(), null);

        ToolResultCache cache = new ToolResultCache();
        cache.put("readFile", "{\"path\":\"/tmp/a\"}", "file content");

        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall("tc1", "tool", "readFile", "{\"path\":\"/tmp/a\"}");

        ToolExecutionResult result = pipeline.executeSingleTool(
                tc, "agent1", null, 1L, null,
                new ToolCallLoopDetector(5, 3, 5, Set.of()),
                cache, Duration.ofSeconds(30), Set.of(), Map.of()
        ).block();

        assertThat(result).isNotNull();
        assertThat(result.success()).isTrue();
        assertThat(result.result()).contains("file content");
        assertThat(result.result()).contains("缓存结果");
    }
}
