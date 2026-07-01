package com.atm.intellimate.gateway.service;

import com.atm.intellimate.gateway.entity.AgentMemoryEntity;
import com.atm.intellimate.gateway.repository.AgentMemoryRepository;
import com.atm.intellimate.memory.config.ResolvedMemoryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DailyMemoryConsolidator")
class DailyMemoryConsolidatorTest {

    private static final String USER_ID = "user-1";
    private static final String AGENT_ID = "agent-1";
    private static final String PAIR = USER_ID + "\u001F" + AGENT_ID;

    @Mock
    private AgentMemoryRepository memoryRepository;

    @Mock
    private MemoryConfigService configService;

    private DailyMemoryConsolidator consolidator;

    @BeforeEach
    void setUp() {
        consolidator = new DailyMemoryConsolidator(memoryRepository, configService);
    }

    @Test
    @DisplayName("按主题分组并整合：3 条 detail → 2 条 consolidated")
    void shouldGroupMemoriesByTopicAndConsolidate() {
        when(memoryRepository.findDistinctUserAgentPairsWithTodayDetails())
                .thenReturn(Flux.just(PAIR));
        when(configService.resolveForAgent(AGENT_ID)).thenReturn(Mono.just(enabledConfig()));
        when(memoryRepository.findTodayDetailMemories(USER_ID, AGENT_ID))
                .thenReturn(Flux.just(
                        detailMemory(1L, "饮食", "早餐爱吃粥"),
                        detailMemory(2L, "饮食", "午餐少油"),
                        detailMemory(3L, "住房", "偏好朝南")
                ));
        when(memoryRepository.save(any(AgentMemoryEntity.class)))
                .thenAnswer(inv -> {
                    AgentMemoryEntity e = inv.getArgument(0);
                    e.setId(100L);
                    return Mono.just(e);
                });

        StepVerifier.create(consolidator.consolidateAll())
                .assertNext(stats -> {
                    assertThat(stats.get("pairsProcessed")).isEqualTo(1);
                    assertThat(stats.get("topicGroups")).isEqualTo(2);
                    assertThat(stats.get("consolidatedMemories")).isEqualTo(2);
                })
                .verifyComplete();

        ArgumentCaptor<AgentMemoryEntity> saved = ArgumentCaptor.forClass(AgentMemoryEntity.class);
        verify(memoryRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues())
                .extracting(AgentMemoryEntity::getMemoryLevel)
                .containsOnly("consolidated");
        assertThat(saved.getAllValues())
                .extracting(AgentMemoryEntity::getMemoryType)
                .containsOnly("semantic");
        assertThat(saved.getAllValues())
                .filteredOn(e -> "饮食".equals(e.getTopic()))
                .singleElement()
                .satisfies(e -> assertThat(e.getSourceMemoryIds()).contains("1", "2"));
    }

    @Test
    @DisplayName("相近主题 Jaccard ≥ 0.7 时合并为一组（吃饭 / 饮食偏好类主题）")
    void shouldMergeSimilarTopics() {
        when(memoryRepository.findDistinctUserAgentPairsWithTodayDetails())
                .thenReturn(Flux.just(PAIR));
        when(configService.resolveForAgent(AGENT_ID)).thenReturn(Mono.just(enabledConfig()));
        when(memoryRepository.findTodayDetailMemories(USER_ID, AGENT_ID))
                .thenReturn(Flux.just(
                        detailMemory(10L, "吃饭习惯", "午饭常吃面"),
                        detailMemory(11L, "吃饭习惯相", "晚饭清淡")
                ));
        when(memoryRepository.save(any(AgentMemoryEntity.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(consolidator.consolidateAll())
                .assertNext(stats -> {
                    assertThat(stats.get("topicGroups")).isEqualTo(1);
                    assertThat(stats.get("consolidatedMemories")).isEqualTo(1);
                })
                .verifyComplete();

        verify(memoryRepository, times(1)).save(any(AgentMemoryEntity.class));
    }

    @Test
    @DisplayName("longTermEnabled=false 时跳过整合")
    void shouldSkipAgentsWithLongTermDisabled() {
        when(memoryRepository.findDistinctUserAgentPairsWithTodayDetails())
                .thenReturn(Flux.just(PAIR));
        when(configService.resolveForAgent(AGENT_ID))
                .thenReturn(Mono.just(disabledLongTermConfig()));

        StepVerifier.create(consolidator.consolidateAll())
                .assertNext(stats -> assertThat(stats).isEmpty())
                .verifyComplete();

        verify(memoryRepository, never()).findTodayDetailMemories(any(), any());
        verify(memoryRepository, never()).save(any());
    }

    @Test
    @DisplayName("无今日 detail 记忆时返回空统计")
    void shouldHandleEmptyResults() {
        when(memoryRepository.findDistinctUserAgentPairsWithTodayDetails())
                .thenReturn(Flux.empty());

        StepVerifier.create(consolidator.consolidateAll())
                .assertNext(stats -> assertThat(stats).isEmpty())
                .verifyComplete();

        verify(configService, never()).resolveForAgent(any());
    }

    private static AgentMemoryEntity detailMemory(Long id, String topic, String content) {
        AgentMemoryEntity m = new AgentMemoryEntity();
        m.setId(id);
        m.setUserId(USER_ID);
        m.setAgentId(AGENT_ID);
        m.setMemoryLevel("detail");
        m.setMemoryType("semantic");
        m.setTopic(topic);
        m.setContent(content);
        m.setImportance(0.5f);
        return m;
    }

    private static ResolvedMemoryConfig disabledLongTermConfig() {
        Map<String, String> map = baseConfigMap();
        map.put("long_term.enabled", "false");
        return ResolvedMemoryConfig.fromMap(map);
    }

    private static ResolvedMemoryConfig enabledConfig() {
        Map<String, String> map = baseConfigMap();
        map.put("long_term.enabled", "true");
        map.put("consolidation.topic_similarity_threshold", "0.7");
        return ResolvedMemoryConfig.fromMap(map);
    }

    private static Map<String, String> baseConfigMap() {
        Map<String, String> map = new HashMap<>();
        map.put("working.token_budget", "128000");
        map.put("working.consolidation_threshold", "0.75");
        map.put("consolidation.model", "qwen-turbo");
        map.put("consolidation.fallback_model", "qwen-lite");
        map.put("consolidation.max_summary_tokens", "1024");
        map.put("consolidation.timeout_ms", "30000");
        map.put("consolidation.max_retries", "2");
        map.put("consolidation.overflow_tolerance", "1.10");
        map.put("long_term.max_memories_per_user", "500");
        map.put("long_term.max_injection_tokens", "2048");
        map.put("long_term.decay_lambda", "0.1");
        map.put("long_term.compaction_threshold", "300");
        map.put("long_term.archive_after_days", "30");
        map.put("long_term.min_chunks_for_episodic", "4");
        map.put("vector.enabled", "true");
        map.put("embedding.definition_id", "");
        map.put("retrieval.strategy", "hybrid");
        map.put("retrieval.vector_weight", "0.6");
        map.put("retrieval.keyword_weight", "0.4");
        map.put("scoring.semantic_weight", "1.2");
        map.put("scoring.episodic_weight", "0.8");
        map.put("scoring.procedural_weight", "1.0");
        map.put("scoring.semantic_decay_lambda", "0.03");
        map.put("scoring.episodic_decay_lambda", "0.10");
        map.put("scoring.procedural_decay_lambda", "0.05");
        map.put("long_term.min_fact_importance", "0.3");
        map.put("long_term.max_merged_content_length", "1000");
        return map;
    }
}
