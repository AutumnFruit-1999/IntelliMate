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
            Map.entry("long_term.min_chunks_for_episodic", "4")
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
            Map.entry("long_term.archive_after_days", "冷记忆归档天数")
    );

    public MemoryConfigService(MemoryConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    @Override
    public Mono<ResolvedMemoryConfig> resolve() {
        return configRepo.findAll()
                .collectMap(e -> e.getConfigKey(), e -> e.getConfigValue())
                .map(ResolvedMemoryConfig::fromMap);
    }

    public Mono<Map<String, ConfigItem>> resolveGrouped() {
        return configRepo.findAll()
                .collectMap(e -> e.getConfigKey(), e -> new ConfigItem(
                        e.getConfigValue(),
                        DEFAULTS.getOrDefault(e.getConfigKey(), ""),
                        e.getDescription() != null ? e.getDescription()
                                : DESCRIPTIONS.getOrDefault(e.getConfigKey(), ""),
                        inferType(e.getConfigKey())
                ));
    }

    public Mono<Void> updateConfig(Map<String, String> updates) {
        return reactor.core.publisher.Flux.fromIterable(updates.entrySet())
                .flatMap(e -> configRepo.upsert(e.getKey(), e.getValue(),
                        DESCRIPTIONS.getOrDefault(e.getKey(), "")))
                .then();
    }

    public Mono<Void> resetToDefaults() {
        return configRepo.deleteAll()
                .then(reactor.core.publisher.Flux.fromIterable(DEFAULTS.entrySet())
                        .flatMap(e -> configRepo.upsert(e.getKey(), e.getValue(),
                                DESCRIPTIONS.getOrDefault(e.getKey(), "")))
                        .then());
    }

    public static Map<String, String> getDefaults() {
        return DEFAULTS;
    }

    private String inferType(String key) {
        if (key.contains("enabled")) return "boolean";
        if (key.contains("model")) return "string";
        if (key.contains("threshold") || key.contains("tolerance") || key.contains("lambda")) return "number";
        return "number";
    }

    public record ConfigItem(String value, String defaultValue, String description, String type) {}
}
