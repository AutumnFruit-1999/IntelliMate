package com.atm.intellimate.agent.runtime;

import com.atm.intellimate.agent.model.ChatModelRegistry;
import com.atm.intellimate.agent.model.ProviderType;
import com.atm.intellimate.agent.model.ResolvedModel;
import com.atm.intellimate.agent.plan.PlanOperations;
import com.atm.intellimate.agent.skills.SkillContentProvider;
import com.atm.intellimate.agent.skills.SkillGroupContext;
import com.atm.intellimate.agent.tools.ToolsEngine;
import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.memory.MemorySystem;
import com.atm.intellimate.memory.config.ResolvedMemoryConfig;
import com.atm.intellimate.memory.consolidation.MemoryConsolidator;
import com.atm.intellimate.memory.longterm.LongTermMemory;
import com.atm.intellimate.memory.model.ContentCategory;
import com.atm.intellimate.memory.model.MemoryChunk;
import com.atm.intellimate.memory.perception.ImportanceAssessor;
import com.atm.intellimate.memory.retrieval.MemoryRetrieval;
import com.atm.intellimate.memory.working.TokenEstimator;
import com.atm.intellimate.memory.working.WorkingMemory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class AgentLoopExecutor {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopExecutor.class);

    private static final Set<String> DELEGATION_TOOL_NAMES = Set.of(
            "delegateAgent", "handoffToAgent", "delegateAgentsParallel");

    private final ChatModelRegistry chatModelRegistry;
    private final ToolsEngine toolsEngine;
    private final ObjectMapper objectMapper;
    private final SkillContentProvider skillContentProvider;
    private final PlanOperations planOperations;
    private final LongTermMemory longTermMemory;
    private final MemorySystem memorySystem;
    private final AgentPromptBuilder agentPromptBuilder;
    private final AgentMemoryLifecycle agentMemoryLifecycle;
    private final PlanEventExtractor planEventExtractor;
    private final ToolExecutionPipeline toolExecutionPipeline;
    private final DelegationExecutor delegationExecutor;
    private final MeterRegistry meterRegistry;

    public AgentLoopExecutor(ChatModelRegistry chatModelRegistry,
                             ToolsEngine toolsEngine,
                             ObjectMapper objectMapper,
                             @Autowired(required = false) SkillContentProvider skillContentProvider,
                             @Autowired(required = false) PlanOperations planOperations,
                             @Autowired(required = false) LongTermMemory longTermMemory,
                             @Autowired(required = false) MemorySystem memorySystem,
                             AgentPromptBuilder agentPromptBuilder,
                             AgentMemoryLifecycle agentMemoryLifecycle,
                             PlanEventExtractor planEventExtractor,
                             ToolExecutionPipeline toolExecutionPipeline,
                             DelegationExecutor delegationExecutor,
                             @Autowired(required = false) MeterRegistry meterRegistry) {
        this.chatModelRegistry = chatModelRegistry;
        this.toolsEngine = toolsEngine;
        this.objectMapper = objectMapper;
        this.skillContentProvider = skillContentProvider;
        this.planOperations = planOperations;
        this.longTermMemory = longTermMemory;
        this.memorySystem = memorySystem;
        this.agentPromptBuilder = agentPromptBuilder;
        this.agentMemoryLifecycle = agentMemoryLifecycle;
        this.planEventExtractor = planEventExtractor;
        this.toolExecutionPipeline = toolExecutionPipeline;
        this.delegationExecutor = delegationExecutor;
        this.meterRegistry = meterRegistry;
    }

    public Flux<AgentEvent> executeAgentLoop(AgentRunRequest request, AgentLoopCallbacks callbacks) {
        Mono<List<SkillContentProvider.SkillSummary>> skillsMono;
        if (skillContentProvider != null && request.skillsEnabled() != null && !request.skillsEnabled().isBlank()) {
            skillsMono = skillContentProvider.resolveSkillSummaries(request.skillsEnabled());
        } else {
            skillsMono = Mono.just(List.of());
        }

        agentPromptBuilder.setupSkillGroupContext(request.sessionId(), request.skillGroupsEnabled());

        return skillsMono.publishOn(Schedulers.boundedElastic()).flatMapMany(skillSummaries -> {
            IntelliMateProperties.Agent agentConfig = request.agent();
            boolean parallelEnabled = agentConfig.isEnableParallelToolCalls();
            String systemPrompt = agentPromptBuilder.buildSystemPrompt(agentConfig, skillSummaries, parallelEnabled,
                    request.planContext(), request.forcePlan(), request.skillGroupsEnabled());
            ToolCallback[] allTools = toolsEngine.getToolCallbacksFor(
                    request.toolsEnabled(), request.mcpToolsEnabled(), request.bridgeNode());
            ToolCallback[] tools = agentConfig.isCanDelegate()
                    ? allTools
                    : Arrays.stream(allTools)
                    .filter(cb -> !DELEGATION_TOOL_NAMES.contains(cb.getToolDefinition().name()))
                    .toArray(ToolCallback[]::new);
            Map<String, ToolCallback> toolCallbackMap = new LinkedHashMap<>();
            for (ToolCallback cb : tools) {
                toolCallbackMap.put(cb.getToolDefinition().name(), cb);
            }
            int maxTurns = agentConfig.getMaxTurns();
            Duration timeout = Duration.ofSeconds(agentConfig.getTimeoutSeconds());

            ResolvedModel resolved = resolveModel(agentConfig.getModel());
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
            boolean lastIsCurrentUser = !conversationHistory.isEmpty()
                    && conversationHistory.getLast() instanceof UserMessage lastMsg
                    && request.userMessage().equals(lastMsg.getText());
            if (!lastIsCurrentUser) {
                conversationHistory.add(new UserMessage(request.userMessage()));
            }

            ChatOptions chatOptions = buildChatOptions(tools, resolved, parallelEnabled);

            logRequestParams(request, systemPrompt, tools);

            ToolCallLoopDetector loopDetector = new ToolCallLoopDetector(
                    agentConfig.getLoopDetectorWindowSize(),
                    agentConfig.getLoopDetectorWarnThreshold(),
                    agentConfig.getLoopDetectorTerminateThreshold(),
                    new HashSet<>(agentConfig.getLoopDetectorExcludedTools()));

            TokenEstimator tokenEstimator = memorySystem != null
                    ? memorySystem.getTokenEstimator() : new TokenEstimator();
            MemoryChunk systemChunk = MemoryChunk.system(systemPrompt, tokenEstimator.estimateForMessage(systemPrompt));

            String agentId = agentConfig.getName() != null && !agentConfig.getName().isBlank()
                    ? agentConfig.getName()
                    : "default";

            ImportanceAssessor importanceAssessor = memorySystem != null
                    ? memorySystem.getImportanceAssessor() : new ImportanceAssessor();
            ToolResultCache cache = new ToolResultCache();

            Set<String> approvalTools = new HashSet<>(agentConfig.getApprovalRequiredTools());
            ToolApprovalGate approvalGate = new ToolApprovalGate(approvalTools);
            callbacks.sessionApprovalGates().put(request.sessionId(), approvalGate);

            Duration toolTimeout = Duration.ofSeconds(agentConfig.getToolExecutionTimeoutSeconds());
            int maxParallel = agentConfig.getMaxParallelToolCalls();
            Set<String> nonRetryable = new HashSet<>(agentConfig.getNonRetryableTools());

            PlanStepTracker planStepTracker = request.activePlanId() != null && planOperations != null
                    ? new PlanStepTracker(request.activePlanId(), planOperations) : null;

            return agentMemoryLifecycle.loadMemoryInitReactive(tokenEstimator)
                    .flatMapMany(memoryInit -> {
                        MemoryConsolidator consolidator = memoryInit.consolidator() != null
                                ? memoryInit.consolidator()
                                : (memorySystem != null ? memorySystem.getConsolidator() : null);
                        ResolvedMemoryConfig resolvedMem = memoryInit.resolved();

                        float consolidationThreshold = 0.75f;
                        float overflowTolerance = 1.1f;
                        int tokenBudget = agentConfig.getMaxContextTokens();
                        if (resolvedMem != null) {
                            tokenBudget = resolvedMem.tokenBudget();
                            consolidationThreshold = resolvedMem.consolidationThreshold();
                            overflowTolerance = resolvedMem.overflowTolerance();
                        }

                        WorkingMemory workingMemory = new WorkingMemory(
                                tokenBudget,
                                consolidationThreshold,
                                overflowTolerance,
                                consolidator,
                                systemChunk,
                                agentId
                        );
                        workingMemory.setUserId(request.userId());
                        if (request.planExecutionAssessment() != null
                                && request.planExecutionAssessment().currentStepDescription() != null) {
                            workingMemory.setTaskContext(request.planExecutionAssessment().currentStepDescription());
                        } else if (request.userMessage() != null) {
                            workingMemory.setTaskContext(request.userMessage());
                        }
                        for (Message msg : conversationHistory) {
                            if (msg.getText() != null && !(msg instanceof SystemMessage)) {
                                int tokens = tokenEstimator.estimateForMessage(msg.getText());
                                MemoryChunk chunk = msg instanceof UserMessage
                                        ? MemoryChunk.user(msg.getText(), tokens)
                                        : MemoryChunk.assistant(msg.getText(), tokens);
                                workingMemory.accept(chunk);
                            }
                        }

                        boolean ltEnabled = resolvedMem != null && resolvedMem.longTermEnabled();
                        String sessionUserId = request.userId() != null && !request.userId().isBlank()
                                ? request.userId() : "default";

                        Mono<Void> retrievalMono;
                        if (longTermMemory != null && ltEnabled) {
                            MemoryRetrieval retrieval = memorySystem != null
                                    ? memorySystem.getMemoryRetrieval()
                                    : new MemoryRetrieval(longTermMemory, tokenEstimator);
                            int maxInjectionTokens = resolvedMem.maxInjectionTokens();
                            double lambda = resolvedMem.decayLambda();

                            String cue = request.userMessage() != null ? request.userMessage() : "";
                            if (request.planExecutionAssessment() != null
                                    && request.planExecutionAssessment().currentStepDescription() != null) {
                                cue = cue + " " + request.planExecutionAssessment().currentStepDescription();
                            }

                            long retrievalStart = System.currentTimeMillis();
                            retrievalMono = retrieval.retrieve(cue, sessionUserId, agentId, maxInjectionTokens, lambda)
                                    .timeout(Duration.ofSeconds(3))
                                    .doOnNext(recalledChunks -> {
                                        agentMemoryLifecycle.recordLongTermRetrievalLatency(
                                                agentId, System.currentTimeMillis() - retrievalStart);
                                        if (!recalledChunks.isEmpty()) {
                                            for (MemoryChunk chunk : recalledChunks) {
                                                String prefixed = "[历史记忆] " + chunk.content();
                                                MemoryChunk recalledWithPrefix = MemoryChunk.recalled(
                                                        prefixed, chunk.estimatedTokens(), chunk.importance());
                                                workingMemory.accept(recalledWithPrefix);
                                            }
                                            int injectedTokens = recalledChunks.stream()
                                                    .mapToInt(MemoryChunk::estimatedTokens)
                                                    .sum();
                                            log.info("Injected {} recalled memories ({} tokens) for agent '{}' session {}",
                                                    recalledChunks.size(), injectedTokens, agentId, request.sessionId());
                                        }
                                    })
                                    .onErrorResume(e -> {
                                        log.warn("Failed to retrieve long-term memories, continuing without: {}", e.getMessage());
                                        return Mono.empty();
                                    })
                                    .then();
                        } else {
                            retrievalMono = Mono.empty();
                        }

                        return retrievalMono
                                .then(workingMemory.awaitPendingConsolidation()
                                        .timeout(Duration.ofSeconds(5))
                                        .onErrorResume(e -> {
                                            log.warn("Pre-loop consolidation await failed, continuing: {}", e.getMessage());
                                            return Mono.empty();
                                        }))
                                .thenMany(executeLoopTurn(resolved.chatModel(), conversationHistory, chatOptions, maxTurns, timeout, 1, new StringBuilder(),
                                        agentConfig.getName(), request.sessionId(), skillsBasePath,
                                        loopDetector, workingMemory, importanceAssessor, tokenEstimator, cache, approvalGate,
                                        toolTimeout, maxParallel, nonRetryable,
                                        request.activePlanId(), planStepTracker, request.planExecutionAssessment(), request, toolCallbackMap,
                                        callbacks)
                                        .doFinally(signal -> {
                                            callbacks.sessionApprovalGates().remove(request.sessionId());
                                            agentPromptBuilder.removeSessionSkillGroups(request.sessionId());
                                            SkillGroupContext.clear();
                                            callbacks.latestSnapshots().remove(request.sessionId());
                                            if (request.activePlanId() != null) {
                                                callbacks.pausedPlanIds().remove(request.activePlanId());
                                            }
                                            if (longTermMemory != null && ltEnabled && request.activePlanId() == null) {
                                                int minChunks = resolvedMem != null ? resolvedMem.minChunksForEpisodic() : 4;
                                                agentMemoryLifecycle.deferEpisodicStore(workingMemory, longTermMemory, sessionUserId, agentId, request.sessionId(), minChunks);
                                            }
                                        }));
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
            WorkingMemory workingMemory,
            ImportanceAssessor importanceAssessor,
            TokenEstimator tokenEstimator,
            ToolResultCache cache,
            ToolApprovalGate approvalGate,
            Duration toolTimeout,
            int maxParallel,
            Set<String> nonRetryableTools,
            Long activePlanId,
            PlanStepTracker planStepTracker,
            PlanExecutionAssessment planExecutionAssessment,
            AgentRunRequest request,
            Map<String, ToolCallback> toolCallbackMap,
            AgentLoopCallbacks callbacks) {

        if (activePlanId != null && callbacks.pausedPlanIds().contains(activePlanId)) {
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

        if (workingMemory.usageRatio() > workingMemory.getOverflowTolerance()) {
            log.error("Context window exceeded: ~{} / {} tokens (ratio={}), forcing completion",
                    workingMemory.getTokenUsage(), workingMemory.getTokenBudget(), workingMemory.usageRatio());
            String text = fullText.toString();
            if (text.isEmpty()) {
                text = "[上下文窗口已超限，强制结束]";
            }
            return Flux.just(new AgentEvent.Done(text, turn));
        }

        log.debug("Agent loop turn={}/{}", turn, maxTurns);

        history.clear();
        history.addAll(workingMemory.buildLLMInputSync());

        Prompt prompt = new Prompt(new ArrayList<>(history), options);
        List<ChatResponse> allChunks = new ArrayList<>();
        String modelId = extractModelId(options);
        Timer.Sample llmSample = meterRegistry != null ? Timer.start(meterRegistry) : null;

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
            if (meterRegistry != null && llmSample != null) {
                llmSample.stop(Timer.builder("agent.llm.latency")
                        .tag("model", modelId)
                        .tag("agent", agentName)
                        .register(meterRegistry));
                meterRegistry.counter("agent.llm.requests",
                        "model", modelId,
                        "agent", agentName,
                        "status", "success").increment();
            }

            for (int i = allChunks.size() - 1; i >= 0; i--) {
                ChatResponse chunk = allChunks.get(i);
                if (chunk != null && chunk.getMetadata() != null) {
                    Usage usage = chunk.getMetadata().getUsage();
                    if (usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
                        workingMemory.setActualTokenUsage(usage.getPromptTokens().intValue());
                        log.debug("Actual prompt tokens from API: {}", usage.getPromptTokens());
                    }
                    if (meterRegistry != null && usage != null) {
                        if (usage.getPromptTokens() != null) {
                            meterRegistry.counter("agent.llm.tokens.prompt",
                                    "model", modelId, "agent", agentName)
                                    .increment(usage.getPromptTokens());
                        }
                        if (usage.getCompletionTokens() != null) {
                            meterRegistry.counter("agent.llm.tokens.completion",
                                    "model", modelId, "agent", agentName)
                                    .increment(usage.getCompletionTokens());
                        }
                    }
                    if (usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
                        break;
                    }
                }
            }

            ChatResponse merged = mergeToolCallsFromChunks(allChunks);

            if (merged != null) {
                return processToolCalls(chatModel, history, options, merged, maxTurns, timeout, turn, fullText,
                        agentName, sessionId, skillsBasePath,
                        loopDetector, workingMemory, importanceAssessor, tokenEstimator, cache, approvalGate,
                        toolTimeout, maxParallel, nonRetryableTools,
                        activePlanId, planStepTracker, planExecutionAssessment, request, toolCallbackMap, callbacks);
            }

            String finalText = fullText.toString();
            if (!finalText.isEmpty()) {
                history.add(new AssistantMessage(finalText));
                MemoryChunk assistantChunk = MemoryChunk.assistant(
                        finalText, tokenEstimator.estimateForMessage(finalText));
                workingMemory.accept(assistantChunk);
            }

            WorkingMemory.MemorySnapshot snap = workingMemory.getSnapshot();
            List<AgentEvent.ChunkInfo> chunkInfos = snap.chunks().stream()
                    .map(c -> new AgentEvent.ChunkInfo(c.id(), c.type(), c.category(),
                            c.importance(), c.tokens(), c.contentPreview(), c.createdAt()))
                    .toList();
            AgentEvent.MemorySnapshot memSnap = new AgentEvent.MemorySnapshot(
                    snap.tokenBudget(), snap.tokenUsed(), snap.tokenEstimated(), snap.usageRatio(),
                    snap.chunkCount(), chunkInfos);
            callbacks.latestSnapshots().put(sessionId, memSnap);

            return Flux.just(memSnap, new AgentEvent.Done(finalText, turn));
        });

        return Flux.concat(turnStart, streaming, afterStream);
    }

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
            WorkingMemory workingMemory,
            ImportanceAssessor importanceAssessor,
            TokenEstimator tokenEstimator,
            ToolResultCache cache,
            ToolApprovalGate approvalGate,
            Duration toolTimeout,
            int maxParallel,
            Set<String> nonRetryableTools,
            Long activePlanId,
            PlanStepTracker planStepTracker,
            PlanExecutionAssessment planExecutionAssessment,
            AgentRunRequest request,
            Map<String, ToolCallback> toolCallbackMap,
            AgentLoopCallbacks callbacks) {

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

        List<AssistantMessage.ToolCall> directCalls = new ArrayList<>();
        List<AssistantMessage.ToolCall> approvalCalls = new ArrayList<>();
        List<AssistantMessage.ToolCall> delegationCalls = new ArrayList<>();
        AssistantMessage.ToolCall handoffCall = null;
        for (AssistantMessage.ToolCall tc : toolCalls) {
            if (DELEGATION_TOOL_NAMES.contains(tc.name())) {
                if ("handoffToAgent".equals(tc.name())) {
                    handoffCall = tc;
                } else {
                    delegationCalls.add(tc);
                }
            } else if (approvalGate.requiresApproval(tc.name())) {
                approvalCalls.add(tc);
            } else {
                directCalls.add(tc);
            }
        }

        Flux<AgentEvent> autoStartFlux = planStepTracker != null
                ? Flux.defer(() -> Flux.fromIterable(planStepTracker.ensureStepActive(toolCalls)))
                      .subscribeOn(Schedulers.boundedElastic())
                : Flux.empty();

        Flux<AgentEvent> callEvents = Flux.fromIterable(toolCalls)
                .map(tc -> {
                    String desc = toolsEngine.getToolDescription(tc.name());
                    return (AgentEvent) new AgentEvent.ToolCall(tc.id(), tc.name(), desc, tc.arguments(), turn);
                });

        Long agentDbId = request.agent() != null ? request.agent().getAgentDbId() : null;

        Mono<List<ToolExecutionResult>> directResultsMono = Flux.fromIterable(directCalls)
                .flatMap(tc -> toolExecutionPipeline.executeSingleTool(tc, agentName, agentDbId, sessionId, skillsBasePath,
                        loopDetector, cache,
                        toolTimeout, nonRetryableTools, toolCallbackMap), maxParallel)
                .collectList();

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
                                return toolExecutionPipeline.doExecuteTool(tc.id(), tc.name(), args, agentName, agentDbId, sessionId, skillsBasePath,
                                        cache, toolTimeout, nonRetryableTools, false, toolCallbackMap);
                            });

                    return Flux.concat(
                            Flux.just(approvalEvent),
                            afterApproval.flatMapMany(r -> {
                                AgentEvent toolResult = new AgentEvent.ToolResult(r.id(), r.name(), r.result(), r.success(), turn);
                                if (r.success()) {
                                    List<AgentEvent> planEvents = planEventExtractor.extractPlanEvents(r.name(), toolCallArgs.get(tc.id()), r.result());
                                    if (!planEvents.isEmpty()) {
                                        if (planStepTracker != null) {
                                            planEvents = PlanEventExtractor.filterDuplicatePlanEvents(planEvents, planStepTracker);
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

        Flux<AgentEvent> directResultEvents = directResultsMono.flatMapMany(results -> {
            return Flux.fromIterable(results)
                    .concatMap(r -> {
                        AgentEvent toolResult = new AgentEvent.ToolResult(r.id(), r.name(), r.result(), r.success(), turn);
                        if (r.success()) {
                            log.debug("Phase3: checking plan events for tool={}, id={}, hasArgs={}",
                                    r.name(), r.id(), toolCallArgs.containsKey(r.id()));
                            List<AgentEvent> planEvents = planEventExtractor.extractPlanEvents(r.name(), toolCallArgs.get(r.id()), r.result());
                            if (!planEvents.isEmpty()) {
                                if (planStepTracker != null) {
                                    planEvents = PlanEventExtractor.filterDuplicatePlanEvents(planEvents, planStepTracker);
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

        DelegationContext parentDelegCtx = request.delegationContext();
        Flux<AgentEvent> delegationFlow = Flux.fromIterable(delegationCalls)
                .concatMap(tc -> delegationExecutor.executeDelegationToolCall(
                        tc, agentName, sessionId, parentDelegCtx, toolCallArgs, turn, callbacks.dispatchFn()));

        final AssistantMessage.ToolCall finalHandoffCall = handoffCall;
        Flux<AgentEvent> handoffFlow = Flux.empty();
        if (finalHandoffCall != null) {
            handoffFlow = delegationExecutor.executeHandoffToolCall(finalHandoffCall, agentName, turn);
        }

        Flux<AgentEvent> execution = Flux.concat(directResultEvents, approvalFlow, delegationFlow, handoffFlow);

        Flux<AgentEvent> withHistory = execution.collectList().flatMapMany(allEvents -> {
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AgentEvent evt : allEvents) {
                if (evt instanceof AgentEvent.ToolResult tr) {
                    responses.add(new ToolResponseMessage.ToolResponse(tr.toolCallId(), tr.name(), tr.result()));
                    String resultText = tr.result() != null ? tr.result() : "";
                    int tokens = tokenEstimator.estimateForToolInteraction(resultText);
                    workingMemory.addIncrementalTokens(tokens);
                    Map<String, String> toolMeta = Map.of("toolName", tr.name());
                    float importance;
                    if (activePlanId != null && planExecutionAssessment != null) {
                        String currentStep = planExecutionAssessment.currentStepDescription();
                        List<String> completed = planExecutionAssessment.completedStepDescriptions() != null
                                ? planExecutionAssessment.completedStepDescriptions() : List.of();
                        List<String> pending = planExecutionAssessment.pendingStepDescriptions() != null
                                ? planExecutionAssessment.pendingStepDescriptions() : List.of();
                        importance = importanceAssessor.assessWithPlanContext(
                                resultText, ContentCategory.COMMAND_OUTPUT, toolMeta,
                                currentStep != null ? currentStep : "", completed, pending);
                    } else {
                        importance = importanceAssessor.assess(resultText, ContentCategory.COMMAND_OUTPUT, toolMeta);
                    }
                    MemoryChunk chunk = MemoryChunk.toolInteraction(
                            resultText, ContentCategory.COMMAND_OUTPUT, importance, tokens,
                            Map.of("toolName", tr.name(), "toolCallId", tr.toolCallId(),
                                    "arguments", toolCallArgs.getOrDefault(tr.toolCallId(), "{}")));
                    workingMemory.accept(chunk);
                }
            }
            if (!responses.isEmpty()) {
                history.add(new ToolResponseMessage(responses));
            }

            List<AgentEvent> result = new ArrayList<>(allEvents);
            WorkingMemory.MemorySnapshot midSnap = workingMemory.getSnapshot();
            List<AgentEvent.ChunkInfo> midChunkInfos = midSnap.chunks().stream()
                    .map(c -> new AgentEvent.ChunkInfo(c.id(), c.type(), c.category(),
                            c.importance(), c.tokens(), c.contentPreview(), c.createdAt()))
                    .toList();
            AgentEvent.MemorySnapshot midEvent = new AgentEvent.MemorySnapshot(
                    midSnap.tokenBudget(), midSnap.tokenUsed(), midSnap.tokenEstimated(), midSnap.usageRatio(),
                    midSnap.chunkCount(), midChunkInfos);
            callbacks.latestSnapshots().put(sessionId, midEvent);
            result.add(midEvent);
            return Flux.fromIterable(result);
        });

        Flux<AgentEvent> memorySnapshot = Flux.defer(() -> workingMemory.awaitPendingConsolidation()
                .flatMapMany(optionalCr -> {
                    List<AgentEvent> events = new ArrayList<>();
                    optionalCr.ifPresent(cr -> {
                        agentMemoryLifecycle.recordMemoryConsolidation(agentName);
                        if (cr.factsStoredToLongTerm()) {
                            agentMemoryLifecycle.recordLongTermStore("consolidation");
                        }
                        events.add(AgentMemoryLifecycle.toConsolidationTriggeredEvent(cr, workingMemory));
                        history.clear();
                        history.addAll(workingMemory.buildLLMInputSync());
                    });
                    WorkingMemory.MemorySnapshot snap = workingMemory.getSnapshot();
                    agentMemoryLifecycle.recordWorkingMemoryUsage(agentName, snap.usageRatio());
                    List<AgentEvent.ChunkInfo> chunkInfos = snap.chunks().stream()
                            .map(c -> new AgentEvent.ChunkInfo(c.id(), c.type(), c.category(),
                                    c.importance(), c.tokens(), c.contentPreview(), c.createdAt()))
                            .toList();
                    AgentEvent.MemorySnapshot event = new AgentEvent.MemorySnapshot(
                            snap.tokenBudget(), snap.tokenUsed(), snap.tokenEstimated(), snap.usageRatio(),
                            snap.chunkCount(), chunkInfos);
                    callbacks.latestSnapshots().put(sessionId, event);
                    events.add(event);
                    return Flux.fromIterable(events);
                }));
        Flux<AgentEvent> nextTurn;
        if (finalHandoffCall != null) {
            nextTurn = Flux.empty();
        } else {
            nextTurn = Flux.defer(() ->
                    executeLoopTurn(chatModel, history, options, maxTurns, timeout, turn + 1, fullText,
                            agentName, sessionId, skillsBasePath,
                            loopDetector, workingMemory, importanceAssessor, tokenEstimator, cache, approvalGate,
                            toolTimeout, maxParallel, nonRetryableTools,
                            activePlanId, planStepTracker, planExecutionAssessment, request, toolCallbackMap, callbacks));
        }

        return Flux.concat(autoStartFlux, callEvents, withHistory, memorySnapshot, nextTurn);
    }

    private String extractModelId(ChatOptions options) {
        if (options != null && options.getModel() != null && !options.getModel().isBlank()) {
            return options.getModel();
        }
        return "unknown";
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

    private ResolvedModel resolveModel(String modelRef) {
        if (modelRef == null || modelRef.isBlank()) {
            throw new IllegalArgumentException("Model reference is null or empty");
        }
        try {
            Long definitionId = Long.parseLong(modelRef);
            return chatModelRegistry.resolve(definitionId);
        } catch (NumberFormatException e) {
            return chatModelRegistry.resolveByModelName(modelRef);
        }
    }

    private ChatOptions buildChatOptions(ToolCallback[] tools, ResolvedModel resolved, boolean parallelEnabled) {
        if (resolved.providerType() == ProviderType.DEEPSEEK) {
            return DeepSeekChatOptions.builder()
                    .toolCallbacks(tools)
                    .model(resolved.modelId())
                    .internalToolExecutionEnabled(false)
                    .build();
        }
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

    private void logRequestParams(AgentRunRequest request, String systemPrompt, ToolCallback[] tools) {
        if (!log.isInfoEnabled()) {
            return;
        }
        try {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("sessionId", request.sessionId());
            params.put("userId", request.userId());
            params.put("model", request.agent().getModel());
            params.put("maxTurns", request.agent().getMaxTurns());
            params.put("timeoutSeconds", request.agent().getTimeoutSeconds());
            params.put("toolsEnabled", request.toolsEnabled());
            params.put("mcpToolsEnabled", request.mcpToolsEnabled());
            params.put("skillsEnabled", request.skillsEnabled());
            params.put("skillGroupsEnabled", request.skillGroupsEnabled());
            params.put("forcePlan", request.forcePlan());
            params.put("activePlanId", request.activePlanId());
            params.put("systemPromptLength", systemPrompt != null ? systemPrompt.length() : 0);
            params.put("systemPrompt", systemPrompt);
            params.put("userMessage", request.userMessage());
            params.put("historySize", request.history() != null ? request.history().size() : 0);
            params.put("history", request.history() != null
                    ? request.history().stream().map(msg -> {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("role", msg.getMessageType().getValue());
                m.put("content", msg.getText());
                return m;
            }).toList()
                    : List.of());
            params.put("tools", Arrays.stream(tools)
                    .map(cb -> cb.getToolDefinition().name())
                    .toList());
            params.put("planContext", request.planContext());
            log.info("LLM request params:\n{}", objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(params));
        } catch (Exception e) {
            log.warn("Failed to serialize LLM request params", e);
        }
    }
}
