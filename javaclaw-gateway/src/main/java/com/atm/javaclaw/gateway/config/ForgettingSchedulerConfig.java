package com.atm.javaclaw.gateway.config;

import com.atm.javaclaw.gateway.entity.AgentMemoryArchiveEntity;
import com.atm.javaclaw.gateway.repository.AgentMemoryArchiveRepository;
import com.atm.javaclaw.gateway.repository.AgentMemoryRepository;
import com.atm.javaclaw.gateway.service.MemoryConfigService;
import com.atm.javaclaw.memory.config.ResolvedMemoryConfig;
import com.atm.javaclaw.memory.forgetting.ForgettingScheduler;
import com.atm.javaclaw.memory.longterm.LongTermMemory;
import com.atm.javaclaw.memory.model.MemoryEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Configuration
@EnableScheduling
public class ForgettingSchedulerConfig {

    private static final float COLD_ARCHIVE_IMPORTANCE_THRESHOLD = 0.35f;

    @Bean
    public ForgettingScheduler forgettingScheduler(LongTermMemory longTermMemory) {
        return new ForgettingScheduler(longTermMemory, 0.1, 500);
    }

    @Bean
    public ForgettingSchedulerJobs forgettingSchedulerJobs(
            ForgettingScheduler forgettingScheduler,
            LongTermMemory longTermMemory,
            AgentMemoryArchiveRepository agentMemoryArchiveRepository,
            MemoryConfigService memoryConfigService,
            AgentMemoryRepository agentMemoryRepository) {
        return new ForgettingSchedulerJobs(
                forgettingScheduler,
                longTermMemory,
                agentMemoryArchiveRepository,
                memoryConfigService,
                agentMemoryRepository);
    }

    /**
     * Nightly long-term memory maintenance: per-user forgetting/compaction, then cold archive.
     */
    public static final class ForgettingSchedulerJobs {

        private static final Logger log = LoggerFactory.getLogger(ForgettingSchedulerJobs.class);

        private final ForgettingScheduler forgettingScheduler;
        private final LongTermMemory longTermMemory;
        private final AgentMemoryArchiveRepository agentMemoryArchiveRepository;
        private final MemoryConfigService memoryConfigService;
        private final AgentMemoryRepository agentMemoryRepository;

        ForgettingSchedulerJobs(
                ForgettingScheduler forgettingScheduler,
                LongTermMemory longTermMemory,
                AgentMemoryArchiveRepository agentMemoryArchiveRepository,
                MemoryConfigService memoryConfigService,
                AgentMemoryRepository agentMemoryRepository) {
            this.forgettingScheduler = forgettingScheduler;
            this.longTermMemory = longTermMemory;
            this.agentMemoryArchiveRepository = agentMemoryArchiveRepository;
            this.memoryConfigService = memoryConfigService;
            this.agentMemoryRepository = agentMemoryRepository;
        }

        @Scheduled(cron = "0 0 3 * * ?")
        public void runNightlyMemoryMaintenance() {
            memoryConfigService.resolve()
                    .flatMap(this::runMaintenanceForConfig)
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            unused -> { },
                            e -> log.error("Nightly memory maintenance failed", e));
        }

        private Mono<Void> runMaintenanceForConfig(ResolvedMemoryConfig cfg) {
            if (!cfg.longTermEnabled()) {
                log.debug("Long-term memory disabled; skipping nightly forgetting and archive");
                return Mono.empty();
            }
            return agentMemoryRepository.findDistinctUserAgentPairs()
                    .flatMap(pair -> {
                        int sep = pair.indexOf('\u001F');
                        if (sep < 0 || sep == pair.length() - 1) {
                            log.warn("Skipping malformed user/agent pair from DB: {}", pair);
                            return Mono.empty();
                        }
                        String userId = pair.substring(0, sep);
                        String agentId = pair.substring(sep + 1);
                        return forgettingScheduler
                                .forgetForUser(userId, agentId, cfg.decayLambda(), cfg.maxMemoriesPerUser())
                                .then(agentMemoryRepository.countByUserIdAndAgentId(userId, agentId)
                                        .flatMap(count -> count >= cfg.compactionThreshold()
                                                ? forgettingScheduler.compactMemories(userId, agentId).then()
                                                : Mono.empty()))
                                .then(archiveColdToDatabase(cfg.archiveAfterDays(), userId, agentId));
                    })
                    .then();
        }

        private Mono<Void> archiveColdToDatabase(int archiveAfterDays, String userId, String agentId) {
            return forgettingScheduler
                    .archiveColdMemories(archiveAfterDays, COLD_ARCHIVE_IMPORTANCE_THRESHOLD, userId, agentId)
                    .flatMap(this::persistArchivedEntry)
                    .then();
        }

        private Mono<Void> persistArchivedEntry(MemoryEntry entry) {
            AgentMemoryArchiveEntity archived = toArchiveEntity(entry);
            return agentMemoryArchiveRepository.save(archived)
                    .then(longTermMemory.deleteById(entry.getId()));
        }

        private static AgentMemoryArchiveEntity toArchiveEntity(MemoryEntry m) {
            AgentMemoryArchiveEntity e = new AgentMemoryArchiveEntity();
            e.setId(m.getId());
            e.setUserId(m.getUserId());
            e.setAgentId(m.getAgentId() != null ? m.getAgentId() : "default");
            e.setMemoryType(m.getMemoryType());
            e.setContent(m.getContent());
            e.setImportance(m.getImportance());
            e.setAccessCount(m.getAccessCount());
            if (m.getLastAccessedAt() != null) {
                e.setLastAccessedAt(LocalDateTime.ofInstant(m.getLastAccessedAt(), ZoneId.systemDefault()));
            }
            if (m.getCreatedAt() != null) {
                e.setCreatedAt(LocalDateTime.ofInstant(m.getCreatedAt(), ZoneId.systemDefault()));
            } else {
                e.setCreatedAt(LocalDateTime.now());
            }
            e.setSourceSessionId(m.getSourceSessionId());
            e.setMetadataJson(m.getMetadataJson());
            e.setArchivedAt(LocalDateTime.now());
            return e;
        }
    }
}
