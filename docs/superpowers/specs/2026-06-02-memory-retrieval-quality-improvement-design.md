# 长期记忆检索质量全链路优化设计

> 日期: 2026-06-02
> 状态: Draft
> 范围: intellimate-memory, intellimate-gateway, intellimate-agent

## 1. 问题背景

### 1.1 现象

对话持久化的长期记忆效果不佳，向量库检索相关性极低。日志示例：

```
[记忆注入] agent='GroupChat', 共召回 6 条记忆, 耗时 1569ms:
[记忆注入] >> type=RECALLED, tokens=10, preview='[历史记忆 | 知识 | 相关度:0.15] 用户偏好使用中文称呼与助手交互'
[记忆注入] >> type=RECALLED, tokens=10, preview='[历史记忆 | 事件 | 相关度:0.10] 用户要求将助手名称设定为张三'
```

6 条召回记忆的相关度均为 0.10-0.15（基本等于噪声），且出现重复条目（同一条记忆出现两次）。

### 1.2 根因分析

通过代码审查定位到 6 个具体问题：

| # | 问题 | 所在模块 | 严重程度 |
|---|------|----------|----------|
| 1 | 记忆提取质量低：LLM 生成的事实仅 5-12 字，信息量不足以支撑语义检索 | `ConsolidationPromptBuilder` | 高 |
| 2 | 关键词提取对短中文查询完全失效："你是谁"经结巴分词 + 停用词过滤后提取结果为空集 | `KeywordExtractor` | 高 |
| 3 | 混合检索去重键不一致（缺陷）：向量路径用 `"id:"+mysqlId`，关键词用 `"keyword:"+hashCode`，永远不匹配 | `HybridMemoryRetrieval` | 高 |
| 4 | 向量搜索无相似度下限：前 20 条结果不管余弦分数多低都会被注入 | `QdrantVectorStoreImpl` / `HybridMemoryRetrieval` | 中 |
| 5 | 向量路径不应用评分函数：只用 `余弦相似度 × 向量权重`，不考虑重要度/时间衰减/访问频率 | `HybridMemoryRetrieval` | 中 |
| 6 | 回退路径使用硬编码假相关度：`fallbackByImportance` 给知识类分配 0.15、其他 0.10，误导日志 | `MemoryRetrieval` | 低 |

### 1.3 影响分析

- "你是谁"等短查询：Jaccard=0 → 全部走回退路径 → 只按重要度排序 → 相关性几乎为零
- 向量路径：因无相似度下限 + 不应用质量加权，低质量记忆照样被注入
- 去重缺陷：同一记忆出现两次，浪费 token 预算

## 2. 设计目标

1. **存储端**：提升记忆提取质量，确保事实内容丰富到足以支撑语义检索
2. **检索端**：修复已知缺陷，增强检索准确性
3. **可观测性**：增加审计日志和指标，方便追踪效果

## 3. 详细设计

### 3.1 记忆提取质量优化

#### 3.1.1 改进巩固提示词构建器

**文件**: `intellimate-memory/.../consolidation/ConsolidationPromptBuilder.java`

在现有提示词的「任务二：事实提取」部分增加质量约束：

```
## 事实提取质量要求
每个事实的 content 必须满足：
- 长度：30-100 个中文字符
- 包含关键实体：人名、项目名、技术名、具体值等
- 包含上下文语境：在什么场景下、为什么、具体怎样
- 避免纯概括性描述，要有具体可区分的信息

示例对比：
❌ 差: {"type":"semantic", "content":"用户偏好使用中文", "importance":0.7}
✅ 好: {"type":"semantic", "content":"用户在首次对话中明确要求助手用中文回复，并将助手命名为'张三'作为默认交互称呼", "importance":0.7}

❌ 差: {"type":"episodic", "content":"用户调试了代码", "importance":0.5}
✅ 好: {"type":"episodic", "content":"用户在 IntelliMate 项目中调试 AgentLoopExecutor 的记忆注入逻辑，修复了向量检索不生效的问题", "importance":0.5}
```

#### 3.1.2 事实质量检查门槛

**文件**: `intellimate-gateway/.../service/LongTermMemoryImpl.java`

在 `store()` 方法的现有最低重要度检查之后，增加内容质量校验：

```java
private volatile int minFactContentLength = 15;  // 可通过 ResolvedMemoryConfig 配置

@Override
public Mono<Void> store(ExtractedFact fact, String userId, String agentId, String metadataJson) {
    // 现有重要度检查
    if (fact.importance() < minFactImportance) {
        return Mono.empty();
    }
    if (fact.content() == null || fact.content().isBlank()) {
        return Mono.empty();
    }
    // 新增：内容长度门槛
    if (fact.content().length() < minFactContentLength) {
        log.info("[记忆存储-质量] 事实被拒绝: 内容长度={} < 最低要求({}), 内容='{}'",
                fact.content().length(), minFactContentLength, fact.content());
        return Mono.empty();
    }
    // ... 后续去重和存储逻辑
}
```

配置来源：在 `ResolvedMemoryConfig` 中新增 `long_term.min_fact_content_length` 字段，默认值 15。

#### 3.1.3 事件/流程类记忆去重合并策略调整

**文件**: `intellimate-gateway/.../service/LongTermMemoryImpl.java`

**问题**: 当前事件/流程类记忆在 Jaccard > 0.85 时追加合并（`\n---\n` 分隔），导致：
- 合并后文本碎片化，向量化质量差
- 超过最大合并长度后截断丢失早期信息
- 一条记忆内混杂多次事件片段，检索时语义模糊

**改动**: 将事件/流程类的合并阈值从 0.85 提高到 0.95（几乎完全重复时才合并），0.85-0.95 之间的相似记忆作为独立条目存储。知识类的覆盖策略保持不变。

```java
private static final double DEDUP_SIMILARITY_THRESHOLD = 0.85;
private static final double MERGE_SIMILARITY_THRESHOLD = 0.95;  // 新增：事件/流程类的合并阈值

// 在 store() 方法中：
if ("semantic".equals(fact.type()) && similarity > DEDUP_SIMILARITY_THRESHOLD) {
    // 知识类：覆盖（保持不变）
    existing.setContent(fact.content());
    // ...
} else if (similarity > MERGE_SIMILARITY_THRESHOLD) {
    // 事件/流程类：仅几乎完全重复时才合并
    // ...合并逻辑
} else {
    // 0.85-0.95 之间：作为独立条目存储（跳过此匹配，走新建路径）
    return Mono.empty();
}
```

这样每条记忆都是一个完整的、可独立理解的语句，向量化质量更好。

#### 3.1.4 事件记忆回退存储增强

**文件**: `intellimate-agent/.../runtime/AgentMemoryLifecycle.java`

当 LLM 摘要失败走 `storeFullConversationFallback` 时，增强提取逻辑：

- 提取**关键转折点**：用户的第一条消息、最后一条消息、包含转折词（"但是"、"不是"、"改为"、"改成"）的消息
- 在元数据中补充前 5 个关键词，方便后续关键词检索路径命中

```java
private void storeFullConversationFallback(List<MemoryChunk> chunks, LongTermMemory ltm,
                                            String userId, String agentId, Long sessionId) {
    // 新增：提取关键转折点而非简单截取
    StringBuilder conversation = new StringBuilder();
    List<MemoryChunk> keyChunks = extractKeyChunks(chunks);  // 首条、末条、转折点
    for (MemoryChunk c : keyChunks) {
        // ... 格式化
    }
    
    List<String> topTopics = extractTopTopicsFromUserChunks(chunks, 5);  // 增至 5 个
    String metadataJson = buildEpisodicMetadataJson(topTopics, outcome);
    // ...
}
```

### 3.2 检索路径修复与增强

#### 3.2.1 修复混合检索去重键缺陷

**文件**: `intellimate-memory/.../retrieval/HybridMemoryRetrieval.java`

**前置依赖**: `MemoryChunk` 需新增 `sourceId` 字段（来自 MySQL 主键），通过 `MemoryEntry.toRecalledChunk()` 传入。

修复 `mergeAndRank()` 中的键生成逻辑，统一使用 MySQL 主键作为去重标识：

```java
private List<MemoryChunk> mergeAndRank(List<VectorSearchResult> vectorResults,
                                        List<MemoryChunk> keywordResults,
                                        int maxTokens) {
    Map<String, MergedCandidate> candidates = new LinkedHashMap<>();

    // 向量路径
    for (VectorSearchResult vr : vectorResults) {
        String key = vr.mysqlId() != null
                ? String.valueOf(vr.mysqlId())
                : "vec:" + vr.content().hashCode();
        candidates.put(key, new MergedCandidate(
                vr.toRecalledChunk(vr.content().length() / 3),
                vr.similarity() * vectorWeight,
                0.0
        ));
    }

    // 关键词路径：使用 sourceId 匹配向量路径的键
    for (int i = 0; i < keywordResults.size(); i++) {
        MemoryChunk kw = keywordResults.get(i);
        double normalizedKeywordScore = 1.0 - ((double) i / keywordResults.size());
        String key = kw.sourceId() != null
                ? String.valueOf(kw.sourceId())
                : "kw:" + kw.content().hashCode();

        MergedCandidate existing = candidates.get(key);
        if (existing != null) {
            // 同一记忆两路命中：合并分数
            candidates.put(key, new MergedCandidate(
                    existing.chunk,
                    existing.vectorScore,
                    normalizedKeywordScore * keywordWeight
            ));
        } else {
            candidates.put(key, new MergedCandidate(
                    kw, 0.0, normalizedKeywordScore * keywordWeight));
        }
    }
    // ... 排序和 token 预算裁剪保持不变
}
```

**MemoryChunk 改动**: 新增 `sourceId` 字段（Long 类型，可为空）。

```java
// MemoryChunk record 新增字段
public record MemoryChunk(ChunkType type, String content, int estimatedTokens,
                           float importance, String category, Long sourceId) {
    // sourceId: 来源 MySQL 主键，用于混合检索去重
    // 现有工厂方法保持兼容：不传 sourceId 时默认为空
}
```

**MemoryEntry.toRecalledChunk() 改动**: 传入 `entry.getId()` 作为 `sourceId`。

#### 3.2.2 向量搜索增加相似度下限

**文件**: `intellimate-gateway/.../service/QdrantVectorStoreImpl.java`

利用 Spring AI 的相似度阈值参数过滤低分结果：

```java
@Override
public Mono<List<VectorSearchResult>> search(String query, String userId, String agentId, int topK) {
    return Mono.fromCallable(() -> {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)  // 新增，默认 0.35
                .filterExpression(filter)
                .build();
        // ...
    });
}
```

`similarityThreshold` 从构造函数注入，默认 0.35，可通过 `ResolvedMemoryConfig` 的 `vector.similarity_threshold` 配置。

新增配置项：`vector.similarity_threshold`，数据库默认值 `0.35`。

#### 3.2.3 向量路径应用质量加权

**文件**: `intellimate-memory/.../retrieval/HybridMemoryRetrieval.java`

向量候选在合并阶段应用重要度和时效性加权：

```java
for (VectorSearchResult vr : vectorResults) {
    double importanceBoost = Math.max(0.3, vr.importance());
    double recencyBoost = computeRecencyBoost(vr.createdAt(), 0.05);
    double weightedVectorScore = vr.similarity() * vectorWeight * importanceBoost * recencyBoost;
    // ...
}

private double computeRecencyBoost(Instant createdAt, double lambda) {
    if (createdAt == null) return 0.5;
    long daysSince = Duration.between(createdAt, Instant.now()).toDays();
    return Math.exp(-lambda * daysSince);
}
```

使用较小的衰减系数（0.05），避免新记忆过度衰减。

#### 3.2.4 基于模型的查询扩展（QueryExpander）

**新增文件**: `intellimate-memory/.../retrieval/QueryExpander.java`

当关键词提取结果 ≤ 1 个词时，调用 LLM 做查询改写：

```java
public class QueryExpander {
    private final ChatModel chatModel;
    private final KeywordExtractor keywordExtractor;
    private final String modelId;
    private static final long TIMEOUT_MS = 1500;

    private static final String EXPAND_PROMPT = """
            你是记忆检索助手。用户输入了一个简短的查询，需要你将它扩展为更丰富的检索查询。
            要求：
            - 保留原始意图
            - 补充可能相关的同义词和上下文词
            - 输出一句话，不超过 50 字
            - 不要解释，直接输出扩展后的查询
            
            用户查询：%s
            扩展查询：""";

    public Mono<String> expandIfNeeded(String cue) {
        if (cue == null || cue.isBlank()) return Mono.just("");
        List<String> keywords = keywordExtractor.extract(cue);
        if (keywords.size() >= 2) {
            return Mono.just(cue);
        }
        return callLLMExpand(cue)
                .timeout(Duration.ofMillis(TIMEOUT_MS))
                .onErrorResume(e -> {
                    log.debug("查询扩展超时/失败，使用原始查询: {}", e.getMessage());
                    return Mono.just(cue);
                });
    }
}
```

**集成点**: 在 `HybridMemoryRetrieval.retrieve()` 入口处调用 `QueryExpander`。
扩展后的查询**仅用于向量搜索路径**，关键词路径仍用原始查询。

**模型选择**: 复用巩固模型（已配置的最轻量模型），不引入新的模型依赖。

**超时策略**: 1500ms 超时覆盖 LLM 冷启动场景。由于查询扩展与关键词检索可并行执行，实际增加的端到端延迟通常小于超时值。超时后回退到原始查询，不影响正常检索。

**关键词提取器微调**: 放宽单字中文过滤——对疑问代词（谁、何、哪、什么）和高信息量单字不过滤：

```java
private static final Set<String> MEANINGFUL_SINGLE_CHARS = Set.of(
        "谁", "何", "哪", "吗", "呢"
);

private static void addToken(Set<String> tokens, String word) {
    if (word.length() >= 2 && !STOP_WORDS.contains(word)) {
        tokens.add(word);
    } else if (word.length() == 1 && MEANINGFUL_SINGLE_CHARS.contains(word)) {
        tokens.add(word);
    }
}
```

#### 3.2.5 回退机制改进

**文件**: `intellimate-memory/.../retrieval/MemoryRetrieval.java`

`fallbackByImportance` 不再使用硬编码假相关度：

```java
private List<ScoredMemory> fallbackByImportance(List<MemoryEntry> candidates, double lambda) {
    return candidates.stream()
            .map(m -> {
                double score = scoringFunction.computeRetentionScore(m, lambda);
                return new ScoredMemory(m, score, -1.0);  // -1 表示相关度未知
            })
            .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
            .limit(IMPORTANCE_FALLBACK_LIMIT)
            .toList();
}
```

`MemoryEntry.toRecalledChunk()` 中的标签格式：当相关度 < 0 时显示 `相关度:N/A` 而非误导性数字。

### 3.3 可观测性增强

#### 3.3.1 记忆质量审计日志

**存储路径日志** (`LongTermMemoryImpl.store()`):
```
[记忆存储] 类型={}, 内容长度={}, 重要度={}, 操作={新建|覆盖|合并}, 智能体={}
[记忆存储-质量] 事实被拒绝: 内容长度={} < 最低要求({}), 内容='{}'
```

**检索路径日志** (`HybridMemoryRetrieval.retrieve()` / `MemoryRetrieval.retrieve()`):
```
[记忆检索-详情] 查询='{}', 提取关键词数={}, 是否扩展={}, 扩展后查询='{}'
[记忆检索-详情] 策略={}, 向量结果={}(平均余弦={}), 关键词结果={}, 合并候选={}, 注入数={}
```

级别均为 INFO，生产环境可通过日志配置降为 DEBUG。

#### 3.3.2 监控指标

在 `AgentMemoryLifecycle` 中新增：

| 指标名 | 类型 | 含义 |
|--------|------|------|
| `memory.retrieval.avg_similarity` | 分布摘要 | 每次检索的平均余弦相似度 |
| `memory.retrieval.zero_keyword_count` | 计数器 | 关键词提取为空的查询次数 |
| `memory.retrieval.fallback_count` | 计数器 | 触发重要度回退的次数 |
| `memory.retrieval.query_expanded_count` | 计数器 | 触发查询扩展的次数 |
| `memory.store.quality_rejected_count` | 计数器 | 因质量不达标被拒绝的事实数 |
| `memory.store.avg_content_length` | 分布摘要 | 存储事实的内容长度分布 |

## 4. 新增配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `long_term.min_fact_content_length` | `15` | 事实内容最短长度（字符数），低于此值拒绝存储 |
| `vector.similarity_threshold` | `0.35` | 向量搜索余弦相似度下限，低于此值不返回 |

需要新增数据库迁移脚本 (`V38__memory_quality_config.sql`)，在 `memory_config` 表中为 `_global_` 智能体插入这两个配置项。

## 5. 涉及文件清单

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `ConsolidationPromptBuilder.java` | 修改 | 增强提示词质量约束 |
| `LongTermMemoryImpl.java` | 修改 | 增加内容长度门槛 + 事件/流程类合并阈值调整 + 审计日志 |
| `AgentMemoryLifecycle.java` | 修改 | 事件记忆回退增强 + 新监控指标 |
| `HybridMemoryRetrieval.java` | 修改 | 修复去重缺陷 + 向量加权 + 查询扩展集成 |
| `MemoryRetrieval.java` | 修改 | 回退机制改进 |
| `MemoryChunk.java` | 修改 | 新增 sourceId 字段 |
| `MemoryEntry.java` | 修改 | toRecalledChunk 传入 sourceId |
| `QdrantVectorStoreImpl.java` | 修改 | 增加相似度阈值 |
| `KeywordExtractor.java` | 修改 | 放宽单字过滤 |
| `QueryExpander.java` | 新增 | 基于 LLM 的查询扩展 |
| `ResolvedMemoryConfig.java` | 修改 | 新增配置字段 |
| `V38__memory_quality_config.sql` | 新增 | 新配置项迁移脚本 |
| 相关测试文件 | 修改/新增 | 同步更新测试 |

## 6. 测试要点

1. **巩固提示词构建器**: 验证新提示词输出的事实长度 ≥ 30 字符
2. **长期记忆存储**: 验证短事实（< 15 字符）被拒绝存储 + 日志输出；验证事件类 Jaccard 0.85-0.95 之间不合并而是创建新条目
3. **混合检索**: 验证相同 MySQL 主键的记忆在两路结果中正确合并分数
4. **向量存储**: 验证相似度阈值生效（模拟测试）
5. **关键词提取器**: 验证"谁"等单字不再被过滤
6. **查询扩展器**: 验证短查询扩展 + 超时回退
7. **关键词检索**: 验证回退路径的相关度为 -1 而非假值
8. **端到端**: "你是谁"查询应能通过查询扩展 + 向量搜索召回身份相关记忆

## 7. 风险与回退

| 风险 | 缓解措施 |
|------|----------|
| 查询扩展增加延迟 | 1500ms 超时 + 仅短查询触发 + 超时回退到原始查询 |
| 新提示词可能影响现有摘要质量 | 通过日志对比新旧效果，可通过配置回退旧提示词 |
| 相似度阈值过高可能误过滤有用记忆 | 默认 0.35 偏保守，可通过数据库配置调整 |
| MemoryChunk 新增字段可能影响序列化 | sourceId 可为空，不影响旧数据 |
