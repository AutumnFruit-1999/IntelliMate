package com.atm.javaclaw.agent.runtime;

import com.atm.javaclaw.agent.model.ChatModelRegistry;
import com.atm.javaclaw.agent.model.ProviderType;
import com.atm.javaclaw.agent.model.ResolvedModel;
import com.atm.javaclaw.agent.plan.PlanOperations;
import com.atm.javaclaw.agent.skills.SkillContentProvider;
import com.atm.javaclaw.agent.skills.SkillUsageRecorder;
import com.atm.javaclaw.agent.tools.AgentSessionContext;
import com.atm.javaclaw.agent.tools.ToolsEngine;
import com.atm.javaclaw.agent.tools.WritePlanTool;
import com.atm.javaclaw.core.config.JavaClawProperties;
import com.atm.javaclaw.core.prompt.PromptLoader;
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

            PlanStepTracker planStepTracker = request.activePlanId() != null && planOperations != null
                    ? new PlanStepTracker(request.activePlanId(), planOperations) : null;

            return executeLoopTurn(resolved.chatModel(), conversationHistory, chatOptions, maxTurns, timeout, 1, new StringBuilder(),
                    agentConfig.getName(), request.sessionId(), skillsBasePath,
                    loopDetector, tracker, condenser, cache, approvalGate,
                    toolTimeout, maxToolResultChars, maxParallel, nonRetryable,
                    request.activePlanId(), planStepTracker)
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
            Long activePlanId,
            PlanStepTracker planStepTracker) {

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
                text = "[Agent 已达最大轮次 (" + maxTurns + ")]";
            }
            return Flux.just(new AgentEvent.Done(text, turn - 1));
        }

        if (tracker.isOverLimit()) {
            log.error("Context window exceeded: ~{} / {} tokens, forcing completion",
                    tracker.estimatedTotalTokens(), tracker.getMaxContextTokens());
            String text = fullText.toString();
            if (text.isEmpty()) {
                text = "[上下文窗口已超限，强制结束]";
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
                    text = "[压缩后上下文仍超限，强制结束]";
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
                        activePlanId, planStepTracker);
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
            Long activePlanId,
            PlanStepTracker planStepTracker) {

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

        // Phase 0: Auto-start plan step if needed (fallback when LLM skips updatePlan).
        // Deferred to boundedElastic because ensureStepActive uses PlanOperationsImpl
        // which calls .block() — not allowed on reactor-http-nio threads.
        Flux<AgentEvent> autoStartFlux = planStepTracker != null
                ? Flux.defer(() -> Flux.fromIterable(planStepTracker.ensureStepActive(toolCalls)))
                      .subscribeOn(Schedulers.boundedElastic())
                : Flux.empty();

        // Phase 1: Emit all ToolCall events (with turn for frontend grouping)
        Flux<AgentEvent> callEvents = Flux.fromIterable(toolCalls)
                .map(tc -> {
                    String desc = toolsEngine.getToolDescription(tc.name());
                    return (AgentEvent) new AgentEvent.ToolCall(tc.id(), tc.name(), desc, tc.arguments(), turn);
                });

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
                                        if (planStepTracker != null) {
                                            planEvents = filterDuplicatePlanEvents(planEvents, planStepTracker);
                                        }
                                        if (!planEvents.isEmpty()) {
                                            return Flux.concat(Flux.just(toolResult), Flux.fromIterable(planEvents));
                                        }
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
                                if (planStepTracker != null) {
                                    planEvents = filterDuplicatePlanEvents(planEvents, planStepTracker);
                                }
                                if (!planEvents.isEmpty()) {
                                    log.info("Phase3: injecting {} plan events after tool={}", planEvents.size(), r.name());
                                    return Flux.concat(Flux.just(toolResult), Flux.fromIterable(planEvents));
                                }
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
                        activePlanId, planStepTracker));

        return Flux.concat(autoStartFlux, callEvents, withHistory, nextTurn);
    }

    /**
     * Executes a single tool call with the full middleware chain:
     * loop detection -> approval gate -> cache check -> actual execution (with retry + timeout) -> truncation.
     * <p>
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
            String terminateMsg = "检测到循环调用：你已多次使用相同参数调用 " + tc.name() + "，已拦截执行。请利用已有信息或尝试其他方法。";
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
            String result = cached + "\n[缓存结果]";
            result = truncateToolResult(result, maxToolResultChars);
            if (appendWarning) {
                result += "\n\n[警告：你已多次使用相同参数调用此工具，请尝试其他方法。]";
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
                            result += "\n\n[警告：你已多次使用相同参数调用此工具，请尝试其他方法。]";
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
                + "\n\n... [已截断：显示前后各 " + half
                + " 字符，共 " + result.length() + " 字符] ...\n\n"
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

        appendSection(sb, "soul", agentConfig.getSoulMd());
        appendSection(sb, "user_context", agentConfig.getUserMd());
        appendSection(sb, "agents", agentConfig.getAgentsMd());

        if (skillSummaries != null && !skillSummaries.isEmpty()) {
            String skillsSection = buildSkillsDiscovery(skillSummaries);
            if (skillsSection != null && !skillsSection.isBlank()) {
                appendSection(sb, "skills", skillsSection);
            }
        }

        appendSection(sb, "plan_system", buildPlanSystemSection(forcePlan));

        if (planContext != null && !planContext.isBlank()) {
            appendSection(sb, "plan_execution", planContext);
        }

        String parallelSection = parallelEnabled
                ? "\n\n### 并行与串行调用\n\n"
                + "当多个工具调用彼此独立、无数据依赖时，**必须**在同一轮回复中同时调用。\n"
                + "适用场景：读取多个文件、搜索多个关键词、执行多个不相关的命令。\n\n"
                + "仅当某个工具的输出必须作为另一个工具的输入时，才采用顺序调用。\n"
                + "禁止并行的场景：文件写入后需要验证结果、有明确的先后依赖关系。\n"
                : "";
        appendSection(sb, "tool_guidelines", PromptLoader.format("prompts/tool-usage-guidelines.md", parallelSection));

        String prompt = sb.toString();
        if (prompt.length() > TOTAL_MAX_CHARS) {
            prompt = prompt.substring(0, TOTAL_MAX_CHARS) + "\n...[truncated]";
        }
        return prompt;
    }

    private String buildPlanSystemSection(boolean forcePlan) {
        StringBuilder sb = new StringBuilder();
        sb.append(PromptLoader.load("prompts/plan-system.md"));
        if (forcePlan) {
            sb.append("\n\n**重要指令：你必须先调用 `writePlan` 创建计划，等待用户审批后再执行。在审批通过之前，不要调用任何其他工具或直接开始执行任务。**");
        }
        return sb.toString();
    }

    private String buildSkillsDiscovery(List<SkillContentProvider.SkillSummary> skills) {
        if (skills.isEmpty()) return null;

        String basePath = skillContentProvider != null ? skillContentProvider.getSkillsBasePath() : "./skills";

        StringBuilder sb = new StringBuilder();
        sb.append(PromptLoader.load("prompts/skills-discovery.md"));

        for (var skill : skills) {
            sb.append("- **").append(skill.name()).append("**: ")
                    .append(skill.description()).append('\n');
            sb.append("  Read: ").append(basePath).append('/').append(skill.name())
                    .append("/SKILL.md\n");
        }

        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String tag, String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        sb.append('<').append(tag).append(">\n");
        sb.append(content.strip());
        sb.append("\n</").append(tag).append('>');
    }

    // ─── Plan step auto-tracking ───

    /**
     * Tracks the active plan step during execution. When the LLM calls non-plan
     * tools without first marking a step as in_progress via updatePlan, this
     * tracker auto-starts the next pending step so the frontend receives proper
     * step lifecycle events regardless of LLM behavior.
     */
    static class PlanStepTracker {
        private static final Logger tlog = LoggerFactory.getLogger(PlanStepTracker.class);

        private final Long planId;
        private final PlanOperations ops;
        private Integer activeStepIndex;
        private final Set<Integer> autoStartedSteps = new HashSet<>();

        PlanStepTracker(Long planId, PlanOperations ops) {
            this.planId = planId;
            this.ops = ops;
        }

        /**
         * If no step is currently active and the turn contains non-plan tool calls
         * (without the LLM also calling updatePlan markStep in_progress), auto-start
         * the next pending step and return the events to emit ahead of ToolCall events.
         */
        List<AgentEvent> ensureStepActive(List<AssistantMessage.ToolCall> toolCalls) {
            if (activeStepIndex != null) return List.of();

            boolean hasNonPlanTools = toolCalls.stream()
                    .anyMatch(tc -> !"writePlan".equals(tc.name()) && !"updatePlan".equals(tc.name()));
            if (!hasNonPlanTools) return List.of();

            boolean llmStartingStep = toolCalls.stream()
                    .anyMatch(tc -> "updatePlan".equals(tc.name())
                            && tc.arguments() != null
                            && tc.arguments().contains("\"in_progress\""));
            if (llmStartingStep) return List.of();

            try {
                List<PlanOperations.StepSnapshot> steps = ops.getSteps(planId);
                PlanOperations.StepSnapshot next = steps.stream()
                        .filter(s -> "pending".equals(s.status()))
                        .findFirst().orElse(null);
                if (next == null) return List.of();

                int idx = next.index();
                PlanOperations.StepResult markResult = ops.markStep(planId, idx, "in_progress", null);
                if ("error".equals(markResult.status())) {
                    tlog.warn("Failed to auto-start step {} for plan {}: {}",
                            idx, planId, markResult.message());
                    return List.of();
                }
                activeStepIndex = idx;
                autoStartedSteps.add(idx);
                tlog.info("Auto-started step {} for plan {}", idx, planId);

                return List.of(
                        new AgentEvent.PlanStatusChanged(planId, "executing"),
                        new AgentEvent.PlanStepStart(planId, idx, next.title()));
            } catch (Exception e) {
                tlog.warn("Failed to auto-start step for plan {}: {}", planId, e.getMessage());
                return List.of();
            }
        }

        boolean isAutoStartedStep(int stepIndex) {
            return autoStartedSteps.contains(stepIndex);
        }

        void onStepStart(int stepIndex) {
            activeStepIndex = stepIndex;
        }

        void onStepDone(int stepIndex) {
            if (activeStepIndex != null && activeStepIndex == stepIndex) {
                activeStepIndex = null;
            }
            autoStartedSteps.remove(stepIndex);
        }

        void onPlanCompleted() {
            activeStepIndex = null;
        }
    }

    /**
     * Filters plan events that were already emitted by the auto-tracker to avoid
     * duplicates when the LLM also calls updatePlan(markStep, in_progress).
     */
    private static List<AgentEvent> filterDuplicatePlanEvents(List<AgentEvent> events, PlanStepTracker tracker) {
        List<AgentEvent> filtered = new ArrayList<>(events.size());
        for (AgentEvent evt : events) {
            if (evt instanceof AgentEvent.PlanStepStart pss) {
                if (tracker.isAutoStartedStep(pss.stepIndex())) {
                    log.debug("Filtering duplicate PlanStepStart for auto-started step {}", pss.stepIndex());
                    continue;
                }
                tracker.onStepStart(pss.stepIndex());
            } else if (evt instanceof AgentEvent.PlanStepDone psd) {
                tracker.onStepDone(psd.stepIndex());
            } else if (evt instanceof AgentEvent.PlanCompleted) {
                tracker.onPlanCompleted();
            }
            filtered.add(evt);
        }
        return filtered;
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
            // Defense against double-encoded JSON (e.g. from @Tool methods whose
            // String return value gets re-serialized by Spring AI's MethodToolCallback).
            if (resultNode.isTextual()) {
                log.debug("extractPlanEvents: result is a JSON string (double-encoded), unwrapping");
                resultNode = objectMapper.readTree(resultNode.asText());
            }
            if (resultNode.has("error")) {
                log.debug("extractPlanEvents: result contains 'error' key, skipping");
                return List.of();
            }
            String resultStatus = resultNode.has("status") ? resultNode.get("status").asText() : "";
            if ("error".equals(resultStatus)) {
                log.warn("extractPlanEvents: tool {} returned error status: {}",
                        toolName, resultNode.has("message") ? resultNode.get("message").asText() : "unknown");
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

        String cleanArgs = WritePlanTool.extractJsonObjectPayload(arguments);
        JsonNode argsNode = WritePlanTool.parseJsonLenient(cleanArgs);
        String title = argsNode.has("title") ? argsNode.get("title").asText() : "";
        JsonNode stepsArray = argsNode.has("steps") ? argsNode.get("steps") : objectMapper.createArrayNode();
        log.debug("extractWritePlanEvents: title={}, steps count={}", title, stepsArray.size());

        List<AgentEvent.PlanStepInfo> steps = new ArrayList<>();
        for (int i = 0; i < stepsArray.size(); i++) {
            JsonNode step = stepsArray.get(i);
            steps.add(new AgentEvent.PlanStepInfo(
                    i + 1,
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
        String cleanArgs = WritePlanTool.extractJsonObjectPayload(arguments);
        JsonNode argsNode = WritePlanTool.parseJsonLenient(cleanArgs);
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
