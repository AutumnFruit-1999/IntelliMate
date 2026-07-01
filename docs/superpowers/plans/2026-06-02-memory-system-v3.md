# 记忆系统 v3 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将记忆系统从 v2（三类记忆 + 同内容双写）重构为 v3（分层双通道 + 主题聚类 + 每日整合），提升记忆提取质量和检索相关性。

**架构：** 对话结束时 LLM 按主题聚类提取记忆，每条记忆同时生成 content（关键词检索）和 enriched（语义检索）两种形式。MySQL 存 content + keywords（TEXT/FULLTEXT），Qdrant 存 enriched embedding。每日定时任务整合同主题记忆。受 `longTermEnabled` / `vectorEnabled` 开关控制。

**技术栈：** Java 17, Spring WebFlux, R2DBC, Qdrant, Spring AI, Reactor, MySQL 8.0, Jieba 分词

**规格文档：** `docs/superpowers/specs/2026-06-02-memory-system-v3-design.md`

---

## 文件结构

| 文件路径 | 操作 | 职责 |
|----------|------|------|
| `intellimate-gateway/src/main/resources/db/migration/V38__memory_system_v3.sql` | 新增 | 数据库迁移：新增 keywords/topic/memory_level/source_memory_ids/enriched_content 字段 |
| `intellimate-memory/src/main/java/.../memory/model/ExtractedFact.java` | 重构 | 从 `(type, content, importance)` 扩展为 `(topic, keywords, content, enriched, importance)` |
| `intellimate-memory/src/main/java/.../memory/model/MemoryEntry.java` | 修改 | 新增 enrichedContent/topic/keywords/memoryLevel/sourceMemoryIds 字段 |
| `intellimate-memory/src/main/java/.../memory/model/MemoryChunk.java` | 修改 | 新增 sourceId 字段 |
| `intellimate-gateway/src/main/java/.../gateway/entity/AgentMemoryEntity.java` | 修改 | 新增数据库字段映射 |
| `intellimate-memory/src/main/java/.../memory/config/ResolvedMemoryConfig.java` | 修改 | 新增 similarityThreshold/topicSimilarityThreshold 字段 |
| `intellimate-memory/src/main/java/.../memory/consolidation/ConsolidationPromptBuilder.java` | 重构 | v3 主题聚类提示词 |
| `intellimate-memory/src/main/java/.../memory/consolidation/MemoryConsolidator.java` | 修改 | 解析 v3 JSON 格式（topic/keywords/content/enriched） |
| `intellimate-memory/src/main/java/.../memory/consolidation/ConsolidationResult.java` | 修改 | facts 类型从 `List<ExtractedFact>` 适配新字段 |
| `intellimate-gateway/src/main/java/.../gateway/service/LongTermMemoryImpl.java` | 重构 | 新存储接口 + topic 去重 + 开关控制 |
| `intellimate-gateway/src/main/java/.../gateway/service/QdrantVectorStoreImpl.java` | 修改 | enriched embedding + 相似度阈值 + 新 metadata |
| `intellimate-memory/src/main/java/.../memory/retrieval/HybridMemoryRetrieval.java` | 修改 | 修复去重键 + 向量加权 + 检索优先级 |
| `intellimate-memory/src/main/java/.../memory/retrieval/MemoryRetrieval.java` | 修改 | 回退机制改进 |
| `intellimate-memory/src/main/java/.../memory/retrieval/KeywordExtractor.java` | 修改 | 放宽单字过滤 |
| `intellimate-memory/src/main/java/.../memory/retrieval/VectorSearchResult.java` | 修改 | 新增 topic/memoryLevel 字段 |
| `intellimate-memory/src/main/java/.../memory/retrieval/VectorMemoryStore.java` | 修改 | search 方法新增 similarityThreshold 参数 |
| `intellimate-agent/src/main/java/.../agent/runtime/AgentMemoryLifecycle.java` | 修改 | 对话结束存储流程适配 v3 |
| `intellimate-gateway/src/main/java/.../gateway/scheduler/jobs/DailyMemoryConsolidationJob.java` | 新增 | 定时任务 Job |
| `intellimate-gateway/src/main/java/.../gateway/service/DailyMemoryConsolidator.java` | 新增 | 每日整合核心逻辑 |
| `intellimate-memory/src/main/java/.../memory/longterm/LongTermMemory.java` | 修改 | 新增 storeEnriched 接口方法 |
| `intellimate-web/src/components/MemoryManagerPage.tsx` | 修改 | 前端从3标签改为2标签 + 分层展示 |
| `intellimate-web/src/lib/api.ts` | 修改 | 前端 API 适配新字段 |
| 各模块测试文件 | 修改/新增 | 同步更新测试 |

---

## 任务 1：数据库迁移

**文件：**
- 创建：`intellimate-gateway/src/main/resources/db/migration/V38__memory_system_v3.sql`
- 测试：手动验证迁移脚本

- [ ] **步骤 1：编写迁移脚本**

```sql
-- V38__memory_system_v3.sql
-- Memory System v3: 分层记忆 + 双通道检索

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

- [ ] **步骤 2：启动应用验证迁移**

运行：`cd intellimate-gateway && mvn spring-boot:run`
预期：Flyway 成功执行 V38 迁移，无报错

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/resources/db/migration/V38__memory_system_v3.sql
git commit -m "feat(memory-v3): add V38 migration for topic/keywords/memory_level/enriched fields"
```

---

## 任务 2：数据模型扩展

**文件：**
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/model/ExtractedFact.java`
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/model/MemoryEntry.java`
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/model/MemoryChunk.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/AgentMemoryEntity.java`
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/retrieval/VectorSearchResult.java`
- 测试：`intellimate-memory/src/test/java/com/atm/intellimate/memory/model/ExtractedFactTest.java`

- [ ] **步骤 1：编写 ExtractedFact 测试**

```java
// intellimate-memory/src/test/java/.../memory/model/ExtractedFactTest.java
@Test
void v3FactShouldContainTopicKeywordsContentEnrichedImportance() {
    ExtractedFact fact = new ExtractedFact(
            "助手身份设定",
            List.of("李四", "张三", "助手名称"),
            "用户将助手名称设定为李四",
            "用户先命名为张三，后改为李四。当前有效名称为李四。",
            0.8f
    );
    assertEquals("助手身份设定", fact.topic());
    assertEquals(3, fact.keywords().size());
    assertEquals("用户将助手名称设定为李四", fact.content());
    assertNotNull(fact.enriched());
    assertEquals(0.8f, fact.importance());
}

@Test
void legacyV2FactShouldStillWork() {
    // 兼容旧的 3 参数构造
    ExtractedFact fact = ExtractedFact.legacy("semantic", "用户喜欢中文", 0.6f);
    assertEquals("", fact.topic());
    assertTrue(fact.keywords().isEmpty());
    assertEquals("用户喜欢中文", fact.content());
    assertEquals("", fact.enriched());
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd intellimate-memory && mvn test -pl . -Dtest=ExtractedFactTest -DfailIfNoTests=false`
预期：编译失败，ExtractedFact 没有新构造函数

- [ ] **步骤 3：重构 ExtractedFact**

当前 `ExtractedFact.java`（11行）是 `record(String type, String content, float importance)`。

改为：

```java
package com.atm.intellimate.memory.model;

import java.util.List;

public record ExtractedFact(
    String topic,
    List<String> keywords,
    String content,
    String enriched,
    float importance
) {
    public static ExtractedFact legacy(String type, String content, float importance) {
        return new ExtractedFact("", List.of(), content, "", importance);
    }
}
```

- [ ] **步骤 4：扩展 MemoryEntry**

在 `MemoryEntry.java` 中新增字段（在现有字段 `metadataJson` 之后）：

```java
private String enrichedContent;
private String topic;
private String keywords;          // 空格分隔
private String memoryLevel;       // "detail" | "consolidated"
private String sourceMemoryIds;   // JSON array string, e.g. "[1,2,3]"
```

加上 getter/setter。新增构造函数重载支持 v3 字段。

- [ ] **步骤 5：扩展 AgentMemoryEntity**

在 `AgentMemoryEntity.java` 中新增字段映射（在现有 `metadataJson` 之后）：

```java
@Column("keywords")
private String keywords;

@Column("topic")
private String topic;

@Column("memory_level")
private String memoryLevel;

@Column("source_memory_ids")
private String sourceMemoryIds;

@Column("enriched_content")
private String enrichedContent;
```

加上 getter/setter。

- [ ] **步骤 6：扩展 MemoryChunk**

在 `MemoryChunk.java` record 中新增 `sourceId` 字段：

```java
public record MemoryChunk(
    String id,
    ChunkType type,
    String content,
    ContentCategory category,
    float importance,
    int estimatedTokens,
    long originalSize,
    Instant createdAt,
    Map<String, String> metadata,
    Long sourceId           // MySQL 主键，用于去重
) {
    // 现有工厂方法保持兼容，sourceId 默认为 null
    public MemoryChunk(String id, ChunkType type, String content, ContentCategory category,
                       float importance, int estimatedTokens, long originalSize,
                       Instant createdAt, Map<String, String> metadata) {
        this(id, type, content, category, importance, estimatedTokens, originalSize,
             createdAt, metadata, null);
    }
}
```

- [ ] **步骤 7：扩展 VectorSearchResult**

在 `VectorSearchResult.java` record 中新增 `topic` 和 `memoryLevel` 字段：

```java
public record VectorSearchResult(
    Long mysqlId,
    String content,
    String memoryType,
    float importance,
    double similarity,
    Instant createdAt,
    String topic,
    String memoryLevel
) {
    // 兼容旧构造（无 topic/memoryLevel）
    public VectorSearchResult(Long mysqlId, String content, String memoryType,
                              float importance, double similarity, Instant createdAt) {
        this(mysqlId, content, memoryType, importance, similarity, createdAt, null, null);
    }
}
```

- [ ] **步骤 8：运行全量测试**

运行：`cd intellimate-memory && mvn test`
预期：所有测试通过（新字段有默认值，兼容旧代码）

- [ ] **步骤 9：Commit**

```bash
git add intellimate-memory/src/main/java/com/atm/intellimate/memory/model/
git add intellimate-memory/src/test/java/com/atm/intellimate/memory/model/
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/AgentMemoryEntity.java
git commit -m "feat(memory-v3): extend data models with topic/keywords/enriched/memoryLevel fields"
```

---

## 任务 3：ResolvedMemoryConfig 扩展

**文件：**
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/config/ResolvedMemoryConfig.java`
- 修改：`intellimate-memory/src/test/java/com/atm/intellimate/memory/config/ResolvedMemoryConfigTest.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/MemoryConfigService.java`

- [ ] **步骤 1：编写测试**

在 `ResolvedMemoryConfigTest.java` 中新增：

```java
@Test
void shouldParseV3ConfigFields() {
    Map<String, String> map = buildBaseMap();
    map.put("vector.similarity_threshold", "0.35");
    map.put("consolidation.topic_similarity_threshold", "0.7");
    ResolvedMemoryConfig config = ResolvedMemoryConfig.fromMap(map);
    assertEquals(0.35f, config.similarityThreshold());
    assertEquals(0.7f, config.topicSimilarityThreshold());
}

@Test
void v3FieldsShouldHaveDefaults() {
    Map<String, String> map = buildBaseMap();
    // 不设置 v3 字段
    ResolvedMemoryConfig config = ResolvedMemoryConfig.fromMap(map);
    assertEquals(0.35f, config.similarityThreshold());
    assertEquals(0.7f, config.topicSimilarityThreshold());
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd intellimate-memory && mvn test -Dtest=ResolvedMemoryConfigTest`
预期：编译失败

- [ ] **步骤 3：扩展 ResolvedMemoryConfig**

在 record 参数列表末尾新增：

```java
float similarityThreshold,
float topicSimilarityThreshold
```

在 `fromMap` 中新增：

```java
parseFloatOrDefault(map, "vector.similarity_threshold", 0.35f),
parseFloatOrDefault(map, "consolidation.topic_similarity_threshold", 0.7f)
```

- [ ] **步骤 4：修复 MemoryConfigService 中的 fromMap 调用**

确保所有构造 `ResolvedMemoryConfig` 的地方传入新参数。

- [ ] **步骤 5：运行测试验证通过**

运行：`cd intellimate-memory && mvn test`
预期：全部通过

- [ ] **步骤 6：Commit**

```bash
git add intellimate-memory/src/main/java/com/atm/intellimate/memory/config/ResolvedMemoryConfig.java
git add intellimate-memory/src/test/java/com/atm/intellimate/memory/config/ResolvedMemoryConfigTest.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/MemoryConfigService.java
git commit -m "feat(memory-v3): add similarityThreshold and topicSimilarityThreshold to ResolvedMemoryConfig"
```

---

## 任务 4：LLM 巩固提示词改造

**文件：**
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/consolidation/ConsolidationPromptBuilder.java`
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/consolidation/MemoryConsolidator.java:213-256`（parseConsolidationResponse）
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/consolidation/ConsolidationResult.java`
- 测试：`intellimate-memory/src/test/java/com/atm/intellimate/memory/consolidation/MemoryConsolidatorTest.java`

- [ ] **步骤 1：编写 JSON 解析测试**

```java
@Test
void shouldParseV3ConsolidationResponse() {
    String json = """
        {
          "summary": "用户与助手进行了身份设定相关的对话",
          "memories": [
            {
              "topic": "助手身份设定",
              "keywords": ["李四", "张三", "助手名称", "改名"],
              "content": "用户将助手名称设定为李四",
              "enriched": "用户先命名为张三，后改为李四。当前有效名称为李四。",
              "importance": 0.8
            }
          ]
        }
        """;
    ConsolidationResult result = consolidator.parseConsolidationResponse(json, chunks);
    assertNotNull(result.summaryChunk());
    assertEquals(1, result.facts().size());
    ExtractedFact fact = result.facts().get(0);
    assertEquals("助手身份设定", fact.topic());
    assertEquals(4, fact.keywords().size());
    assertEquals("用户将助手名称设定为李四", fact.content());
    assertTrue(fact.enriched().contains("张三"));
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd intellimate-memory && mvn test -Dtest=MemoryConsolidatorTest#shouldParseV3ConsolidationResponse`
预期：失败（JSON 格式不匹配）

- [ ] **步骤 3：重写 ConsolidationPromptBuilder.TEMPLATE**

替换 `ConsolidationPromptBuilder.java:13-42` 的 `TEMPLATE` 常量为规格文档 4.1 节中的 v3 提示词。保留 `build()` 方法（L44-58）的 truncation 逻辑不变。

- [ ] **步骤 4：修改 MemoryConsolidator.parseConsolidationResponse**

修改 `MemoryConsolidator.java:213-256`，解析新的 JSON 格式：

```java
private ConsolidationResult parseConsolidationResponse(String response, List<MemoryChunk> sourceChunks) {
    String json = extractJson(response);
    JsonNode root = objectMapper.readTree(json);
    
    String summary = root.path("summary").asText("");
    MemoryChunk summaryChunk = MemoryChunk.consolidated(summary, tokenEstimator.estimate(summary), 0.5f);
    
    List<ExtractedFact> facts = new ArrayList<>();
    JsonNode memoriesNode = root.path("memories");
    if (memoriesNode.isArray()) {
        for (JsonNode mem : memoriesNode) {
            String topic = mem.path("topic").asText("");
            List<String> keywords = new ArrayList<>();
            mem.path("keywords").forEach(k -> keywords.add(k.asText()));
            String content = mem.path("content").asText("");
            String enriched = mem.path("enriched").asText("");
            float importance = (float) mem.path("importance").asDouble(0.5);
            facts.add(new ExtractedFact(topic, keywords, content, enriched, importance));
        }
    }
    
    // 兼容旧格式 "facts" 数组
    JsonNode factsNode = root.path("facts");
    if (facts.isEmpty() && factsNode.isArray()) {
        for (JsonNode f : factsNode) {
            facts.add(ExtractedFact.legacy(
                f.path("type").asText("semantic"),
                f.path("content").asText(""),
                (float) f.path("importance").asDouble(0.5)
            ));
        }
    }
    
    return new ConsolidationResult(summaryChunk, facts, sourceChunks.size(), -1, -1,
            buildPreviews(sourceChunks), false);
}
```

- [ ] **步骤 5：运行测试验证通过**

运行：`cd intellimate-memory && mvn test -Dtest=MemoryConsolidatorTest`
预期：全部通过

- [ ] **步骤 6：Commit**

```bash
git add intellimate-memory/src/main/java/com/atm/intellimate/memory/consolidation/
git add intellimate-memory/src/test/java/com/atm/intellimate/memory/consolidation/
git commit -m "feat(memory-v3): v3 consolidation prompt with topic clustering and dual-channel output"
```

---

## 任务 5：LongTermMemoryImpl 存储改造

**文件：**
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/longterm/LongTermMemory.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/LongTermMemoryImpl.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/` 相关 Repository
- 测试：`intellimate-gateway/src/test/java/com/atm/intellimate/gateway/service/LongTermMemoryImplTest.java`

- [ ] **步骤 1：在 LongTermMemory 接口新增 storeEnriched 方法**

```java
Mono<Void> storeEnriched(ExtractedFact fact, String userId, String agentId, Long sessionId);
```

- [ ] **步骤 2：编写 storeEnriched 测试**

```java
@Test
void storeEnrichedShouldWriteMysqlAndQdrant() {
    ExtractedFact fact = new ExtractedFact(
        "助手身份", List.of("李四", "助手名称"),
        "用户设定助手名为李四", "用户先命名张三后改为李四", 0.8f
    );
    StepVerifier.create(longTermMemory.storeEnriched(fact, "user1", "agent1", 1L))
            .verifyComplete();
    // 验证 MySQL 写入
    verify(memoryRepository).save(argThat(entity -> {
        assertEquals("李四 助手名称", entity.getKeywords());
        assertEquals("助手身份", entity.getTopic());
        assertEquals("detail", entity.getMemoryLevel());
        return true;
    }));
    // 验证 Qdrant 写入（vectorEnabled=true 时）
    verify(vectorStore).store(argThat(entry ->
        entry.getEnrichedContent().contains("张三")
    ));
}

@Test
void storeEnrichedShouldSkipQdrantWhenVectorDisabled() {
    config.setVectorEnabled(false);
    longTermMemory.updateConfig(config);
    ExtractedFact fact = new ExtractedFact("话题", List.of("关键词"), "内容", "增强", 0.5f);
    StepVerifier.create(longTermMemory.storeEnriched(fact, "user1", "agent1", 1L))
            .verifyComplete();
    verify(vectorStore, never()).store(any());
}
```

- [ ] **步骤 3：实现 storeEnriched**

在 `LongTermMemoryImpl.java` 中新增方法（在现有 `store` 方法之后）：

```java
@Override
public Mono<Void> storeEnriched(ExtractedFact fact, String userId, String agentId, Long sessionId) {
    if (fact.content().isBlank()) return Mono.empty();
    
    return findExistingByTopic(userId, agentId, fact.topic())
        .flatMap(existing -> handleDedup(existing, fact, userId, agentId, sessionId))
        .switchIfEmpty(createNew(fact, userId, agentId, sessionId))
        .then();
}

private Mono<AgentMemoryEntity> findExistingByTopic(String userId, String agentId, String topic) {
    if (topic == null || topic.isBlank()) return Mono.empty();
    return memoryRepository.findByUserIdAndAgentIdAndTopic(userId, agentId, topic)
        .filter(existing -> {
            double sim = KeywordExtractor.jaccardSimilarity(
                existing.getKeywords() != null ? existing.getKeywords() : "",
                String.join(" ", fact.keywords())
            );
            return sim > 0.95;
        })
        .next();
}
```

- [ ] **步骤 4：在 Repository 新增按 topic 查询**

```java
Flux<AgentMemoryEntity> findByUserIdAndAgentIdAndTopic(String userId, String agentId, String topic);
```

- [ ] **步骤 5：修改旧 store 方法调用 storeEnriched**

旧的 `store(ExtractedFact, userId, agentId)` 方法改为委托到 `storeEnriched`：

```java
@Override
public Mono<Void> store(ExtractedFact fact, String userId, String agentId, Long sessionId) {
    if (fact.topic() != null && !fact.topic().isBlank()) {
        return storeEnriched(fact, userId, agentId, sessionId);
    }
    // 兼容 v2 旧路径
    return storeLegacy(fact, userId, agentId, sessionId);
}
```

- [ ] **步骤 6：运行测试**

运行：`cd intellimate-gateway && mvn test -Dtest=LongTermMemoryImplTest`
预期：全部通过

- [ ] **步骤 7：Commit**

```bash
git add intellimate-memory/src/main/java/com/atm/intellimate/memory/longterm/LongTermMemory.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/LongTermMemoryImpl.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/
git add intellimate-gateway/src/test/java/
git commit -m "feat(memory-v3): implement storeEnriched with topic-based dedup and dual-channel storage"
```

---

## 任务 6：QdrantVectorStoreImpl 改造

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/QdrantVectorStoreImpl.java`
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/retrieval/VectorMemoryStore.java`
- 测试：`intellimate-gateway/src/test/java/com/atm/intellimate/gateway/service/QdrantVectorStoreImplTest.java`

- [ ] **步骤 1：编写测试**

```java
@Test
void storeShouldUseEnrichedContentForEmbedding() {
    MemoryEntry entry = new MemoryEntry("user1", "agent1", "semantic", "短内容", 0.8f, 1L);
    entry.setEnrichedContent("这是语义增强后的丰富内容，包含完整的上下文信息");
    entry.setTopic("助手身份");
    
    StepVerifier.create(qdrantStore.store(entry)).verifyComplete();
    
    verify(vectorStore).add(argThat(docs -> {
        Document doc = docs.get(0);
        assertTrue(doc.getText().contains("语义增强"));
        assertEquals("助手身份", doc.getMetadata().get("topic"));
        return true;
    }));
}

@Test
void searchShouldApplySimilarityThreshold() {
    // 验证低于 0.35 的结果被过滤
}
```

- [ ] **步骤 2：修改 store 方法**

在 `QdrantVectorStoreImpl.java:36-49`，修改 `store` 方法：

```java
@Override
public Mono<Void> store(MemoryEntry entry) {
    return Mono.fromRunnable(() -> {
        String docId = toUUID(entry.getId());
        String textToEmbed = entry.getEnrichedContent() != null && !entry.getEnrichedContent().isBlank()
                ? entry.getEnrichedContent()
                : entry.getContent();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("mysql_id", entry.getId().intValue());
        metadata.put("user_id", entry.getUserId());
        metadata.put("agent_id", entry.getAgentId());
        metadata.put("memory_type", entry.getMemoryType());
        metadata.put("importance", (double) entry.getImportance());
        metadata.put("created_at", entry.getCreatedAt() != null ? entry.getCreatedAt().toString() : "");
        metadata.put("topic", entry.getTopic() != null ? entry.getTopic() : "");
        metadata.put("memory_level", entry.getMemoryLevel() != null ? entry.getMemoryLevel() : "detail");
        Document doc = new Document(docId, textToEmbed, metadata);
        vectorStore.add(List.of(doc));
    }).subscribeOn(Schedulers.boundedElastic()).then();
}
```

- [ ] **步骤 3：修改 VectorMemoryStore 接口和 search 方法**

在 `VectorMemoryStore.java` 新增带 threshold 的 search 重载：

```java
Mono<List<VectorSearchResult>> search(String query, String userId, String agentId, int topK, float similarityThreshold);
```

在 `QdrantVectorStoreImpl.java` 实现中增加 `similarityThreshold`：

```java
SearchRequest request = SearchRequest.builder()
        .query(query)
        .topK(topK)
        .similarityThreshold(similarityThreshold)
        .filterExpression(filter)
        .build();
```

解析 response 时新增 `topic` 和 `memoryLevel`：

```java
String topic = metadata.getOrDefault("topic", "").toString();
String memoryLevel = metadata.getOrDefault("memory_level", "detail").toString();
return new VectorSearchResult(mysqlId, content, memoryType, importance, similarity, createdAt, topic, memoryLevel);
```

- [ ] **步骤 4：运行测试**

运行：`cd intellimate-gateway && mvn test -Dtest=QdrantVectorStoreImplTest`
预期：全部通过

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/QdrantVectorStoreImpl.java
git add intellimate-memory/src/main/java/com/atm/intellimate/memory/retrieval/VectorMemoryStore.java
git add intellimate-memory/src/main/java/com/atm/intellimate/memory/retrieval/VectorSearchResult.java
git add intellimate-gateway/src/test/java/
git commit -m "feat(memory-v3): enriched embedding, similarity threshold, topic/memoryLevel metadata in Qdrant"
```

---

## 任务 7：检索路径修复

**文件：**
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/retrieval/HybridMemoryRetrieval.java`
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/retrieval/MemoryRetrieval.java`
- 修改：`intellimate-memory/src/main/java/com/atm/intellimate/memory/retrieval/KeywordExtractor.java`
- 测试：`intellimate-memory/src/test/java/com/atm/intellimate/memory/retrieval/HybridMemoryRetrievalTest.java`
- 测试：`intellimate-memory/src/test/java/com/atm/intellimate/memory/retrieval/KeywordExtractorTest.java`

- [ ] **步骤 1：编写去重键修复测试**

```java
@Test
void mergeAndRankShouldDeduplicateByMysqlId() {
    // 向量路径返回 mysqlId=1 的记忆
    // 关键词路径也返回 mysqlId=1 的记忆
    // 合并后应该只有一条，分数合并
}
```

- [ ] **步骤 2：修复 HybridMemoryRetrieval.mergeAndRank**

在 `HybridMemoryRetrieval.java:83-133`，统一去重键：

```java
// 向量路径 (L89)
String key = vr.mysqlId() != null ? String.valueOf(vr.mysqlId()) : "vec:" + vr.content().hashCode();

// 关键词路径 (L105)
String key = kw.sourceId() != null ? String.valueOf(kw.sourceId()) : "kw:" + kw.content().hashCode();
```

新增向量加权：

```java
double importanceBoost = Math.max(0.3, vr.importance());
double recencyBoost = computeRecencyBoost(vr.createdAt());
double weightedVectorScore = vr.similarity() * vectorWeight * importanceBoost * recencyBoost;
```

- [ ] **步骤 3：编写 KeywordExtractor 单字测试**

```java
@Test
void shouldRetainMeaningfulSingleChars() {
    List<String> keywords = KeywordExtractor.extract("你是谁");
    assertTrue(keywords.contains("谁"));
}
```

- [ ] **步骤 4：修改 KeywordExtractor.addToken**

在 `KeywordExtractor.java:74-78`：

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

- [ ] **步骤 5：实现检索优先级（consolidated 优先）**

在 `HybridMemoryRetrieval.mergeAndRank` 中，排序时 consolidated 记忆优先于 detail：

```java
// 在最终排序中，consolidated 记忆分数 +0.1 bonus
if ("consolidated".equals(candidate.chunk().metadata().get("memory_level"))) {
    candidate.addBonus(0.1);
}
```

- [ ] **步骤 6：修改 MemoryRetrieval 回退机制**

在 `MemoryRetrieval.java:119-129`，将硬编码相关度改为 -1：

```java
private List<ScoredMemory> fallbackByImportance(List<MemoryEntry> candidates, double lambda) {
    return candidates.stream()
            .map(m -> new ScoredMemory(m, scoringFunction.computeRetentionScore(m, lambda), -1.0))
            .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
            .limit(IMPORTANCE_FALLBACK_LIMIT)
            .toList();
}
```

- [ ] **步骤 7：添加审计日志和监控指标**

在 `HybridMemoryRetrieval.retrieve` 方法中添加结构化日志：

```java
log.info("[记忆检索-详情] 查询='{}', 提取关键词数={}, 策略={}, 向量启用={}",
    cue, keywords.size(), strategy, strategy != Strategy.KEYWORD_ONLY);
log.info("[记忆检索-详情] 向量结果={}(平均余弦={}), 关键词结果={}, 合并候选={}, 注入数={}",
    vectorResults.size(), avgSimilarity, keywordResults.size(), candidates.size(), injected.size());
```

- [ ] **步骤 8：运行全量测试**

运行：`cd intellimate-memory && mvn test`
预期：全部通过

- [ ] **步骤 9：Commit**

```bash
git add intellimate-memory/src/main/java/com/atm/intellimate/memory/retrieval/
git add intellimate-memory/src/test/java/com/atm/intellimate/memory/retrieval/
git commit -m "fix(memory-v3): unified dedup keys, vector weighting, retrieval priority, single-char keywords, honest fallback scores"
```

---

## 任务 8：AgentMemoryLifecycle 适配

**文件：**
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentMemoryLifecycle.java`

- [ ] **步骤 1：修改 storeSessionEpisodicViaLLM**

在 `AgentMemoryLifecycle.java:284-312`，`summarizeSession` 返回的 `ConsolidationResult.facts()` 现在包含 v3 格式的 `ExtractedFact`。存储路径需要调用 `storeEnriched` 而非旧的 `store`：

```java
// 替换原有的 facts 存储循环
for (ExtractedFact fact : result.facts()) {
    ltm.storeEnriched(fact, userId, agentId, sessionId).subscribe();
}
```

- [ ] **步骤 2：修改 storeFullConversationFallback**

在 `AgentMemoryLifecycle.java:323-361`，fallback 路径生成的事实改用 `ExtractedFact.legacy()` 保持兼容：

```java
ExtractedFact fallbackFact = ExtractedFact.legacy("episodic", conversationSummary, importance);
```

- [ ] **步骤 3：添加开关检查**

在 `storeSessionEpisodicMemory` 方法入口增加开关检查：

```java
if (!resolvedConfig.longTermEnabled()) {
    log.info("[记忆存储] 持久化未开启，跳过会话记忆存储 agent={}", agentId);
    return Mono.empty();
}
```

- [ ] **步骤 4：运行测试**

运行：`cd intellimate-agent && mvn test`
预期：全部通过

- [ ] **步骤 5：Commit**

```bash
git add intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentMemoryLifecycle.java
git commit -m "feat(memory-v3): adapt AgentMemoryLifecycle to use storeEnriched and config switches"
```

---

## 任务 9：每日整合定时任务

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/scheduler/jobs/DailyMemoryConsolidationJob.java`
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/DailyMemoryConsolidator.java`
- 测试：`intellimate-gateway/src/test/java/com/atm/intellimate/gateway/service/DailyMemoryConsolidatorTest.java`

- [ ] **步骤 1：编写 DailyMemoryConsolidator 测试**

```java
@Test
void shouldGroupMemoriesByTopicAndConsolidate() {
    // 准备：3 条 detail 记忆，2 条 topic="饮食"，1 条 topic="住房"
    // 执行 consolidateDaily
    // 验证：生成 2 条 consolidated 记忆
    // 验证：source_memory_ids 正确引用
}

@Test
void shouldMergeSimilarTopics() {
    // "吃饭" 和 "饮食偏好" topic 相似度 > 0.7 → 合并为一组
}

@Test
void shouldSkipAgentsWithLongTermDisabled() {
    // longTermEnabled=false 的 agent 不参与整合
}
```

- [ ] **步骤 2：实现 DailyMemoryConsolidator**

```java
@Component
public class DailyMemoryConsolidator {
    private final AgentMemoryRepository memoryRepository;
    private final MemoryConfigService configService;
    private final LongTermMemory longTermMemory;
    private final VectorMemoryStore vectorStore;
    
    public Mono<Map<String, Integer>> consolidateAll() {
        return memoryRepository.findDistinctUserAgentPairsWithTodayDetails()
            .flatMap(pair -> configService.loadConfig(pair.agentId())
                .filter(ResolvedMemoryConfig::longTermEnabled)
                .flatMap(config -> consolidateDaily(pair.userId(), pair.agentId(), config))
            )
            .reduce(new HashMap<>(), this::mergeStats);
    }
    
    private Mono<Map<String, Integer>> consolidateDaily(String userId, String agentId, ResolvedMemoryConfig config) {
        return memoryRepository.findTodayDetailMemories(userId, agentId)
            .collectList()
            .flatMap(memories -> {
                if (memories.isEmpty()) return Mono.just(Map.of());
                Map<String, List<AgentMemoryEntity>> groups = groupByTopic(memories, config.topicSimilarityThreshold());
                return Flux.fromIterable(groups.entrySet())
                    .concatMap(entry -> consolidateTopic(entry.getKey(), entry.getValue(), userId, agentId, config))
                    .collectList()
                    .map(results -> Map.of("topicGroups", groups.size()));
            });
    }
    
    // ... groupByTopic, consolidateTopic 等方法
}
```

- [ ] **步骤 3：实现 DailyMemoryConsolidationJob**

```java
@Component
public class DailyMemoryConsolidationJob implements ScheduledJob {
    private static final Logger log = LoggerFactory.getLogger(DailyMemoryConsolidationJob.class);
    private final DailyMemoryConsolidator consolidator;

    public DailyMemoryConsolidationJob(DailyMemoryConsolidator consolidator) {
        this.consolidator = consolidator;
    }

    @Override
    public String getJobName() { return "memory-daily-consolidation"; }

    @Override
    public String getJobGroup() { return "data"; }

    @Override
    public Duration getDefaultTimeout() { return Duration.ofMinutes(30); }

    @Override
    public Mono<JobResult> execute(JobExecutionContext context) {
        return consolidator.consolidateAll()
                .map(stats -> JobResult.ok("Daily memory consolidation completed", stats))
                .onErrorResume(e -> {
                    log.error("[每日整合] 任务失败", e);
                    return Mono.just(JobResult.fail("Consolidation failed: " + e.getMessage()));
                });
    }
}
```

- [ ] **步骤 4：在 Repository 新增查询方法**

```java
// AgentMemoryRepository
@Query("SELECT DISTINCT user_id, agent_id FROM agent_memory WHERE memory_level = 'detail' AND DATE(created_at) = CURDATE()")
Flux<UserAgentPair> findDistinctUserAgentPairsWithTodayDetails();

@Query("SELECT * FROM agent_memory WHERE user_id = :userId AND agent_id = :agentId AND memory_level = 'detail' AND DATE(created_at) = CURDATE() ORDER BY created_at")
Flux<AgentMemoryEntity> findTodayDetailMemories(@Param("userId") String userId, @Param("agentId") String agentId);
```

- [ ] **步骤 5：运行测试**

运行：`cd intellimate-gateway && mvn test -Dtest=DailyMemoryConsolidatorTest`
预期：全部通过

- [ ] **步骤 6：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/scheduler/jobs/DailyMemoryConsolidationJob.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/DailyMemoryConsolidator.java
git add intellimate-gateway/src/test/java/com/atm/intellimate/gateway/service/DailyMemoryConsolidatorTest.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/
git commit -m "feat(memory-v3): daily memory consolidation job with topic grouping and LLM-based merge"
```

---

## 任务 10：前端 UI 调整

**文件：**
- 修改：`intellimate-web/src/components/MemoryManagerPage.tsx`
- 修改：`intellimate-web/src/lib/api.ts`

- [ ] **步骤 1：修改 API 类型定义**

在 `api.ts` 中更新记忆数据类型：

```typescript
interface Memory {
  id: number;
  userId: string;
  agentId: string;
  memoryType: string;
  content: string;
  keywords?: string;
  topic?: string;
  memoryLevel: 'detail' | 'consolidated';
  sourceMemoryIds?: number[];
  enrichedContent?: string;
  importance: number;
  createdAt: string;
}
```

- [ ] **步骤 2：修改 MemoryManagerPage 标签**

将三个标签（情景记忆/语义记忆/程序记忆）改为两个标签：
- 关键词记忆：展示 MySQL 中的 content + keywords
- 语义记忆：展示 enrichedContent

列表按 memoryLevel 分层展示：consolidated 在前可展开，detail 在后。

- [ ] **步骤 3：移除 v2 评分权重配置区域**

将 scoring 相关的配置项（semantic_weight、episodic_weight 等）在 UI 中标记为"已废弃"或隐藏。

- [ ] **步骤 4：本地验证 UI**

运行：`cd intellimate-web && npm run dev`
预期：页面正常加载，两个标签显示正确

- [ ] **步骤 5：Commit**

```bash
git add intellimate-web/src/components/MemoryManagerPage.tsx
git add intellimate-web/src/lib/api.ts
git commit -m "feat(memory-v3): frontend 2-tab layout with keyword/semantic memory and hierarchical display"
```

---

## 任务 11：集成测试与全量验证

**文件：**
- 修改/新增：各模块测试文件
- 验证：全量构建

- [ ] **步骤 1：运行全量编译**

运行：`mvn compile -DskipTests`
预期：全部编译通过，无报错

- [ ] **步骤 2：运行全量测试**

运行：`mvn test`
预期：全部测试通过

- [ ] **步骤 3：修复失败的测试**

对于因 ExtractedFact 构造函数变更导致的编译失败，使用 `ExtractedFact.legacy()` 进行兼容适配。

- [ ] **步骤 4：启动应用端到端验证**

运行：`./start.sh`

验证项：
1. Flyway V38 迁移成功
2. 开启持久化后，新对话结束时生成 v3 格式记忆（含 topic/keywords/enriched）
3. 检索时日志显示正确的去重键和相似度分数
4. 关闭向量开关时只存 MySQL，检索只走关键词路径
5. 每日整合 job 出现在 scheduled_job_config 中

- [ ] **步骤 5：Commit 最终修复**

```bash
git add -A
git commit -m "fix(memory-v3): fix test compilation and integration issues"
```
