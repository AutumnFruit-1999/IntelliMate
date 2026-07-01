package com.atm.intellimate.gateway.migration;

import com.atm.intellimate.gateway.entity.AgentMemoryEntity;
import com.atm.intellimate.gateway.repository.AgentMemoryRepository;
import com.atm.intellimate.memory.model.MemoryEntry;
import com.atm.intellimate.memory.retrieval.VectorMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 启动时将现有 MySQL 长期记忆迁移到向量数据库。
 * 特性：幂等、批量处理、速率限制、优雅的错误处理。
 */
@Component
@ConditionalOnBean(VectorMemoryStore.class)
public class MemoryVectorMigrator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorMigrator.class);
    private static final int BATCH_SIZE = 50;
    private static final int MAX_CONCURRENT = 5;
    private static final int EXISTENCE_SEARCH_TOP_K = 10;
    private static final Duration BATCH_DELAY = Duration.ofMillis(100);
    private static final int AVAILABILITY_MAX_RETRIES = 5;
    private static final Duration AVAILABILITY_RETRY_DELAY = Duration.ofSeconds(3);

    private final VectorMemoryStore vectorStore;
    private final AgentMemoryRepository memoryRepository;

    public MemoryVectorMigrator(VectorMemoryStore vectorStore, AgentMemoryRepository memoryRepository) {
        this.vectorStore = vectorStore;
        this.memoryRepository = memoryRepository;
    }

    public record MigrationStats(int migrated, int skipped, int failed, int total) {}

    @Override
    public void run(ApplicationArguments args) {
        migrate()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        null,
                        err -> log.error("Memory vector migration failed", err)
                );
    }

    public Mono<MigrationStats> migrate() {
        return waitForVectorStoreAvailable()
                .flatMap(available -> {
                    if (!available) {
                        return Mono.just(new MigrationStats(0, 0, 0, 0));
                    }

                    AtomicInteger migrated = new AtomicInteger();
                    AtomicInteger skipped = new AtomicInteger();
                    AtomicInteger failed = new AtomicInteger();
                    AtomicInteger processed = new AtomicInteger();

                    return memoryRepository.findAll()
                            .buffer(BATCH_SIZE)
                            .concatMap(batch -> Flux.fromIterable(batch)
                                    .flatMap(entity -> migrateOne(entity, migrated, skipped, failed, processed),
                                            MAX_CONCURRENT)
                                    .then(Mono.delay(BATCH_DELAY)))
                            .then(Mono.fromSupplier(() -> {
                                int m = migrated.get();
                                int s = skipped.get();
                                int f = failed.get();
                                return new MigrationStats(m, s, f, m + s + f);
                            }));
                });
    }

    /**
     * Waits for vector store to become available with retries.
     * The DelegatingEmbeddingModel delegate is set asynchronously after DB config loads,
     * so the vector store may not be ready immediately at startup.
     */
    Mono<Boolean> waitForVectorStoreAvailable() {
        return Mono.defer(() -> vectorStore.isAvailable()
                        .flatMap(available -> {
                            if (available) return Mono.just(true);
                            return Mono.error(new IllegalStateException("Vector store not yet available"));
                        }))
                .retryWhen(Retry.fixedDelay(AVAILABILITY_MAX_RETRIES, AVAILABILITY_RETRY_DELAY)
                        .filter(t -> t instanceof IllegalStateException))
                .onErrorResume(e -> Mono.just(false));
    }

    private Mono<Void> migrateOne(AgentMemoryEntity entity,
                                  AtomicInteger migrated,
                                  AtomicInteger skipped,
                                  AtomicInteger failed,
                                  AtomicInteger processed) {
        if (entity.getId() == null) {
            log.warn("Skipping memory without id");
            recordProgress(processed, migrated, skipped, failed);
            skipped.incrementAndGet();
            return Mono.empty();
        }
        if (entity.getContent() == null || entity.getContent().isBlank()) {
            log.warn("Skipping memory id={} with empty content", entity.getId());
            recordProgress(processed, migrated, skipped, failed);
            skipped.incrementAndGet();
            return Mono.empty();
        }

        MemoryEntry entry = toMemoryEntry(entity);
        return existsInVector(entry)
                .flatMap(exists -> {
                    if (exists) {
                        skipped.incrementAndGet();
                        recordProgress(processed, migrated, skipped, failed);
                        return Mono.empty();
                    }
                    return vectorStore.store(entry)
                            .doOnSuccess(v -> {
                                migrated.incrementAndGet();
                                recordProgress(processed, migrated, skipped, failed);
                            })
                            .onErrorResume(e -> {
                                failed.incrementAndGet();
                                log.warn("Failed to migrate memory id={}: {}", entity.getId(), e.getMessage());
                                recordProgress(processed, migrated, skipped, failed);
                                return Mono.empty();
                            });
                });
    }

    private Mono<Boolean> existsInVector(MemoryEntry entry) {
        return vectorStore.search(entry.getContent(), entry.getUserId(), entry.getAgentId(), EXISTENCE_SEARCH_TOP_K)
                .map(results -> results.stream()
                        .anyMatch(result -> entry.getId().equals(result.mysqlId())))
                .defaultIfEmpty(false)
                .onErrorResume(e -> Mono.just(false));
    }

    private void recordProgress(AtomicInteger processed,
                                AtomicInteger migrated,
                                AtomicInteger skipped,
                                AtomicInteger failed) {
        processed.incrementAndGet();
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
