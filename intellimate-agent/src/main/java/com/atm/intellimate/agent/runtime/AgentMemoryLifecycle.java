package com.atm.intellimate.agent.runtime;

import com.atm.intellimate.agent.model.ChatModelRegistry;
import com.atm.intellimate.agent.model.ResolvedModel;
import com.atm.intellimate.memory.MemorySystem;
import com.atm.intellimate.memory.config.MemoryConfigProvider;
import com.atm.intellimate.memory.config.ResolvedMemoryConfig;
import com.atm.intellimate.memory.consolidation.ConsolidationResult;
import com.atm.intellimate.memory.consolidation.MemoryConsolidator;
import com.atm.intellimate.memory.longterm.LongTermMemory;
import com.atm.intellimate.memory.model.ExtractedFact;
import com.atm.intellimate.memory.model.MemoryChunk;
import com.atm.intellimate.memory.model.ChunkType;
import com.atm.intellimate.memory.retrieval.KeywordExtractor;
import com.atm.intellimate.memory.working.TokenEstimator;
import com.atm.intellimate.memory.working.WorkingMemory;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class AgentMemoryLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryLifecycle.class);

    private final MemoryConfigProvider memoryConfigProvider;
    private final LongTermMemory longTermMemory;
    private final MemorySystem memorySystem;
    private final ChatModelRegistry chatModelRegistry;
    private final MeterRegistry meterRegistry;

    private record DeferredEpisodicStore(WorkingMemory workingMemory, LongTermMemory ltm,
                                         String userId, String agentId, Long sessionId,
                                         int minChunksForEpisodic) {}

    record MemoryInit(ResolvedMemoryConfig resolved, MemoryConsolidator consolidator) {}

    private final ConcurrentMap<Long, DeferredEpisodicStore> deferredEpisodicStores = new ConcurrentHashMap<>();

    public AgentMemoryLifecycle(@Autowired(required = false) MemoryConfigProvider memoryConfigProvider,
                                @Autowired(required = false) LongTermMemory longTermMemory,
                                @Autowired(required = false) MemorySystem memorySystem,
                                ChatModelRegistry chatModelRegistry,
                                @Autowired(required = false) MeterRegistry meterRegistry) {
        this.memoryConfigProvider = memoryConfigProvider;
        this.longTermMemory = longTermMemory;
        this.memorySystem = memorySystem;
        this.chatModelRegistry = chatModelRegistry;
        this.meterRegistry = meterRegistry;
    }

    public void recordMemoryConsolidation(String agentId) {
        if (meterRegistry != null) {
            meterRegistry.counter("memory.consolidation.triggered",
                    "agent", agentId).increment();
        }
    }

    public void recordWorkingMemoryUsage(String agentId, float usageRatio) {
        if (meterRegistry != null) {
            io.micrometer.core.instrument.Gauge.builder("memory.working.usage_ratio",
                            () -> (double) usageRatio)
                    .tag("agent", agentId)
                    .register(meterRegistry);
        }
    }

    public void recordLongTermRetrievalLatency(String agentId, long durationMs) {
        if (meterRegistry != null) {
            meterRegistry.timer("memory.longterm.retrieval.latency", "agent", agentId)
                    .record(java.time.Duration.ofMillis(durationMs));
        }
    }

    public void recordLongTermStore(String type) {
        if (meterRegistry != null) {
            meterRegistry.counter("memory.longterm.store.count", "type", type).increment();
        }
    }

    private void recordEpisodicStored(String agentId) {
        if (meterRegistry != null) {
            meterRegistry.counter("memory.longterm.store.count",
                    "type", "episodic").increment();
        }
    }

    /**
     * Called on WebSocket disconnect to flush deferred episodic memory for the session.
     * Only stores if chunks > 4 and no prior episodic was generated during this session.
     */
    public boolean flushDeferredEpisodicMemory(Long sessionId) {
        DeferredEpisodicStore deferred = deferredEpisodicStores.remove(sessionId);
        if (deferred == null) {
            return false;
        }
        storeSessionEpisodicMemory(deferred.workingMemory(), deferred.ltm(),
                deferred.userId(), deferred.agentId(), deferred.sessionId(), deferred.minChunksForEpisodic());
        return true;
    }

    /**
     * Resolves DB-backed memory config once per run for WorkingMemory thresholds,
     * consolidator construction, and long-term retrieval settings.
     */
    public Mono<MemoryInit> loadMemoryInitReactive(TokenEstimator tokenEstimator, String agentName) {
        if (memoryConfigProvider == null) {
            return Mono.just(new MemoryInit(null, null));
        }
        return memoryConfigProvider.resolveForAgent(agentName)
                .timeout(Duration.ofSeconds(2))
                .map(memConfig -> new MemoryInit(memConfig, createMemoryConsolidator(memConfig, tokenEstimator)))
                .defaultIfEmpty(new MemoryInit(null, null))
                .onErrorResume(e -> {
                    log.warn("Failed to load memory config for agent '{}': {}", agentName, e.getMessage());
                    return Mono.just(new MemoryInit(null, null));
                });
    }

    public static AgentEvent.ConsolidationTriggered toConsolidationTriggeredEvent(
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

    /**
     * Defers episodic storage until WebSocket disconnects. Updates the deferred state on every agent run
     * so that the latest conversation state is used when flush is triggered.
     */
    public void deferEpisodicStore(WorkingMemory workingMemory, LongTermMemory ltm,
                                   String userId, String agentId, Long sessionId, int minChunksForEpisodic) {
        deferredEpisodicStores.put(sessionId, new DeferredEpisodicStore(workingMemory, ltm, userId, agentId, sessionId, minChunksForEpisodic));
    }

    /**
     * Store episodic memory from pre-built MemoryChunks (e.g. converted from transcript messages).
     * Always tries LLM summarization first; falls back to storing full conversation text.
     * Creates its own consolidator per-call because the global MemorySystem singleton has null consolidator
     * (consolidator needs a request-scoped ChatModel resolved from per-agent config).
     */
    public void storeEpisodicFromChunks(List<MemoryChunk> chunks, String userId, String agentId, Long sessionId) {
        if (chunks == null || chunks.size() < 2) {
            return;
        }

        LongTermMemory ltm = longTermMemory;
        TokenEstimator tokenEstimator = memorySystem != null ? memorySystem.getTokenEstimator() : new TokenEstimator();

        if (memoryConfigProvider != null) {
            memoryConfigProvider.resolveForAgent(agentId)
                    .timeout(Duration.ofSeconds(2))
                    .subscribe(
                            memConfig -> {
                                if (ltm != null) {
                                    ltm.updateConfig(memConfig);
                                }
                                MemoryConsolidator consolidator = createMemoryConsolidator(memConfig, tokenEstimator);
                                if (consolidator != null) {
                                    doLLMSummarization(consolidator, chunks, ltm, userId, agentId, sessionId);
                                } else {
                                    log.warn("[记忆摘要] agent='{}', session={}, consolidator 创建失败, 回退存储原始对话",
                                            agentId, sessionId);
                                    storeFullConversationFallback(chunks, ltm, userId, agentId, sessionId);
                                }
                            },
                            e -> {
                                log.warn("[记忆摘要] agent='{}', session={}, 配置加载失败: {}, 回退存储原始对话",
                                        agentId, sessionId, e.getMessage());
                                storeFullConversationFallback(chunks, ltm, userId, agentId, sessionId);
                            });
        } else {
            log.warn("[记忆摘要] agent='{}', session={}, memoryConfigProvider 为 null, 回退存储原始对话",
                    agentId, sessionId);
            storeFullConversationFallback(chunks, ltm, userId, agentId, sessionId);
        }
    }

    private void doLLMSummarization(MemoryConsolidator consolidator, List<MemoryChunk> chunks,
                                     LongTermMemory ltm, String userId, String agentId, Long sessionId) {
        Mono.fromCallable(() -> consolidator.summarizeSession(chunks, agentId, userId))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        result -> {
                            if (result != null && !result.facts().isEmpty()) {
                                log.info("[记忆摘要] agent='{}', session={}, 提取 {} 条记忆",
                                        agentId, sessionId, result.facts().size());
                                recordEpisodicStored(agentId);
                            } else {
                                log.warn("[记忆摘要] agent='{}', session={}, LLM 摘要未返回事实, 回退存储原始对话",
                                        agentId, sessionId);
                                storeFullConversationFallback(chunks, ltm, userId, agentId, sessionId);
                            }
                        },
                        e -> {
                            log.warn("[记忆摘要] agent='{}', session={}, LLM 摘要失败: {}, 回退存储原始对话",
                                    agentId, sessionId, e.getMessage());
                            storeFullConversationFallback(chunks, ltm, userId, agentId, sessionId);
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

    /**
     * Asynchronously store an episodic summary of the session.
     * Uses LLM summarization when no mid-session consolidation occurred and conversation is substantial.
     */
    void storeSessionEpisodicMemory(WorkingMemory workingMemory, LongTermMemory ltm,
                                    String userId, String agentId, Long sessionId, int minChunksForEpisodic) {
        log.info("[记忆存储] 开始会话记忆存储 agent='{}', session={}", agentId, sessionId);
        try {
            List<MemoryChunk> chunks = workingMemory.getChunks();
            if (chunks.isEmpty()) {
                return;
            }
            long nonSystemCount = chunks.stream()
                    .filter(c -> c.type() != com.atm.intellimate.memory.model.ChunkType.SYSTEM
                            && c.type() != com.atm.intellimate.memory.model.ChunkType.RECALLED)
                    .count();
            if (nonSystemCount < minChunksForEpisodic) {
                return;
            }

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
                                log.info("[记忆存储] agent='{}', session={}, LLM 摘要提取 {} 条记忆",
                                        agentId, sessionId, result.facts().size());
                                recordEpisodicStored(agentId);
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

    /**
     * Fallback: store the actual conversation content when LLM summarization is unavailable.
     * Keeps user+assistant dialogue so the LLM can understand context when recalled.
     */
    private void storeSessionEpisodicSimple(List<MemoryChunk> chunks, LongTermMemory ltm,
                                            String userId, String agentId, Long sessionId) {
        storeFullConversationFallback(chunks, ltm, userId, agentId, sessionId);
    }

    private void storeFullConversationFallback(List<MemoryChunk> chunks, LongTermMemory ltm,
                                                String userId, String agentId, Long sessionId) {
        StringBuilder conversation = new StringBuilder();
        for (MemoryChunk c : chunks) {
            if (c.type() == ChunkType.SYSTEM || c.type() == ChunkType.RECALLED) continue;
            String role = switch (c.type()) {
                case USER -> "用户";
                case ASSISTANT, CONSOLIDATED -> "助手";
                case TOOL_INTERACTION -> "工具";
                default -> "系统";
            };
            String text = c.content();
            if (text.length() > 300) {
                text = text.substring(0, 300) + "...";
            }
            conversation.append(role).append(": ").append(text).append("\n");
        }

        String conversationText = conversation.toString();
        if (conversationText.length() > 2000) {
            conversationText = conversationText.substring(conversationText.length() - 2000);
            int firstNewline = conversationText.indexOf('\n');
            if (firstNewline > 0) {
                conversationText = conversationText.substring(firstNewline + 1);
            }
        }

        String outcome = detectSessionOutcome(chunks);
        float importance = "success".equals(outcome) ? 0.6f : 0.5f;
        List<String> topTopics = extractTopTopicsFromUserChunks(chunks, 3);
        String metadataJson = buildEpisodicMetadataJson(topTopics, outcome);

        String episodicContent = String.format("对话记录 (session %d):\n%s", sessionId, conversationText);
        ExtractedFact episodic = ExtractedFact.legacy("episodic", episodicContent, importance);
        ltm.store(episodic, userId, agentId, metadataJson)
                .subscribe(
                        unused -> recordEpisodicStored(agentId),
                        e -> log.warn("Failed to store conversation episodic for session {}: {}", sessionId, e.getMessage()));
    }

    private static List<String> extractTopTopicsFromUserChunks(List<MemoryChunk> chunks, int topN) {
        KeywordExtractor extractor = new KeywordExtractor();
        Map<String, Integer> freq = new HashMap<>();
        for (MemoryChunk c : chunks) {
            if (c.type() == ChunkType.USER) {
                for (String kw : extractor.extract(c.content())) {
                    freq.merge(kw, 1, Integer::sum);
                }
            }
        }
        return freq.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(topN)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static String detectSessionOutcome(List<MemoryChunk> chunks) {
        MemoryChunk lastAssistant = null;
        for (int i = chunks.size() - 1; i >= 0; i--) {
            if (chunks.get(i).type() == ChunkType.ASSISTANT) {
                lastAssistant = chunks.get(i);
                break;
            }
        }
        if (lastAssistant == null) {
            return "unknown";
        }
        String content = lastAssistant.content().toLowerCase();
        if (containsAny(content, "成功", "完成", "已解决", "success", "completed", "done")) {
            return "success";
        }
        if (containsAny(content, "失败", "错误", "无法", "failed", "error", "unable")) {
            return "failure";
        }
        return "unknown";
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private static String buildEpisodicMetadataJson(List<String> topics, String outcome) {
        StringBuilder sb = new StringBuilder("{\"topics\":[");
        for (int i = 0; i < topics.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(escapeJson(topics.get(i))).append('"');
        }
        sb.append("],\"outcome\":\"").append(escapeJson(outcome)).append("\"}");
        return sb.toString();
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
}
