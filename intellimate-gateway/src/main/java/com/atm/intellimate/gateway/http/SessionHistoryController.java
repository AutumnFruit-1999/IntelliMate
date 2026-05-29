package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.entity.SessionEntity;
import com.atm.intellimate.gateway.entity.TranscriptMessageEntity;
import com.atm.intellimate.gateway.repository.TranscriptMessageRepository;
import com.atm.intellimate.gateway.session.SessionManager;
import com.atm.intellimate.memory.longterm.LongTermMemory;
import com.atm.intellimate.memory.model.ExtractedFact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/sessions")
public class SessionHistoryController {

    private static final Logger log = LoggerFactory.getLogger(SessionHistoryController.class);

    private final SessionManager sessionManager;
    private final TranscriptMessageRepository transcriptRepository;
    private final LongTermMemory longTermMemory;

    public SessionHistoryController(SessionManager sessionManager,
                                     TranscriptMessageRepository transcriptRepository,
                                     @org.springframework.beans.factory.annotation.Autowired(required = false)
                                     LongTermMemory longTermMemory) {
        this.sessionManager = sessionManager;
        this.transcriptRepository = transcriptRepository;
        this.longTermMemory = longTermMemory;
    }

    @GetMapping("/{agentName}/messages")
    public Mono<Map<String, Object>> getActiveMessages(
            @PathVariable String agentName,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) Long before) {
        return sessionManager.findActiveSession(agentName)
                .flatMap(session -> {
                    var query = before != null
                            ? transcriptRepository.findRecentBySessionIdBeforeId(session.getId(), before, limit)
                            : transcriptRepository.findRecentBySessionId(session.getId(), limit);
                    return query.collectList().map(messages -> {
                        messages.sort(Comparator.comparing(TranscriptMessageEntity::getCreatedAt));
                        List<Map<String, Object>> dtos = messages.stream()
                                .map(this::toMessageDto)
                                .collect(Collectors.toList());
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("messages", dtos);
                        result.put("hasMore", messages.size() >= limit);
                        return result;
                    });
                })
                .defaultIfEmpty(Map.of("messages", List.of(), "hasMore", false));
    }

    @PostMapping("/{agentName}/clear")
    public Mono<Map<String, Object>> clearSession(@PathVariable String agentName) {
        return sessionManager.findActiveSession(agentName)
                .flatMap(session -> persistFromTranscript(session).thenReturn(session))
                .then(sessionManager.archiveAndCreateNew(agentName))
                .map(newSession -> Map.<String, Object>of(
                        "success", true,
                        "newSessionId", newSession.getId()
                ));
    }

    private Mono<Void> persistFromTranscript(SessionEntity session) {
        if (longTermMemory == null) {
            log.warn("persistFromTranscript: longTermMemory is null, skip");
            return Mono.empty();
        }
        log.info("persistFromTranscript: reading transcript for session {}", session.getId());
        return transcriptRepository.findRecentBySessionId(session.getId(), 200)
                .collectList()
                .flatMap(messages -> {
                    log.info("persistFromTranscript: session {} has {} messages in transcript", session.getId(), messages.size());
                    if (messages.isEmpty()) {
                        log.info("persistFromTranscript: no messages, skip");
                        return Mono.<Void>empty();
                    }
                    Collections.reverse(messages);
                    StringBuilder summary = new StringBuilder();
                    int userCount = 0;
                    for (var msg : messages) {
                        if ("user".equals(msg.getRole())) {
                            userCount++;
                            if (summary.length() < 500 && msg.getContent() != null) {
                                if (!summary.isEmpty()) summary.append("; ");
                                String content = msg.getContent().length() > 100
                                        ? msg.getContent().substring(0, 100) : msg.getContent();
                                summary.append(content);
                            }
                        }
                    }
                    if (userCount == 0) {
                        log.info("persistFromTranscript: no user messages, skip");
                        return Mono.<Void>empty();
                    }
                    String episodicContent = String.format("Session %d: %d messages, topics: %s",
                            session.getId(), messages.size(), summary);
                    String userId = session.getContextId() != null ? session.getContextId() : "default";
                    String agentId = session.getAgentName() != null ? session.getAgentName() : "default";
                    ExtractedFact fact = new ExtractedFact("episodic", episodicContent, 0.5f);
                    log.info("persistFromTranscript: storing episodic memory for session {} (userId={}, agentId={}, {} user msgs)",
                            session.getId(), userId, agentId, userCount);
                    return longTermMemory.store(fact, userId, agentId)
                            .doOnSuccess(v -> log.info("persistFromTranscript: store SUCCESS for session {}", session.getId()))
                            .doOnError(e -> log.error("persistFromTranscript: store FAILED for session {}: {}", session.getId(), e.getMessage()));
                });
    }

    @GetMapping("/{agentName}/archived")
    public Mono<Map<String, Object>> getArchivedSessions(
            @PathVariable String agentName,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return sessionManager.getArchivedSessions(agentName, limit, offset)
                .map(this::toSessionSummaryDto)
                .collectList()
                .zipWith(sessionManager.countArchivedSessions(agentName))
                .map(tuple -> {
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("sessions", tuple.getT1());
                    result.put("total", tuple.getT2());
                    result.put("hasMore", offset + limit < tuple.getT2());
                    return result;
                });
    }

    @DeleteMapping("/by-id/{sessionId}")
    public Mono<Map<String, Object>> deleteSession(@PathVariable Long sessionId) {
        return sessionManager.deleteArchivedSession(sessionId)
                .thenReturn(Map.<String, Object>of("success", true));
    }

    @GetMapping("/{agentName}/search")
    public Mono<Map<String, Object>> searchMessages(
            @PathVariable String agentName,
            @RequestParam String q,
            @RequestParam(defaultValue = "20") int limit) {
        if (q == null || q.isBlank()) {
            return Mono.just(Map.of("results", List.of()));
        }
        return transcriptRepository.searchByAgentNameAndKeyword(agentName, q.trim(), limit)
                .map(this::toMessageDto)
                .collectList()
                .map(results -> Map.<String, Object>of("results", results));
    }

    @GetMapping("/by-id/{sessionId}/messages")
    public Mono<Map<String, Object>> getSessionMessages(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "100") int limit) {
        return transcriptRepository.findRecentBySessionId(sessionId, limit)
                .collectList()
                .map(messages -> {
                    messages.sort(Comparator.comparing(TranscriptMessageEntity::getCreatedAt));
                    List<Map<String, Object>> dtos = messages.stream()
                            .map(this::toMessageDto)
                            .collect(Collectors.toList());
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("messages", dtos);
                    result.put("hasMore", messages.size() >= limit);
                    return result;
                });
    }

    private Map<String, Object> toMessageDto(TranscriptMessageEntity entity) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", entity.getId());
        dto.put("role", entity.getRole());
        dto.put("content", entity.getContent());
        dto.put("createdAt", entity.getCreatedAt().toString());
        dto.put("toolName", entity.getToolName());
        if (entity.getMetadataJson() != null && !entity.getMetadataJson().isBlank()) {
            dto.put("metadata", entity.getMetadataJson());
        }
        if (entity.getSourceChannel() != null && !entity.getSourceChannel().isBlank()) {
            dto.put("sourceChannel", entity.getSourceChannel());
        }
        return dto;
    }

    private Map<String, Object> toSessionSummaryDto(SessionEntity entity) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", entity.getId());
        dto.put("title", entity.getTitle());
        dto.put("agentName", entity.getAgentName());
        dto.put("lastActiveAt", entity.getLastActiveAt().toString());
        dto.put("createdAt", entity.getCreatedAt().toString());
        return dto;
    }
}
