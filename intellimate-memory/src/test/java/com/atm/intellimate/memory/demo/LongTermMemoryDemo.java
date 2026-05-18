package com.atm.intellimate.memory.demo;

import com.atm.intellimate.memory.forgetting.ForgettingScheduler;
import com.atm.intellimate.memory.longterm.LongTermMemory;
import com.atm.intellimate.memory.model.ExtractedFact;
import com.atm.intellimate.memory.model.MemoryEntry;
import com.atm.intellimate.memory.retrieval.MemoryRetrieval;
import com.atm.intellimate.memory.working.TokenEstimator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Demo: 长期记忆检索与遗忘")
class LongTermMemoryDemo {

    @Test
    @DisplayName("写入 -> 检索 -> 强化 -> 遗忘 完整流程")
    void fullLifecycle() {
        List<MemoryEntry> storage = new ArrayList<>();
        AtomicLong idGen = new AtomicLong(1);

        LongTermMemory ltm = new LongTermMemory() {
            @Override
            public Mono<Void> store(ExtractedFact fact, String userId, String agentId) {
                String aid = agentId == null || agentId.isBlank() ? "default" : agentId;
                MemoryEntry e = new MemoryEntry(userId, aid, fact.type(), fact.content(), fact.importance(), null);
                e.setId(idGen.getAndIncrement());
                e.setLastAccessedAt(Instant.now());
                storage.add(e);
                return Mono.empty();
            }

            @Override
            public Flux<MemoryEntry> search(String cue, String userId, String agentId) {
                String aid = agentId == null || agentId.isBlank() ? "default" : agentId;
                return Flux.fromIterable(storage)
                        .filter(m -> m.getUserId().equals(userId) && aid.equals(m.getAgentId()));
            }

            @Override
            public Mono<Void> recordAccess(MemoryEntry entry) {
                storage.stream().filter(m -> m.getId().equals(entry.getId()))
                        .findFirst().ifPresent(m -> {
                            m.setAccessCount(m.getAccessCount() + 1);
                            m.setLastAccessedAt(Instant.now());
                        });
                return Mono.empty();
            }

            @Override public Flux<MemoryEntry> findByUserId(String userId, String agentId) {
                String aid = agentId == null || agentId.isBlank() ? "default" : agentId;
                return Flux.fromIterable(storage)
                        .filter(m -> m.getUserId().equals(userId) && aid.equals(m.getAgentId()));
            }
            @Override public Mono<Long> countByUserId(String userId, String agentId) {
                String aid = agentId == null || agentId.isBlank() ? "default" : agentId;
                return Mono.just(storage.stream()
                        .filter(m -> m.getUserId().equals(userId) && aid.equals(m.getAgentId()))
                        .count());
            }
            @Override public Mono<Void> deleteById(Long id) {
                storage.removeIf(m -> m.getId().equals(id));
                return Mono.empty();
            }
            @Override public Flux<MemoryEntry> findColdMemories(int d, float t) { return Flux.empty(); }
            @Override public Flux<MemoryEntry> findColdMemories(int d, float t, String u, String a) { return Flux.empty(); }
            @Override public Mono<MemoryStats> getStats(String userId, String agentId) {
                return Mono.just(new MemoryStats(0, 0, 0, 0));
            }
        };

        // 1. Store 3 memories
        ltm.store(new ExtractedFact("episodic", "上次修复了 auth 模块 NPE", 0.7f), "user1", "default").block();
        ltm.store(new ExtractedFact("semantic", "项目用 Spring Boot 3.4.3", 0.8f), "user1", "default").block();
        ltm.store(new ExtractedFact("procedural", "bug修复流程: 读日志->定位->修复->测试", 0.6f), "user1", "default").block();
        assertEquals(3, storage.size());

        // 2. Retrieve with cue "auth 模块报错"
        MemoryRetrieval retrieval = new MemoryRetrieval(ltm, new TokenEstimator());
        var results = retrieval.retrieve("auth 模块报错", "user1", "default", 2000, 0.1).block();
        assertNotNull(results);
        assertFalse(results.isEmpty(), "Should find relevant memories");

        // 3. Simulate time passing - set lastAccessedAt to 30 days ago for procedural memory
        MemoryEntry procedural = storage.stream()
                .filter(m -> "procedural".equals(m.getMemoryType())).findFirst().orElse(null);
        assertNotNull(procedural);
        procedural.setLastAccessedAt(Instant.now().minus(31, ChronoUnit.DAYS));

        // 4. Run forgetting
        ForgettingScheduler scheduler = new ForgettingScheduler(ltm, 0.1, 500);
        scheduler.forgetForUser("user1").block();

        // The procedural memory (low importance, old, 0 accesses) should be forgotten
        boolean proceduralExists = storage.stream()
                .anyMatch(m -> "procedural".equals(m.getMemoryType()));
        assertFalse(proceduralExists, "Low-retention procedural memory should be forgotten");

        // Episodic memory (accessed during retrieval) should survive
        assertTrue(storage.stream().anyMatch(m -> "episodic".equals(m.getMemoryType())),
                "Accessed episodic memory should survive");
    }
}
