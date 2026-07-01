# 记忆系统 v3 设计：分层记忆与双通道检索

> 日期: 2026-06-02
> 状态: Draft
> 范围: intellimate-memory, intellimate-gateway, intellimate-agent, intellimate-web

## 1. 问题背景

### 1.1 现有系统的问题

当前记忆系统（v2）采用三种记忆类型（情景/语义/程序）+ MySQL 与 Qdrant 双写同一份内容的架构。存在以下核心问题：

1. **记忆提取质量低**：LLM 生成的事实仅 5-12 字，信息量不足以支撑语义检索
2. **同一内容双写导致检索冲突**：MySQL 存的内容与 Qdrant 存的是同一份文本，但两种检索方式对内容形式的要求不同——关键词检索需要精炼的关键词，向量检索需要语义丰富的描述
3. **三种类型分类无实质差异**：情景/语义/程序的分类在检索时并未提供有效区分，且增加了去重和评分的复杂度
4. **缺少跨对话整合能力**：每次对话独立提取记忆，无法跨对话归纳同一主题
5. **按对话顺序线性提取**：未按主题聚类，同一对话中的散落在不同位置的同一主题内容被拆成多条碎片记忆

### 1.2 检索层已知缺陷

| # | 问题 | 所在模块 | 严重程度 |
|---|------|----------|----------|
| 1 | 混合检索去重键不一致：向量路径用 `"id:"+mysqlId`，关键词用 `"keyword:"+hashCode`，永远不匹配 | `HybridMemoryRetrieval` | 高 |
| 2 | 关键词提取对短中文查询完全失效（"你是谁"提取结果为空集） | `KeywordExtractor` | 高 |
| 3 | 向量搜索无相似度下限（低分结果照样注入） | `QdrantVectorStoreImpl` | 中 |
| 4 | 向量路径不应用重要度/时效性加权 | `HybridMemoryRetrieval` | 中 |
| 5 | 回退路径使用硬编码假相关度（0.10/0.15），误导日志 | `MemoryRetrieval` | 低 |

## 2. 设计目标

1. **分层记忆模型**：建立对话级详细记忆和每日整合记忆的两层结构
2. **双通道存储**：MySQL 存关键词+原始内容（关键词检索），Qdrant 存语义增强内容（向量检索），两者内容形式不同
3. **主题聚类提取**：按主题而非对话顺序提取记忆，同一主题聚合为一条完整记忆
4. **每日自动整合**：定时任务跨对话归纳同一主题，生成整合记忆并引用详细记忆
5. **冲突解决**：同一主题出现矛盾信息时，结论取最新，历史保留变更脉络
6. **修复检索缺陷**：解决去重键不一致、无相似度下限等已知问题

## 3. 核心架构

### 3.1 分层记忆模型

```
┌─────────────────────────────────────────────────┐
│                    检索层                         │
│  用户查询 → 并行: MySQL关键词搜索 + Qdrant向量搜索 │
│          → 合并去重 → 按需追溯详细记忆 → 注入上下文 │
└─────────────────────────────────────────────────┘
                      ↑ 读取
┌─────────────────────────────────────────────────┐
│          每日整合记忆（第二层 consolidated）       │
│  定时任务(23:00) → 相似主题合并后整合当天详细记忆    │
│  引用详细记忆ID列表 → 可追溯                       │
│  存储: MySQL(关键词+原始内容+引用) + Qdrant(语义增强)│
└─────────────────────────────────────────────────┘
                      ↑ 整合
┌─────────────────────────────────────────────────┐
│          对话级详细记忆（第一层 detail）            │
│  对话结束 → LLM主题聚类 → 每主题一条记忆           │
│  每条记忆带时间戳，永久保留不过期                    │
│  存储: MySQL(关键词+原始内容) + Qdrant(语义增强)    │
└─────────────────────────────────────────────────┘
```

### 3.2 记忆类型简化

| v2 类型 | v3 类型 | 存储位置 | 内容形式 | 用途 |
|---------|---------|----------|----------|------|
| 情景/语义/程序 | 关键词记忆 | MySQL | 空格分隔关键词(TEXT) + 原始事实描述 | FULLTEXT 关键词检索 |
| _(同上)_ | 语义记忆 | Qdrant | LLM 语义增强后的丰富描述 | 向量相似度检索 |

**核心改变**：不再是同一份内容存两个地方，而是针对不同存储生成不同形式的内容。

### 3.3 记忆冲突解决

**规则：结论取最新，变更历史保留在详细记忆中。**

分层策略不同：

**对话级详细记忆（detail）**——保留变更脉络：
- **content**：只保留当前有效值（如"用户将助手名称设定为李四"）
- **enriched**：保留完整变更历史（如"用户最初将助手命名为'张三'，后改为'李四'。当前有效名称为'李四'。"）
- **keywords**：包含新旧值（如 `["李四", "张三", "助手名称", "改名"]`），确保用旧值也能检索到

**每日整合记忆（consolidated）**——只保留最终结论：
- **content**：只保留当前有效值
- **enriched**：围绕最终结论做语义增强，不需要保留变更历史
- **keywords**：以当前有效值为主，可适当包含旧值以支持检索
- 如需变更历史，通过 `source_memory_ids` 追溯引用的详细记忆

适用场景：
- **对话内整合**：同一对话中用户改变了偏好（"叫张三"→"改叫李四"），detail 记忆的 enriched 记录变更脉络
- **每日整合**：content/enriched 均取最终结论，变更历史保留在被引用的 detail 记忆中
- **跨日整合**：新的整合覆盖旧的同主题整合记忆

## 4. 详细设计

### 4.1 LLM 巩固提示词改造

**文件**: `intellimate-memory/.../consolidation/ConsolidationPromptBuilder.java`

改造提示词，要求 LLM 执行三步处理：

```
你是一个专业的记忆巩固助手。请对以下对话执行三步处理：

## 第一步：主题识别与聚类
从对话中识别出不同的讨论主题。注意：
- 同一主题可能出现在对话的不同位置（如开头和结尾都在讨论吃饭），请将其聚合
- 每个主题应该是一个独立的、完整的概念单元

## 第二步：冲突解决
如果同一主题内出现矛盾信息（如先说"叫张三"后说"改叫李四"）：
- content 字段只保留最终结论（当前有效值，如"用户将助手名称设定为李四"）
- enriched 字段保留完整的变更历史（如"用户最初命名助手为'张三'，后改为'李四'。当前有效名称为'李四'。"），便于后续追溯
- keywords 同时包含新旧值（如 ["李四", "张三", "助手名称", "改名"]），确保用新旧值均能检索到

## 第三步：每个主题生成一条记忆
每条记忆包含以下字段：

### 字段要求
- topic：主题标签（2-8字，简洁概括）
- keywords：关键词数组（3-10个，包含关键实体、名称、具体值）
- content：原始事实描述（简洁精确，20-50字，用于关键词检索）
- enriched：语义增强描述（丰富详细，50-150字，包含完整上下文和场景信息，用于语义检索）
- importance：重要度（0-1，核心身份/偏好>0.7，一般事件0.4-0.6，临时信息<0.4）

### 质量要求
- keywords 必须包含具体实体（人名、项目名、技术名、具体值）
- enriched 必须比 content 更丰富，包含场景、原因、上下文
- 避免纯概括性描述，要有具体可区分的信息

### 输出格式
请严格按以下 JSON 格式输出，不要包含任何其他文字：
```json
{
  "summary": "对话整体摘要（不超过 %d tokens）",
  "memories": [
    {
      "topic": "助手身份设定",
      "keywords": ["李四", "张三", "助手名称", "改名", "中文", "身份设定"],
      "content": "用户将助手名称设定为李四，偏好使用中文交互",
      "enriched": "用户在对话中先将助手命名为'张三'，后改为'李四'。当前有效名称为'李四'。用户偏好使用中文进行对话和回复，这是一个持久性的身份和语言偏好设定。",
      "importance": 0.8
    }
  ]
}
```

## 对话内容
%s
```

### 4.2 MySQL 表结构调整

**新增迁移脚本**: `V38__memory_system_v3.sql`

```sql
-- 新增字段
ALTER TABLE agent_memory
    ADD COLUMN keywords TEXT DEFAULT NULL COMMENT '空格分隔的关键词列表，用于 FULLTEXT 检索',
    ADD COLUMN topic VARCHAR(100) DEFAULT NULL COMMENT '主题标签，用于主题聚类和每日整合',
    ADD COLUMN memory_level VARCHAR(20) NOT NULL DEFAULT 'detail' COMMENT 'detail=对话级详细记忆, consolidated=每日整合记忆',
    ADD COLUMN source_memory_ids JSON DEFAULT NULL COMMENT '整合记忆引用的详细记忆ID列表',
    ADD COLUMN enriched_content TEXT DEFAULT NULL COMMENT '语义增强内容（存入Qdrant的文本副本，便于调试）';

-- 为关键词检索创建全文索引（ngram 分词器适配中文）
ALTER TABLE agent_memory ADD FULLTEXT INDEX idx_keywords (keywords) WITH PARSER ngram;

-- 为主题聚类创建索引
ALTER TABLE agent_memory ADD INDEX idx_topic (topic);

-- 为记忆层级创建索引
ALTER TABLE agent_memory ADD INDEX idx_memory_level (memory_level);

-- 新增记忆配置项
INSERT INTO memory_config (agent_name, config_key, config_value) VALUES
    ('_global_', 'vector.similarity_threshold', '0.35'),
    ('_global_', 'consolidation.topic_similarity_threshold', '0.7');

-- 新增每日整合定时任务（集成现有 ScheduledJob 框架）
INSERT INTO scheduled_job_config (job_name, job_group, trigger_type, trigger_value, timezone, enabled, description)
VALUES ('memory-daily-consolidation', 'data', 'cron', '0 0 23 * * ?', 'Asia/Shanghai', 1, '每日记忆整合');
```

### 4.3 存储流程改造

**文件**: `intellimate-gateway/.../service/LongTermMemoryImpl.java`

#### 4.3.0 配置开关控制

存储和检索受两个开关控制，读取 `ResolvedMemoryConfig`：

| `longTermEnabled` | `vectorEnabled` | 巩固行为 | 检索行为 |
|---|---|---|---|
| `false` | - | 不巩固，不存储任何记忆 | 不检索 |
| `true` | `false` | LLM 巩固 → 只存 MySQL（content + keywords） | 只走关键词检索 |
| `true` | `true` | LLM 巩固 → 存 MySQL + 存 Qdrant（enriched） | 关键词 + 向量混合检索 |

```java
// 巩固时的开关判断
if (!resolvedConfig.longTermEnabled()) {
    return Mono.empty(); // 持久化未开启，跳过巩固
}

// 存储时：MySQL 始终写入（持久化已开启），Qdrant 按 vectorEnabled 决定
Mono<Void> mysqlStore = storeMysql(fact, userId, agentId);
Mono<Void> qdrantStore = resolvedConfig.vectorEnabled()
        ? storeQdrant(fact, userId, agentId)
        : Mono.empty();
return mysqlStore.then(qdrantStore);
```

每日整合定时任务同样受此控制：只对 `longTermEnabled=true` 的 agent 执行整合。

#### 4.3.1 新的存储接口

```java
public record EnrichedFact(
    String topic,
    List<String> keywords,
    String content,
    String enriched,
    float importance
) {}

public Mono<Void> storeEnriched(EnrichedFact fact, String userId, String agentId) {
    // 1. 去重检查（基于 topic + keywords 的语义去重）
    //    - 同一 topic 下 keywords Jaccard > 0.95 视为重复
    //    - detail 层重复时: content 覆盖为新值, enriched 追加变更历史, keywords 合并新旧值
    //    - consolidated 层重复时: 整体覆盖为新值（变更历史保留在 detail 记忆中）
    
    // 2. MySQL 存储（longTermEnabled=true 时始终执行）
    //    - content, keywords(TEXT, 空格分隔), topic, memory_level='detail', importance
    
    // 3. Qdrant 存储（仅 vectorEnabled=true 时执行，异步）
    //    - 使用 enriched 内容做 embedding（而非 content）
    //    - metadata: mysql_id, user_id, agent_id, topic, importance, created_at
    
    // 4. 审计日志
    log.info("[记忆存储] 主题={}, 关键词数={}, 内容长度={}, 语义增强长度={}, 重要度={}, 向量={}, 操作={}",
            fact.topic(), fact.keywords().size(), fact.content().length(),
            fact.enriched().length(), fact.importance(), resolvedConfig.vectorEnabled(), action);
}
```

#### 4.3.2 去重与冲突解决

```java
// 去重策略：基于 topic 匹配
// 同一 (userId, agentId, topic) 下：
//   - detail 层：如果已有记忆且 keywords 高度重叠 → 合并（content 取新值，enriched 追加变更历史，keywords 合并新旧值）
//   - consolidated 层：如果已有记忆且 keywords 高度重叠 → 整体覆盖为新值
//   - 如果已有记忆但 keywords 差异大 → 作为独立条目存储
//   - 如果没有匹配 → 新建
```

### 4.4 Qdrant 存储改造

**文件**: `intellimate-gateway/.../service/QdrantVectorStoreImpl.java`

关键改变：embedding 的内容从 `content` 改为 `enriched`。

```java
@Override
public Mono<Void> store(MemoryEntry entry) {
    return Mono.fromRunnable(() -> {
        String docId = toUUID(entry.getId());
        // 使用 enrichedContent 做 embedding（如果有）
        String textToEmbed = entry.getEnrichedContent() != null 
                ? entry.getEnrichedContent() 
                : entry.getContent();
        Map<String, Object> metadata = Map.of(
                "mysql_id", entry.getId().intValue(),
                "user_id", entry.getUserId(),
                "agent_id", entry.getAgentId(),
                "topic", entry.getTopic() != null ? entry.getTopic() : "",
                "importance", (double) entry.getImportance(),
                "created_at", entry.getCreatedAt() != null ? entry.getCreatedAt().toString() : ""
        );
        Document doc = new Document(docId, textToEmbed, metadata);
        vectorStore.add(List.of(doc));
    }).subscribeOn(Schedulers.boundedElastic()).then();
}
```

同时增加相似度下限：

```java
// search() 方法增加 similarityThreshold
SearchRequest request = SearchRequest.builder()
        .query(query)
        .topK(topK)
        .similarityThreshold(similarityThreshold)  // 默认 0.35
        .filterExpression(filter)
        .build();
```

### 4.5 每日整合定时任务

#### 4.5.1 定时任务注册

集成到现有的 `ScheduledJob` 框架，复用 `TaskRegistry` + `ReactiveScheduleEngine` 调度能力。

**新增文件**: `intellimate-gateway/.../scheduler/jobs/DailyMemoryConsolidationJob.java`

```java
@Component
public class DailyMemoryConsolidationJob implements ScheduledJob {

    private final DailyMemoryConsolidator consolidator;

    @Override
    public String getJobName() { return "memory-daily-consolidation"; }

    @Override
    public String getJobGroup() { return "data"; }

    @Override
    public Duration getDefaultTimeout() { return Duration.ofMinutes(30); }

    @Override
    public Mono<JobResult> execute(JobExecutionContext context) {
        return consolidator.consolidateAll()
                .map(stats -> JobResult.ok(
                        "Daily memory consolidation completed",
                        Map.of(
                                "usersProcessed", stats.getOrDefault("usersProcessed", 0),
                                "topicGroups", stats.getOrDefault("topicGroups", 0),
                                "consolidatedCreated", stats.getOrDefault("consolidatedCreated", 0),
                                "consolidatedUpdated", stats.getOrDefault("consolidatedUpdated", 0)
                        )
                ))
                .onErrorResume(e -> {
                    log.error("[每日整合] 任务失败", e);
                    return Mono.just(JobResult.fail("Consolidation failed: " + e.getMessage()));
                });
    }
}
```

**数据库迁移中新增 job 配置**（在 `V38__memory_system_v3.sql` 中）：

```sql
INSERT INTO scheduled_job_config (job_name, job_group, trigger_type, trigger_value, timezone, enabled, description)
VALUES ('memory-daily-consolidation', 'data', 'cron', '0 0 23 * * ?', 'Asia/Shanghai', 1, '每日记忆整合');
```

#### 4.5.2 整合核心逻辑

**新增文件**: `intellimate-gateway/.../service/DailyMemoryConsolidator.java`

```java
@Component
public class DailyMemoryConsolidator {

    public Mono<Map<String, Integer>> consolidateAll() {
        // 1. 查询所有有当天 detail 记忆的 (userId, agentId) 组合
        // 2. 对每个组合执行 consolidateDaily
        // 3. 汇总统计
    }
    
    public Mono<Void> consolidateDaily(String userId, String agentId) {
        return repository.findTodayDetailMemories(userId, agentId)
            .collectList()
            .flatMap(todayMemories -> {
                if (todayMemories.isEmpty()) return Mono.empty();
                
                // 按 topic 分组（含语义合并）
                Map<String, List<AgentMemoryEntity>> topicGroups = groupByTopic(todayMemories);
                
                // 每组调用 LLM 整合
                return Flux.fromIterable(topicGroups.entrySet())
                    .concatMap(entry -> consolidateTopic(entry.getKey(), entry.getValue(), userId, agentId))
                    .then();
            });
    }
    
    private Mono<Void> consolidateTopic(String topic, List<AgentMemoryEntity> memories,
                                         String userId, String agentId) {
        // 调用 LLM 整合同一主题的多条记忆
        // 输入：该主题下所有详细记忆的 content
        // 输出：整合后的 keywords + enriched + importance
        
        // 检查是否已有该主题的历史整合记忆
        // 如果有 → 更新（追加 source_memory_ids，重新生成 enriched）
        // 如果没有 → 新建 consolidated 记忆
        
        // 冲突解决：传入 LLM 时按时间排序，提示词要求只保留最终结论（变更历史在 detail 记忆中）
    }
    
    private Map<String, List<AgentMemoryEntity>> groupByTopic(List<AgentMemoryEntity> memories) {
        // 两级分组策略：
        // 1. 精确匹配：topic 完全相同的归为一组
        // 2. 语义合并：对各组的 topic 做 embedding 相似度比较（> 0.7），
        //    将语义接近的主题合并为一组（如"吃饭"+"饮食偏好"合并，但"吃饭"和"住房"分开）
        //    合并后的组使用更概括的 topic 标签（由 LLM 生成）
    }
}
```

**整合提示词**：

```
你是记忆整合助手。请将以下同一主题的多条记忆整合为一条完整的记忆。

## 整合规则
1. 保留所有关键信息，不要遗漏细节
2. 如果出现矛盾信息，content 和 enriched 均只保留最终结论（当前有效值），不需要保留变更历史（变更历史保留在详细记忆中，可按需追溯）
3. 生成的记忆应该是一个连贯的、完整的描述

## 待整合的记忆（按时间排序）
%s

## 输出格式
```json
{
  "topic": "主题标签",
  "keywords": ["关键词1", "关键词2"],
  "content": "整合后的原始描述",
  "enriched": "整合后的语义增强描述",
  "importance": 0.8
}
```
```

### 4.6 检索路径改造

检索路径同样受 `ResolvedMemoryConfig` 的开关控制：
- `longTermEnabled=false` → 不检索
- `vectorEnabled=false` → 只走 MySQL 关键词检索（`retrieval.strategy` 强制为 `keyword_only`）
- `vectorEnabled=true` → 按 `retrieval.strategy` 配置走 `hybrid` 或 `vector_only`

#### 4.6.1 混合检索去重修复

**文件**: `intellimate-memory/.../retrieval/HybridMemoryRetrieval.java`

统一使用 MySQL 主键作为去重键。`MemoryChunk` 新增 `sourceId` 字段。

```java
// 向量路径
String key = vr.mysqlId() != null ? String.valueOf(vr.mysqlId()) : "vec:" + vr.content().hashCode();

// 关键词路径
String key = kw.sourceId() != null ? String.valueOf(kw.sourceId()) : "kw:" + kw.content().hashCode();
```

#### 4.6.2 向量路径应用质量加权

```java
for (VectorSearchResult vr : vectorResults) {
    double importanceBoost = Math.max(0.3, vr.importance());
    double recencyBoost = computeRecencyBoost(vr.createdAt(), 0.05);
    double weightedVectorScore = vr.similarity() * vectorWeight * importanceBoost * recencyBoost;
    candidates.put(key, new MergedCandidate(chunk, weightedVectorScore, 0.0));
}
```

#### 4.6.3 检索优先级

```java
// 检索时优先查整合记忆（consolidated），然后才是详细记忆（detail）
// 当整合记忆命中但信息不够详细时，通过 source_memory_ids 加载相关详细记忆

public Mono<List<MemoryChunk>> retrieve(String cue, String userId, String agentId, ...) {
    // 并行：MySQL 关键词搜索 + Qdrant 向量搜索
    // 两路结果合并后：
    //   1. 先展示 consolidated 层记忆
    //   2. 如果 token 预算还有余量，补充 detail 层记忆
    //   3. 如果某条 consolidated 记忆有 source_memory_ids 且上下文需要更多细节，
    //      加载引用的详细记忆
}
```

#### 4.6.4 关键词提取器微调

放宽单字中文过滤：

```java
private static final Set<String> MEANINGFUL_SINGLE_CHARS = Set.of("谁", "何", "哪", "吗", "呢");

private static void addToken(Set<String> tokens, String word) {
    if (word.length() >= 2 && !STOP_WORDS.contains(word)) {
        tokens.add(word);
    } else if (word.length() == 1 && MEANINGFUL_SINGLE_CHARS.contains(word)) {
        tokens.add(word);
    }
}
```

#### 4.6.5 回退机制改进

不再使用硬编码假相关度，改为 -1 表示相关度未知：

```java
private List<ScoredMemory> fallbackByImportance(List<MemoryEntry> candidates, double lambda) {
    return candidates.stream()
            .map(m -> new ScoredMemory(m, scoringFunction.computeRetentionScore(m, lambda), -1.0))
            .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
            .limit(IMPORTANCE_FALLBACK_LIMIT)
            .toList();
}
```

### 4.7 前端 UI 调整

**文件**: `intellimate-web/src/components/MemoryManagerPage.tsx`

将三个标签（情景记忆/语义记忆/程序记忆）改为两个标签（关键词记忆/语义记忆）：

```
┌─────────────┬─────────────┐
│ 关键词记忆   │ 语义记忆     │
├─────────────┴─────────────┤
│                           │
│  [整合] 助手身份设定       │  ← memory_level='consolidated'
│    └── [详细] 06-02 16:44 │  ← 展开显示 source_memory_ids 引用的详细记忆
│    └── [详细] 06-01 09:30 │
│                           │
│  [整合] 用户偏好           │
│    └── [详细] 06-02 15:00 │
│                           │
│  [详细] 今天未整合的记忆1  │  ← 当天产生但尚未被整合的 detail 记忆
│  [详细] 今天未整合的记忆2  │
│                           │
└───────────────────────────┘
```

- 整合记忆显示在前，可展开查看引用的详细记忆
- 当天未被整合的详细记忆显示在后
- 「关键词记忆」标签展示 MySQL 中的 content + keywords
- 「语义记忆」标签展示 Qdrant 中的 enriched 内容

### 4.8 可观测性

#### 审计日志

```
[记忆存储] 主题={}, 关键词数={}, 内容长度={}, 语义增强长度={}, 重要度={}, 层级={detail|consolidated}
[记忆检索-详情] 查询='{}', 提取关键词数={}, 策略={}, 向量启用={}
[记忆检索-详情] 向量结果={}(平均余弦={}), 关键词结果={}, 合并候选={}, 注入数={}
[每日整合] 日期={}, 详细记忆数={}, 主题组数={}, 新建整合={}, 更新整合={}
```

#### 监控指标

| 指标名 | 类型 | 含义 |
|--------|------|------|
| `memory.retrieval.avg_similarity` | 分布摘要 | 每次检索的平均余弦相似度 |
| `memory.retrieval.zero_keyword_count` | 计数器 | 关键词提取为空的查询次数 |
| `memory.consolidation.daily_count` | 计数器 | 每日整合产生的整合记忆数 |
| `memory.consolidation.topic_count` | 分布摘要 | 每日整合识别的主题数 |

## 5. 新增配置项

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `vector.similarity_threshold` | `0.35` | 向量搜索余弦相似度下限 |
| `consolidation.topic_similarity_threshold` | `0.7` | 主题相似度阈值（用于判断是否为同一主题） |

> 注：每日整合触发时间通过 `scheduled_job_config` 表的 cron 表达式配置（默认 `0 0 23 * * ?`），不在 `memory_config` 中管理。

## 6. 涉及文件清单

| 文件 | 改动类型 | 说明 |
|------|----------|------|
| `ConsolidationPromptBuilder.java` | 重构 | 主题聚类提示词 + 双输出（keywords + enriched） |
| `LongTermMemoryImpl.java` | 重构 | 新存储接口 EnrichedFact + 基于 topic 的去重 |
| `QdrantVectorStoreImpl.java` | 修改 | embedding 用 enriched 内容 + 相似度阈值 |
| `HybridMemoryRetrieval.java` | 修改 | 修复去重键 + 向量加权 + 检索优先级（整合优先） |
| `MemoryRetrieval.java` | 修改 | 回退机制 + 关键词检索适配新字段 |
| `MemoryChunk.java` | 修改 | 新增 sourceId 字段 |
| `MemoryEntry.java` | 修改 | 新增 enrichedContent/topic/keywords/memoryLevel/sourceMemoryIds 字段 |
| `AgentMemoryEntity.java` | 修改 | 新增数据库字段映射 |
| `KeywordExtractor.java` | 修改 | 放宽单字过滤 |
| `DailyMemoryConsolidationJob.java` | 新增 | 定时任务 Job，集成 ScheduledJob 框架 |
| `DailyMemoryConsolidator.java` | 新增 | 每日整合核心逻辑 |
| `EnrichedFact.java` | 新增 | 新的事实数据模型 |
| `AgentMemoryLifecycle.java` | 修改 | 对话结束存储流程适配 |
| `MemoryConsolidator.java` | 修改 | 解析新的 JSON 输出格式 |
| `ResolvedMemoryConfig.java` | 修改 | 新增配置字段 |
| `V38__memory_system_v3.sql` | 新增 | 数据库迁移脚本 |
| `MemoryManagerPage.tsx` | 修改 | 前端从3标签改为2标签 + 分层展示 |
| `api.ts` | 修改 | 前端 API 适配新字段 |
| `ModelList.tsx` | 可能修改 | 如果涉及记忆类型展示 |
| 相关测试文件 | 修改/新增 | 同步更新测试 |

## 7. 测试要点

1. **巩固提示词**：验证 LLM 输出包含 topic/keywords/content/enriched 四个字段
2. **主题聚类**：验证同一对话中散落的同主题内容被正确聚合
3. **冲突解决**：验证 detail 记忆中 content 取最新值、enriched 保留变更脉络、keywords 包含新旧值；consolidated 记忆中 content 和 enriched 均只保留最终结论
4. **双通道存储**：验证 MySQL 存 content+keywords，Qdrant 存 enriched 内容
5. **每日整合**：验证跨对话同主题记忆被正确整合，source_memory_ids 正确引用
6. **检索去重**：验证 MySQL 和 Qdrant 两路相同记忆正确合并分数
7. **相似度阈值**：验证低于 0.35 的向量结果不被返回
8. **短查询检索**：验证"你是谁"等短查询通过语义增强的存储内容能召回相关记忆
9. **前端展示**：验证两个标签正确展示，整合记忆可展开查看详细记忆

## 8. 废弃的 v2 配置

以下 v2 配置在 v3 中不再使用（三种记忆类型已合并），保留字段但不影响行为：
- `scoring.semantic_weight` / `scoring.episodic_weight` / `scoring.procedural_weight`
- `scoring.semantic_decay_lambda` / `scoring.episodic_decay_lambda` / `scoring.procedural_decay_lambda`

前端 UI 中对应的评分权重配置项可在后续版本移除。

## 9. 数据迁移

现有记忆数据迁移方案：
- 现有 `memory_type` 字段保留兼容，新增字段默认为空
- 旧数据的 `memory_level` 默认为 `'detail'`
- 旧数据的 `keywords` 和 `enriched_content` 为空，检索时回退到原有逻辑
- 可选：后续通过批量任务对旧数据补充 keywords 和 enriched_content

## 10. 风险与回退

| 风险 | 缓解措施 |
|------|----------|
| 新提示词增加 LLM 调用复杂度 | 渐进式迁移：新对话用 v3 提示词，旧数据保持兼容 |
| 每日整合定时任务可能失败 | 幂等设计 + 失败重试 + 告警 |
| 主题相似度判断不准 | 默认阈值 0.7 偏保守，可通过配置调整 |
| 前端改动影响用户体验 | 新旧标签同时支持过渡期 |
| Flux.defer 改动影响现有行为 | 仅添加包装，与递归调用模式（第 729 行）一致 |
| SQL LIMIT 10 可能遗漏候选 | FULLTEXT 分数与实际相关性强相关，10 条足够覆盖 |
| per-agent 配置去掉 _global_ 回退 | 缺失 key 使用代码 DEFAULTS 兜底 |

---

## 11. 实施记录（2026-06-02）

### 11.1 已修复的 Bug

#### Bug 1：Reactor 时序 bug（P0 阻塞性）

**现象**：日志显示记忆检索成功（6 条），但 LLM 请求参数中 messages 仅包含 system + user，无 RECALLED 记忆。`[LLM请求-详细参数]` 在 `[记忆注入]` 之前 1.5 秒输出。

**根因**：`AgentLoopExecutor.java` 第 280 行 `executeLoopTurn(...)` 在 Reactor 链的**组装阶段**就被调用。方法内部立刻执行了 `history.clear()` + `buildLLMInputSync()`，此时 workingMemory 尚无记忆。而同文件第 729 行的递归调用正确使用了 `Flux.defer()`。

**修复**：

```java
// 修复前（第 280 行）
.thenMany(executeLoopTurn(resolved.chatModel(), conversationHistory, ...))

// 修复后
.thenMany(Flux.defer(() -> executeLoopTurn(resolved.chatModel(), conversationHistory, ...)))
```

**时序变化**：
```
修复前: 组装→history构建(无记忆)→Prompt创建 → 订阅→记忆检索 → LLM调用(无记忆)
修复后: 组装→defer包装 → 订阅→记忆检索→记忆注入 → defer触发→history构建(有记忆)→LLM调用(有记忆)
```

**文件**: `intellimate-agent/.../runtime/AgentLoopExecutor.java`

#### Bug 2：Per-Agent 记忆配置不生效（P0 功能性）

**现象**：UI 为特定 Agent 配置了 `long_term.enabled=true`，但运行时始终读 `_global_` 全局配置（默认 false），记忆检索被跳过。

**根因**：`MemoryConfigProvider` 接口仅定义 `resolve()`，缺少 `resolveForAgent(agentName)`。

**修复**（涉及 7 个文件）：

| 文件 | 改动 |
|------|------|
| `MemoryConfigProvider.java` | 增加 `resolveForAgent` 默认方法 |
| `MemoryConfigService.java` | 不再读 `_global_`，用 DEFAULTS 兜底 |
| `AgentMemoryLifecycle.java` | `loadMemoryInitReactive` 增加 agentName 参数 |
| `AgentLoopExecutor.java` | 传入 agentId |
| `SessionHistoryController.java` | 使用 `resolveForAgent` |
| `PlanExecutionOrchestrator.java` | 使用 `resolveForAgent` |
| `MemoryConfigServiceTest.java` | 更新测试 |

### 11.2 已实现的检索优化

#### 优化 1：FULLTEXT 联合搜索 keywords 字段

**文件**: `AgentMemoryRepository.java`

原 SQL 仅搜索 `content` 字段，改为同时搜索 `content` 和 `keywords`（OR 连接）：

```sql
AND (MATCH(content) AGAINST(:searchExpr IN BOOLEAN MODE)
     OR (keywords IS NOT NULL AND MATCH(keywords) AGAINST(:searchExpr IN BOOLEAN MODE)))
```

#### 优化 2：FULLTEXT 搜索按相关性排序 + LIMIT 10

**文件**: `AgentMemoryRepository.java`

添加 `ft_score` 计算列并排序：

```sql
SELECT *,
       (MATCH(content) AGAINST(:searchExpr IN BOOLEAN MODE)
        + CASE WHEN keywords IS NOT NULL
               THEN MATCH(keywords) AGAINST(:searchExpr IN BOOLEAN MODE)
               ELSE 0 END) AS ft_score
FROM agent_memory
WHERE ...
ORDER BY ft_score DESC
LIMIT 10
```

同步修改：
- `LongTermMemoryImpl.java` — 去掉 `maxResults` 参数
- `MemoryRetrieval.java` — 去掉 `.take(100)` 和 `.limit(20)` 中间排序
- `MemoryRetrievalTest.java` — 更新 DisplayName

#### 优化 3：检索日志增强

在 `LongTermMemoryImpl.search()` 和 `MemoryRetrieval.selectAndScore()` 添加详细日志：
- `[关键词检索]` — 记录 cue、提取的关键词、FULLTEXT 命中数和命中详情
- `[记忆评分]` — 记录每条候选的 Jaccard、score、content 预览及过滤结果

### 11.3 已实现的前端改造

**文件**: `MemoryManagerPage.tsx`

- **关键词记忆**标签：显示全部记忆
- **语义记忆**标签：仅显示有 `enrichedContent` 的记忆
- 语义记忆无数据时显示专门提示

### 11.4 关键词检索系统详细分析

#### 完整数据流

```
用户输入 "你的名称是什么？"
    ↓
KeywordExtractor.extract() — Jieba 分词 + 停用词过滤
    ↓
提取关键词: ["名称", "什么"]
    ↓
FULLTEXT BOOLEAN MODE: MATCH(content/keywords) AGAINST('名称 什么')
    ↓
MySQL ngram parser 匹配 → 按 ft_score 排序 → 返回 top 10
    ↓
Jaccard 评分: 查询词集合 ∩ 记忆词集合 / 查询词集合 ∪ 记忆词集合
    ↓
过滤: Jaccard >= 0.20 → 综合评分 → token 预算裁剪 → 注入 LLM
```

#### 已知限制

- **Jaccard 对短查询不利**：用户查询通常 2-3 个有效词，记忆 5-10 个词，即使完全匹配 Jaccard 也只有 0.33
- **"名字"≠"名称"**：Jieba 词级别不同，Jaccard=0，但 FULLTEXT ngram 可能通过共享 "名" 匹配
- **MIN_RELEVANCE_THRESHOLD=0.20** 过滤掉大量只有 1 个词命中的记忆

### 11.5 已修改文件汇总

| 文件 | 模块 | 改动内容 |
|------|------|----------|
| `AgentLoopExecutor.java` | intellimate-agent | Flux.defer() 修复 + 传入 agentId |
| `AgentMemoryLifecycle.java` | intellimate-agent | loadMemoryInitReactive 增加 agentName |
| `MemoryConfigProvider.java` | intellimate-memory | 增加 resolveForAgent 默认方法 |
| `ResolvedMemoryConfig.java` | intellimate-memory | 新增配置字段 |
| `MemoryRetrieval.java` | intellimate-memory | 去掉 .take(100)、简化评分流程 + 日志 |
| `HybridMemoryRetrieval.java` | intellimate-memory | 检索详情日志 |
| `AgentMemoryRepository.java` | intellimate-gateway | ft_score 排序 + LIMIT 10 + keywords 联合搜索 |
| `LongTermMemoryImpl.java` | intellimate-gateway | 去掉 maxResults 参数 + 关键词检索日志 |
| `MemoryConfigService.java` | intellimate-gateway | resolveForAgent 不再读 _global_ |
| `SessionHistoryController.java` | intellimate-gateway | 使用 resolveForAgent |
| `PlanExecutionOrchestrator.java` | intellimate-gateway | 使用 resolveForAgent |
| `MemoryManagerPage.tsx` | intellimate-web | 标签数据分离 + 空状态提示 |
| `MemoryRetrievalTest.java` | intellimate-memory (test) | 更新 DisplayName |
| `MemoryConfigServiceTest.java` | intellimate-gateway (test) | 更新 resolveForAgent 测试 |
