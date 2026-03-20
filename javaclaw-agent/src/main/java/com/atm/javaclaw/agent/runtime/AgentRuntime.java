package com.atm.javaclaw.agent.runtime;

import com.atm.javaclaw.agent.model.ChatModelRegistry;
import com.atm.javaclaw.agent.model.ProviderType;
import com.atm.javaclaw.agent.model.ResolvedModel;
import com.atm.javaclaw.agent.skills.SkillContentProvider;
import com.atm.javaclaw.agent.skills.SkillUsageRecorder;
import com.atm.javaclaw.agent.tools.ToolsEngine;
import com.atm.javaclaw.core.config.JavaClawProperties;
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
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;

@Service
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);
    private static final int MAX_CONTENT_LENGTH = 500;
    private static final int MAX_RETRIES = 2;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private final ChatModelRegistry chatModelRegistry;
    private final ToolsEngine toolsEngine;
    private final RunQueueManager runQueueManager;
    private final ObjectMapper objectMapper;
    private final SkillContentProvider skillContentProvider;
    private final SkillUsageRecorder skillUsageRecorder;
    private final JavaClawProperties properties;

    public AgentRuntime(ChatModelRegistry chatModelRegistry,
                        ToolsEngine toolsEngine,
                        RunQueueManager runQueueManager,
                        ObjectMapper objectMapper,
                        @Autowired(required = false) SkillContentProvider skillContentProvider,
                        @Autowired(required = false) SkillUsageRecorder skillUsageRecorder,
                        JavaClawProperties properties) {
        this.chatModelRegistry = chatModelRegistry;
        this.toolsEngine = toolsEngine;
        this.runQueueManager = runQueueManager;
        this.objectMapper = objectMapper;
        this.skillContentProvider = skillContentProvider;
        this.skillUsageRecorder = skillUsageRecorder;
        this.properties = properties;
    }

    public Flux<AgentEvent> dispatch(AgentRunRequest request) {
        return runQueueManager.enqueue(request.sessionId(), () -> executeAgentLoop(request));
    }

    private final ConcurrentMap<Long, ToolApprovalGate> sessionApprovalGates = new ConcurrentHashMap<>();

    /**
     * Resolves a pending tool approval for a given session.
     * Called by MessagePipeline when the user responds to an approval request.
     */
    public void resolveApproval(Long sessionId, String toolCallId, boolean approved, String modifiedArguments) {
        ToolApprovalGate gate = sessionApprovalGates.get(sessionId);
        if (gate != null) {
            gate.resolve(toolCallId, approved, modifiedArguments);
        } else {
            log.warn("No approval gate found for sessionId={}, toolCallId={}", sessionId, toolCallId);
        }
    }

    private Flux<AgentEvent> executeAgentLoop(AgentRunRequest request) {
        Mono<List<SkillContentProvider.SkillSummary>> skillsMono;
        if (skillContentProvider != null && request.skillsEnabled() != null && !request.skillsEnabled().isBlank()) {
            skillsMono = skillContentProvider.resolveSkillSummaries(request.skillsEnabled());
        } else {
            skillsMono = Mono.just(List.of());
        }

        return skillsMono.flatMapMany(skillSummaries -> {
            JavaClawProperties.Agent agentConfig = request.agent();
            boolean parallelEnabled = agentConfig.isEnableParallelToolCalls();
            String systemPrompt = buildSystemPrompt(agentConfig, skillSummaries, parallelEnabled);
            ToolCallback[] tools = toolsEngine.getToolCallbacksFor(request.toolsEnabled(), request.mcpToolsEnabled());
            int maxTurns = agentConfig.getMaxTurns();
            Duration timeout = Duration.ofSeconds(agentConfig.getTimeoutSeconds());

            ResolvedModel resolved = chatModelRegistry.resolveByModelName(agentConfig.getModel());
            log.info("Agent '{}' model='{}' (resolved modelId='{}'), tools: {} total ({} builtin, {} custom, {} mcp), toolsSpec='{}', mcpSpec='{}', skills={}",
                    agentConfig.getName(), agentConfig.getModel(), resolved.modelId(),
                    tools.length,
                    toolsEngine.getBuiltinCount(), toolsEngine.getDynamicCount(), toolsEngine.getMcpCount(),
                    request.toolsEnabled(), request.mcpToolsEnabled(), skillSummaries.size());

            String skillsBasePath = skillContentProvider != null ? skillContentProvider.getSkillsBasePath() : null;

            List<Message> conversationHistory = new ArrayList<>();
            conversationHistory.add(new SystemMessage(systemPrompt));
            if (request.history() != null) {
                conversationHistory.addAll(request.history());
            }
            conversationHistory.add(new UserMessage(request.userMessage()));

            ChatOptions chatOptions = buildChatOptions(tools, resolved, parallelEnabled);

            logRequestParams(request, systemPrompt, tools);

            // Per-run middleware instances
            ToolCallLoopDetector loopDetector = new ToolCallLoopDetector(
                    agentConfig.getLoopDetectorWindowSize(),
                    agentConfig.getLoopDetectorWarnThreshold(),
                    agentConfig.getLoopDetectorTerminateThreshold(),
                    new HashSet<>(agentConfig.getLoopDetectorExcludedTools()));

            ContextWindowTracker tracker = new ContextWindowTracker(agentConfig.getMaxContextTokens());
            tracker.addToolResultChars(systemPrompt.length());
            for (Message msg : conversationHistory) {
                if (msg.getText() != null) {
                    tracker.addToolResultChars(msg.getText().length());
                }
            }

            ContextCondenser condenser = new ContextCondenser(
                    agentConfig.getCondenserKeepRecent(),
                    agentConfig.getCondenserSummaryLength(),
                    agentConfig.getCondenserMinTurnsBetween());
            ToolResultCache cache = new ToolResultCache();

            Set<String> approvalTools = new HashSet<>(agentConfig.getApprovalRequiredTools());
            ToolApprovalGate approvalGate = new ToolApprovalGate(approvalTools);
            sessionApprovalGates.put(request.sessionId(), approvalGate);

            Duration toolTimeout = Duration.ofSeconds(agentConfig.getToolExecutionTimeoutSeconds());
            int maxToolResultChars = agentConfig.getMaxToolResultChars();
            int maxParallel = agentConfig.getMaxParallelToolCalls();
            Set<String> nonRetryable = new HashSet<>(agentConfig.getNonRetryableTools());

            return executeLoopTurn(resolved.chatModel(), conversationHistory, chatOptions, maxTurns, timeout, 1, new StringBuilder(),
                    agentConfig.getName(), request.sessionId(), skillsBasePath,
                    loopDetector, tracker, condenser, cache, approvalGate,
                    toolTimeout, maxToolResultChars, maxParallel, nonRetryable)
                    .doFinally(signal -> sessionApprovalGates.remove(request.sessionId()));
        });
    }

    private Flux<AgentEvent> executeLoopTurn(
            ChatModel chatModel,
            List<Message> history,
            ChatOptions options,
            int maxTurns,
            Duration timeout,
            int turn,
            StringBuilder fullText,
            String agentName,
            Long sessionId,
            String skillsBasePath,
            ToolCallLoopDetector loopDetector,
            ContextWindowTracker tracker,
            ContextCondenser condenser,
            ToolResultCache cache,
            ToolApprovalGate approvalGate,
            Duration toolTimeout,
            int maxToolResultChars,
            int maxParallel,
            Set<String> nonRetryableTools) {

        if (turn > maxTurns) {
            log.warn("Agent loop reached maxTurns={}", maxTurns);
            String text = fullText.toString();
            if (text.isEmpty()) {
                text = "[Agent Loop reached maximum turns (" + maxTurns + ")]";
            }
            return Flux.just(new AgentEvent.Done(text, turn - 1));
        }

        if (tracker.isOverLimit()) {
            log.error("Context window exceeded: ~{} / {} tokens, forcing completion",
                    tracker.estimatedTotalTokens(), tracker.getMaxContextTokens());
            String text = fullText.toString();
            if (text.isEmpty()) {
                text = "[Context window exceeded, forcing completion]";
            }
            return Flux.just(new AgentEvent.Done(text, turn));
        }

        // Context condensation check
        if (condenser.shouldCondense(turn, tracker)) {
            int beforeSize = history.size();
            List<Message> condensed = condenser.condense(history, turn);
            history.clear();
            history.addAll(condensed);
            tracker.recalculate(history);
            log.info("Context condensed at turn {}: {} messages, ~{} tokens",
                    turn, history.size(), tracker.estimatedTotalTokens());

            if (tracker.isOverLimit()) {
                log.error("Still over limit after condensation, forcing completion");
                String text = fullText.toString();
                if (text.isEmpty()) {
                    text = "[Context window exceeded after condensation, forcing completion]";
                }
                return Flux.just(new AgentEvent.Done(text, turn));
            }
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
            if (!allChunks.isEmpty()) {
                ChatResponse lastChunk = allChunks.get(allChunks.size() - 1);
                try {
                    if (lastChunk.getMetadata() != null
                            && lastChunk.getMetadata().getUsage() != null
                            && lastChunk.getMetadata().getUsage().getTotalTokens() > 0) {
                        tracker.updateFromApiUsage(lastChunk.getMetadata().getUsage().getTotalTokens());
                    }
                } catch (Exception e) {
                    log.trace("Could not extract usage from last chunk: {}", e.getMessage());
                }
            }

            ChatResponse merged = mergeToolCallsFromChunks(allChunks);

            if (merged != null) {
                return processToolCalls(chatModel, history, options, merged, maxTurns, timeout, turn, fullText,
                        agentName, sessionId, skillsBasePath,
                        loopDetector, tracker, condenser, cache, approvalGate,
                        toolTimeout, maxToolResultChars, maxParallel, nonRetryableTools);
            }

            return Flux.just(new AgentEvent.Done(fullText.toString(), turn));
        });

        return Flux.concat(turnStart, streaming, afterStream);
    }

    /**
     * Merges tool calls from ALL streaming chunks into a single ChatResponse.
     * Unlike findToolCallResponse (which only takes the last chunk), this collects
     * tool calls scattered across multiple chunks to support parallel tool calling.
     */
    private ChatResponse mergeToolCallsFromChunks(List<ChatResponse> chunks) {
        List<AssistantMessage.ToolCall> allToolCalls = new ArrayList<>();
        ChatResponse lastToolCallChunk = null;

        for (ChatResponse chunk : chunks) {
            try {
                if (chunk != null && chunk.hasToolCalls()) {
                    lastToolCallChunk = chunk;
                    List<AssistantMessage.ToolCall> calls = chunk.getResult().getOutput().getToolCalls();
                    if (calls != null) {
                        allToolCalls.addAll(calls);
                    }
                }
            } catch (Exception e) {
                log.trace("Error extracting tool calls from chunk: {}", e.getMessage());
            }
        }

        if (allToolCalls.isEmpty() || lastToolCallChunk == null) {
            return null;
        }

        if (allToolCalls.size() == lastToolCallChunk.getResult().getOutput().getToolCalls().size()) {
            return lastToolCallChunk;
        }

        AssistantMessage original = lastToolCallChunk.getResult().getOutput();
        AssistantMessage merged = new AssistantMessage(
                original.getText() != null ? original.getText() : "",
                original.getMetadata(),
                allToolCalls);
        Generation mergedGen = new Generation(merged, lastToolCallChunk.getResult().getMetadata());
        return new ChatResponse(List.of(mergedGen), lastToolCallChunk.getMetadata());
    }

    private Flux<AgentEvent> processToolCalls(
            ChatModel chatModel,
            List<Message> history,
            ChatOptions options,
            ChatResponse toolCallResponse,
            int maxTurns,
            Duration timeout,
            int turn,
            StringBuilder fullText,
            String agentName,
            Long sessionId,
            String skillsBasePath,
            ToolCallLoopDetector loopDetector,
            ContextWindowTracker tracker,
            ContextCondenser condenser,
            ToolResultCache cache,
            ToolApprovalGate approvalGate,
            Duration toolTimeout,
            int maxToolResultChars,
            int maxParallel,
            Set<String> nonRetryableTools) {

        AssistantMessage assistantMsg = toolCallResponse.getResult().getOutput();
        history.add(assistantMsg);

        List<AssistantMessage.ToolCall> toolCalls = assistantMsg.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Flux.just(new AgentEvent.Done(fullText.toString(), turn));
        }

        if (toolCalls.size() > 1) {
            log.info("Turn {} has {} tool calls, executing in parallel (maxParallel={}): {}",
                    turn, toolCalls.size(), maxParallel,
                    toolCalls.stream().map(AssistantMessage.ToolCall::name).toList());
        } else {
            log.debug("Turn {} has {} tool call(s)", turn, toolCalls.size());
        }

        // Separate tool calls into those needing approval and those that don't
        List<AssistantMessage.ToolCall> directCalls = new ArrayList<>();
        List<AssistantMessage.ToolCall> approvalCalls = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : toolCalls) {
            if (approvalGate.requiresApproval(tc.name())) {
                approvalCalls.add(tc);
            } else {
                directCalls.add(tc);
            }
        }

        // Phase 1: Emit all ToolCall events (with turn for frontend grouping)
        Flux<AgentEvent> callEvents = Flux.fromIterable(toolCalls)
                .map(tc -> (AgentEvent) new AgentEvent.ToolCall(tc.id(), tc.name(), tc.arguments(), turn));

        // Phase 2a: Execute non-approval tools in parallel
        Mono<List<ToolExecutionResult>> directResultsMono = Flux.fromIterable(directCalls)
                .flatMap(tc -> executeSingleTool(tc, agentName, sessionId, skillsBasePath,
                        loopDetector, cache, approvalGate,
                        toolTimeout, maxToolResultChars, nonRetryableTools), maxParallel)
                .collectList();

        // Phase 2b: Handle approval tools (emit approval events, wait, then execute)
        Flux<AgentEvent> approvalFlow = Flux.fromIterable(approvalCalls)
                .concatMap(tc -> {
                    AgentEvent approvalEvent = new AgentEvent.ApprovalRequired(tc.id(), tc.name(), tc.arguments());

                    Mono<ToolExecutionResult> afterApproval = approvalGate.requestApproval(tc.id())
                            .flatMap(decision -> {
                                if (!decision.approved()) {
                                    return Mono.just(new ToolExecutionResult(tc.id(), tc.name(),
                                            "User rejected tool execution.", false));
                                }
                                String args = decision.modifiedArguments() != null
                                        ? decision.modifiedArguments() : tc.arguments();
                                return doExecuteTool(tc.id(), tc.name(), args, agentName, sessionId, skillsBasePath,
                                        cache, toolTimeout, maxToolResultChars, nonRetryableTools, false);
                            });

                    return Flux.concat(
                            Flux.just(approvalEvent),
                            afterApproval.map(r -> (AgentEvent) new AgentEvent.ToolResult(r.id(), r.name(), r.result(), r.success(), turn))
                    );
                });

        // Phase 3: Combine results, add to history, emit ToolResult events for direct calls
        Flux<AgentEvent> directResultEvents = directResultsMono.flatMapMany(results -> {
            // Approval results are handled inline; collect direct results for history
            return Flux.fromIterable(results)
                    .map(r -> (AgentEvent) new AgentEvent.ToolResult(r.id(), r.name(), r.result(), r.success(), turn));
        });

        // Phase 4: After all tool results are emitted, collect everything into history and recurse
        Flux<AgentEvent> execution = Flux.concat(directResultEvents, approvalFlow);

        Flux<AgentEvent> withHistory = execution.collectList().flatMapMany(allEvents -> {
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AgentEvent evt : allEvents) {
                if (evt instanceof AgentEvent.ToolResult tr) {
                    responses.add(new ToolResponseMessage.ToolResponse(tr.toolCallId(), tr.name(), tr.result()));
                    int chars = tr.result() != null ? tr.result().length() : 0;
                    tracker.addToolResultChars(chars);
                }
            }
            if (!responses.isEmpty()) {
                history.add(new ToolResponseMessage(responses));
            }
            return Flux.fromIterable(allEvents);
        });

        // Phase 5: Recurse to next turn
        Flux<AgentEvent> nextTurn = Flux.defer(() ->
                executeLoopTurn(chatModel, history, options, maxTurns, timeout, turn + 1, fullText,
                        agentName, sessionId, skillsBasePath,
                        loopDetector, tracker, condenser, cache, approvalGate,
                        toolTimeout, maxToolResultChars, maxParallel, nonRetryableTools));

        return Flux.concat(callEvents, withHistory, nextTurn);
    }

    /**
     * Executes a single tool call with the full middleware chain:
     * loop detection -> approval gate -> cache check -> actual execution (with retry + timeout) -> truncation.
     *
     * Returns a Mono of ToolExecutionResult. Approval events are emitted separately
     * via processToolCalls which handles the approval flow.
     */
    private Mono<ToolExecutionResult> executeSingleTool(
            AssistantMessage.ToolCall tc,
            String agentName,
            Long sessionId,
            String skillsBasePath,
            ToolCallLoopDetector loopDetector,
            ToolResultCache cache,
            ToolApprovalGate approvalGate,
            Duration toolTimeout,
            int maxToolResultChars,
            Set<String> nonRetryableTools) {

        // 1. Loop detection
        ToolCallLoopDetector.LoopStatus loopStatus = loopDetector.check(tc.name(), tc.arguments());

        if (loopStatus == ToolCallLoopDetector.LoopStatus.TERMINATE) {
            log.warn("Tool call loop detected (TERMINATE): {}({})", tc.name(),
                    tc.arguments().length() > 100 ? tc.arguments().substring(0, 100) + "..." : tc.arguments());
            String terminateMsg = "Loop detected: you've called " + tc.name()
                    + " with identical arguments multiple times. "
                    + "Please use the information you already have or try a different approach.";
            return Mono.just(new ToolExecutionResult(tc.id(), tc.name(), terminateMsg, false));
        }

        boolean appendWarning = (loopStatus == ToolCallLoopDetector.LoopStatus.WARN);

        // 2. Direct execution (approval is handled at the processToolCalls level)
        return doExecuteTool(tc.id(), tc.name(), tc.arguments(), agentName, sessionId, skillsBasePath,
                cache, toolTimeout, maxToolResultChars, nonRetryableTools, appendWarning);
    }

    private Mono<ToolExecutionResult> doExecuteTool(
            String toolCallId,
            String toolName,
            String arguments,
            String agentName,
            Long sessionId,
            String skillsBasePath,
            ToolResultCache cache,
            Duration toolTimeout,
            int maxToolResultChars,
            Set<String> nonRetryableTools,
            boolean appendWarning) {

        // Check cache first
        String cached = cache.get(toolName, arguments);
        if (cached != null) {
            String result = cached + "\n[cached result]";
            result = truncateToolResult(result, maxToolResultChars);
            if (appendWarning) {
                result += "\n\n[WARNING: You've called this tool with identical arguments multiple times. Consider a different approach.]";
            }
            return Mono.just(new ToolExecutionResult(toolCallId, toolName, result, true));
        }

        Mono<ToolExecutionResult> execution = Mono.fromCallable(() -> {
            ToolCallback callback = toolsEngine.getCallbackByName(toolName);
            String result = callback.call(arguments);
            log.debug("Tool {} executed, result length={}", toolName, result != null ? result.length() : 0);

            recordSkillActivationIfApplicable(toolName, arguments, agentName, sessionId, skillsBasePath);

            cache.put(toolName, arguments, result);
            cache.invalidateForWrite(toolName, arguments);

            result = truncateToolResult(result, maxToolResultChars);
            if (appendWarning) {
                result += "\n\n[WARNING: You've called this tool with identical arguments multiple times. Consider a different approach.]";
            }
            return new ToolExecutionResult(toolCallId, toolName, result != null ? result : "", true);
        }).subscribeOn(Schedulers.boundedElastic())
          .timeout(toolTimeout);

        // Apply retry for retryable errors (skip for tools in nonRetryableTools)
        if (!nonRetryableTools.contains(toolName)) {
            execution = execution.retryWhen(Retry.backoff(MAX_RETRIES, RETRY_DELAY)
                    .filter(this::isRetryableError)
                    .doBeforeRetry(signal ->
                            log.info("Retrying tool {} (attempt {})", toolName, signal.totalRetries() + 1)));
        }

        return execution.onErrorResume(e -> {
            String errorMsg = "Tool execution failed: " + e.getMessage();
            log.warn("Tool {} failed: {}", toolName, e.getMessage(), e);
            return Mono.just(new ToolExecutionResult(toolCallId, toolName, errorMsg, false));
        });
    }

    private boolean isRetryableError(Throwable e) {
        if (e instanceof SocketTimeoutException
                || e instanceof ConnectException
                || e instanceof TimeoutException) {
            return true;
        }
        if (e instanceof FileNotFoundException
                || e instanceof IllegalArgumentException
                || e instanceof SecurityException) {
            return false;
        }
        if (e instanceof com.atm.javaclaw.core.exception.ToolExecutionException) {
            return false;
        }
        return e.getMessage() != null && e.getMessage().contains("429");
    }

    static String truncateToolResult(String result, int maxChars) {
        if (result == null || result.length() <= maxChars) {
            return result;
        }
        int half = maxChars / 2;
        return result.substring(0, half)
                + "\n\n... [truncated: showing first and last " + half
                + " chars of " + result.length() + " total] ...\n\n"
                + result.substring(result.length() - half);
    }

    // ─── skill activation recording ───

    private void recordSkillActivationIfApplicable(String toolName, String arguments,
                                                   String agentName, Long sessionId, String skillsBasePath) {
        if (skillUsageRecorder == null) return;

        try {
            if ("readFile".equals(toolName) && skillsBasePath != null) {
                String path = extractPathFromArgs(arguments);
                if (path != null && path.contains("/SKILL.md")) {
                    String skillName = extractSkillNameFromPath(path, skillsBasePath);
                    if (skillName != null) {
                        skillUsageRecorder.recordActivation(skillName, agentName, sessionId, "file_read");
                    }
                }
            } else if ("getSkillContent".equals(toolName)) {
                String skillName = extractSkillNameFromArgs(arguments);
                if (skillName != null) {
                    skillUsageRecorder.recordActivation(skillName, agentName, sessionId, "tool_call");
                }
            }
        } catch (Exception e) {
            log.debug("Failed to record skill activation: {}", e.getMessage());
        }
    }

    private String extractPathFromArgs(String arguments) {
        try {
            var node = objectMapper.readTree(arguments);
            return node.has("path") ? node.get("path").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String extractSkillNameFromPath(String path, String skillsBasePath) {
        String normalized = path.replace("\\", "/");
        String basePath = skillsBasePath.replace("\\", "/");
        if (!basePath.endsWith("/")) basePath += "/";

        if (normalized.startsWith(basePath)) {
            String relative = normalized.substring(basePath.length());
            int slashIdx = relative.indexOf('/');
            return slashIdx > 0 ? relative.substring(0, slashIdx) : null;
        }
        return null;
    }

    private String extractSkillNameFromArgs(String arguments) {
        try {
            var node = objectMapper.readTree(arguments);
            return node.has("skillName") ? node.get("skillName").asText() : null;
        } catch (Exception e) {
            return null;
        }
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

    // ─── ChatOptions building ───

    private ChatOptions buildChatOptions(ToolCallback[] tools, ResolvedModel resolved, boolean parallelEnabled) {
        if (parallelEnabled && resolved.providerType() == ProviderType.OPENAI_COMPATIBLE) {
            return OpenAiChatOptions.builder()
                    .toolCallbacks(tools)
                    .model(resolved.modelId())
                    .internalToolExecutionEnabled(false)
                    .parallelToolCalls(true)
                    .build();
        }
        return ToolCallingChatOptions.builder()
                .toolCallbacks(tools)
                .model(resolved.modelId())
                .internalToolExecutionEnabled(false)
                .build();
    }

    // ─── logging / prompt building ───

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

    private String buildSystemPrompt(JavaClawProperties.Agent agentConfig,
                                     List<SkillContentProvider.SkillSummary> skillSummaries,
                                     boolean parallelEnabled) {
        StringBuilder sb = new StringBuilder();

        appendSection(sb, "SOUL", agentConfig.getSoulMd());
        appendSection(sb, "USER", agentConfig.getUserMd());
        appendSection(sb, "AGENTS", agentConfig.getAgentsMd());

        if (skillSummaries != null && !skillSummaries.isEmpty()) {
            String skillsSection = buildSkillsDiscovery(skillSummaries);
            if (skillsSection != null && !skillsSection.isBlank()) {
                appendSection(sb, "SKILLS", skillsSection);
            }
        }

        sb.append("\n\n## TOOL USAGE GUIDELINES\n");
        if (parallelEnabled) {
            sb.append("PARALLEL TOOL CALLING: When you need to gather information from multiple independent sources ");
            sb.append("(e.g., reading several files, searching multiple terms, executing unrelated commands), ");
            sb.append("call ALL relevant tools simultaneously in a single response rather than one at a time. ");
            sb.append("Only use sequential calls when one tool's output is needed as input for another.\n\n");
        }
        sb.append("Avoid calling the same tool with identical arguments repeatedly. ");
        sb.append("If a tool returns unexpected results, try a different approach ");
        sb.append("or modify the arguments instead of retrying with the same parameters.\n");
        sb.append("When a tool fails, consider alternative approaches:\n");
        sb.append("- If readFile fails, try searchFiles to locate the file\n");
        sb.append("- If exec fails, check the error and adjust the command\n");

        String prompt = sb.toString();
        if (prompt.length() > TOTAL_MAX_CHARS) {
            prompt = prompt.substring(0, TOTAL_MAX_CHARS) + "\n...[truncated]";
        }
        return prompt;
    }

    private String buildSkillsDiscovery(List<SkillContentProvider.SkillSummary> skills) {
        if (skills.isEmpty()) return null;

        String basePath = skillContentProvider != null ? skillContentProvider.getSkillsBasePath() : "./skills";

        StringBuilder sb = new StringBuilder();
        sb.append("You have the following skills installed. ");
        sb.append("Each skill is a directory containing a SKILL.md with detailed instructions, ");
        sb.append("and optionally scripts/ and references/ directories.\n\n");
        sb.append("When a user's request matches a skill's description, ");
        sb.append("activate it by reading the skill's SKILL.md file first.\n\n");
        sb.append("Available skills:\n");

        for (var skill : skills) {
            sb.append("- **").append(skill.name()).append("**: ")
              .append(skill.description()).append('\n');
            sb.append("  Read: ").append(basePath).append('/').append(skill.name())
              .append("/SKILL.md\n");
        }

        return sb.toString();
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
