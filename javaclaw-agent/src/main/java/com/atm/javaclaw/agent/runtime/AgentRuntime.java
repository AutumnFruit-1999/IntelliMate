package com.atm.javaclaw.agent.runtime;

import com.atm.javaclaw.agent.tools.ToolsEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);
    private static final int MAX_CONTENT_LENGTH = 500;

    private final ChatModel chatModel;
    private final ToolsEngine toolsEngine;
    private final RunQueueManager runQueueManager;
    private final ObjectMapper objectMapper;

    public AgentRuntime(ChatModel chatModel,
                        ToolsEngine toolsEngine,
                        RunQueueManager runQueueManager,
                        ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.toolsEngine = toolsEngine;
        this.runQueueManager = runQueueManager;
        this.objectMapper = objectMapper;
    }

    public Flux<AgentEvent> dispatch(AgentRunRequest request) {
        return runQueueManager.enqueue(request.sessionId(), () -> executeAgentLoop(request));
    }

    private Flux<AgentEvent> executeAgentLoop(AgentRunRequest request) {
        String systemPrompt = buildSystemPrompt(request.agent());
        ToolCallback[] tools = toolsEngine.getToolCallbacksFor(request.toolsEnabled(), request.mcpToolsEnabled());
        int maxTurns = request.agent().getMaxTurns();
        Duration timeout = Duration.ofSeconds(request.agent().getTimeoutSeconds());

        log.info("Agent '{}' tools: {} total ({} builtin, {} custom, {} mcp), toolsSpec='{}', mcpSpec='{}'",
                request.agent().getName(), tools.length,
                toolsEngine.getBuiltinCount(), toolsEngine.getDynamicCount(), toolsEngine.getMcpCount(),
                request.toolsEnabled(), request.mcpToolsEnabled());

        List<Message> conversationHistory = new ArrayList<>();
        conversationHistory.add(new SystemMessage(systemPrompt));
        if (request.history() != null) {
            conversationHistory.addAll(request.history());
        }
        conversationHistory.add(new UserMessage(request.userMessage()));

        ToolCallingChatOptions chatOptions = ToolCallingChatOptions.builder()
                .toolCallbacks(tools)
                .internalToolExecutionEnabled(false)
                .build();

        logRequestParams(request, systemPrompt, tools);

        return executeLoopTurn(conversationHistory, chatOptions, maxTurns, timeout, 1, new StringBuilder());
    }

    private Flux<AgentEvent> executeLoopTurn(
            List<Message> history,
            ToolCallingChatOptions options,
            int maxTurns,
            Duration timeout,
            int turn,
            StringBuilder fullText) {

        if (turn > maxTurns) {
            log.warn("Agent loop reached maxTurns={}", maxTurns);
            String text = fullText.toString();
            if (text.isEmpty()) {
                text = "[Agent Loop reached maximum turns (" + maxTurns + ")]";
            }
            return Flux.just(new AgentEvent.Done(text, turn - 1));
        }

        log.debug("Agent loop turn={}/{}", turn, maxTurns);

        Prompt prompt = new Prompt(new ArrayList<>(history), options);
        List<ChatResponse> allChunks = new ArrayList<>();

        Flux<AgentEvent> turnStart = Flux.just(new AgentEvent.TurnStart(turn, maxTurns));

        Flux<AgentEvent> streaming = chatModel.stream(prompt)
                .timeout(timeout)
                .concatMap(chunk -> {
                    allChunks.add(chunk);
                    String delta = extractTextDelta(chunk);
                    if (delta != null && !delta.isEmpty()) {
                        fullText.append(delta);
                        return Flux.just((AgentEvent) new AgentEvent.TextChunk(delta));
                    }
                    return Flux.empty();
                });

        Flux<AgentEvent> afterStream = Flux.defer(() -> {
            ChatResponse lastWithToolCalls = findToolCallResponse(allChunks);

            if (lastWithToolCalls != null) {
                return processToolCalls(history, options, lastWithToolCalls, maxTurns, timeout, turn, fullText);
            }

            return Flux.just(new AgentEvent.Done(fullText.toString(), turn));
        });

        return Flux.concat(turnStart, streaming, afterStream);
    }

    private ChatResponse findToolCallResponse(List<ChatResponse> chunks) {
        for (int i = chunks.size() - 1; i >= 0; i--) {
            ChatResponse chunk = chunks.get(i);
            try {
                if (chunk != null && chunk.hasToolCalls()) {
                    return chunk;
                }
            } catch (Exception e) {
                log.trace("Error checking tool calls on chunk {}: {}", i, e.getMessage());
            }
        }
        return null;
    }

    private Flux<AgentEvent> processToolCalls(
            List<Message> history,
            ToolCallingChatOptions options,
            ChatResponse toolCallResponse,
            int maxTurns,
            Duration timeout,
            int turn,
            StringBuilder fullText) {

        AssistantMessage assistantMsg = toolCallResponse.getResult().getOutput();
        history.add(assistantMsg);

        List<AssistantMessage.ToolCall> toolCalls = assistantMsg.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Flux.just(new AgentEvent.Done(fullText.toString(), turn));
        }

        log.debug("Turn {} has {} tool call(s)", turn, toolCalls.size());

        return Flux.fromIterable(toolCalls)
                .concatMap(tc -> {
                    AgentEvent callEvent = new AgentEvent.ToolCall(tc.id(), tc.name(), tc.arguments());

                    Mono<AgentEvent> resultEvent = Mono.fromCallable(() -> {
                        try {
                            ToolCallback callback = toolsEngine.getCallbackByName(tc.name());
                            String result = callback.call(tc.arguments());
                            log.debug("Tool {} executed, result length={}", tc.name(),
                                    result != null ? result.length() : 0);

                            history.add(new ToolResponseMessage(List.of(
                                    new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), result)
                            )));

                            return (AgentEvent) new AgentEvent.ToolResult(
                                    tc.id(), tc.name(),
                                    result != null ? result : "", true);
                        } catch (Exception e) {
                            String errorMsg = "Tool execution failed: " + e.getMessage();
                            log.warn("Tool {} failed: {}", tc.name(), e.getMessage(), e);

                            history.add(new ToolResponseMessage(List.of(
                                    new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), errorMsg)
                            )));

                            return (AgentEvent) new AgentEvent.ToolResult(
                                    tc.id(), tc.name(), errorMsg, false);
                        }
                    }).subscribeOn(Schedulers.boundedElastic());

                    return Flux.concat(Flux.just(callEvent), resultEvent.flux());
                })
                .concatWith(Flux.defer(() ->
                        executeLoopTurn(history, options, maxTurns, timeout, turn + 1, fullText)
                ));
    }

    private String extractTextDelta(ChatResponse chunk) {
        if (chunk == null || chunk.getResults() == null || chunk.getResults().isEmpty()) {
            return null;
        }
        Generation gen = chunk.getResults().get(0);
        if (gen == null || gen.getOutput() == null) {
            return null;
        }
        return gen.getOutput().getText();
    }

    // ─── logging / prompt building (unchanged) ───

    private void logRequestParams(AgentRunRequest request, String systemPrompt, ToolCallback[] tools) {
        if (!log.isDebugEnabled()) {
            return;
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("sessionId", request.sessionId());
            params.put("model", request.agent().getModel());
            params.put("maxTurns", request.agent().getMaxTurns());
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
                    : List.of());
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
//        if (instructions == null || instructions.isBlank()) {
//            instructions = "You are %s, a helpful AI assistant. You can use tools to help answer questions and complete tasks. Be concise and accurate in your responses."
//                    .formatted(agentConfig.getName());
//        }
//        appendSection(sb, "Instructions", instructions);

        String prompt = sb.toString();
        System.out.println("prompt = " + prompt);
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
