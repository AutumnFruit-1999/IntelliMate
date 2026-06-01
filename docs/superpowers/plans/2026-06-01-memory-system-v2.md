# 记忆系统 v2 实现计划 — 混合向量检索 + 缺陷修复

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将记忆检索从纯关键词匹配升级为混合向量语义检索，同时修复已知缺陷和优化持久化质量。

**架构：** 引入 Qdrant 向量数据库与 Spring AI VectorStore 集成，实现向量 + 关键词双路并行检索。MySQL 保持为主存储，Qdrant 作为语义索引层（双写、异步、可降级）。同时引入 jieba 中文分词、类型差异化评分、记忆标注增强。

**技术栈：** Spring AI Qdrant VectorStore、DashScope Embedding、jieba-analysis、Reactor、Flyway

**设计规格：** `docs/superpowers/specs/2026-06-01-memory-system-v2-design.md`

---

## 文件结构

### 新增文件

| 文件 | 职责 |
|------|------|
| `intellimate-memory/src/main/java/.../retrieval/VectorMemoryStore.java` | 向量存储接口 |
| `intellimate-memory/src/main/java/.../retrieval/VectorSearchResult.java` | 向量搜索结果记录 |
| `intellimate-memory/src/main/java/.../retrieval/HybridMemoryRetrieval.java` | 混合检索编排器 |
| `intellimate-gateway/src/main/java/.../service/QdrantVectorStoreImpl.java` | Qdrant 实现 |
| `intellimate-gateway/src/main/java/.../config/QdrantVectorStoreConfig.java` | Qdrant Bean 配置 |
| `intellimate-gateway/src/main/java/.../migration/VectorMemoryMigrationRunner.java` | 数据迁移任务 |
| `intellimate-gateway/src/main/resources/db/migration/V26__add_vector_and_scoring_config.sql` | 新配置项 |
| `intellimate-memory/src/test/java/.../retrieval/HybridMemoryRetrievalTest.java` | 混合检索测试 |
| `intellimate-memory/src/test/java/.../retrieval/VectorSearchResultTest.java` | 搜索结果测试 |

### 修改文件

| 文件 | 变更说明 |
|------|---------|
| `intellimate-memory/pom.xml` | 新增 jieba-analysis 依赖 |
| `intellimate-gateway/pom.xml` | 新增 spring-ai-qdrant-store 依赖 |
| `intellimate-memory/.../retrieval/KeywordExtractor.java` | jieba 替代 bigram |
| `intellimate-memory/.../retrieval/ScoringFunction.java` | 类型差异化评分 |
| `intellimate-memory/.../model/MemoryEntry.java` | 增强 toRecalledChunk |
| `intellimate-memory/.../config/ResolvedMemoryConfig.java` | 新增配置字段 |
| `intellimate-gateway/.../service/LongTermMemoryImpl.java` | userId 修复 + 双写 + 质量过滤 + 类型差异化存储 |
| `intellimate-gateway/.../service/MemoryConfigService.java` | 新配置键解析 |
| `intellimate-gateway/.../http/SessionHistoryController.java` | 尊重 enabled 开关 |
| `intellimate-gateway/.../config/MemorySystemConfig.java` | 注入向量存储 Bean |
| `intellimate-agent/.../runtime/AgentLoopExecutor.java` | HybridRetrieval + 标注注入 |
| `intellimate-agent/.../runtime/AgentMemoryLifecycle.java` | min_chunks 检查 + episodic 增强 |
| `intellimate-gateway/.../pipeline/PlanExecutionOrchestrator.java` | procedural 前置条件 |
| `intellimate-gateway/src/main/resources/application.yml` | Qdrant 连接配置 |
| `docker-compose.yml` | Qdrant 容器 |
| `intellimate-web/src/components/MemoryManagerPage.tsx` | 向量/评分配置 UI |
| `intellimate-web/src/stores/memoryStore.ts` | 配置分组适配 |
| 所有受影响的测试文件 | 同步测试 |

---

### 任务 1：Flyway 迁移 + ResolvedMemoryConfig 扩展

**文件：**
- 创建：`intellimate-gateway/src/main/resources/db/migration/V26__add_vector_and_scoring_config.sql`
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/config/ResolvedMemoryConfig.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/MemoryConfigService.java`
- 测试：`intellimate-memory/src/test/java/com/atm/intellimate/memory/config/ResolvedMemoryConfigTest.java`
- 测试：`intellimate-gateway/src/test/java/com/atm/intellimate/gateway/service/MemoryConfigServiceTest.java`

- [ ] **步骤 1：创建 Flyway 迁移文件**

```sql
-- V26__add_vector_and_scoring_config.sql
INSERT INTO memory_config (config_key, config_value, description) VALUES
('vector.enabled', 'true', '向量检索主开关'),
('embedding.model', 'text-embedding-v3', 'Embedding 模型名称'),
('embedding.dimensions', '1024', '向量维度'),
('retrieval.strategy', 'hybrid', '检索策略: hybrid/vector-only/keyword-only'),
('retrieval.vector_weight', '0.6', '向量得分权重'),
('retrieval.keyword_weight', '0.4', '关键词得分权重'),
('scoring.semantic_weight', '1.2', 'semantic 类型权重'),
('scoring.episodic_weight', '0.8', 'episodic 类型权重'),
('scoring.procedural_weight', '1.0', 'procedural 类型权重'),
('scoring.semantic_decay_lambda', '0.03', 'semantic 衰减系数'),
('scoring.episodic_decay_lambda', '0.10', 'episodic 衰减系数'),
('scoring.procedural_decay_lambda', '0.05', 'procedural 衰减系数'),
('long_term.min_fact_importance', '0.3', '低于此 importance 的 fact 不存储'),
('long_term.max_merged_content_length', '1000', '合并后单条记忆最大字符数')
ON DUPLICATE KEY UPDATE config_key = config_key;
```

- [ ] **步骤 2：扩展 ResolvedMemoryConfig 记录**

在 `ResolvedMemoryConfig.java` 中新增字段：

```java
public record ResolvedMemoryConfig(
        // ... 现有 15 个字段保持不变 ...
        // 新增向量配置
        boolean vectorEnabled,
        String embeddingModel,
        int embeddingDimensions,
        String retrievalStrategy,
        float vectorWeight,
        float keywordWeight,
        // 新增评分配置
        float semanticWeight,
        float episodicWeight,
        float proceduralWeight,
        float semanticDecayLambda,
        float episodicDecayLambda,
        float proceduralDecayLambda,
        // 新增持久化质量配置
        float minFactImportance,
        int maxMergedContentLength
) {
    public static ResolvedMemoryConfig fromMap(Map<String, String> map) {
        return new ResolvedMemoryConfig(
                // ... 现有解析 ...
                parseBooleanOrDefault(map, "vector.enabled", true),
                getOrDefault(map, "embedding.model", "text-embedding-v3"),
                parseIntOrDefault(map, "embedding.dimensions", 1024),
                getOrDefault(map, "retrieval.strategy", "hybrid"),
                parseFloatOrDefault(map, "retrieval.vector_weight", 0.6f),
                parseFloatOrDefault(map, "retrieval.keyword_weight", 0.4f),
                parseFloatOrDefault(map, "scoring.semantic_weight", 1.2f),
                parseFloatOrDefault(map, "scoring.episodic_weight", 0.8f),
                parseFloatOrDefault(map, "scoring.procedural_weight", 1.0f),
                parseFloatOrDefault(map, "scoring.semantic_decay_lambda", 0.03f),
                parseFloatOrDefault(map, "scoring.episodic_decay_lambda", 0.10f),
                parseFloatOrDefault(map, "scoring.procedural_decay_lambda", 0.05f),
                parseFloatOrDefault(map, "long_term.min_fact_importance", 0.3f),
                parseIntOrDefault(map, "long_term.max_merged_content_length", 1000)
        );
    }

    private static String getOrDefault(Map<String, String> map, String key, String defaultValue) {
        String val = map.get(key);
        return val != null && !val.isBlank() ? val : defaultValue;
    }

    private static float parseFloatOrDefault(Map<String, String> map, String key, float defaultValue) {
        String val = map.get(key);
        if (val == null || val.isBlank()) return defaultValue;
        try { return Float.parseFloat(val); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static boolean parseBooleanOrDefault(Map<String, String> map, String key, boolean defaultValue) {
        String val = map.get(key);
        if (val == null || val.isBlank()) return defaultValue;
        return Boolean.parseBoolean(val);
    }
}
```

- [ ] **步骤 3：更新 MemoryConfigService 的配置键数量**

在 `MemoryConfigService` 中更新默认配置键数量期望值（现有 15 → 29），确保 `resolve()` 方法能正确解析新键。

- [ ] **步骤 4：更新测试**

更新 `ResolvedMemoryConfigTest` 和 `MemoryConfigServiceTest` 以验证新配置字段的解析和默认值。

运行：`mvn test -pl intellimate-memory,intellimate-gateway -Dtest="ResolvedMemoryConfigTest,MemoryConfigServiceTest" -DfailIfNoTests=false`
预期：所有测试 PASS

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "feat(memory): add Flyway migration and config fields for vector retrieval and scoring"
```

---

### 任务 2：缺陷修复 — userId 隔离 + min_chunks + enabled 开关

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/LongTermMemoryImpl.java`
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentMemoryLifecycle.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/SessionHistoryController.java`

- [ ] **步骤 1：修复 LongTermMemoryImpl.findByUserId()，使用 userId + agentId 联合查询**

`LongTermMemoryImpl.java` 中：

```java
// findByUserId 修复 — 之前: repository.findByAgentId(aid)
@Override
public Flux<MemoryEntry> findByUserId(String userId, String agentId) {
    final String uid = effectiveUserId(userId);
    final String aid = effectiveAgentId(agentId);
    String key = uid + ":" + aid;   // 改为 userId:agentId 组合
    List<MemoryEntry> cached = hotCache.getIfPresent(key);
    if (cached != null) {
        return Flux.fromIterable(cached);
    }
    return repository.findByUserIdAndAgentId(uid, aid)  // 修复：使用已有的联合查询
            .map(this::toMemoryEntry)
            .collectList()
            .doOnNext(list -> hotCache.put(key, list))
            .flatMapMany(Flux::fromIterable);
}

// countByUserId 修复
@Override
public Mono<Long> countByUserId(String userId, String agentId) {
    final String uid = effectiveUserId(userId);
    final String aid = effectiveAgentId(agentId);
    return repository.countByUserIdAndAgentId(uid, aid);  // 已有此方法
}

// search 修复 — 增加 userId 过滤
@Override
public Flux<MemoryEntry> search(String cue, String userId, String agentId) {
    final String uid = effectiveUserId(userId);
    final String aid = effectiveAgentId(agentId);
    List<String> keywords = keywordExtractor.extract(cue);
    if (keywords.isEmpty()) {
        return repository.findByUserIdAndAgentId(uid, aid).map(this::toMemoryEntry);
    }
    String fulltextExpr = String.join(" ", keywords);
    return repository.fulltextSearch(uid, aid, fulltextExpr, 100)  // 使用已有的 userId+agentId 版本
            .map(this::toMemoryEntry)
            .switchIfEmpty(Flux.fromIterable(keywords)
                    .flatMap(kw -> repository.findByUserIdAndAgentId(uid, aid)
                            .filter(e -> e.getContent() != null && e.getContent().contains(kw)))
                    .distinct(AgentMemoryEntity::getId)
                    .map(this::toMemoryEntry));
}

// cacheKey 修复
private String cacheKey(String userId, String agentId) {
    return effectiveUserId(userId) + ":" + effectiveAgentId(agentId);
}

// invalidateCache 修复
private void invalidateCache(String userId, String agentId) {
    hotCache.invalidate(cacheKey(userId, agentId));
}
```

- [ ] **步骤 2：修复 AgentMemoryLifecycle — 检查 min_chunks_for_episodic**

`AgentMemoryLifecycle.java` 的 `storeSessionEpisodicMemory` 方法中，在 `if (chunks.isEmpty())` 检查后添加：

```java
long nonSystemCount = chunks.stream()
        .filter(c -> c.type() != com.atm.intellimate.memory.model.ChunkType.SYSTEM
                && c.type() != com.atm.intellimate.memory.model.ChunkType.RECALLED)
        .count();
if (nonSystemCount < minChunksForEpisodic) {
    log.info("storeSessionEpisodicMemory: skipped for session {} ({} non-system chunks < min {})",
            sessionId, nonSystemCount, minChunksForEpisodic);
    return;
}
```

- [ ] **步骤 3：修复 SessionHistoryController — 尊重 long_term.enabled**

在 `SessionHistoryController.java` 的 `persistFromTranscript` 流程中，包裹 episodic 存储逻辑在配置检查内。需要注入 `MemoryConfigProvider` 并在存储前检查 `longTermEnabled`。

- [ ] **步骤 4：运行现有测试确认无回归**

运行：`mvn test -pl intellimate-gateway,intellimate-agent -DfailIfNoTests=false`
预期：所有测试 PASS

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "fix(memory): enforce userId isolation, min_chunks check, and long_term.enabled gate"
```

---

### 任务 3：KeywordExtractor 升级 — jieba 中文分词

**文件：**
- 修改：`intellimate-memory/pom.xml`
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/retrieval/KeywordExtractor.java`
- 测试：`intellimate-memory/src/test/java/com/atm/intellimate/memory/retrieval/KeywordExtractorTest.java`（若不存在则创建）

- [ ] **步骤 1：添加 jieba-analysis 依赖**

在 `intellimate-memory/pom.xml` 的 `<dependencies>` 中添加：

```xml
<dependency>
    <groupId>com.huaban</groupId>
    <artifactId>jieba-analysis</artifactId>
    <version>1.0.2</version>
</dependency>
```

- [ ] **步骤 2：编写 KeywordExtractor 测试**

```java
@Test
void extract_chineseText_usesJiebaSegmentation() {
    KeywordExtractor extractor = new KeywordExtractor();
    List<String> tokens = extractor.extract("数据库连接超时问题");
    assertTrue(tokens.contains("数据库"), "Should contain '数据库' as a word, not bigrams");
    assertTrue(tokens.contains("连接") || tokens.contains("超时"), "Should contain meaningful words");
    assertFalse(tokens.contains("库连"), "Should NOT contain meaningless bigram '库连'");
}

@Test
void jaccardSimilarity_withJieba_improvedAccuracy() {
    KeywordExtractor extractor = new KeywordExtractor();
    double sim = extractor.jaccardSimilarity("数据库连接问题", "MySQL 数据库的连接池配置");
    assertTrue(sim > 0.2, "Jieba-based similarity should be higher for related content");
}
```

- [ ] **步骤 3：运行测试确认失败**

运行：`mvn test -pl intellimate-memory -Dtest="KeywordExtractorTest" -DfailIfNoTests=false`
预期：FAIL（因为当前用 bigram 会包含"库连"）

- [ ] **步骤 4：改造 KeywordExtractor 使用 jieba**

```java
package com.atm.intellimate.memory.retrieval;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;

import java.util.*;
import java.util.stream.Collectors;

public class KeywordExtractor {

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "我", "有", "和", "就", "不", "人", "都", "一", "一个",
            "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好",
            "这", "那", "个", "们", "来", "被", "把", "让", "给", "从",
            "the", "a", "an", "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did", "will", "would", "shall",
            "should", "may", "might", "must", "can", "could", "i", "you", "he",
            "she", "it", "we", "they", "this", "that", "and", "or", "but", "in",
            "on", "at", "to", "for", "of", "with", "from", "by", "as", "into",
            "about", "not", "no", "so", "if", "then", "what", "how", "when"
    );

    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    public List<String> extract(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        String normalized = text.toLowerCase();
        List<SegToken> segTokens = segmenter.process(normalized, JiebaSegmenter.SegMode.SEARCH);
        return segTokens.stream()
                .map(token -> token.word.trim())
                .filter(word -> word.length() >= 2 && !STOP_WORDS.contains(word))
                .distinct()
                .limit(15)
                .collect(Collectors.toList());
    }

    public double jaccardSimilarity(String a, String b) {
        Set<String> setA = Set.copyOf(extract(a));
        Set<String> setB = Set.copyOf(extract(b));
        if (setA.isEmpty() && setB.isEmpty()) return 1.0;
        if (setA.isEmpty() || setB.isEmpty()) return 0.0;
        long intersection = setA.stream().filter(setB::contains).count();
        long union = setA.size() + setB.size() - intersection;
        return (double) intersection / union;
    }
}
```

- [ ] **步骤 5：运行测试确认通过**

运行：`mvn test -pl intellimate-memory -Dtest="KeywordExtractorTest" -DfailIfNoTests=false`
预期：PASS

- [ ] **步骤 6：运行所有 memory 模块测试确认无回归**

运行：`mvn test -pl intellimate-memory -DfailIfNoTests=false`
预期：所有测试 PASS

- [ ] **步骤 7：Commit**

```bash
git add -A && git commit -m "feat(memory): replace bigram with jieba for Chinese tokenization in KeywordExtractor"
```

---

### 任务 4：ScoringFunction 类型差异化评分

**文件：**
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/retrieval/ScoringFunction.java`
- 测试：`intellimate-memory/src/test/java/com/atm/intellimate/memory/retrieval/ScoringFunctionTest.java`

- [ ] **步骤 1：编写类型差异化评分测试**

```java
@Test
void computeRetrievalScore_semanticMemory_hasHigherWeightAndSlowerDecay() {
    ScoringFunction sf = new ScoringFunction(1.2, 0.8, 1.0, 0.03, 0.10, 0.05);
    MemoryEntry semantic = createEntry("semantic", 0.7f, 30);
    MemoryEntry episodic = createEntry("episodic", 0.7f, 30);

    double semanticScore = sf.computeRetrievalScore(semantic, 0.5, 0.1);
    double episodicScore = sf.computeRetrievalScore(episodic, 0.5, 0.1);

    assertTrue(semanticScore > episodicScore,
        "Semantic memory should score higher than episodic after 30 days");
}
```

- [ ] **步骤 2：运行测试确认失败**

运行：`mvn test -pl intellimate-memory -Dtest="ScoringFunctionTest#computeRetrievalScore_semanticMemory_hasHigherWeightAndSlowerDecay" -DfailIfNoTests=false`
预期：FAIL（构造函数不存在）

- [ ] **步骤 3：改造 ScoringFunction，增加类型参数**

```java
public class ScoringFunction {
    private final double semanticWeight;
    private final double episodicWeight;
    private final double proceduralWeight;
    private final double semanticDecayLambda;
    private final double episodicDecayLambda;
    private final double proceduralDecayLambda;

    public ScoringFunction() {
        this(1.2, 0.8, 1.0, 0.03, 0.10, 0.05);
    }

    public ScoringFunction(double semanticWeight, double episodicWeight, double proceduralWeight,
                           double semanticDecayLambda, double episodicDecayLambda, double proceduralDecayLambda) {
        this.semanticWeight = semanticWeight;
        this.episodicWeight = episodicWeight;
        this.proceduralWeight = proceduralWeight;
        this.semanticDecayLambda = semanticDecayLambda;
        this.episodicDecayLambda = episodicDecayLambda;
        this.proceduralDecayLambda = proceduralDecayLambda;
    }

    public double computeRetrievalScore(MemoryEntry memory, double relevance, double lambda) {
        double importance = memory.getImportance();
        double typeWeight = getTypeWeight(memory.getMemoryType());
        double typeLambda = getTypeLambda(memory.getMemoryType(), lambda);
        double recencyDecay = computeRecencyDecay(memory.getLastAccessedAt(), typeLambda);
        double accessBoost = 1.0 + Math.log1p(memory.getAccessCount());
        return relevance * importance * typeWeight * recencyDecay * accessBoost;
    }

    // computeRetentionScore 同样改造...

    private double getTypeWeight(String memoryType) {
        if (memoryType == null) return 1.0;
        return switch (memoryType) {
            case "semantic" -> semanticWeight;
            case "episodic" -> episodicWeight;
            case "procedural" -> proceduralWeight;
            default -> 1.0;
        };
    }

    private double getTypeLambda(String memoryType, double baseLambda) {
        if (memoryType == null) return baseLambda;
        return switch (memoryType) {
            case "semantic" -> semanticDecayLambda;
            case "episodic" -> episodicDecayLambda;
            case "procedural" -> proceduralDecayLambda;
            default -> baseLambda;
        };
    }

    // computeRecencyDecay 保持不变
}
```

- [ ] **步骤 4：运行测试确认通过**

运行：`mvn test -pl intellimate-memory -Dtest="ScoringFunctionTest" -DfailIfNoTests=false`
预期：PASS

- [ ] **步骤 5：更新 MemoryRetrieval 中 ScoringFunction 的构造**

在 `MemoryRetrieval` 构造函数中，接受 `ScoringFunction` 而非自行 `new`（便于注入配置化参数）。

- [ ] **步骤 6：Commit**

```bash
git add -A && git commit -m "feat(memory): add type-differentiated weights and decay in ScoringFunction"
```

---

### 任务 5：持久化质量优化

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/LongTermMemoryImpl.java`
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentMemoryLifecycle.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/PlanExecutionOrchestrator.java`
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/longterm/LongTermMemory.java`（recordAccess 签名扩展）

- [ ] **步骤 1：fact 质量过滤 — LongTermMemoryImpl.store() 入口添加门控**

在 `store()` 方法最开头、去重检查之前加入：

```java
if (fact.importance() < minFactImportance) {
    log.debug("Fact filtered by importance: {} < {}", fact.importance(), minFactImportance);
    return Mono.empty();
}
if (fact.content() == null || fact.content().isBlank()) {
    log.debug("Fact filtered: content is null or blank");
    return Mono.empty();
}
```

`minFactImportance` 从注入的配置中获取（需要 `LongTermMemoryImpl` 支持配置更新）。

- [ ] **步骤 2：合并内容大小限制**

在 `store()` 的合并分支中，拼接后检查长度：

```java
mergedContent = existing.getContent() + "\n---\n" + fact.content();
if (mergedContent.length() > maxMergedContentLength) {
    mergedContent = mergedContent.substring(mergedContent.length() - maxMergedContentLength);
    int firstSep = mergedContent.indexOf("\n---\n");
    if (firstSep > 0) {
        mergedContent = mergedContent.substring(firstSep + 5);
    }
}
```

- [ ] **步骤 3：semantic 类型冲突检测 — 替换而非拼接**

在合并分支中，对 semantic 类型特殊处理：

```java
if ("semantic".equals(fact.type()) && similarity > DEDUP_SIMILARITY_THRESHOLD) {
    existing.setContent(fact.content());
    existing.setImportance(Math.max(existing.getImportance(), fact.importance()));
    existing.setAccessCount(existing.getAccessCount() + 1);
    existing.setLastAccessedAt(LocalDateTime.now());
    return repository.save(existing).then();
}
```

- [ ] **步骤 4：episodic 持久化增强 — AgentMemoryLifecycle**

在 `storeSessionEpisodicMemory` 中：
1. 从 USER chunk 提取主题关键词（取频率 top 3）
2. 判断会话结果（检查最后 ASSISTANT chunk 的关键词）
3. 将 topics + outcome 写入 `metadata_json`
4. 根据 outcome 调整 importance

- [ ] **步骤 5：procedural 前置条件记录 — PlanExecutionOrchestrator**

在存储 procedural 记忆时，将 plan 名称和适用上下文写入 `metadata_json`。

- [ ] **步骤 6：recordAccess 扩展 — 支持 importance boost**

在 `LongTermMemory` 接口中新增：

```java
Mono<Void> recordAccess(MemoryEntry entry, float importanceBoost);
```

`LongTermMemoryImpl` 实现中，当 boost > 0 时同时更新 importance。

- [ ] **步骤 7：运行测试确认无回归**

运行：`mvn test -pl intellimate-gateway,intellimate-agent -DfailIfNoTests=false`

- [ ] **步骤 8：Commit**

```bash
git add -A && git commit -m "feat(memory): add persistence quality gates, type-specific storage strategies"
```

---

### 任务 6：MemoryEntry 召回标注增强

**文件：**
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/model/MemoryEntry.java`
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/retrieval/MemoryRetrieval.java`
- 测试：`intellimate-memory/src/test/java/com/atm/intellimate/memory/model/MemoryEntryTest.java`（若不存在则创建）

- [ ] **步骤 1：编写测试**

```java
@Test
void toRecalledChunk_withRelevanceScore_includesTypeAndDatePrefix() {
    MemoryEntry entry = new MemoryEntry("user1", "agent1", "semantic", "用户偏好简洁代码", 0.8f, 1L);
    entry.setCreatedAt(Instant.parse("2026-05-28T10:00:00Z"));
    MemoryChunk chunk = entry.toRecalledChunk(50, 0.82);
    assertTrue(chunk.content().startsWith("[历史记忆 | 知识 | 2026-05-28 | 相关度:0.82]"));
    assertTrue(chunk.content().contains("用户偏好简洁代码"));
}
```

- [ ] **步骤 2：实现 toRecalledChunk 重载**

在 `MemoryEntry.java` 中添加：

```java
public MemoryChunk toRecalledChunk(int estimatedTokens, double relevanceScore) {
    String typeLabel = switch (memoryType) {
        case "semantic" -> "知识";
        case "episodic" -> "事件";
        case "procedural" -> "流程";
        default -> memoryType;
    };
    String dateStr = createdAt != null
        ? createdAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()
        : "未知";
    String prefix = String.format("[历史记忆 | %s | %s | 相关度:%.2f] ",
        typeLabel, dateStr, relevanceScore);
    return MemoryChunk.recalled(prefix + content, estimatedTokens, importance);
}
```

- [ ] **步骤 3：修改 MemoryRetrieval.selectAndScore，传递 relevance 分数**

在 `selectAndScore` 中，将 `sm.entry().toRecalledChunk(chunkTokens)` 改为 `sm.entry().toRecalledChunk(chunkTokens, sm.relevance())`。

- [ ] **步骤 4：运行测试确认通过**

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "feat(memory): enhance recalled memory annotation with type, date, and relevance"
```

---

### 任务 7：VectorMemoryStore 接口 + VectorSearchResult

**文件：**
- 创建：`intellimate-memory/src/main/java/com/atm/intellimate/memory/retrieval/VectorMemoryStore.java`
- 创建：`intellimate-memory/src/main/java/com/atm/intellimate/memory/retrieval/VectorSearchResult.java`

- [ ] **步骤 1：创建 VectorMemoryStore 接口**

```java
package com.atm.intellimate.memory.retrieval;

import com.atm.intellimate.memory.model.MemoryEntry;
import reactor.core.publisher.Mono;
import java.util.List;

public interface VectorMemoryStore {
    Mono<Void> store(MemoryEntry entry);
    Mono<List<VectorSearchResult>> search(String query, String userId, String agentId, int topK);
    Mono<Void> deleteById(Long mysqlId);
    Mono<Boolean> isAvailable();
}
```

- [ ] **步骤 2：创建 VectorSearchResult 记录**

```java
package com.atm.intellimate.memory.retrieval;

import com.atm.intellimate.memory.model.MemoryChunk;
import java.time.Instant;
import java.time.ZoneId;

public record VectorSearchResult(
        Long mysqlId,
        String content,
        String memoryType,
        float importance,
        double similarity,
        Instant createdAt
) {
    public MemoryChunk toRecalledChunk(int estimatedTokens) {
        String typeLabel = switch (memoryType) {
            case "semantic" -> "知识";
            case "episodic" -> "事件";
            case "procedural" -> "流程";
            default -> memoryType;
        };
        String dateStr = createdAt != null
                ? createdAt.atZone(ZoneId.systemDefault()).toLocalDate().toString()
                : "未知";
        String prefix = String.format("[历史记忆 | %s | %s | 相关度:%.2f] ",
                typeLabel, dateStr, similarity);
        return MemoryChunk.recalled(prefix + content, estimatedTokens, importance);
    }
}
```

- [ ] **步骤 3：Commit**

```bash
git add -A && git commit -m "feat(memory): add VectorMemoryStore interface and VectorSearchResult"
```

---

### 任务 8：Qdrant 基础设施 — Docker + Spring 配置 + 实现

**文件：**
- 修改：`intellimate-gateway/pom.xml`
- 修改：`docker-compose.yml`（若存在）或创建启动说明
- 修改：`intellimate-gateway/src/main/resources/application.yml`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/QdrantVectorStoreConfig.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/QdrantVectorStoreImpl.java`

- [ ] **步骤 1：添加 spring-ai-qdrant-store 依赖**

在 `intellimate-gateway/pom.xml` 中添加：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-qdrant-store-spring-boot-starter</artifactId>
</dependency>
```

确认 Spring AI BOM 版本中包含 Qdrant 模块。

- [ ] **步骤 2：配置 application.yml**

```yaml
spring:
  ai:
    vectorstore:
      qdrant:
        host: ${QDRANT_HOST:localhost}
        port: ${QDRANT_GRPC_PORT:6334}
        collection-name: intellimate_memories
        initialize-schema: true
```

- [ ] **步骤 3：Docker 配置**

在 `docker-compose.yml` 或项目启动文档中添加 Qdrant：

```yaml
qdrant:
  image: qdrant/qdrant:latest
  ports:
    - "6333:6333"
    - "6334:6334"
  volumes:
    - qdrant_data:/qdrant/storage
  restart: unless-stopped
```

- [ ] **步骤 4：创建 QdrantVectorStoreConfig**

```java
@Configuration
@ConditionalOnProperty(name = "spring.ai.vectorstore.qdrant.host")
public class QdrantVectorStoreConfig {

    @Bean
    @ConditionalOnBean(VectorStore.class)
    public VectorMemoryStore vectorMemoryStore(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        return new QdrantVectorStoreImpl(vectorStore, embeddingModel);
    }
}
```

- [ ] **步骤 5：实现 QdrantVectorStoreImpl**

实现 `VectorMemoryStore` 接口，使用 Spring AI 的 `VectorStore` API：
- `store()`: 构建 `Document` 对象（content + metadata），调用 `vectorStore.add()`
- `search()`: 构建 `SearchRequest` 带 filter expression（`agent_id == 'xxx' && user_id == 'yyy'`），调用 `vectorStore.similaritySearch()`
- `isAvailable()`: try-catch 一个简单搜索，超时 2 秒
- `deleteById()`: 调用 `vectorStore.delete()` 带 filter

- [ ] **步骤 6：运行启动测试确认 Bean 可加载**

运行：`mvn test -pl intellimate-gateway -DfailIfNoTests=false`
预期：测试 PASS（Qdrant 不可用时不会阻塞启动）

- [ ] **步骤 7：Commit**

```bash
git add -A && git commit -m "feat(memory): add Qdrant vector store integration with Spring AI"
```

---

### 任务 9：LongTermMemoryImpl 双写

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/LongTermMemoryImpl.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/MemorySystemConfig.java`

- [ ] **步骤 1：注入 VectorMemoryStore（可选依赖）**

```java
@Service
public class LongTermMemoryImpl implements LongTermMemory {
    private final AgentMemoryRepository repository;
    private final VectorMemoryStore vectorMemoryStore;  // nullable

    public LongTermMemoryImpl(AgentMemoryRepository repository,
                               @Autowired(required = false) VectorMemoryStore vectorMemoryStore) {
        this.repository = repository;
        this.vectorMemoryStore = vectorMemoryStore;
    }
}
```

- [ ] **步骤 2：在 store() 成功后异步写入向量**

在 MySQL 写入成功的 `.doOnSuccess()` 或 `.then()` 链中添加：

```java
.doOnSuccess(saved -> {
    if (vectorMemoryStore != null) {
        MemoryEntry entry = toMemoryEntry(saved);
        vectorMemoryStore.store(entry)
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1)))
                .subscribe(
                        null,
                        err -> log.error("Vector store write failed after retries for mysql_id={}: {}",
                                saved.getId(), err.getMessage())
                );
    }
})
```

- [ ] **步骤 3：在 deleteById() 中同步删除向量**

```java
@Override
public Mono<Void> deleteById(Long id) {
    return repository.findById(id)
            .doOnNext(entity -> invalidateCache(entity.getUserId(), entity.getAgentId()))
            .flatMap(entity -> {
                Mono<Void> deleteFromDb = repository.deleteById(id);
                if (vectorMemoryStore != null) {
                    return deleteFromDb.then(vectorMemoryStore.deleteById(id)
                            .onErrorResume(e -> { log.warn("Vector delete failed: {}", e.getMessage()); return Mono.empty(); }));
                }
                return deleteFromDb;
            });
}
```

- [ ] **步骤 4：运行测试确认无回归**

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "feat(memory): add dual-write to Qdrant vector store in LongTermMemoryImpl"
```

---

### 任务 10：HybridMemoryRetrieval 编排器

**文件：**
- 创建：`intellimate-memory/src/main/java/com/atm/intellimate/memory/retrieval/HybridMemoryRetrieval.java`
- 测试：`intellimate-memory/src/test/java/com/atm/intellimate/memory/retrieval/HybridMemoryRetrievalTest.java`

- [ ] **步骤 1：编写测试**

```java
@Test
void retrieve_hybridMode_mergesVectorAndKeywordResults() {
    // Mock VectorMemoryStore 返回 2 条结果
    // Mock MemoryRetrieval (keyword) 返回 2 条结果
    // 其中 1 条在两路中都出现（mysql_id 相同）
    // 验证：融合后去重，最终 3 条，按 finalScore 降序
}

@Test
void retrieve_vectorUnavailable_fallsBackToKeyword() {
    // Mock VectorMemoryStore.isAvailable() = false
    // 验证：自动降级到 keyword-only
}
```

- [ ] **步骤 2：实现 HybridMemoryRetrieval**

```java
public class HybridMemoryRetrieval {

    public enum Strategy { HYBRID, VECTOR_ONLY, KEYWORD_ONLY }

    private final MemoryRetrieval keywordRetrieval;
    private final VectorMemoryStore vectorStore;
    private final TokenEstimator tokenEstimator;
    private final float vectorWeight;
    private final float keywordWeight;

    public Mono<List<MemoryChunk>> retrieve(
            String cue, String userId, String agentId,
            int maxInjectionTokens, double lambda, Strategy strategy) {

        if (vectorStore == null || strategy == Strategy.KEYWORD_ONLY) {
            return keywordRetrieval.retrieve(cue, userId, agentId, maxInjectionTokens, lambda);
        }

        return vectorStore.isAvailable()
                .flatMap(available -> {
                    if (!available || strategy == Strategy.KEYWORD_ONLY) {
                        return keywordRetrieval.retrieve(cue, userId, agentId, maxInjectionTokens, lambda);
                    }
                    if (strategy == Strategy.VECTOR_ONLY) {
                        return vectorOnlyRetrieve(cue, userId, agentId, maxInjectionTokens);
                    }
                    return hybridRetrieve(cue, userId, agentId, maxInjectionTokens, lambda);
                });
    }

    private Mono<List<MemoryChunk>> hybridRetrieve(...) {
        Mono<List<VectorSearchResult>> vectorMono = vectorStore.search(cue, userId, agentId, 20);
        Mono<List<MemoryChunk>> keywordMono = keywordRetrieval.retrieve(cue, userId, agentId, maxInjectionTokens, lambda);

        return Mono.zip(vectorMono, keywordMono)
                .map(tuple -> mergeAndRank(tuple.getT1(), tuple.getT2(), maxInjectionTokens));
    }

    private List<MemoryChunk> mergeAndRank(List<VectorSearchResult> vectorResults,
                                            List<MemoryChunk> keywordResults,
                                            int maxTokens) {
        // 1. 以 mysql_id 为 key 合并
        // 2. 融合分数 = α × vectorSim + β × keywordScore (归一化)
        // 3. 按融合分数降序
        // 4. Token budget 截取
    }
}
```

- [ ] **步骤 3：运行测试确认通过**

- [ ] **步骤 4：Commit**

```bash
git add -A && git commit -m "feat(memory): implement HybridMemoryRetrieval with vector+keyword fusion"
```

---

### 任务 11：AgentLoopExecutor 集成

**文件：**
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentLoopExecutor.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/config/MemorySystemConfig.java`

- [ ] **步骤 1：在 MemorySystemConfig 中注册 HybridMemoryRetrieval Bean**

```java
@Bean
public HybridMemoryRetrieval hybridMemoryRetrieval(
        MemoryRetrieval keywordRetrieval,
        @Autowired(required = false) VectorMemoryStore vectorStore,
        TokenEstimator tokenEstimator,
        ResolvedMemoryConfig config) {
    return new HybridMemoryRetrieval(
            keywordRetrieval, vectorStore, tokenEstimator,
            config.vectorWeight(), config.keywordWeight());
}
```

- [ ] **步骤 2：修改 AgentLoopExecutor，注入 HybridMemoryRetrieval**

将现有 `MemoryRetrieval` 注入改为 `HybridMemoryRetrieval`（可选依赖）：
- 如果 `HybridMemoryRetrieval` 可用，使用它（根据 config 决定 strategy）
- 否则回退到现有 `MemoryRetrieval`

- [ ] **步骤 3：修改记忆注入逻辑，移除手动添加 [历史记忆] 前缀**

当前 `AgentLoopExecutor` 手动添加 `[历史记忆]` 前缀。因为 `toRecalledChunk` 已包含增强标注，需要移除手动前缀避免重复：

```java
// 之前:
String prefixed = "[历史记忆] " + chunk.content();
MemoryChunk recalledWithPrefix = MemoryChunk.recalled(prefixed, ...);

// 之后:
// chunk 已经包含 [历史记忆 | 知识 | 2026-05-28 | 相关度:0.82] 前缀
workingMemory.accept(chunk);
```

- [ ] **步骤 4：运行全量测试**

运行：`mvn test -DfailIfNoTests=false`
预期：所有测试 PASS

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "feat(memory): integrate HybridMemoryRetrieval into AgentLoopExecutor"
```

---

### 任务 12：数据迁移 — 现有 MySQL 记忆向量化

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/migration/VectorMemoryMigrationRunner.java`

- [ ] **步骤 1：实现 ApplicationRunner 迁移任务**

```java
@Component
@ConditionalOnProperty(name = "spring.ai.vectorstore.qdrant.host")
public class VectorMemoryMigrationRunner implements ApplicationRunner {

    private final AgentMemoryRepository repository;
    private final VectorMemoryStore vectorStore;

    @Override
    public void run(ApplicationArguments args) {
        // 1. 检查 vectorStore.isAvailable()
        // 2. 遍历 agent_memory 表
        // 3. 每批 50 条，批次间 200ms 间隔
        // 4. 按 mysql_id 去重（幂等）
        // 5. 每 100 条输出进度日志
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add -A && git commit -m "feat(memory): add startup migration runner for existing memories to Qdrant"
```

---

### 任务 13：前端配置 UI 更新

**文件：**
- 修改：`intellimate-web/src/components/MemoryManagerPage.tsx`
- 修改：`intellimate-web/src/stores/memoryStore.ts`

- [ ] **步骤 1：在 memoryStore.ts 中更新配置分组**

新增 `vector`、`retrieval`、`scoring` 分组到配置解析逻辑中。

- [ ] **步骤 2：在 MemoryManagerPage.tsx 配置 Tab 中新增分组展示**

在"配置"Tab 中添加三个新分组的表单控件：
- 向量配置：enabled 开关、embedding model、dimensions
- 检索策略：strategy 下拉、vector_weight/keyword_weight 滑块
- 评分参数：六个类型相关参数
- 持久化质量：min_fact_importance、max_merged_content_length

- [ ] **步骤 3：Commit**

```bash
git add -A && git commit -m "feat(web): add vector, retrieval, and scoring config sections to memory settings"
```

---

### 任务 14：最终集成验证

- [ ] **步骤 1：启动 Docker 服务**

```bash
docker-compose up -d mysql qdrant
```

- [ ] **步骤 2：运行全量测试**

```bash
mvn clean test -DfailIfNoTests=false
```

- [ ] **步骤 3：启动应用验证**

```bash
mvn spring-boot:run -pl intellimate-gateway
```

检查日志确认：
1. Flyway 迁移成功
2. Qdrant 连接正常
3. 数据迁移任务执行
4. 记忆检索使用混合模式

- [ ] **步骤 4：功能验证**

1. 存储一条 semantic 记忆，验证同时写入 MySQL 和 Qdrant
2. 用语义相关但词面不同的 query 检索，验证向量路径能召回
3. 关闭 Qdrant，验证降级到纯关键词模式
4. 检查召回记忆的标注格式是否正确

- [ ] **步骤 5：Commit**

```bash
git add -A && git commit -m "feat(memory): memory system v2 - hybrid vector retrieval complete"
```
