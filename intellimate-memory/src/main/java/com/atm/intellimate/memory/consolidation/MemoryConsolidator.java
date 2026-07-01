package com.atm.intellimate.memory.consolidation;

import com.atm.intellimate.memory.longterm.LongTermMemory;
import com.atm.intellimate.memory.model.ExtractedFact;
import com.atm.intellimate.memory.model.MemoryChunk;
import com.atm.intellimate.memory.working.TokenEstimator;
import com.atm.intellimate.memory.working.WorkingMemory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Consolidates old, low-relevance chunks via LLM summarization + fact extraction.
 * Implements retry and model fallback strategies.
 */
public class MemoryConsolidator {

    private static final Logger log = LoggerFactory.getLogger(MemoryConsolidator.class);
    private static final int MIN_CHUNKS_FOR_CONSOLIDATION = 3;

    private final ChatModel primaryModel;
    private final ChatModel fallbackModel;
    private final LongTermMemory longTermMemory;
    private final ConsolidationPromptBuilder promptBuilder;
    private final TokenEstimator tokenEstimator;
    private final ObjectMapper objectMapper;
    private final int maxSummaryTokens;
    private final int maxRetries;
    private final long timeoutMs;
    private final String primaryModelId;
    private final String fallbackModelId;

    public MemoryConsolidator(ChatModel primaryModel,
                               ChatModel fallbackModel,
                               LongTermMemory longTermMemory,
                               TokenEstimator tokenEstimator,
                               int maxSummaryTokens,
                               int maxRetries,
                               long timeoutMs) {
        this(primaryModel, fallbackModel, longTermMemory, tokenEstimator, maxSummaryTokens, maxRetries, timeoutMs, null, null);
    }

    public MemoryConsolidator(ChatModel primaryModel,
                               ChatModel fallbackModel,
                               LongTermMemory longTermMemory,
                               TokenEstimator tokenEstimator,
                               int maxSummaryTokens,
                               int maxRetries,
                               long timeoutMs,
                               String primaryModelId,
                               String fallbackModelId) {
        this.primaryModel = primaryModel;
        this.fallbackModel = fallbackModel;
        this.longTermMemory = longTermMemory;
        this.promptBuilder = new ConsolidationPromptBuilder();
        this.tokenEstimator = tokenEstimator;
        this.objectMapper = new ObjectMapper();
        this.maxSummaryTokens = maxSummaryTokens;
        this.maxRetries = maxRetries;
        this.timeoutMs = timeoutMs;
        this.primaryModelId = primaryModelId;
        this.fallbackModelId = fallbackModelId;
    }

    /**
     * Summarize a full session: processes ALL non-system chunks for end-of-session fact extraction.
     * Unlike tryConsolidate, this does NOT modify the working memory or apply candidate selection.
     */
    public ConsolidationResult summarizeSession(List<MemoryChunk> allChunks, String agentId, String userId) {
        List<MemoryChunk> meaningful = allChunks.stream()
                .filter(c -> c.type() != com.atm.intellimate.memory.model.ChunkType.SYSTEM
                        && c.type() != com.atm.intellimate.memory.model.ChunkType.RECALLED)
                .toList();
        if (meaningful.size() < 2) {
            return null;
        }

        ConsolidationResult result = doLLMConsolidate(meaningful);
        if (result == null) {
            log.warn("Session summarization: all LLM attempts failed");
            return null;
        }

        if (longTermMemory != null && !result.facts().isEmpty()) {
            Flux.fromIterable(result.facts())
                    .concatMap(fact -> longTermMemory.store(fact, userId, agentId)
                            .onErrorResume(e -> {
                                log.warn("Failed to store session fact to long-term memory", e);
                                return Mono.empty();
                            }))
                    .subscribe();
        }
        return result;
    }

    /**
     * Attempt consolidation on the working memory.
     * @return ConsolidationResult if successful, null if no consolidation possible
     */
    public ConsolidationResult tryConsolidate(WorkingMemory workingMemory, String agentId) {
        return tryConsolidate(workingMemory, agentId, "default");
    }

    public ConsolidationResult tryConsolidate(WorkingMemory workingMemory, String agentId, String userId) {
        List<MemoryChunk> candidates = workingMemory.getConsolidationCandidates(MIN_CHUNKS_FOR_CONSOLIDATION);
        if (candidates.isEmpty()) {
            return null;
        }

        ConsolidationResult result = doLLMConsolidate(candidates);
        if (result == null) {
            log.warn("[Consolidation] All attempts failed for agent={}; deferring", agentId);
            return null;
        }

        int tokensBefore = workingMemory.getTokenUsage();
        workingMemory.replaceWithConsolidated(candidates, result.summaryChunk());

        boolean factsStored = false;
        if (longTermMemory != null && !result.facts().isEmpty()) {
            factsStored = true;
            Flux.fromIterable(result.facts())
                    .concatMap(fact -> longTermMemory.store(fact, userId, agentId)
                            .onErrorResume(e -> {
                                log.warn("Failed to store extracted fact to long-term memory", e);
                                return Mono.empty();
                            }))
                    .subscribe();
        }

        List<ConsolidationResult.SourceChunkPreview> previews = candidates.stream()
                .map(c -> {
                    String preview = c.content().length() > 80
                            ? c.content().substring(0, 80) + "..."
                            : c.content();
                    return new ConsolidationResult.SourceChunkPreview(
                            c.type().name(), c.estimatedTokens(), c.importance(), preview);
                })
                .toList();

        int tokensAfter = workingMemory.getTokenUsage();
        return new ConsolidationResult(
                result.summaryChunk(),
                result.facts(),
                candidates.size(),
                tokensBefore,
                tokensAfter,
                previews,
                factsStored
        );
    }

    private ConsolidationResult doLLMConsolidate(List<MemoryChunk> chunks) {
        String prompt = promptBuilder.build(chunks, maxSummaryTokens);

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                boolean usePrimary = (attempt <= maxRetries / 2);
                ChatModel model = usePrimary ? primaryModel : effectiveFallback();
                String activeModelId = usePrimary ? primaryModelId : fallbackModelId;
                String responseText = callWithTimeoutStreaming(model, prompt, activeModelId);
                return parseConsolidationResponse(responseText, chunks);
            } catch (Exception e) {
                log.warn("Consolidation attempt {} failed: {}", attempt + 1, e.getMessage());
            }
        }

        return null;
    }

    private String callWithTimeoutStreaming(ChatModel model, String prompt, String activeModelId) throws Exception {
        Prompt p;
        if (activeModelId != null && !activeModelId.isBlank()) {
            p = new Prompt(new UserMessage(prompt), ChatOptions.builder().model(activeModelId).build());
        } else {
            p = new Prompt(new UserMessage(prompt));
        }
        java.time.Duration timeout = timeoutMs > 0
                ? java.time.Duration.ofMillis(timeoutMs)
                : java.time.Duration.ofSeconds(120);
        String result = model.stream(p)
                .map(r -> {
                    if (r.getResult() != null && r.getResult().getOutput() != null) {
                        String text = r.getResult().getOutput().getText();
                        return text != null ? text : "";
                    }
                    return "";
                })
                .collectList()
                .map(parts -> String.join("", parts))
                .block(timeout);
        if (result == null || result.isBlank()) {
            throw new RuntimeException("Empty response from consolidation model (streaming)");
        }
        return result;
    }

    private ChatModel effectiveFallback() {
        return fallbackModel != null ? fallbackModel : primaryModel;
    }

    private ConsolidationResult parseConsolidationResponse(String responseText, List<MemoryChunk> originalChunks) {
        try {
            String json = extractJson(responseText);
            JsonNode root = objectMapper.readTree(json);

            String summary = root.path("summary").asText("[consolidation summary]");
            int summaryTokens = tokenEstimator.estimate(summary);
            float avgImportance = (float) originalChunks.stream()
                    .mapToDouble(MemoryChunk::importance).average().orElse(0.5);
            MemoryChunk summaryChunk = MemoryChunk.consolidated(summary, summaryTokens, avgImportance);

            List<ExtractedFact> facts = new ArrayList<>();
            JsonNode memoriesNode = root.path("memories");
            if (memoriesNode.isArray()) {
                for (JsonNode mem : memoriesNode) {
                    String topic = mem.path("topic").asText("");
                    List<String> keywords = new ArrayList<>();
                    mem.path("keywords").forEach(k -> keywords.add(k.asText()));
                    String content = mem.path("content").asText("");
                    String enriched = mem.path("enriched").asText("");
                    float importance = (float) mem.path("importance").asDouble(0.5);
                    facts.add(new ExtractedFact(topic, keywords, content, enriched, importance));
                }
            }

            JsonNode factsNode = root.path("facts");
            if (facts.isEmpty() && factsNode.isArray()) {
                for (JsonNode f : factsNode) {
                    facts.add(ExtractedFact.legacy(
                            f.path("type").asText("semantic"),
                            f.path("content").asText(""),
                            (float) f.path("importance").asDouble(0.5)
                    ));
                }
            }

            return new ConsolidationResult(
                    summaryChunk,
                    facts,
                    originalChunks.size(),
                    -1,
                    -1
            );
        } catch (Exception e) {
            log.warn("Failed to parse consolidation response: {}", e.getMessage());
            String fallbackSummary = "[Consolidation summary - parse failed] " +
                    responseText.substring(0, Math.min(500, responseText.length()));
            int tokens = tokenEstimator.estimate(fallbackSummary);
            return new ConsolidationResult(
                    MemoryChunk.consolidated(fallbackSummary, tokens, 0.5f),
                    List.of(),
                    originalChunks.size(),
                    -1,
                    -1
            );
        }
    }

    private String extractJson(String text) {
        int jsonStart = text.indexOf('{');
        int jsonEnd = text.lastIndexOf('}');
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            return text.substring(jsonStart, jsonEnd + 1);
        }
        return text;
    }
}
