package com.atm.javaclaw.gateway.http;

import com.atm.javaclaw.agent.runtime.AgentEvent;
import com.atm.javaclaw.agent.runtime.AgentRuntime;
import com.atm.javaclaw.gateway.entity.AgentMemoryArchiveEntity;
import com.atm.javaclaw.gateway.entity.AgentMemoryEntity;
import com.atm.javaclaw.gateway.repository.AgentMemoryArchiveRepository;
import com.atm.javaclaw.gateway.repository.AgentMemoryRepository;
import com.atm.javaclaw.gateway.service.MemoryConfigService;
import com.atm.javaclaw.memory.longterm.LongTermMemory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
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

    public MemoryController(MemoryConfigService configService,
                            LongTermMemory longTermMemory,
                            AgentMemoryRepository agentMemoryRepository,
                            AgentMemoryArchiveRepository agentMemoryArchiveRepository) {
        this.configService = configService;
        this.longTermMemory = longTermMemory;
        this.agentMemoryRepository = agentMemoryRepository;
        this.agentMemoryArchiveRepository = agentMemoryArchiveRepository;
    }

    @GetMapping("/config")
    public Mono<Map<String, Object>> getConfig() {
        return configService.resolveGrouped().map(items -> {
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
            return grouped;
        });
    }

    @PutMapping("/config")
    public Mono<Map<String, String>> updateConfig(@RequestBody Map<String, String> updates) {
        return configService.updateConfig(updates)
                .then(Mono.just(Map.of("success", "true")));
    }

    @PostMapping("/config/reset")
    public Mono<Map<String, String>> resetConfig() {
        return configService.resetToDefaults()
                .then(Mono.just(Map.of("success", "true")));
    }

    @GetMapping("/long-term")
    public Mono<List<Map<String, Object>>> getLongTermMemories(
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
        }).collectList();
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
    public Mono<Map<String, Object>> getLongTermMemoryById(@PathVariable Long id) {
        return agentMemoryRepository.findById(id)
                .map(this::toLongTermMap)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory not found")));
    }

    @GetMapping("/archive")
    public Mono<List<Map<String, Object>>> getArchivedMemories(
            @RequestParam(defaultValue = "default") String userId,
            @RequestParam(defaultValue = "default") String agentId) {
        return agentMemoryArchiveRepository.findByUserIdAndAgentId(userId, agentId)
                .map(this::toArchiveMap)
                .collectList();
    }

    @DeleteMapping("/long-term/{id}")
    public Mono<Map<String, String>> deleteLongTermMemory(@PathVariable Long id) {
        return agentMemoryRepository.existsById(id)
                .flatMap(exists -> {
                    if (!exists) {
                        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Memory not found"));
                    }
                    return longTermMemory.deleteById(id)
                            .then(Mono.just(Map.of("success", "true")));
                });
    }

    @GetMapping("/stats")
    public Mono<Map<String, Object>> getStats(
            @RequestParam(defaultValue = "default") String userId,
            @RequestParam(defaultValue = "default") String agentId) {
        return longTermMemory.getStats(userId, agentId).map(stats -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("episodicCount", stats.episodicCount());
            m.put("semanticCount", stats.semanticCount());
            m.put("proceduralCount", stats.proceduralCount());
            m.put("totalCount", stats.totalCount());
            return m;
        });
    }

    @GetMapping("/working/{sessionId}")
    public Mono<Map<String, Object>> getWorkingMemory(@PathVariable Long sessionId) {
        AgentEvent.MemorySnapshot snap = AgentRuntime.getLatestSnapshot(sessionId);
        if (snap == null) {
            return Mono.just(Map.of("message", "No active working memory for session"));
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
        return Mono.just(body);
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
