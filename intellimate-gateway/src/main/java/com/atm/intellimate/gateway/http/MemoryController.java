package com.atm.intellimate.gateway.http;

import com.atm.intellimate.agent.runtime.AgentEvent;
import com.atm.intellimate.agent.runtime.AgentRuntime;
import com.atm.intellimate.core.exception.ErrorCode;
import com.atm.intellimate.core.exception.IntelliMateException;
import com.atm.intellimate.gateway.dto.ApiResponse;
import com.atm.intellimate.gateway.entity.AgentMemoryArchiveEntity;
import com.atm.intellimate.gateway.entity.AgentMemoryEntity;
import com.atm.intellimate.gateway.repository.AgentMemoryArchiveRepository;
import com.atm.intellimate.gateway.repository.AgentMemoryRepository;
import com.atm.intellimate.gateway.repository.SessionRepository;
import com.atm.intellimate.gateway.repository.TranscriptMessageRepository;
import com.atm.intellimate.gateway.service.MemoryConfigService;
import com.atm.intellimate.memory.longterm.LongTermMemory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    private final MemoryConfigService configService;
    private final LongTermMemory longTermMemory;
    private final AgentMemoryRepository agentMemoryRepository;
    private final AgentMemoryArchiveRepository agentMemoryArchiveRepository;
    private final SessionRepository sessionRepository;
    private final TranscriptMessageRepository transcriptRepository;

    public MemoryController(MemoryConfigService configService,
                            LongTermMemory longTermMemory,
                            AgentMemoryRepository agentMemoryRepository,
                            AgentMemoryArchiveRepository agentMemoryArchiveRepository,
                            SessionRepository sessionRepository,
                            TranscriptMessageRepository transcriptRepository) {
        this.configService = configService;
        this.longTermMemory = longTermMemory;
        this.agentMemoryRepository = agentMemoryRepository;
        this.agentMemoryArchiveRepository = agentMemoryArchiveRepository;
        this.sessionRepository = sessionRepository;
        this.transcriptRepository = transcriptRepository;
    }

    @GetMapping("/config")
    public Mono<ApiResponse<Map<String, Object>>> getConfig(
            @RequestParam(defaultValue = "_global_") String agentName) {
        return configService.resolveGroupedForAgent(agentName).map(items -> {
            Map<String, Object> grouped = new LinkedHashMap<>();
            Map<String, Object> working = new LinkedHashMap<>();
            Map<String, Object> consolidation = new LinkedHashMap<>();
            Map<String, Object> longTerm = new LinkedHashMap<>();

            items.forEach((key, item) -> {
                Map<String, Object> entry = Map.of(
                        "value", item.value(),
                        "default", item.defaultValue(),
                        "description", item.description(),
                        "type", item.type()
                );
                if (key.startsWith("working.")) {
                    working.put(key.substring("working.".length()), entry);
                } else if (key.startsWith("consolidation.")) {
                    consolidation.put(key.substring("consolidation.".length()), entry);
                } else if (key.startsWith("long_term.")) {
                    longTerm.put(key.substring("long_term.".length()), entry);
                }
            });

            grouped.put("working", working);
            grouped.put("consolidation", consolidation);
            grouped.put("longTerm", longTerm);
            return ApiResponse.ok(grouped);
        });
    }

    @PutMapping("/config")
    public Mono<ApiResponse<Map<String, String>>> updateConfig(
            @RequestParam(defaultValue = "_global_") String agentName,
            @RequestBody Map<String, String> updates) {
        return configService.updateConfigForAgent(agentName, updates)
                .then(Mono.just(ApiResponse.ok(Map.of("success", "true"))));
    }

    @PostMapping("/config/reset")
    public Mono<ApiResponse<Map<String, String>>> resetConfig(
            @RequestParam(defaultValue = "_global_") String agentName) {
        return configService.resetToDefaultsForAgent(agentName)
                .then(Mono.just(ApiResponse.ok(Map.of("success", "true"))));
    }

    @GetMapping("/long-term")
    public Mono<ApiResponse<List<Map<String, Object>>>> getLongTermMemories(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "default") String agentId) {
        var flux = resolveMemoryQuery(userId, agentId, type);
        return flux.map(e -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getId());
            m.put("userId", e.getUserId());
            m.put("agentId", e.getAgentId());
            m.put("memoryType", e.getMemoryType());
            m.put("content", e.getContent());
            m.put("importance", e.getImportance());
            m.put("accessCount", e.getAccessCount());
            m.put("lastAccessedAt", e.getLastAccessedAt());
            m.put("createdAt", e.getCreatedAt());
            return m;
        }).collectList().map(ApiResponse::ok);
    }

    private Flux<AgentMemoryEntity> resolveMemoryQuery(String userId, String agentId, String type) {
        boolean hasUserId = userId != null && !userId.isBlank() && !"all".equals(userId);
        if (hasUserId) {
            return type != null
                    ? agentMemoryRepository.findByUserIdAndAgentIdAndMemoryType(userId, agentId, type)
                    : agentMemoryRepository.findByUserIdAndAgentId(userId, agentId);
        }
        return type != null
                ? agentMemoryRepository.findByAgentIdAndMemoryType(agentId, type)
                : agentMemoryRepository.findByAgentId(agentId);
    }

    @GetMapping("/long-term/{id}")
    public Mono<ApiResponse<Map<String, Object>>> getLongTermMemoryById(@PathVariable Long id) {
        return agentMemoryRepository.findById(id)
                .map(this::toLongTermMap)
                .map(ApiResponse::ok)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SESSION_NOT_FOUND, "Memory not found")));
    }

    @GetMapping("/archive")
    public Mono<ApiResponse<List<Map<String, Object>>>> getArchivedMemories(
            @RequestParam(defaultValue = "default") String userId,
            @RequestParam(defaultValue = "default") String agentId) {
        return agentMemoryArchiveRepository.findByUserIdAndAgentId(userId, agentId)
                .map(this::toArchiveMap)
                .collectList()
                .map(ApiResponse::ok);
    }

    @DeleteMapping("/long-term/{id}")
    public Mono<ApiResponse<Map<String, String>>> deleteLongTermMemory(@PathVariable Long id) {
        return agentMemoryRepository.existsById(id)
                .flatMap(exists -> {
                    if (!exists) {
                        return Mono.error(new IntelliMateException(ErrorCode.SESSION_NOT_FOUND, "Memory not found"));
                    }
                    return longTermMemory.deleteById(id)
                            .then(Mono.just(ApiResponse.ok(Map.of("success", "true"))));
                });
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getStats(
            @RequestParam(defaultValue = "default") String userId,
            @RequestParam(defaultValue = "default") String agentId) {
        return longTermMemory.getStats(userId, agentId).map(stats -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("episodicCount", stats.episodicCount());
            m.put("semanticCount", stats.semanticCount());
            m.put("proceduralCount", stats.proceduralCount());
            m.put("totalCount", stats.totalCount());
            return ApiResponse.ok(m);
        });
    }

    @GetMapping("/working/{sessionId}")
    public Mono<ApiResponse<Map<String, Object>>> getWorkingMemory(@PathVariable Long sessionId) {
        return Mono.just(buildSnapshotResponse(AgentRuntime.getLatestSnapshot(sessionId)));
    }

    @GetMapping("/working/by-agent/{agentName}")
    public Mono<ApiResponse<Map<String, Object>>> getWorkingMemoryByAgent(@PathVariable String agentName) {
        return sessionRepository.findActiveByAgentName(agentName)
                .flatMap(session -> {
                    AgentEvent.MemorySnapshot snap = AgentRuntime.getLatestSnapshot(session.getId());
                    if (snap != null) {
                        return Mono.just(buildSnapshotResponse(snap));
                    }
                    return rebuildFromTranscript(session.getId());
                })
                .defaultIfEmpty(ApiResponse.ok(Map.of("message", "No active session for agent")));
    }

    private Mono<ApiResponse<Map<String, Object>>> rebuildFromTranscript(Long sessionId) {
        return transcriptRepository.findRecentBySessionId(sessionId, 200)
                .collectList()
                .map(messages -> {
                    java.util.Collections.reverse(messages);
                    List<Map<String, Object>> chunks = new java.util.ArrayList<>();
                    int totalTokens = 0;
                    for (var msg : messages) {
                        if (msg.getContent() == null || msg.getContent().isBlank()) continue;
                        int estimatedTokens = msg.getContent().length() / 3;
                        totalTokens += estimatedTokens;
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("id", "transcript-" + msg.getId());
                        row.put("type", "user".equals(msg.getRole()) ? "USER_MESSAGE" : "ASSISTANT_RESPONSE");
                        row.put("category", "conversation");
                        row.put("importance", 0.5f);
                        row.put("tokens", estimatedTokens);
                        row.put("contentPreview", msg.getContent().length() > 80
                                ? msg.getContent().substring(0, 80) + "..."
                                : msg.getContent());
                        row.put("createdAt", msg.getCreatedAt() != null ? msg.getCreatedAt().toString() : "");
                        chunks.add(row);
                    }
                    int tokenBudget = 128000;
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("tokenBudget", tokenBudget);
                    body.put("tokenUsed", totalTokens);
                    body.put("tokenEstimated", totalTokens);
                    body.put("usageRatio", tokenBudget > 0 ? (float) totalTokens / tokenBudget : 0f);
                    body.put("chunkCount", chunks.size());
                    body.put("chunks", chunks);
                    body.put("source", "transcript");
                    return ApiResponse.ok(body);
                });
    }

    private ApiResponse<Map<String, Object>> buildSnapshotResponse(AgentEvent.MemorySnapshot snap) {
        if (snap == null) {
            return ApiResponse.ok(Map.of("message", "No active working memory for session"));
        }
        List<Map<String, Object>> chunks = snap.chunks().stream()
                .map(c -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("id", c.id());
                    row.put("type", c.type());
                    row.put("category", c.category());
                    row.put("importance", c.importance());
                    row.put("tokens", c.tokens());
                    row.put("contentPreview", c.contentPreview());
                    row.put("createdAt", c.createdAt());
                    return row;
                })
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tokenBudget", snap.tokenBudget());
        body.put("tokenUsed", snap.tokenUsed());
        body.put("tokenEstimated", snap.tokenEstimated());
        body.put("usageRatio", snap.usageRatio());
        body.put("chunkCount", snap.chunkCount());
        body.put("chunks", chunks);
        return ApiResponse.ok(body);
    }

    private Map<String, Object> toLongTermMap(AgentMemoryEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("userId", e.getUserId());
        m.put("agentId", e.getAgentId());
        m.put("memoryType", e.getMemoryType());
        m.put("content", e.getContent());
        m.put("importance", e.getImportance());
        m.put("accessCount", e.getAccessCount());
        m.put("lastAccessedAt", e.getLastAccessedAt());
        m.put("createdAt", e.getCreatedAt());
        return m;
    }

    private Map<String, Object> toArchiveMap(AgentMemoryArchiveEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("userId", e.getUserId());
        m.put("agentId", e.getAgentId());
        m.put("memoryType", e.getMemoryType());
        m.put("content", e.getContent());
        m.put("importance", e.getImportance());
        m.put("accessCount", e.getAccessCount());
        m.put("lastAccessedAt", e.getLastAccessedAt());
        m.put("createdAt", e.getCreatedAt());
        m.put("archivedAt", e.getArchivedAt());
        return m;
    }
}
