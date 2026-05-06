package com.atm.javaclaw.memory.retrieval;

import com.atm.javaclaw.memory.longterm.LongTermMemory;
import com.atm.javaclaw.memory.model.ExtractedFact;
import com.atm.javaclaw.memory.model.MemoryChunk;
import com.atm.javaclaw.memory.model.MemoryEntry;
import com.atm.javaclaw.memory.working.TokenEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MemoryRetrieval")
class MemoryRetrievalTest {

    private MemoryRetrieval retrieval;

    private MemoryEntry createEntry(String content, float importance, int accessCount) {
        MemoryEntry m = new MemoryEntry("user1", "default", "semantic", content, importance, null);
        m.setId((long) content.hashCode());
        m.setAccessCount(accessCount);
        m.setLastAccessedAt(Instant.now());
        return m;
    }

    @BeforeEach
    void setup() {
        List<MemoryEntry> memories = List.of(
                createEntry("Spring Boot 项目使用 R2DBC 连接 MySQL", 0.8f, 3),
                createEntry("AuthService 中有一个 NPE bug 已修复", 0.7f, 1),
                createEntry("用户偏好使用中文回复", 0.6f, 0),
                createEntry("天气预报 API 不可用", 0.3f, 0)
        );

        LongTermMemory mockLTM = new LongTermMemory() {
            @Override public Mono<Void> store(ExtractedFact fact, String userId, String agentId) { return Mono.empty(); }
            @Override public Flux<MemoryEntry> search(String cue, String userId, String agentId) { return Flux.fromIterable(memories); }
            @Override public Mono<Void> recordAccess(MemoryEntry entry) { return Mono.empty(); }
            @Override public Flux<MemoryEntry> findByUserId(String userId, String agentId) { return Flux.fromIterable(memories); }
            @Override public Mono<Long> countByUserId(String userId, String agentId) { return Mono.just((long) memories.size()); }
            @Override public Mono<Void> deleteById(Long id) { return Mono.empty(); }
            @Override public Flux<MemoryEntry> findColdMemories(int d, float t) { return Flux.empty(); }
            @Override public Flux<MemoryEntry> findColdMemories(int d, float t, String u, String a) { return Flux.empty(); }
            @Override public Mono<MemoryStats> getStats(String userId, String agentId) { return Mono.just(new MemoryStats(1, 2, 1, 4)); }
        };

        retrieval = new MemoryRetrieval(mockLTM, new TokenEstimator());
    }

    @Test
    @DisplayName("Retrieve returns top-scored memories within token budget")
    void retrieve_returnsTopScoredWithinBudget() {
        StepVerifier.create(retrieval.retrieve("Spring Boot R2DBC", "user1", "default", 500, 0.1))
                .assertNext(result -> {
                    assertFalse(result.isEmpty(), "Should return at least one memory");
                    int totalTokens = result.stream().mapToInt(MemoryChunk::estimatedTokens).sum();
                    assertTrue(totalTokens <= 500, "Should respect token budget");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Retrieve with empty memories returns empty list")
    void retrieve_emptyMemory_returnsEmpty() {
        LongTermMemory emptyLTM = new LongTermMemory() {
            @Override public Mono<Void> store(ExtractedFact fact, String userId, String agentId) { return Mono.empty(); }
            @Override public Flux<MemoryEntry> search(String cue, String userId, String agentId) { return Flux.empty(); }
            @Override public Mono<Void> recordAccess(MemoryEntry entry) { return Mono.empty(); }
            @Override public Flux<MemoryEntry> findByUserId(String userId, String agentId) { return Flux.empty(); }
            @Override public Mono<Long> countByUserId(String userId, String agentId) { return Mono.just(0L); }
            @Override public Mono<Void> deleteById(Long id) { return Mono.empty(); }
            @Override public Flux<MemoryEntry> findColdMemories(int d, float t) { return Flux.empty(); }
            @Override public Flux<MemoryEntry> findColdMemories(int d, float t, String u, String a) { return Flux.empty(); }
            @Override public Mono<MemoryStats> getStats(String userId, String agentId) { return Mono.just(new MemoryStats(0, 0, 0, 0)); }
        };
        MemoryRetrieval emptyRetrieval = new MemoryRetrieval(emptyLTM, new TokenEstimator());

        StepVerifier.create(emptyRetrieval.retrieve("anything", "user1", "default", 1000, 0.1))
                .assertNext(result -> assertTrue(result.isEmpty()))
                .verifyComplete();
    }

    @Test
    @DisplayName("When memory count > 1000, uses search path capped at 100 then top-20 pre-filter")
    void retrieve_largeCorpus_usesStagedSearchNotFullScan() {
        List<MemoryEntry> many = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            MemoryEntry m = createEntry("topic-" + i + " keyword match", 0.5f, 0);
            m.setId((long) (1000 + i));
            many.add(m);
        }
        boolean[] findByUserIdCalled = { false };
        LongTermMemory largeLtm = new LongTermMemory() {
            @Override public Mono<Void> store(ExtractedFact fact, String userId, String agentId) { return Mono.empty(); }
            @Override public Flux<MemoryEntry> search(String cue, String userId, String agentId) {
                return Flux.fromIterable(many);
            }
            @Override public Mono<Void> recordAccess(MemoryEntry entry) { return Mono.empty(); }
            @Override public Flux<MemoryEntry> findByUserId(String userId, String agentId) {
                findByUserIdCalled[0] = true;
                return Flux.error(new AssertionError("findByUserId should not run when count > 1000"));
            }
            @Override public Mono<Long> countByUserId(String userId, String agentId) { return Mono.just(2000L); }
            @Override public Mono<Void> deleteById(Long id) { return Mono.empty(); }
            @Override public Flux<MemoryEntry> findColdMemories(int d, float t) { return Flux.empty(); }
            @Override public Flux<MemoryEntry> findColdMemories(int d, float t, String u, String a) { return Flux.empty(); }
            @Override public Mono<MemoryStats> getStats(String userId, String agentId) {
                return Mono.just(new MemoryStats(0, 0, 0, 0));
            }
        };
        MemoryRetrieval staged = new MemoryRetrieval(largeLtm, new TokenEstimator());
        StepVerifier.create(staged.retrieve("keyword match", "user1", "default", 50_000, 0.1))
                .assertNext(result -> assertFalse(result.isEmpty()))
                .verifyComplete();
        assertFalse(findByUserIdCalled[0]);
    }
}
