package com.atm.intellimate.gateway.service;

import com.atm.intellimate.gateway.entity.AgentMemoryEntity;
import com.atm.intellimate.gateway.repository.AgentMemoryRepository;
import com.atm.intellimate.memory.config.ResolvedMemoryConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DailyMemoryConsolidator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentMemoryRepository memoryRepository;
    private final MemoryConfigService configService;

    public DailyMemoryConsolidator(AgentMemoryRepository memoryRepository, MemoryConfigService configService) {
        this.memoryRepository = memoryRepository;
        this.configService = configService;
    }

    public Mono<Map<String, Integer>> consolidateAll() {
        return memoryRepository.findDistinctUserAgentPairsWithTodayDetails()
                .flatMap(pair -> {
                    String[] parts = pair.split("\u001F", -1);
                    String userId = parts[0];
                    String agentId = parts.length > 1 ? parts[1] : "default";
                    return configService.resolveForAgent(agentId)
                            .filter(ResolvedMemoryConfig::longTermEnabled)
                            .flatMap(config -> consolidateForPair(userId, agentId, config))
                            .defaultIfEmpty(Map.of());
                })
                .reduce(new HashMap<>(), (acc, stats) -> {
                    stats.forEach((k, v) -> acc.merge(k, v, Integer::sum));
                    return acc;
                });
    }

    private Mono<Map<String, Integer>> consolidateForPair(
            String userId, String agentId, ResolvedMemoryConfig config) {
        return memoryRepository.findTodayDetailMemories(userId, agentId)
                .collectList()
                .flatMap(memories -> {
                    if (memories.isEmpty()) {
                        return Mono.just(Map.<String, Integer>of());
                    }
                    Map<String, List<AgentMemoryEntity>> groups =
                            groupByTopic(memories, config.topicSimilarityThreshold());
                    return Flux.fromIterable(groups.entrySet())
                            .concatMap(entry -> consolidateTopic(
                                    entry.getKey(), entry.getValue(), userId, agentId))
                            .collectList()
                            .map(results -> Map.of(
                                    "pairsProcessed", 1,
                                    "topicGroups", groups.size(),
                                    "consolidatedMemories", results.size()
                            ));
                });
    }

    private Map<String, List<AgentMemoryEntity>> groupByTopic(
            List<AgentMemoryEntity> memories, float threshold) {
        Map<String, List<AgentMemoryEntity>> groups = new LinkedHashMap<>();
        for (AgentMemoryEntity mem : memories) {
            String topic = mem.getTopic() != null ? mem.getTopic() : "未分类";
            String matchedKey = findSimilarTopic(groups.keySet(), topic, threshold);
            groups.computeIfAbsent(matchedKey != null ? matchedKey : topic, k -> new ArrayList<>()).add(mem);
        }
        return groups;
    }

    private String findSimilarTopic(Set<String> existingTopics, String newTopic, float threshold) {
        for (String existing : existingTopics) {
            if (jaccardSimilarity(existing, newTopic) >= threshold) {
                return existing;
            }
        }
        return null;
    }

    private float jaccardSimilarity(String a, String b) {
        Set<Character> setA = a.chars().mapToObj(c -> (char) c).collect(Collectors.toSet());
        Set<Character> setB = b.chars().mapToObj(c -> (char) c).collect(Collectors.toSet());
        Set<Character> intersection = new HashSet<>(setA);
        intersection.retainAll(setB);
        Set<Character> union = new HashSet<>(setA);
        union.addAll(setB);
        return union.isEmpty() ? 0f : (float) intersection.size() / union.size();
    }

    private Mono<AgentMemoryEntity> consolidateTopic(
            String topic,
            List<AgentMemoryEntity> memories,
            String userId,
            String agentId) {
        StringBuilder contentBuilder = new StringBuilder();
        StringBuilder enrichedBuilder = new StringBuilder();
        Set<String> allKeywords = new LinkedHashSet<>();
        List<Long> sourceIds = new ArrayList<>();
        float maxImportance = 0f;

        for (AgentMemoryEntity mem : memories) {
            if (mem.getContent() != null) {
                contentBuilder.append(mem.getContent()).append('\n');
            }
            if (mem.getEnrichedContent() != null) {
                enrichedBuilder.append(mem.getEnrichedContent()).append('\n');
            }
            if (mem.getKeywords() != null) {
                Arrays.stream(mem.getKeywords().split("\\s+"))
                        .filter(k -> !k.isBlank())
                        .forEach(allKeywords::add);
            }
            sourceIds.add(mem.getId());
            maxImportance = Math.max(
                    maxImportance, mem.getImportance() != null ? mem.getImportance() : 0f);
        }

        AgentMemoryEntity consolidated = new AgentMemoryEntity();
        consolidated.setUserId(userId);
        consolidated.setAgentId(agentId);
        consolidated.setMemoryType("semantic");
        consolidated.setContent(contentBuilder.toString().trim());
        consolidated.setEnrichedContent(enrichedBuilder.toString().trim());
        consolidated.setKeywords(String.join(" ", allKeywords));
        consolidated.setTopic(topic);
        consolidated.setMemoryLevel("consolidated");
        consolidated.setImportance(maxImportance);

        try {
            consolidated.setSourceMemoryIds(OBJECT_MAPPER.writeValueAsString(sourceIds));
        } catch (Exception e) {
            consolidated.setSourceMemoryIds("[]");
        }

        return memoryRepository.save(consolidated);
    }
}
