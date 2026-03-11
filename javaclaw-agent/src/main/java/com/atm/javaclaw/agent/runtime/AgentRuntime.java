package com.atm.javaclaw.agent.runtime;

import com.atm.javaclaw.agent.tools.ToolsEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);
    private static final int MAX_CONTENT_LENGTH = 500;

    private final ChatClient chatClient;
    private final ToolsEngine toolsEngine;
    private final RunQueueManager runQueueManager;
    private final ObjectMapper objectMapper;

    public AgentRuntime(ChatClient.Builder chatClientBuilder,
                        ToolsEngine toolsEngine,
                        RunQueueManager runQueueManager,
                        ObjectMapper objectMapper) {
        this.chatClient = chatClientBuilder.build();
        this.toolsEngine = toolsEngine;
        this.runQueueManager = runQueueManager;
        this.objectMapper = objectMapper;
    }

    /**
     * Dispatch a streaming run request. Returns a Flux that emits tokens
     * in real-time as they arrive from the LLM.
     */
    public Flux<String> dispatch(AgentRunRequest request) {
        return runQueueManager.enqueue(request.sessionId(), () -> executeRun(request));
    }

    private Flux<String> executeRun(AgentRunRequest request) {
        String systemPrompt = buildSystemPrompt(request.agent());
        ToolCallback[] tools = toolsEngine.getToolCallbacksFor(request.toolsEnabled());

        logRequestParams(request, systemPrompt, tools);

        return chatClient.prompt()
                .system(systemPrompt)
                .messages(request.history())
                .user(request.userMessage())
                .toolCallbacks(tools)
                .stream()
                .content()
                .timeout(Duration.ofSeconds(request.agent().getTimeoutSeconds()));
    }

    private void logRequestParams(AgentRunRequest request, String systemPrompt, ToolCallback[] tools) {
        if (!log.isDebugEnabled()) {
            return;
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("sessionId", request.sessionId());
            params.put("model", request.agent().getModel());
            params.put("timeoutSeconds", request.agent().getTimeoutSeconds());
            params.put("systemPrompt", truncate(systemPrompt));
            params.put("userMessage", truncate(request.userMessage()));
            params.put("historySize", request.history() != null ? request.history().size() : 0);
            params.put("history", request.history() != null
                    ? request.history().stream().map(msg -> {
                        Map<String, String> m = new LinkedHashMap<>();
                        m.put("role", msg.getMessageType().getValue());
                        m.put("content", truncate(msg.getText()));
                        return m;
                    }).toList()
                    : java.util.List.of());
            params.put("tools", Arrays.stream(tools)
                    .map(cb -> cb.getToolDefinition().name())
                    .toList());
            params.put("toolsEnabledSpec", request.toolsEnabled());
            log.debug("LLM request params:\n{}", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(params));
        } catch (Exception e) {
            log.warn("Failed to serialize LLM request params", e);
        }
    }

    private static String truncate(String text) {
        if (text == null) return null;
        return text.length() > MAX_CONTENT_LENGTH
                ? text.substring(0, MAX_CONTENT_LENGTH) + "..."
                : text;
    }

    private static final int SECTION_MAX_CHARS = 20_000;
    private static final int TOTAL_MAX_CHARS = 150_000;

    private String buildSystemPrompt(com.atm.javaclaw.core.config.JavaClawProperties.Agent agentConfig) {
        StringBuilder sb = new StringBuilder();

        appendSection(sb, "SOUL", agentConfig.getSoulMd());
        appendSection(sb, "USER", agentConfig.getUserMd());
        appendSection(sb, "AGENTS", agentConfig.getAgentsMd());

        String instructions = agentConfig.getSystemPrompt();
        if (instructions == null || instructions.isBlank()) {
            instructions = "You are %s, a helpful AI assistant. You can use tools to help answer questions and complete tasks. Be concise and accurate in your responses."
                    .formatted(agentConfig.getName());
        }
        appendSection(sb, "Instructions", instructions);

        String prompt = sb.toString();
        if (prompt.length() > TOTAL_MAX_CHARS) {
            prompt = prompt.substring(0, TOTAL_MAX_CHARS) + "\n...[truncated]";
        }
        return prompt;
    }

    private static void appendSection(StringBuilder sb, String title, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append("## ").append(title).append('\n');
        if (content.length() > SECTION_MAX_CHARS) {
            sb.append(content, 0, SECTION_MAX_CHARS).append("\n...[truncated]");
        } else {
            sb.append(content);
        }
    }
}
