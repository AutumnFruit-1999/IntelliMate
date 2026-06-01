package com.atm.intellimate.gateway.service;

import com.atm.intellimate.gateway.repository.MemoryConfigRepository;
import com.atm.intellimate.memory.config.MemoryConfigProvider;
import com.atm.intellimate.memory.config.ResolvedMemoryConfig;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

@Service
public class MemoryConfigService implements MemoryConfigProvider {

    private final MemoryConfigRepository configRepo;

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("working.token_budget", "128000"),
            Map.entry("working.consolidation_threshold", "0.75"),
            Map.entry("consolidation.model", "qwen-turbo"),
            Map.entry("consolidation.fallback_model", "qwen-lite"),
            Map.entry("consolidation.max_summary_tokens", "1024"),
            Map.entry("consolidation.timeout_ms", "5000"),
            Map.entry("consolidation.max_retries", "2"),
            Map.entry("consolidation.overflow_tolerance", "1.10"),
            Map.entry("long_term.enabled", "false"),
            Map.entry("long_term.max_memories_per_user", "500"),
            Map.entry("long_term.max_injection_tokens", "2048"),
            Map.entry("long_term.decay_lambda", "0.1"),
            Map.entry("long_term.compaction_threshold", "300"),
            Map.entry("long_term.archive_after_days", "30"),
            Map.entry("long_term.min_chunks_for_episodic", "4"),
            Map.entry("vector.enabled", "true"),
            Map.entry("embedding.model", "text-embedding-v3"),
            Map.entry("embedding.dimensions", "1024"),
            Map.entry("retrieval.strategy", "hybrid"),
            Map.entry("retrieval.vector_weight", "0.6"),
            Map.entry("retrieval.keyword_weight", "0.4"),
            Map.entry("scoring.semantic_weight", "1.2"),
            Map.entry("scoring.episodic_weight", "0.8"),
            Map.entry("scoring.procedural_weight", "1.0"),
            Map.entry("scoring.semantic_decay_lambda", "0.03"),
            Map.entry("scoring.episodic_decay_lambda", "0.10"),
            Map.entry("scoring.procedural_decay_lambda", "0.05"),
            Map.entry("long_term.min_fact_importance", "0.3"),
            Map.entry("long_term.max_merged_content_length", "1000")
    );

    private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry("working.token_budget", "工作记忆 token 容量上限"),
            Map.entry("working.consolidation_threshold", "触发巩固的使用率阈值 (0.5-0.95)"),
            Map.entry("consolidation.model", "巩固专用模型"),
            Map.entry("consolidation.fallback_model", "降级备用模型"),
            Map.entry("consolidation.max_summary_tokens", "摘要最大 token 数"),
            Map.entry("consolidation.timeout_ms", "单次巩固调用超时(ms)"),
            Map.entry("consolidation.max_retries", "最大重试次数"),
            Map.entry("consolidation.overflow_tolerance", "容量弹性上限 (1.0-1.5)"),
            Map.entry("long_term.enabled", "长期记忆开关"),
            Map.entry("long_term.max_memories_per_user", "单用户最大记忆条数"),
            Map.entry("long_term.max_injection_tokens", "检索注入 token 预算"),
            Map.entry("long_term.decay_lambda", "遗忘曲线衰减速率 (约7天衰减到50%)"),
            Map.entry("long_term.compaction_threshold", "触发记忆压实的条数"),
            Map.entry("long_term.archive_after_days", "冷记忆归档天数"),
            Map.entry("vector.enabled", "向量检索主开关"),
            Map.entry("embedding.model", "Embedding 模型名称"),
            Map.entry("embedding.dimensions", "向量维度"),
            Map.entry("retrieval.strategy", "检索策略: hybrid/vector-only/keyword-only"),
            Map.entry("retrieval.vector_weight", "向量得分权重"),
            Map.entry("retrieval.keyword_weight", "关键词得分权重"),
            Map.entry("scoring.semantic_weight", "semantic 类型权重"),
            Map.entry("scoring.episodic_weight", "episodic 类型权重"),
            Map.entry("scoring.procedural_weight", "procedural 类型权重"),
            Map.entry("scoring.semantic_decay_lambda", "semantic 衰减系数"),
            Map.entry("scoring.episodic_decay_lambda", "episodic 衰减系数"),
            Map.entry("scoring.procedural_decay_lambda", "procedural 衰减系数"),
            Map.entry("long_term.min_fact_importance", "低于此 importance 的 fact 不存储"),
            Map.entry("long_term.max_merged_content_length", "合并后单条记忆最大字符数")
    );

    private static final String GLOBAL_AGENT = "_global_";

    public MemoryConfigService(MemoryConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override
    public Mono<ResolvedMemoryConfig> resolve() {
        return configRepo.findByAgentName(GLOBAL_AGENT)
                .collectMap(e -> e.getConfigKey(), e -> e.getConfigValue())
                .map(ResolvedMemoryConfig::fromMap);
    }

    public Mono<ResolvedMemoryConfig> resolveForAgent(String agentName) {
        return configRepo.findByAgentName(GLOBAL_AGENT)
                .collectMap(e -> e.getConfigKey(), e -> e.getConfigValue())
                .flatMap(globalMap -> configRepo.findByAgentName(agentName)
                        .collectMap(e -> e.getConfigKey(), e -> e.getConfigValue())
                        .map(agentMap -> {
                            Map<String, String> merged = new java.util.HashMap<>(globalMap);
                            merged.putAll(agentMap);
                            return ResolvedMemoryConfig.fromMap(merged);
                        }));
    }

    public Mono<Map<String, ConfigItem>> resolveGrouped() {
        return resolveGroupedForAgent(GLOBAL_AGENT);
    }

    public Mono<Map<String, ConfigItem>> resolveGroupedForAgent(String agentName) {
        return configRepo.findByAgentName(GLOBAL_AGENT)
                .collectMap(e -> e.getConfigKey(), e -> e.getConfigValue())
                .flatMap(globalMap -> configRepo.findByAgentName(agentName)
                        .collectMap(e -> e.getConfigKey(), e -> e.getConfigValue())
                        .map(agentMap -> {
                            Map<String, ConfigItem> result = new java.util.LinkedHashMap<>();
                            DEFAULTS.forEach((key, defVal) -> {
                                String value = agentMap.containsKey(key) ? agentMap.get(key)
                                        : globalMap.getOrDefault(key, defVal);
                                result.put(key, new ConfigItem(
                                        value, defVal,
                                        DESCRIPTIONS.getOrDefault(key, ""),
                                        inferType(key)));
                            });
                            return result;
                        }));
    }

    public Mono<Void> updateConfig(Map<String, String> updates) {
        return updateConfigForAgent(GLOBAL_AGENT, updates);
    }

    public Mono<Void> updateConfigForAgent(String agentName, Map<String, String> updates) {
        return reactor.core.publisher.Flux.fromIterable(updates.entrySet())
                .flatMap(e -> configRepo.upsertForAgent(agentName, e.getKey(), e.getValue(),
                        DESCRIPTIONS.getOrDefault(e.getKey(), "")))
                .then();
    }

    public Mono<Void> resetToDefaults() {
        return resetToDefaultsForAgent(GLOBAL_AGENT);
    }

    public Mono<Void> resetToDefaultsForAgent(String agentName) {
        return configRepo.deleteByAgentName(agentName)
                .then(reactor.core.publisher.Flux.fromIterable(DEFAULTS.entrySet())
                        .flatMap(e -> configRepo.upsertForAgent(agentName, e.getKey(), e.getValue(),
                                DESCRIPTIONS.getOrDefault(e.getKey(), "")))
                        .then());
    }

    public static Map<String, String> getDefaults() {
        return DEFAULTS;
    }

    private String inferType(String key) {
        if (key.contains("enabled")) return "boolean";
        if (key.contains("model")) return "string";
        if (key.contains("strategy")) return "string";
        if (key.contains("threshold") || key.contains("tolerance")
                || key.contains("lambda") || key.contains("weight")) return "number";
        return "number";
    }

    public record ConfigItem(String value, String defaultValue, String description, String type) {}
}
