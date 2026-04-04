package com.atm.javaclaw.agent.runtime;

import com.atm.javaclaw.agent.model.ChatModelRegistry;
import com.atm.javaclaw.agent.model.ProviderType;
import com.atm.javaclaw.agent.model.ResolvedModel;
import com.atm.javaclaw.agent.plan.PlanOperations;
import com.atm.javaclaw.agent.skills.SkillContentProvider;
import com.atm.javaclaw.agent.skills.SkillUsageRecorder;
import com.atm.javaclaw.agent.tools.AgentSessionContext;
import com.atm.javaclaw.agent.tools.ToolsEngine;
import com.atm.javaclaw.core.config.JavaClawProperties;
import com.fasterxml.jackson.databind.JsonNode;
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
    private final AgentSessionContext agentSessionContext;
    private final JavaClawProperties properties;
    private final PlanOperations planOperations;

    public AgentRuntime(ChatModelRegistry chatModelRegistry,
                        ToolsEngine toolsEngine,
                        RunQueueManager runQueueManager,
                        ObjectMapper objectMapper,
                        @Autowired(required = false) SkillContentProvider skillContentProvider,
                        @Autowired(required = false) SkillUsageRecorder skillUsageRecorder,
                        AgentSessionContext agentSessionContext,
                        JavaClawProperties properties,
                        @Autowired(required = false) PlanOperations planOperations) {
        this.chatModelRegistry = chatModelRegistry;
        this.toolsEngine = toolsEngine;
        this.runQueueManager = runQueueManager;
        this.objectMapper = objectMapper;
        this.skillContentProvider = skillContentProvider;
        this.skillUsageRecorder = skillUsageRecorder;
        this.agentSessionContext = agentSessionContext;
        this.properties = properties;
        this.planOperations = planOperations;
    }

    public Flux<AgentEvent> dispatch(AgentRunRequest request) {
        return runQueueManager.enqueue(request.sessionId(), () -> executeAgentLoop(request));
    }

    private final ConcurrentMap<Long, ToolApprovalGate> sessionApprovalGates = new ConcurrentHashMap<>();
    private final Set<Long> pausedPlanIds = ConcurrentHashMap.newKeySet();

    public void signalPlanPaused(Long planId) {
        if (planId != null) {
            pausedPlanIds.add(planId);
            log.info("Plan {} signalled as paused/cancelled", planId);
        }
    }

    public void clearPlanPaused(Long planId) {
        if (planId != null) {
            pausedPlanIds.remove(planId);
        }
    }

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
            String systemPrompt = buildSystemPrompt(agentConfig, skillSummaries, parallelEnabled,
                    request.planContext(), request.forcePlan());
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
                    toolTimeout, maxToolResultChars, maxParallel, nonRetryable,
                    request.activePlanId())
                    .doFinally(signal -> {
                        sessionApprovalGates.remove(request.sessionId());
                        if (request.activePlanId() != null) {
                            pausedPlanIds.remove(request.activePlanId());
                        }
                    });
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
            Set<String> nonRetryableTools,
            Long activePlanId) {

        if (activePlanId != null && pausedPlanIds.contains(activePlanId)) {
            log.info("Plan {} is paused/cancelled, stopping agent loop at turn {}", activePlanId, turn);
            String text = fullText.toString();
            if (text.isEmpty()) {
                text = "[计划已暂停，当前步骤完成后停止执行]";
            }
            return Flux.just(new AgentEvent.Done(text, turn));
        }

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
                        toolTimeout, maxToolResultChars, maxParallel, nonRetryableTools,
                        activePlanId);
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

        if (allToolCalls.isEmpty()) {
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
            Set<String> nonRetryableTools,
            Long activePlanId) {

        AssistantMessage assistantMsg = toolCallResponse.getResult().getOutput();
        history.add(assistantMsg);

        List<AssistantMessage.ToolCall> toolCalls = assistantMsg.getToolCalls();
        if (toolCalls.isEmpty()) {
            return Flux.just(new AgentEvent.Done(fullText.toString(), turn));
        }

        Map<String, String> toolCallArgs = new LinkedHashMap<>();
        for (AssistantMessage.ToolCall tc : toolCalls) {
            toolCallArgs.put(tc.id(), tc.arguments());
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
                        loopDetector, cache,
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
                            afterApproval.flatMapMany(r -> {
                                AgentEvent toolResult = new AgentEvent.ToolResult(r.id(), r.name(), r.result(), r.success(), turn);
                                if (r.success()) {
                                    List<AgentEvent> planEvents = extractPlanEvents(r.name(), toolCallArgs.get(tc.id()), r.result());
                                    if (!planEvents.isEmpty()) {
                                        return Flux.concat(Flux.just(toolResult), Flux.fromIterable(planEvents));
                                    }
                                }
                                return Flux.just(toolResult);
                            })
                    );
                });

        // Phase 3: Combine results, add to history, emit ToolResult events for direct calls
        Flux<AgentEvent> directResultEvents = directResultsMono.flatMapMany(results -> {
            return Flux.fromIterable(results)
                    .concatMap(r -> {
                        AgentEvent toolResult = new AgentEvent.ToolResult(r.id(), r.name(), r.result(), r.success(), turn);
                        if (r.success()) {
                            log.debug("Phase3: checking plan events for tool={}, id={}, hasArgs={}",
                                    r.name(), r.id(), toolCallArgs.containsKey(r.id()));
                            List<AgentEvent> planEvents = extractPlanEvents(r.name(), toolCallArgs.get(r.id()), r.result());
                            if (!planEvents.isEmpty()) {
                                log.info("Phase3: injecting {} plan events after tool={}", planEvents.size(), r.name());
                                return Flux.concat(Flux.just(toolResult), Flux.fromIterable(planEvents));
                            }
                        }
                        return Flux.just(toolResult);
                    });
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
                        toolTimeout, maxToolResultChars, maxParallel, nonRetryableTools,
                        activePlanId));

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
            agentSessionContext.set(sessionId);
            try {
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
            } finally {
                agentSessionContext.clear();
            }
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
                                     boolean parallelEnabled,
                                     String planContext,
                                     boolean forcePlan) {
        StringBuilder sb = new StringBuilder();

        appendSection(sb, "SOUL:指定以何种语气去回答用户的问题，回答问题的风格以这个描述为主。","{"+agentConfig.getSoulMd()+"}");
        appendSection(sb, "USER:你需要面对的客户，针对不同的客户的要求，产出不同的结果", "{"+agentConfig.getUserMd()+"}");
        appendSection(sb, "AGENTS", agentConfig.getAgentsMd());

        if (skillSummaries != null && !skillSummaries.isEmpty()) {
            String skillsSection = buildSkillsDiscovery(skillSummaries);
            if (skillsSection != null && !skillsSection.isBlank()) {
                appendSection(sb, "SKILLS", skillsSection);
            }
        }

        sb.append(buildPlanSystemSection(forcePlan));

        if (planContext != null && !planContext.isBlank()) {
            sb.append("\n\n").append(planContext);
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

    private String buildPlanSystemSection(boolean forcePlan) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n<plan_system>\n");
        sb.append("你拥有 `writePlan` 和 `updatePlan` 两个工具来管理任务计划。\n\n");
        sb.append("### 何时创建计划\n");
        sb.append("- 当任务涉及 3 个以上独立步骤时，先调用 `writePlan` 创建计划\n");
        sb.append("- **即使存在匹配的 Skill，如果任务本身涉及多个步骤（如项目搭建、架构重构、系统迁移等），仍应优先创建计划。** Skill 可以在计划的各个步骤中被引用和激活\n");
        sb.append("- 复杂任务的判断标准：需要创建/修改多个文件、涉及多个独立配置、需要按顺序完成多个阶段\n");
        sb.append("- 简单任务（1-2 步）不需要创建计划，直接执行即可\n");
        sb.append("- 用户显式要求时，必须创建计划\n\n");
        sb.append("### 创建计划的要求\n");
        sb.append("1. 每个步骤应该是独立、可验证的\n");
        sb.append("2. 步骤标题简洁明确，描述包含具体操作内容\n");
        sb.append("3. 步骤之间的依赖关系要在描述中说明\n");
        sb.append("4. 合理评估步骤数量，避免过于细碎或笼统\n\n");
        sb.append("### 执行计划\n");
        sb.append("- 创建计划后等待用户审批，用户可能会编辑步骤\n");
        sb.append("- 审批通过后，按步骤顺序执行\n");
        sb.append("- 每完成一步，调用 `updatePlan` 的 `markStep` 标记完成\n");
        sb.append("- 如果发现需要调整计划，使用 `updatePlan` 的 `addStep` / `removeStep`\n");
        sb.append("- 如果发现后续步骤已不再必要，调用 `updatePlan` 的 `completePlan`，不要执行多余步骤\n\n");
        sb.append("### 失败处理\n");
        sb.append("- 步骤失败时先自行尝试不同方法解决\n");
        sb.append("- 如果确实无法完成，调用 `updatePlan` 的 `markStep` 标记 failed，并说明原因\n");
        sb.append("- 可以通过 `addStep` 新增替代步骤，或 `removeStep` 删除不可行的步骤\n");
        sb.append("</plan_system>");
        if (forcePlan) {
            sb.append("\n\n**重要指令：你必须先调用 `writePlan` 创建计划，等待用户审批后再执行。不要直接开始执行任务。**");
        }
        return sb.toString();
    }

    private String buildSkillsDiscovery(List<SkillContentProvider.SkillSummary> skills) {
        if (skills.isEmpty()) return null;

        String basePath = skillContentProvider != null ? skillContentProvider.getSkillsBasePath() : "./skills";

        StringBuilder sb = new StringBuilder();
        sb.append("You have the following skills installed. ");
        sb.append("Each skill is a directory containing a SKILL.md with detailed instructions, ");
        sb.append("and optionally scripts/ and references/ directories.\n\n");
        sb.append("When a user's request matches a skill's description, ");
        sb.append("consider activating it by reading the skill's SKILL.md file. ");
        sb.append("For complex multi-step tasks, create a plan first and use skills within individual steps.\n\n");
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

    /**
     * After a writePlan/updatePlan tool execution succeeds, parse the JSON result
     * and arguments to produce the corresponding Plan* AgentEvents that the
     * frontend needs for real-time UI updates.
     */
    private List<AgentEvent> extractPlanEvents(String toolName, String arguments, String result) {
        if (!"writePlan".equals(toolName) && !"updatePlan".equals(toolName)) {
            return List.of();
        }
        if (arguments == null || result == null) {
            log.warn("extractPlanEvents: null arguments or result for tool={}", toolName);
            return List.of();
        }
        log.debug("extractPlanEvents: tool={}, resultLen={}, argsLen={}, result={}",
                toolName, result.length(), arguments.length(),
                result.substring(0, Math.min(result.length(), 500)));
        try {
            JsonNode resultNode = objectMapper.readTree(result);
            if (resultNode.has("error")) {
                log.debug("extractPlanEvents: result contains 'error' key, skipping");
                return List.of();
            }

            List<AgentEvent> events;
            if ("writePlan".equals(toolName)) {
                events = extractWritePlanEvents(arguments, resultNode);
            } else {
                events = extractUpdatePlanEvents(arguments, resultNode);
            }
            log.info("extractPlanEvents: emitting {} plan event(s) for {}", events.size(), toolName);
            return events;
        } catch (Exception e) {
            log.warn("Failed to extract plan events from {} result: {}", toolName, e.getMessage(), e);
            return List.of();
        }
    }

    private List<AgentEvent> extractWritePlanEvents(String arguments, JsonNode resultNode) throws Exception {
        log.debug("extractWritePlanEvents: resultNode={}", resultNode);
        JsonNode planIdNode = resultNode.get("planId");
        if (planIdNode == null) planIdNode = resultNode.get("plan_id");
        if (planIdNode == null) planIdNode = resultNode.get("id");
        if (planIdNode == null || planIdNode.isNull()) {
            log.warn("extractWritePlanEvents: no planId found in result: {}", resultNode);
            return List.of();
        }
        Long planId = planIdNode.asLong();

        JsonNode argsNode = objectMapper.readTree(arguments);
        String title = argsNode.has("title") ? argsNode.get("title").asText() : "";
        JsonNode stepsArray = argsNode.has("steps") ? argsNode.get("steps") : objectMapper.createArrayNode();
        log.debug("extractWritePlanEvents: title={}, steps count={}", title, stepsArray.size());

        List<AgentEvent.PlanStepInfo> steps = new ArrayList<>();
        for (int i = 0; i < stepsArray.size(); i++) {
            JsonNode step = stepsArray.get(i);
            steps.add(new AgentEvent.PlanStepInfo(
                    i,
                    step.has("title") ? step.get("title").asText() : "",
                    step.has("description") ? step.get("description").asText() : ""
            ));
        }
        log.info("extractWritePlanEvents: PlanCreated planId={}, title='{}', steps={}", planId, title, steps.size());

        return List.of(
                new AgentEvent.PlanCreated(planId, title, steps),
                new AgentEvent.PlanAwaitingApproval(planId)
        );
    }

    private List<AgentEvent> extractUpdatePlanEvents(String arguments, JsonNode resultNode) throws Exception {
        JsonNode argsNode = objectMapper.readTree(arguments);
        JsonNode planIdNode = argsNode.get("planId");
        if (planIdNode == null) planIdNode = argsNode.get("plan_id");
        JsonNode actionNode = argsNode.get("action");
        if (planIdNode == null || planIdNode.isNull() || actionNode == null || actionNode.isNull()) {
            log.warn("extractUpdatePlanEvents: missing planId or action in args: {}",
                    arguments.substring(0, Math.min(arguments.length(), 500)));
            return List.of();
        }
        Long planId = planIdNode.asLong();
        String action = actionNode.asText();
        log.debug("extractUpdatePlanEvents: planId={}, action={}", planId, action);

        return switch (action) {
            case "markStep" -> {
                JsonNode stepIndexNode = argsNode.get("stepIndex");
                JsonNode statusNode = argsNode.get("status");
                if (stepIndexNode == null || statusNode == null) {
                    log.warn("extractUpdatePlanEvents: markStep missing stepIndex or status");
                    yield List.of();
                }
                int stepIndex = stepIndexNode.asInt();
                String status = statusNode.asText();
                if ("in_progress".equals(status)) {
                    yield java.util.List.<AgentEvent>of(
                            new AgentEvent.PlanStatusChanged(planId, "executing"),
                            new AgentEvent.PlanStepStart(planId, stepIndex, ""));
                } else {
                    String summary = "";
                    if (argsNode.has("resultSummary") && !argsNode.get("resultSummary").isNull()) {
                        summary = argsNode.get("resultSummary").asText();
                    }
                    yield List.of((AgentEvent) new AgentEvent.PlanStepDone(planId, stepIndex, status, summary));
                }
            }
            case "completePlan" -> List.of((AgentEvent) new AgentEvent.PlanCompleted(planId, "completed"));
            case "addStep", "removeStep" -> {
                List<AgentEvent.PlanStepInfo> currentSteps = List.of();
                if (planOperations != null) {
                    try {
                        currentSteps = planOperations.getSteps(planId).stream()
                                .map(s -> new AgentEvent.PlanStepInfo(s.index(), s.title(), s.description()))
                                .toList();
                    } catch (Exception e) {
                        log.warn("Failed to load steps for PlanAdjusted event: {}", e.getMessage());
                    }
                }
                yield List.of((AgentEvent) new AgentEvent.PlanAdjusted(planId, action, currentSteps));
            }
            default -> List.of();
        };
    }
}
