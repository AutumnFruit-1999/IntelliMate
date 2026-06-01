package com.atm.intellimate.gateway.service;

import com.atm.intellimate.gateway.entity.AgentMemoryEntity;
import com.atm.intellimate.gateway.repository.AgentMemoryRepository;
import com.atm.intellimate.memory.config.ResolvedMemoryConfig;
import com.atm.intellimate.memory.longterm.LongTermMemory;
import com.atm.intellimate.memory.model.ExtractedFact;
import com.atm.intellimate.memory.model.MemoryEntry;
import com.atm.intellimate.memory.retrieval.KeywordExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
public class LongTermMemoryImpl implements LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryImpl.class);
    private static final double DEDUP_SIMILARITY_THRESHOLD = 0.85;

    private final AgentMemoryRepository repository;
    private final KeywordExtractor keywordExtractor = new KeywordExtractor();

    private volatile float minFactImportance = 0.3f;
    private volatile int maxMergedContentLength = 1000;

    /** Hot tier: recently accessed memories keyed by (userId:agentId). LRU eviction via Caffeine. */
    private final Cache<String, List<MemoryEntry>> hotCache = Caffeine.newBuilder()
            .maximumSize(200)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    private record SimilarMatch(AgentMemoryEntity entity, double similarity) {}

    public LongTermMemoryImpl(AgentMemoryRepository repository) {
        this.repository = repository;
    }

    public void updateConfig(ResolvedMemoryConfig config) {
        if (config != null) {
            this.minFactImportance = config.minFactImportance();
            this.maxMergedContentLength = config.maxMergedContentLength();
        }
    }

    private String cacheKey(String userId, String agentId) {
        return effectiveUserId(userId) + ":" + effectiveAgentId(agentId);
    }

    /** Invalidate hot cache for a specific scope (called on write/delete). */
    private void invalidateCache(String userId, String agentId) {
        hotCache.invalidate(cacheKey(userId, agentId));
    }

    private static String effectiveUserId(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }

    private static String effectiveAgentId(String agentId) {
        return agentId == null || agentId.isBlank() ? "default" : agentId;
    }

    @Override
    public Mono<Void> store(ExtractedFact fact, String userId, String agentId) {
        return store(fact, userId, agentId, null);
    }

    @Override
    public Mono<Void> store(ExtractedFact fact, String userId, String agentId, String metadataJson) {
        final String uid = effectiveUserId(userId);
        final String aid = effectiveAgentId(agentId);

        if (fact.importance() < minFactImportance) {
            log.debug("Fact filtered by importance: {} < {}", fact.importance(), minFactImportance);
            return Mono.empty();
        }
        if (fact.content() == null || fact.content().isBlank()) {
            log.debug("Fact filtered: content is null or blank");
            return Mono.empty();
        }

        return repository.findByUserIdAndAgentIdAndMemoryType(uid, aid, fact.type())
                .flatMap(existing -> {
                    double similarity = keywordExtractor.jaccardSimilarity(
                            existing.getContent(), fact.content());
                    if (similarity <= DEDUP_SIMILARITY_THRESHOLD) {
                        return Mono.empty();
                    }
                    return Mono.just(new SimilarMatch(existing, similarity));
                })
                .next()
                .flatMap(match -> {
                    AgentMemoryEntity existing = match.entity();
                    double similarity = match.similarity();

                    if ("semantic".equals(fact.type()) && similarity > DEDUP_SIMILARITY_THRESHOLD) {
                        existing.setContent(fact.content());
                        existing.setImportance(Math.max(existing.getImportance(), fact.importance()));
                        existing.setAccessCount(existing.getAccessCount() + 1);
                        existing.setLastAccessedAt(LocalDateTime.now());
                        return repository.save(existing).then();
                    }

                    existing.setImportance(Math.max(existing.getImportance(), fact.importance()));
                    existing.setAccessCount(existing.getAccessCount() + 1);
                    existing.setLastAccessedAt(LocalDateTime.now());
                    String mergedContent = existing.getContent();
                    if (!existing.getContent().contains(fact.content())
                            && fact.content().length() < 500) {
                        mergedContent = existing.getContent() + "\n---\n" + fact.content();
                        if (mergedContent.length() > maxMergedContentLength) {
                            mergedContent = mergedContent.substring(mergedContent.length() - maxMergedContentLength);
                            int firstSep = mergedContent.indexOf("\n---\n");
                            if (firstSep > 0) {
                                mergedContent = mergedContent.substring(firstSep + 5);
                            }
                        }
                    }
                    existing.setContent(mergedContent);
                    return repository.save(existing).then();
                })
                .switchIfEmpty(Mono.defer(() -> {
                    AgentMemoryEntity entity = new AgentMemoryEntity();
                    entity.setUserId(uid);
                    entity.setAgentId(aid);
                    entity.setMemoryType(fact.type());
                    entity.setContent(fact.content());
                    entity.setImportance(fact.importance());
                    entity.setAccessCount(0);
                    entity.setCreatedAt(LocalDateTime.now());
                    if (metadataJson != null && !metadataJson.isBlank()) {
                        entity.setMetadataJson(metadataJson);
                    }
                    return repository.save(entity).then();
                }))
                .doOnTerminate(() -> invalidateCache(uid, aid));
    }

    @Override
    public Flux<MemoryEntry> search(String cue, String userId, String agentId) {
        final String uid = effectiveUserId(userId);
        final String aid = effectiveAgentId(agentId);
        List<String> keywords = keywordExtractor.extract(cue);
        if (keywords.isEmpty()) {
            return repository.findByUserIdAndAgentId(uid, aid).map(this::toMemoryEntry);
        }

        String fulltextExpr = String.join(" ", keywords);
        return repository.fulltextSearch(uid, aid, fulltextExpr, 100)
                .map(this::toMemoryEntry)
                .switchIfEmpty(Flux.fromIterable(keywords)
                        .flatMap(kw -> repository.findByUserIdAndAgentId(uid, aid)
                                .filter(e -> e.getContent() != null && e.getContent().contains(kw)))
                        .distinct(AgentMemoryEntity::getId)
                        .map(this::toMemoryEntry));
    }

    @Override
    public Mono<Void> recordAccess(MemoryEntry entry) {
        if (entry.getId() == null) return Mono.empty();
        return repository.incrementAccessCount(entry.getId()).then();
    }

    @Override
    public Mono<Void> recordAccess(MemoryEntry entry, float importanceBoost) {
        if (entry.getId() == null) return Mono.empty();
        if (importanceBoost > 0) {
            return repository.incrementAccessCountWithBoost(entry.getId(), importanceBoost).then();
        }
        return recordAccess(entry);
    }

    @Override
    public Flux<MemoryEntry> findByUserId(String userId, String agentId) {
        final String uid = effectiveUserId(userId);
        final String aid = effectiveAgentId(agentId);
        String key = cacheKey(uid, aid);
        List<MemoryEntry> cached = hotCache.getIfPresent(key);
        if (cached != null) {
            return Flux.fromIterable(cached);
        }
        return repository.findByUserIdAndAgentId(uid, aid)
                .map(this::toMemoryEntry)
                .collectList()
                .doOnNext(list -> hotCache.put(key, list))
                .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<Long> countByUserId(String userId, String agentId) {
        final String uid = effectiveUserId(userId);
        final String aid = effectiveAgentId(agentId);
        return repository.countByUserIdAndAgentId(uid, aid);
    }

    @Override
    public Mono<Void> deleteById(Long id) {
        return repository.findById(id)
                .doOnNext(entity -> invalidateCache(entity.getUserId(), entity.getAgentId()))
                .then(repository.deleteById(id));
    }

    @Override
    public Flux<MemoryEntry> findColdMemories(int archiveDays, float importanceThreshold) {
        return repository.findColdMemories(archiveDays, importanceThreshold)
                .map(this::toMemoryEntry);
    }

    @Override
    public Flux<MemoryEntry> findColdMemories(int archiveDays, float importanceThreshold, String userId, String agentId) {
        final String uid = effectiveUserId(userId);
        final String aid = effectiveAgentId(agentId);
        return repository.findColdMemoriesByUserIdAndAgentId(uid, aid, archiveDays, importanceThreshold)
                .map(this::toMemoryEntry);
    }

    @Override
    public Mono<MemoryStats> getStats(String userId, String agentId) {
        final String uid = effectiveUserId(userId);
        final String aid = effectiveAgentId(agentId);
        return Mono.zip(
                repository.countByUserIdAndAgentIdAndMemoryType(uid, aid, "episodic").defaultIfEmpty(0L),
                repository.countByUserIdAndAgentIdAndMemoryType(uid, aid, "semantic").defaultIfEmpty(0L),
                repository.countByUserIdAndAgentIdAndMemoryType(uid, aid, "procedural").defaultIfEmpty(0L)
        ).map(t -> new MemoryStats(t.getT1(), t.getT2(), t.getT3(),
                t.getT1() + t.getT2() + t.getT3()));
    }

    private MemoryEntry toMemoryEntry(AgentMemoryEntity entity) {
        MemoryEntry entry = new MemoryEntry();
        entry.setId(entity.getId());
        entry.setUserId(entity.getUserId());
        entry.setAgentId(entity.getAgentId() != null ? entity.getAgentId() : "default");
        entry.setMemoryType(entity.getMemoryType());
        entry.setContent(entity.getContent());
        entry.setImportance(entity.getImportance() != null ? entity.getImportance() : 0.5f);
        entry.setAccessCount(entity.getAccessCount() != null ? entity.getAccessCount() : 0);
        entry.setLastAccessedAt(entity.getLastAccessedAt() != null
                ? entity.getLastAccessedAt().atZone(ZoneId.systemDefault()).toInstant()
                : null);
        entry.setCreatedAt(entity.getCreatedAt() != null
                ? entity.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant()
                : Instant.now());
        entry.setSourceSessionId(entity.getSourceSessionId());
        entry.setMetadataJson(entity.getMetadataJson());
        return entry;
    }
}
