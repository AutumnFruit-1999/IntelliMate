package com.atm.intellimate.agent.runtime;

import com.atm.intellimate.agent.model.ChatModelRegistry;
import com.atm.intellimate.agent.model.ProviderType;
import com.atm.intellimate.agent.model.ResolvedModel;
import com.atm.intellimate.agent.plan.PlanOperations;
import com.atm.intellimate.agent.skills.SkillContentProvider;
import com.atm.intellimate.agent.skills.SkillGroupContext;
import com.atm.intellimate.agent.skills.SkillUsageRecorder;
import com.atm.intellimate.agent.tools.AgentContext;
import com.atm.intellimate.agent.tools.AgentSessionContext;
import com.atm.intellimate.agent.tools.ToolsEngine;
import com.atm.intellimate.agent.tools.WritePlanTool;
import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.core.prompt.PromptLoader;
import com.atm.intellimate.memory.MemorySystem;
import com.atm.intellimate.memory.config.MemoryConfigProvider;
import com.atm.intellimate.memory.config.ResolvedMemoryConfig;
import com.atm.intellimate.memory.consolidation.ConsolidationResult;
import com.atm.intellimate.memory.consolidation.MemoryConsolidator;
import com.atm.intellimate.memory.longterm.LongTermMemory;
import com.atm.intellimate.memory.model.ContentCategory;
import com.atm.intellimate.memory.model.ExtractedFact;
import com.atm.intellimate.memory.model.MemoryChunk;
import com.atm.intellimate.memory.perception.ImportanceAssessor;
import com.atm.intellimate.memory.retrieval.MemoryRetrieval;
import com.atm.intellimate.memory.working.TokenEstimator;
import com.atm.intellimate.memory.working.WorkingMemory;
import com.fasterxml.jackson.databind.JsonNode;
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

    private static final int MAX_RETRIES = 2;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private final ChatModelRegistry chatModelRegistry;
    private final ToolsEngine toolsEngine;
    private final RunQueueManager runQueueManager;
    private final ObjectMapper objectMapper;
    private final SkillContentProvider skillContentProvider;
    private final SkillUsageRecorder skillUsageRecorder;
    private final AgentSessionContext agentSessionContext;
    private final IntelliMateProperties properties;
    private final PlanOperations planOperations;
    private final MemoryConfigProvider memoryConfigProvider;
    private final LongTermMemory longTermMemory;
    private final MemorySystem memorySystem;
    private final DelegationResolver delegationResolver;

    private static final Set<String> DELEGATION_TOOL_NAMES = Set.of(
            "delegateAgent", "handoffToAgent", "delegateAgentsParallel");

    public AgentRuntime(ChatModelRegistry chatModelRegistry,
                        ToolsEngine toolsEngine,
                        RunQueueManager runQueueManager,
                        ObjectMapper objectMapper,
                        @Autowired(required = false) SkillContentProvider skillContentProvider,
                        @Autowired(required = false) SkillUsageRecorder skillUsageRecorder,
                        AgentSessionContext agentSessionContext,
                        IntelliMateProperties properties,
                        @Autowired(required = false) PlanOperations planOperations,
                        @Autowired(required = false) MemoryConfigProvider memoryConfigProvider,
                        @Autowired(required = false) LongTermMemory longTermMemory,
                        @Autowired(required = false) MemorySystem memorySystem,
                        @Autowired(required = false) DelegationResolver delegationResolver) {
        this.chatModelRegistry = chatModelRegistry;
        this.toolsEngine = toolsEngine;
        this.runQueueManager = runQueueManager;
        this.objectMapper = objectMapper;
        this.skillContentProvider = skillContentProvider;
        this.skillUsageRecorder = skillUsageRecorder;
        this.agentSessionContext = agentSessionContext;
        this.properties = properties;
        this.planOperations = planOperations;
        this.memoryConfigProvider = memoryConfigProvider;
        this.longTermMemory = longTermMemory;
        this.memorySystem = memorySystem;
        this.delegationResolver = delegationResolver;
    }

    public Flux<AgentEvent> dispatch(AgentRunRequest request) {
        return runQueueManager.enqueue(request.sessionId(), () -> executeAgentLoop(request));
    }

    private final ConcurrentMap<Long, ToolApprovalGate> sessionApprovalGates = new ConcurrentHashMap<>();
    private static final Set<String> SKILL_GROUPS_UNRESTRICTED = Set.of("__ALL__");
    private final ConcurrentMap<Long, Set<String>> sessionSkillGroups = new ConcurrentHashMap<>();
    private final Set<Long> pausedPlanIds = ConcurrentHashMap.newKeySet();

    private static final ConcurrentMap<Long, AgentEvent.MemorySnapshot> latestSnapshots = new ConcurrentHashMap<>();

    private record DeferredEpisodicStore(WorkingMemory workingMemory, LongTermMemory ltm,
                                          String userId, String agentId, Long sessionId,
                                          int minChunksForEpisodic) {}
    private static final ConcurrentMap<Long, DeferredEpisodicStore> deferredEpisodicStores = new ConcurrentHashMap<>();

    public static AgentEvent.MemorySnapshot getLatestSnapshot(Long sessionId) {
        return latestSnapshots.get(sessionId);
    }

    /**
     * Called on WebSocket disconnect to flush deferred episodic memory for the session.
     * Only stores if chunks > 4 and no prior episodic was generated during this session.
     */
    public void flushDeferredEpisodicMemory(Long sessionId) {
        DeferredEpisodicStore deferred = deferredEpisodicStores.remove(sessionId);
        if (deferred == null) return;
        storeSessionEpisodicMemory(deferred.workingMemory(), deferred.ltm(),
                deferred.userId(), deferred.agentId(), deferred.sessionId(), deferred.minChunksForEpisodic());
    }

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

        setupSkillGroupContext(request.sessionId(), request.skillGroupsEnabled());

        return skillsMono.publishOn(Schedulers.boundedElastic()).flatMapMany(skillSummaries -> {
            IntelliMateProperties.Agent agentConfig = request.agent();
            boolean parallelEnabled = agentConfig.isEnableParallelToolCalls();
            String systemPrompt = buildSystemPrompt(agentConfig, skillSummaries, parallelEnabled,
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

            // Per-run middleware instances
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
            sessionApprovalGates.put(request.sessionId(), approvalGate);

            Duration toolTimeout = Duration.ofSeconds(agentConfig.getToolExecutionTimeoutSeconds());
            int maxParallel = agentConfig.getMaxParallelToolCalls();
            Set<String> nonRetryable = new HashSet<>(agentConfig.getNonRetryableTools());

            PlanStepTracker planStepTracker = request.activePlanId() != null && planOperations != null
                    ? new PlanStepTracker(request.activePlanId(), planOperations) : null;

            return loadMemoryInitReactive(tokenEstimator)
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

                            retrievalMono = retrieval.retrieve(cue, sessionUserId, agentId, maxInjectionTokens, lambda)
                                    .timeout(Duration.ofSeconds(3))
                                    .doOnNext(recalledChunks -> {
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
                                        request.activePlanId(), planStepTracker, request.planExecutionAssessment(), request, toolCallbackMap)
                                        .doFinally(signal -> {
                                            sessionApprovalGates.remove(request.sessionId());
                                            sessionSkillGroups.remove(request.sessionId());
                                            SkillGroupContext.clear();
                                            latestSnapshots.remove(request.sessionId());
                                            if (request.activePlanId() != null) {
                                                pausedPlanIds.remove(request.activePlanId());
                                            }
                                            if (longTermMemory != null && ltEnabled && request.activePlanId() == null) {
                                                int minChunks = resolvedMem != null ? resolvedMem.minChunksForEpisodic() : 4;
                                                deferEpisodicStore(workingMemory, longTermMemory, sessionUserId, agentId, request.sessionId(), minChunks);
                                            }
                                        }));
                    });
        });
    }

    private record MemoryInit(ResolvedMemoryConfig resolved, MemoryConsolidator consolidator) {}

    /**
     * Resolves DB-backed memory config once per run for WorkingMemory thresholds,
     * consolidator construction, and long-term retrieval settings.
     */
    private Mono<MemoryInit> loadMemoryInitReactive(TokenEstimator tokenEstimator) {
        if (memoryConfigProvider == null) {
            return Mono.just(new MemoryInit(null, null));
        }
        return memoryConfigProvider.resolve()
                .timeout(Duration.ofSeconds(2))
                .map(memConfig -> new MemoryInit(memConfig, createMemoryConsolidator(memConfig, tokenEstimator)))
                .defaultIfEmpty(new MemoryInit(null, null))
                .onErrorResume(e -> {
                    log.warn("Failed to load memory config, using defaults: {}", e.getMessage());
                    return Mono.just(new MemoryInit(null, null));
                });
    }

    private MemoryConsolidator createMemoryConsolidator(ResolvedMemoryConfig memConfig, TokenEstimator tokenEstimator) {
        try {
            ResolvedModel consolidationModel = resolveModel(memConfig.consolidationModel());
            ChatModel fallbackChatModel = null;
            String fallbackModelId = null;
            try {
                ResolvedModel fallbackResolved = resolveModel(memConfig.fallbackModel());
                fallbackChatModel = fallbackResolved.chatModel();
                fallbackModelId = fallbackResolved.modelId();
            } catch (Exception ignored) {
                // optional fallback model
            }
            LongTermMemory effectiveLtm = memConfig.longTermEnabled() ? longTermMemory : null;
            return new MemoryConsolidator(
                    consolidationModel.chatModel(),
                    fallbackChatModel,
                    effectiveLtm,
                    tokenEstimator,
                    memConfig.maxSummaryTokens(),
                    memConfig.maxRetries(),
                    memConfig.timeoutMs(),
                    consolidationModel.modelId(),
                    fallbackModelId);
        } catch (Exception e) {
            log.warn("Failed to create MemoryConsolidator, using null: {}", e.getMessage());
            return null;
        }
    }

    private static AgentEvent.ConsolidationTriggered toConsolidationTriggeredEvent(
            ConsolidationResult cr, WorkingMemory workingMemory) {
        WorkingMemory.MemorySnapshot snap = workingMemory.getSnapshot();
        int tokensBefore = cr.tokensBefore() >= 0 ? cr.tokensBefore() : snap.tokenUsed();
        int tokensAfter = cr.tokensAfter() >= 0 ? cr.tokensAfter() : snap.tokenUsed();
        List<String> factStrings = cr.facts().stream()
                .map(f -> f.content())
                .toList();
        List<AgentEvent.ChunkPreview> candidates = cr.sourceChunkPreviews().stream()
                .map(p -> new AgentEvent.ChunkPreview(p.type(), p.tokens(), p.importance(), p.preview()))
                .toList();
        return new AgentEvent.ConsolidationTriggered(
                cr.sourceChunkCount(), tokensBefore, tokensAfter, factStrings,
                candidates, cr.factsStoredToLongTerm());
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
            Map<String, ToolCallback> toolCallbackMap) {

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
            for (int i = allChunks.size() - 1; i >= 0; i--) {
                ChatResponse chunk = allChunks.get(i);
                if (chunk != null && chunk.getMetadata() != null) {
                    Usage usage = chunk.getMetadata().getUsage();
                    if (usage != null && usage.getPromptTokens() != null && usage.getPromptTokens() > 0) {
                        workingMemory.setActualTokenUsage(usage.getPromptTokens().intValue());
                        log.debug("Actual prompt tokens from API: {}", usage.getPromptTokens());
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
                        activePlanId, planStepTracker, planExecutionAssessment, request, toolCallbackMap);
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
            latestSnapshots.put(sessionId, memSnap);

            return Flux.just(memSnap, new AgentEvent.Done(finalText, turn));
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
            Map<String, ToolCallback> toolCallbackMap) {

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

        // Separate tool calls into delegation, approval, and direct categories
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

        Long agentDbId = request.agent() != null ? request.agent().getAgentDbId() : null;

        // Phase 2a: Execute non-approval tools in parallel
        Mono<List<ToolExecutionResult>> directResultsMono = Flux.fromIterable(directCalls)
                .flatMap(tc -> executeSingleTool(tc, agentName, agentDbId, sessionId, skillsBasePath,
                        loopDetector, cache,
                        toolTimeout, nonRetryableTools, toolCallbackMap), maxParallel)
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
                                return doExecuteTool(tc.id(), tc.name(), args, agentName, agentDbId, sessionId, skillsBasePath,
                                        cache, toolTimeout, nonRetryableTools, false, toolCallbackMap);
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

        // Phase 2e: Handle delegation tool calls (delegateAgent / delegateAgentsParallel)
        DelegationContext parentDelegCtx = request.delegationContext();
        Flux<AgentEvent> delegationFlow = Flux.fromIterable(delegationCalls)
                .concatMap(tc -> executeDelegationToolCall(tc, agentName, sessionId, parentDelegCtx, toolCallArgs, turn));

        // Phase 2f: Handle handoff (terminates current agent loop after execution)
        final AssistantMessage.ToolCall finalHandoffCall = handoffCall;
        Flux<AgentEvent> handoffFlow = Flux.empty();
        if (finalHandoffCall != null) {
            handoffFlow = executeHandoffToolCall(finalHandoffCall, agentName, turn);
        }

        // Phase 4: After all tool results are emitted, collect everything into history and recurse
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

            // Emit lightweight snapshot after tool results for real-time frontend token tracking
            List<AgentEvent> result = new ArrayList<>(allEvents);
            WorkingMemory.MemorySnapshot midSnap = workingMemory.getSnapshot();
            List<AgentEvent.ChunkInfo> midChunkInfos = midSnap.chunks().stream()
                    .map(c -> new AgentEvent.ChunkInfo(c.id(), c.type(), c.category(),
                            c.importance(), c.tokens(), c.contentPreview(), c.createdAt()))
                    .toList();
            AgentEvent.MemorySnapshot midEvent = new AgentEvent.MemorySnapshot(
                    midSnap.tokenBudget(), midSnap.tokenUsed(), midSnap.tokenEstimated(), midSnap.usageRatio(),
                    midSnap.chunkCount(), midChunkInfos);
            latestSnapshots.put(sessionId, midEvent);
            result.add(midEvent);
            return Flux.fromIterable(result);
        });

        // Phase 5: Await consolidation (if any), emit ConsolidationTriggered + memory snapshot, recurse
        Flux<AgentEvent> memorySnapshot = Flux.defer(() -> workingMemory.awaitPendingConsolidation()
                .flatMapMany(optionalCr -> {
                    List<AgentEvent> events = new ArrayList<>();
                    optionalCr.ifPresent(cr -> {
                        events.add(toConsolidationTriggeredEvent(cr, workingMemory));
                        history.clear();
                        history.addAll(workingMemory.buildLLMInputSync());
                    });
                    WorkingMemory.MemorySnapshot snap = workingMemory.getSnapshot();
                    List<AgentEvent.ChunkInfo> chunkInfos = snap.chunks().stream()
                            .map(c -> new AgentEvent.ChunkInfo(c.id(), c.type(), c.category(),
                                    c.importance(), c.tokens(), c.contentPreview(), c.createdAt()))
                            .toList();
                    AgentEvent.MemorySnapshot event = new AgentEvent.MemorySnapshot(
                            snap.tokenBudget(), snap.tokenUsed(), snap.tokenEstimated(), snap.usageRatio(),
                            snap.chunkCount(), chunkInfos);
                    latestSnapshots.put(sessionId, event);
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
                            activePlanId, planStepTracker, planExecutionAssessment, request, toolCallbackMap));
        }

        return Flux.concat(autoStartFlux, callEvents, withHistory, memorySnapshot, nextTurn);
    }

    // ─── Delegation execution ───

    private static final int MAX_DELEGATION_RESULT_CHARS = 32_000;

    private Flux<AgentEvent> executeDelegationToolCall(
            AssistantMessage.ToolCall tc, String parentAgentName, Long parentSessionId,
            DelegationContext parentCtx, Map<String, String> toolCallArgs, int turn) {

        if (delegationResolver == null) {
            AgentEvent result = new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Error: delegation is not available (DelegationResolver not configured)", false, turn);
            return Flux.just(result);
        }

        if ("delegateAgent".equals(tc.name())) {
            return executeSingleDelegation(tc, parentAgentName, parentSessionId, parentCtx, turn);
        } else if ("delegateAgentsParallel".equals(tc.name())) {
            return executeParallelDelegation(tc, parentAgentName, parentSessionId, parentCtx, turn);
        }
        return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                "Unknown delegation tool: " + tc.name(), false, turn));
    }

    private Flux<AgentEvent> executeSingleDelegation(
            AssistantMessage.ToolCall tc, String parentAgentName, Long parentSessionId,
            DelegationContext parentCtx, int turn) {

        String args = tc.arguments();
        String workerName, task, context;
        try {
            JsonNode node = objectMapper.readTree(args);
            workerName = node.has("agentName") ? node.get("agentName").asText() : null;
            task = node.has("task") ? node.get("task").asText() : null;
            context = node.has("context") ? node.get("context").asText("") : "";
        } catch (Exception e) {
            return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Failed to parse delegation arguments: " + e.getMessage(), false, turn));
        }
        if (workerName == null || task == null) {
            return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Missing required parameters: agentName and task", false, turn));
        }

        DelegationContext ctx = parentCtx != null ? parentCtx : DelegationContext.root(parentSessionId, parentAgentName);
        if (!ctx.canDelegate()) {
            return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Delegation limit reached (depth=" + ctx.nestingDepth() + ", count=" + ctx.delegationCount().get() + ")", false, turn));
        }
        ctx.incrementAndGetCount();

        String delegationId = java.util.UUID.randomUUID().toString().substring(0, 8);
        DelegationContext childCtx = ctx.incrementDepth();
        String finalWorkerName = workerName;
        String finalTask = task;
        String finalContext = context;

        return Flux.defer(() -> {
            AgentEvent start = new AgentEvent.DelegationStart(finalWorkerName, finalTask, delegationId);
            long startMs = System.currentTimeMillis();

            Mono<DelegationResolver.ResolvedWorkerConfig> workerConfigMono = delegationResolver.resolveWorker(finalWorkerName)
                    .switchIfEmpty(Mono.error(new IllegalArgumentException("Worker agent not found: " + finalWorkerName)));

            Mono<Long> workerSessionMono = delegationResolver.createWorkerSession(parentSessionId, finalWorkerName, delegationId);

            return Flux.just(start).concatWith(
                    Mono.zip(workerConfigMono, workerSessionMono)
                            .flatMapMany(tuple -> {
                                DelegationResolver.ResolvedWorkerConfig workerCfg = tuple.getT1();
                                Long workerSessionId = tuple.getT2();

                                String workerMessage = buildWorkerPrompt(finalTask, finalContext);
                                IntelliMateProperties.Agent workerAgent = workerCfg.agent();
                                if (!workerCfg.canDelegate()) {
                                    workerAgent.setCanDelegate(false);
                                }

                                AgentRunRequest workerRequest = new AgentRunRequest(
                                        workerSessionId, null, workerAgent, workerMessage,
                                        List.of(),
                                        workerCfg.toolsEnabled(), workerCfg.mcpToolsEnabled(),
                                        workerCfg.skillsEnabled(), workerCfg.skillGroupsEnabled(),
                                        null, false, null, null, childCtx,
                                        workerCfg.bridgeNode());

                                StringBuilder workerResult = new StringBuilder();
                                int[] workerTurns = {0};
                                boolean[] workerSuccess = {true};

                                return dispatch(workerRequest)
                                        .concatMap(event -> {
                                            if (event instanceof AgentEvent.Done done) {
                                                workerResult.append(done.fullText());
                                                workerTurns[0] = done.totalTurns();
                                            } else if (event instanceof AgentEvent.Error err) {
                                                workerResult.append("Error: ").append(err.message());
                                                workerSuccess[0] = false;
                                            }
                                            AgentEvent progress = new AgentEvent.DelegationProgress(
                                                    finalWorkerName, delegationId, event);
                                            return Flux.just(progress);
                                        })
                                        .concatWith(Flux.defer(() -> {
                                            long durationMs = System.currentTimeMillis() - startMs;
                                            String resultText = workerResult.toString();
                                            if (resultText.length() > MAX_DELEGATION_RESULT_CHARS) {
                                                resultText = resultText.substring(0, MAX_DELEGATION_RESULT_CHARS) + "\n...[truncated]";
                                            }
                                            AgentEvent delegationResult = new AgentEvent.DelegationResult(
                                                    finalWorkerName, delegationId, resultText,
                                                    workerSuccess[0], workerTurns[0], durationMs);
                                            AgentEvent toolResult = new AgentEvent.ToolResult(
                                                    tc.id(), tc.name(), resultText, workerSuccess[0], turn);
                                            return Flux.just(delegationResult, toolResult);
                                        }));
                            })
                            .onErrorResume(e -> {
                                long durationMs = System.currentTimeMillis() - startMs;
                                String errorMsg = "Delegation failed: " + e.getMessage();
                                log.warn("Delegation to {} failed: {}", finalWorkerName, e.getMessage(), e);
                                AgentEvent delegationResult = new AgentEvent.DelegationResult(
                                        finalWorkerName, delegationId, errorMsg, false, 0, durationMs);
                                AgentEvent toolResult = new AgentEvent.ToolResult(
                                        tc.id(), tc.name(), errorMsg, false, turn);
                                return Flux.just(delegationResult, toolResult);
                            })
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<AgentEvent> executeParallelDelegation(
            AssistantMessage.ToolCall tc, String parentAgentName, Long parentSessionId,
            DelegationContext parentCtx, int turn) {

        List<AgentEvent.ParallelTask> taskList;
        try {
            JsonNode node = objectMapper.readTree(tc.arguments());
            JsonNode tasksNode = node.has("tasks") ? node.get("tasks") : node;
            if (tasksNode.isTextual()) {
                tasksNode = objectMapper.readTree(tasksNode.asText());
            }
            taskList = new ArrayList<>();
            for (JsonNode t : tasksNode) {
                String agent = t.has("agent") ? t.get("agent").asText() : t.has("agentName") ? t.get("agentName").asText() : null;
                String task = t.has("task") ? t.get("task").asText() : null;
                if (agent != null && task != null) {
                    taskList.add(new AgentEvent.ParallelTask(agent, task));
                }
            }
        } catch (Exception e) {
            return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Failed to parse parallel tasks: " + e.getMessage(), false, turn));
        }
        if (taskList.isEmpty()) {
            return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "No valid tasks found in the input", false, turn));
        }

        DelegationContext ctx = parentCtx != null ? parentCtx : DelegationContext.root(parentSessionId, parentAgentName);
        String groupId = java.util.UUID.randomUUID().toString().substring(0, 8);
        int maxPar = ctx.maxParallel();

        AgentEvent parallelStart = new AgentEvent.ParallelStart(groupId, List.copyOf(taskList));

        Flux<AgentEvent> parallelExec = Flux.fromIterable(taskList)
                .flatMap(pt -> {
                    if (!ctx.canDelegate()) {
                        return Flux.just((AgentEvent) new AgentEvent.ParallelProgress(groupId, pt.agentName(),
                                new AgentEvent.Error("Delegation limit reached")));
                    }
                    ctx.incrementAndGetCount();
                    String delegationId = groupId + "-" + pt.agentName();
                    DelegationContext childCtx = ctx.incrementDepth();

                    long startMs = System.currentTimeMillis();
                    return delegationResolver.resolveWorker(pt.agentName())
                            .switchIfEmpty(Mono.error(new IllegalArgumentException("Agent not found: " + pt.agentName())))
                            .flatMap(cfg -> delegationResolver.createWorkerSession(parentSessionId, pt.agentName(), delegationId)
                                    .map(sid -> Map.entry(cfg, sid)))
                            .flatMapMany(entry -> {
                                DelegationResolver.ResolvedWorkerConfig cfg = entry.getKey();
                                Long sid = entry.getValue();
                                IntelliMateProperties.Agent wa = cfg.agent();
                                if (!cfg.canDelegate()) wa.setCanDelegate(false);

                                AgentRunRequest wr = new AgentRunRequest(
                                        sid, null, wa, buildWorkerPrompt(pt.task(), ""),
                                        List.of(), cfg.toolsEnabled(), cfg.mcpToolsEnabled(),
                                        cfg.skillsEnabled(), cfg.skillGroupsEnabled(),
                                        null, false, null, null, childCtx,
                                        cfg.bridgeNode());

                                StringBuilder result = new StringBuilder();
                                boolean[] ok = {true};
                                return dispatch(wr)
                                        .doOnNext(e -> {
                                            if (e instanceof AgentEvent.Done d) result.append(d.fullText());
                                            else if (e instanceof AgentEvent.Error er) { result.append(er.message()); ok[0] = false; }
                                        })
                                        .map(e -> (AgentEvent) new AgentEvent.ParallelProgress(groupId, pt.agentName(), e))
                                        .concatWith(Flux.defer(() -> Flux.empty()));
                            })
                            .onErrorResume(e -> Flux.just(new AgentEvent.ParallelProgress(groupId, pt.agentName(),
                                    new AgentEvent.Error("Worker failed: " + e.getMessage()))));
                }, maxPar)
                .subscribeOn(Schedulers.boundedElastic());

        Flux<AgentEvent> toolResultEvent = Flux.defer(() -> {
            AgentEvent toolResult = new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Parallel delegation completed for " + taskList.size() + " agents", true, turn);
            return Flux.just(toolResult);
        });

        return Flux.concat(Flux.just(parallelStart), parallelExec, toolResultEvent);
    }

    private Flux<AgentEvent> executeHandoffToolCall(AssistantMessage.ToolCall tc, String fromAgent, int turn) {
        String targetAgent, reason, contextSummary;
        try {
            JsonNode node = objectMapper.readTree(tc.arguments());
            targetAgent = node.has("agentName") ? node.get("agentName").asText() : null;
            reason = node.has("reason") ? node.get("reason").asText("") : "";
            contextSummary = node.has("contextSummary") ? node.get("contextSummary").asText("") : "";
        } catch (Exception e) {
            return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Failed to parse handoff arguments: " + e.getMessage(), false, turn));
        }
        if (targetAgent == null) {
            return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Missing required parameter: agentName", false, turn));
        }

        AgentEvent handoffStart = new AgentEvent.HandoffStart(fromAgent, targetAgent, reason, contextSummary);
        AgentEvent toolResult = new AgentEvent.ToolResult(tc.id(), tc.name(),
                "Handoff to " + targetAgent + " initiated", true, turn);
        return Flux.just(handoffStart, toolResult);
    }

    private static String buildWorkerPrompt(String task, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You have been assigned the following task:\n\n");
        sb.append(task);
        if (context != null && !context.isBlank()) {
            sb.append("\n\nAdditional context:\n").append(context);
        }
        sb.append("\n\nComplete this task thoroughly and return your results.");
        return sb.toString();
    }

    /**
     * Executes a single tool call with the full middleware chain:
     * loop detection -> approval gate -> cache check -> actual execution (with retry + timeout).
     * <p>
     * Returns a Mono of ToolExecutionResult. Approval events are emitted separately
     * via processToolCalls which handles the approval flow.
     */
    private Mono<ToolExecutionResult> executeSingleTool(
            AssistantMessage.ToolCall tc,
            String agentName,
            Long agentDbId,
            Long sessionId,
            String skillsBasePath,
            ToolCallLoopDetector loopDetector,
            ToolResultCache cache,
            Duration toolTimeout,
            Set<String> nonRetryableTools,
            Map<String, ToolCallback> toolCallbackMap) {

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
        return doExecuteTool(tc.id(), tc.name(), tc.arguments(), agentName, agentDbId, sessionId, skillsBasePath,
                cache, toolTimeout, nonRetryableTools, appendWarning, toolCallbackMap);
    }

    private Mono<ToolExecutionResult> doExecuteTool(
            String toolCallId,
            String toolName,
            String arguments,
            String agentName,
            Long agentDbId,
            Long sessionId,
            String skillsBasePath,
            ToolResultCache cache,
            Duration toolTimeout,
            Set<String> nonRetryableTools,
            boolean appendWarning,
            Map<String, ToolCallback> toolCallbackMap) {

        // Check cache first
        String cached = cache.get(toolName, arguments);
        if (cached != null) {
            String result = cached + "\n[缓存结果]";
            if (appendWarning) {
                result += "\n\n[警告：你已多次使用相同参数调用此工具，请尝试其他方法。]";
            }
            return Mono.just(new ToolExecutionResult(toolCallId, toolName, result, true));
        }

        Mono<ToolExecutionResult> execution = Mono.fromCallable(() -> {
                    agentSessionContext.set(sessionId);
                    AgentContext.set(agentDbId, agentName);
                    Set<String> skillGroups = sessionSkillGroups.get(sessionId);
                    if (skillGroups == null) {
                        SkillGroupContext.set(Set.of());
                    } else if (skillGroups == SKILL_GROUPS_UNRESTRICTED) {
                        SkillGroupContext.set(null);
                    } else {
                        SkillGroupContext.set(skillGroups);
                    }
                    try {
                        ToolCallback callback = toolCallbackMap != null
                                ? toolCallbackMap.getOrDefault(toolName, toolsEngine.getCallbackByName(toolName))
                                : toolsEngine.getCallbackByName(toolName);
                        String result = callback.call(arguments);
                        log.debug("Tool {} executed, result length={}", toolName, result != null ? result.length() : 0);

                        recordSkillActivationIfApplicable(toolName, arguments, agentName, sessionId, skillsBasePath);

                        cache.put(toolName, arguments, result);
                        cache.invalidateForWrite(toolName, arguments);

                        if (appendWarning) {
                            result += "\n\n[警告：你已多次使用相同参数调用此工具，请尝试其他方法。]";
                        }
                        return new ToolExecutionResult(toolCallId, toolName, result != null ? result : "", true);
                    } finally {
                        SkillGroupContext.clear();
                        agentSessionContext.clear();
                        AgentContext.clear();
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
        if (e instanceof com.atm.intellimate.core.exception.ToolExecutionException) {
            return false;
        }
        return e.getMessage() != null && e.getMessage().contains("429");
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

    // ─── Model resolution ───

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

    // ─── ChatOptions building ───

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

    // ─── logging / prompt building ───

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

    private static final int TOTAL_MAX_CHARS = 150_000;

    private String buildSystemPrompt(IntelliMateProperties.Agent agentConfig,
                                     List<SkillContentProvider.SkillSummary> skillSummaries,
                                     boolean parallelEnabled,
                                     String planContext,
                                     boolean forcePlan,
                                     String skillGroupsEnabled) {
        StringBuilder sb = new StringBuilder();

        appendSection(sb, "soul", agentConfig.getSoulMd());
        appendSection(sb, "agents", agentConfig.getAgentsMd());

        String skillsSection = buildSkillsDiscovery(skillSummaries, skillGroupsEnabled);
        if (skillsSection != null && !skillsSection.isBlank()) {
            appendSection(sb, "skills", skillsSection);
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

    private String buildSkillsDiscovery(List<SkillContentProvider.SkillSummary> skills, String skillGroupsEnabled) {
        if (skillContentProvider == null) return null;

        if (skills != null && !skills.isEmpty()) {
            String basePath = skillContentProvider.getSkillsBasePath();
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

        if (skillGroupsEnabled != null && !skillGroupsEnabled.isBlank()) {
            List<SkillContentProvider.SkillGroupSummary> allGroups = skillContentProvider.listGroups();

            if (allGroups.isEmpty()) {
                log.warn("skillGroupsEnabled='{}' is set but no enabled skill groups found in DB — " +
                        "check that skill_group table has data and enabled=1", skillGroupsEnabled);
                return null;
            }

            List<SkillContentProvider.SkillGroupSummary> groups = allGroups;
            if (!"full".equalsIgnoreCase(skillGroupsEnabled.trim())) {
                Set<String> allowedNames = parseJsonStringArray(skillGroupsEnabled);
                if (!allowedNames.isEmpty()) {
                    groups = allGroups.stream()
                            .filter(g -> allowedNames.contains(g.name()))
                            .toList();
                    if (groups.isEmpty()) {
                        log.warn("skillGroupsEnabled='{}' matched no groups. Available groups: {}",
                                skillGroupsEnabled,
                                allGroups.stream().map(SkillContentProvider.SkillGroupSummary::name).toList());
                        return null;
                    }
                } else {
                    log.warn("skillGroupsEnabled='{}' parsed to empty set, no groups will be shown in prompt", skillGroupsEnabled);
                    return null;
                }
            }

            if (!groups.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append(PromptLoader.load("prompts/skills-discovery.md"));
                for (var g : groups) {
                    sb.append("- **").append(g.name()).append("**");
                    if (g.displayName() != null && !g.displayName().equals(g.name())) {
                        sb.append(" (").append(g.displayName()).append(")");
                    }
                    sb.append(": ").append(g.description() != null ? g.description() : "");
                    sb.append(" [").append(g.skillCount()).append(" 个技能]\n");
                }
                return sb.toString();
            }
        }

        return null;
    }

    private void setupSkillGroupContext(Long sessionId, String skillGroupsEnabled) {
        Set<String> resolved;
        if (skillGroupsEnabled == null || skillGroupsEnabled.isBlank()) {
            resolved = Set.of();
        } else if ("full".equalsIgnoreCase(skillGroupsEnabled.trim())) {
            resolved = null;
        } else {
            Set<String> allowed = parseJsonStringArray(skillGroupsEnabled);
            resolved = allowed.isEmpty() ? Set.of() : allowed;
        }
        SkillGroupContext.set(resolved);
        if (sessionId != null) {
            sessionSkillGroups.put(sessionId, resolved != null ? resolved : SKILL_GROUPS_UNRESTRICTED);
        }
    }

    private static Set<String> parseJsonStringArray(String spec) {
        try {
            String trimmed = spec.trim();
            if (trimmed.startsWith("[")) {
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                List<String> list = mapper.readValue(trimmed, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                return new java.util.HashSet<>(list);
            }
        } catch (Exception e) {
            log.warn("Failed to parse JSON string array: {}", spec);
        }
        return Set.of();
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

    /**
     * Defers episodic storage until WebSocket disconnects. Updates the deferred state on every agent run
     * so that the latest conversation state is used when flush is triggered.
     */
    private void deferEpisodicStore(WorkingMemory workingMemory, LongTermMemory ltm,
                                     String userId, String agentId, Long sessionId, int minChunksForEpisodic) {
        deferredEpisodicStores.put(sessionId, new DeferredEpisodicStore(workingMemory, ltm, userId, agentId, sessionId, minChunksForEpisodic));
    }

    /**
     * Asynchronously store an episodic summary of the session.
     * Uses LLM summarization when no mid-session consolidation occurred and conversation is substantial.
     */
    private void storeSessionEpisodicMemory(WorkingMemory workingMemory, LongTermMemory ltm,
                                             String userId, String agentId, Long sessionId, int minChunksForEpisodic) {
        try {
            List<MemoryChunk> chunks = workingMemory.getChunks();
            if (chunks.size() <= minChunksForEpisodic) return;

            if (workingMemory.getConsolidationCount() == 0) {
                storeSessionEpisodicViaLLM(workingMemory, ltm, userId, agentId, sessionId);
            } else {
                storeSessionEpisodicSimple(chunks, ltm, userId, agentId, sessionId);
            }
        } catch (Exception e) {
            log.warn("Error building session episodic memory: {}", e.getMessage());
        }
    }

    /**
     * High-quality episodic: call consolidation model to summarize the entire session.
     * Uses summarizeSession (processes ALL chunks) instead of tryConsolidate (which only processes old chunks).
     */
    private void storeSessionEpisodicViaLLM(WorkingMemory workingMemory, LongTermMemory ltm,
                                             String userId, String agentId, Long sessionId) {
        MemoryConsolidator consolidator = workingMemory.getConsolidator();
        if (consolidator == null) {
            consolidator = memorySystem != null ? memorySystem.getConsolidator() : null;
        }
        if (consolidator == null) {
            storeSessionEpisodicSimple(workingMemory.getChunks(), ltm, userId, agentId, sessionId);
            return;
        }

        final MemoryConsolidator effectiveConsolidator = consolidator;
        final List<MemoryChunk> allChunks = new ArrayList<>(workingMemory.getChunks());
        Mono.fromCallable(() -> effectiveConsolidator.summarizeSession(allChunks, agentId, userId))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        result -> {
                            if (result != null && !result.facts().isEmpty()) {
                                log.info("Session {} full summarization: {} facts stored", sessionId, result.facts().size());
                            } else {
                                storeSessionEpisodicSimple(allChunks, ltm, userId, agentId, sessionId);
                            }
                        },
                        e -> {
                            log.warn("Session {} full summarization failed, falling back to simple: {}",
                                    sessionId, e.getMessage());
                            storeSessionEpisodicSimple(allChunks, ltm, userId, agentId, sessionId);
                        });
    }

    private void storeSessionEpisodicSimple(List<MemoryChunk> chunks, LongTermMemory ltm,
                                             String userId, String agentId, Long sessionId) {
        StringBuilder summary = new StringBuilder();
        summary.append("Session ").append(sessionId).append(" summary: ");
        int userCount = 0, toolCount = 0;
        for (MemoryChunk c : chunks) {
            switch (c.type()) {
                case USER -> userCount++;
                case TOOL_INTERACTION -> toolCount++;
                default -> {}
            }
        }
        summary.append(userCount).append(" user turns, ").append(toolCount).append(" tool calls. ");

        for (MemoryChunk c : chunks) {
            if (c.type() == com.atm.intellimate.memory.model.ChunkType.USER) {
                String preview = c.content().length() > 100 ? c.content().substring(0, 100) + "..." : c.content();
                summary.append("Topics: ").append(preview);
                break;
            }
        }

        ExtractedFact episodic = new ExtractedFact("episodic", summary.toString(), 0.5f);
        ltm.store(episodic, userId, agentId)
                .subscribe(
                        unused -> {},
                        e -> log.warn("Failed to store session episodic memory for session {}: {}", sessionId, e.getMessage()));
    }
}
