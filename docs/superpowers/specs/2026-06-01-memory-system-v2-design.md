# 记忆系统 v2 设计规格 — 混合向量检索 + 缺陷修复

## 背景

当前记忆系统使用纯关键词检索（Jaccard bigram 相似度 + MySQL FULLTEXT），存在以下核心问题：

- **信息丢失**：重要记忆存在数据库中但因关键词不匹配而检索不到（如"数据库连接"无法匹配"DB connection timeout"）
- **中文分词粗糙**：bigram 切分产生无意义 token（如"库连"）
- **无语义理解**：纯词面匹配，无法处理同义词、近义词、语义相近但用词不同的场景
- **评分无差异化**：三种记忆类型（episodic/semantic/procedural）使用相同权重和衰减参数
- **多用户隔离缺失**：`findByUserId` 实际只按 agentId 查询
- **配置项未生效**：`min_chunks_for_episodic` 和 `long_term.enabled` 在部分路径上被忽略

## 目标

1. 引入向量语义检索，让记忆检索从"词匹配"进化到"意义匹配"
2. 采用混合检索策略，向量 + 关键词双路并行，容错降级
3. 优化评分公式，按记忆类型差异化权重和衰减
4. 增强召回记忆标注，帮助 LLM 判断记忆的类型、时效和可信度
5. 修复已知缺陷

## 技术选型

| 组件 | 选型 | 理由 |
|------|------|------|
| 向量数据库 | **Qdrant** | 单容器 Docker 部署、内存占用小、Spring AI 官方集成、Payload 过滤丰富 |
| Embedding 模型 | **可配置**（默认 DashScope text-embedding-v3） | 与现有 DashScope 技术栈一致，中文支持好 |
| 检索策略 | **混合检索**（向量 + 关键词融合排序） | 兼顾语义理解和精确匹配，Qdrant 不可用时自动回退 |

## 详细设计

### 1. Qdrant 向量存储集成

#### 1.1 新增依赖

```xml
<!-- intellimate-gateway/pom.xml -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-qdrant-store-spring-boot-starter</artifactId>
</dependency>
```

#### 1.2 Docker 部署

```yaml
# docker-compose.yml 新增
qdrant:
  image: qdrant/qdrant:latest
  ports:
    - "6333:6333"
    - "6334:6334"
  volumes:
    - qdrant_data:/qdrant/storage
```

#### 1.3 Qdrant Collection 设计

- Collection 名称：`intellimate_memories`
- 向量维度：可配置（默认 1024，匹配 text-embedding-v3）
- 距离函数：Cosine
- Payload 字段：

```json
{
  "mysql_id": 123,
  "agent_id": "assistant",
  "user_id": "user1",
  "memory_type": "semantic",
  "importance": 0.8,
  "content": "原始文本内容",
  "created_at": "2026-05-28T10:00:00Z"
}
```

#### 1.4 新增接口 — VectorMemoryStore

位置：`intellimate-memory` 模块

```java
public interface VectorMemoryStore {
    Mono<Void> store(MemoryEntry entry);
    Mono<List<VectorSearchResult>> search(String query, String userId, String agentId, int topK);
    Mono<Void> deleteById(Long mysqlId);
    Mono<Boolean> isAvailable();
}

public record VectorSearchResult(
    Long mysqlId,
    String content,
    String memoryType,
    float importance,
    double similarity,      // cosine similarity [0,1]
    Instant createdAt
) {
    /** 直接从向量搜索结果构建 MemoryChunk，无需回查 MySQL */
    public MemoryChunk toRecalledChunk(int estimatedTokens, double relevanceScore) {
        // 复用 MemoryEntry 的标注逻辑
    }
}
```

#### 1.5 实现类 — QdrantVectorStoreImpl

位置：`intellimate-gateway` 模块

- 注入 Spring AI 的 `VectorStore` + `EmbeddingModel`
- `store()`: 调用 `EmbeddingModel.embed()` 获取向量 → 写入 Qdrant（带 metadata）
- `search()`: 将 query embed → Qdrant similarity search（带 agent_id + user_id filter）→ 返回 `VectorSearchResult`
- `isAvailable()`: health check，不可用时返回 false
- `deleteById()`: 按 `mysql_id` payload 删除对应向量

#### 1.6 双写流程

```
ExtractedFact
    → MySQL store (现有，同步)
    → 成功后 → VectorMemoryStore.store(entry) (异步)
                  ↓ 失败不阻塞
               日志 warn + 后台重试队列
```

修改 `LongTermMemoryImpl.store()` 方法，在 MySQL 写入成功后异步调用 `VectorMemoryStore.store()`。向量写入失败不影响主流程。

重试策略：使用 Reactor 内置的 `retryWhen(Retry.backoff(3, Duration.ofSeconds(1)))` 实现指数退避重试，无需引入外部消息队列。3 次重试失败后记录 ERROR 日志并放弃（数据迁移任务会在下次启动时补齐）。

### 2. 混合检索策略

#### 2.1 新增 HybridMemoryRetrieval

位置：`intellimate-memory` 模块

```java
public class HybridMemoryRetrieval {
    private final MemoryRetrieval keywordRetrieval;
    private final VectorMemoryStore vectorStore;
    private final LongTermMemory longTermMemory;
    private final TokenEstimator tokenEstimator;
    private final ScoringFunction scoringFunction;

    public enum Strategy { HYBRID, VECTOR_ONLY, KEYWORD_ONLY }

    public Mono<List<MemoryChunk>> retrieve(
        String cue, String userId, String agentId,
        int maxInjectionTokens, double lambda, Strategy strategy);
}
```

#### 2.2 HYBRID 模式检索流程

```
cue (用户消息)
    │
    ├─ [路径 A - 向量] vectorStore.search(cue, userId, agentId, topK=20)
    │   → List<VectorSearchResult> (带 cosine similarity)
    │
    ├─ [路径 B - 关键词] keywordRetrieval.selectAndScore(candidates, cue, budget, lambda)
    │   → scored candidates (带 Jaccard + importance + decay 综合分)
    │
    └─ [融合器] mergeAndRank()
        1. 以 mysql_id 为 key 合并两路结果去重
        2. 融合分数: finalScore = α × vectorSim + β × keywordScore
           (α=0.6, β=0.4, 可配置)
        3. 按 finalScore 降序排列
        4. Token budget 截取
        5. 返回 List<MemoryChunk>
```

#### 2.3 容错降级

```java
if (!vectorStore.isAvailable() || strategy == KEYWORD_ONLY) {
    return keywordRetrieval.retrieve(cue, userId, agentId, maxTokens, lambda);
}
```

#### 2.4 对现有代码的改动策略

- **不修改** `MemoryRetrieval`，保持为独立可用的关键词检索器
- `HybridMemoryRetrieval` 作为新的顶层编排器
- `AgentLoopExecutor` 注入 `HybridMemoryRetrieval` 替代 `MemoryRetrieval`

### 3. 评分优化 — 类型差异化

#### 3.1 改进后的评分公式

```
score = relevance × importance × typeWeight × recencyDecay(λ_type) × accessBoost
```

#### 3.2 类型参数

| 记忆类型 | typeWeight | λ (衰减系数) | 设计理由 |
|----------|-----------|-------------|---------|
| semantic | 1.2 | 0.03 | 知识/偏好持久性强，衰减慢，权重高 |
| episodic | 0.8 | 0.10 | 事件记忆时效性强，保持现有衰减速度 |
| procedural | 1.0 | 0.05 | 操作流程中等持久 |

#### 3.3 效果对比（30 天后 recency 得分）

| 类型 | 改进前 (统一 λ=0.1) | 改进后 |
|------|-------------------|--------|
| semantic | 5% | 41% (λ=0.03) |
| episodic | 5% | 5% (λ=0.10) |
| procedural | 5% | 22% (λ=0.05) |

#### 3.4 修改 ScoringFunction

```java
public double computeRetrievalScore(MemoryEntry memory, double relevance, double lambda) {
    double importance = memory.getImportance();
    double typeWeight = getTypeWeight(memory.getMemoryType());
    double typeLambda = getTypeLambda(memory.getMemoryType(), lambda);
    double recencyDecay = computeRecencyDecay(memory.getLastAccessedAt(), typeLambda);
    double accessBoost = 1.0 + Math.log1p(memory.getAccessCount());
    return relevance * importance * typeWeight * recencyDecay * accessBoost;
}

private double getTypeWeight(String memoryType) {
    return switch (memoryType) {
        case "semantic" -> semanticWeight;       // 默认 1.2
        case "episodic" -> episodicWeight;       // 默认 0.8
        case "procedural" -> proceduralWeight;   // 默认 1.0
        default -> 1.0;
    };
}

private double getTypeLambda(String memoryType, double baseLambda) {
    return switch (memoryType) {
        case "semantic" -> semanticDecayLambda;      // 默认 0.03
        case "episodic" -> episodicDecayLambda;      // 默认 0.10
        case "procedural" -> proceduralDecayLambda;  // 默认 0.05
        default -> baseLambda;
    };
}
```

### 4. 召回记忆增强标注

#### 4.1 改进后的注入格式

```
[历史记忆 | 知识 | 2026-05-28 | 相关度:0.82] 用户偏好简洁代码风格，不喜欢过度注释
[历史记忆 | 事件 | 2026-05-30 | 相关度:0.71] 上次会话中修复了 auth 模块的 NPE bug
[历史记忆 | 流程 | 2026-05-25 | 相关度:0.65] 部署时先运行 mvn test，再 docker-compose up
```

#### 4.2 实现变更

修改 `MemoryEntry.toRecalledChunk()`，新增带 relevanceScore 参数的重载：

```java
public MemoryChunk toRecalledChunk(int estimatedTokens, double relevanceScore) {
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
        typeLabel, dateStr, relevanceScore);
    return MemoryChunk.recalled(prefix + content, estimatedTokens, importance);
}
```

修改 `AgentLoopExecutor` 中的注入逻辑，使用新的 `toRecalledChunk` 方法。

### 5. 缺陷修复

#### 5.1 findByUserId 多用户隔离修复

**问题**：`LongTermMemoryImpl.findByUserId()` 和 `countByUserId()` 忽略 userId 参数。

**修复**：
- `findByUserId()` 改为 `repository.findByUserIdAndAgentId(uid, aid)`
- `countByUserId()` 改为 `repository.countByUserIdAndAgentId(uid, aid)`
- `search()` 增加 userId 过滤
- 缓存 key 改为 `userId:agentId` 组合
- `AgentMemoryRepository` 新增对应查询方法

#### 5.2 min_chunks_for_episodic 生效

**问题**：配置项存在但从未检查。

**修复**：在 episodic 存储流程入口加入检查：

```java
if (nonSystemChunks.size() < minChunksForEpisodic) {
    log.info("Session too short ({} chunks < {}), skipping episodic storage",
        nonSystemChunks.size(), minChunksForEpisodic);
    return;
}
```

#### 5.3 persistFromTranscript 尊重 long_term.enabled

**问题**：`/clear` 时无视配置开关。

**修复**：在 `SessionHistoryController.persistFromTranscript` 中先读取配置：

```java
memoryConfigService.resolve()
    .filter(config -> config.isLongTermEnabled())
    .flatMap(config -> /* 存储逻辑 */)
    .switchIfEmpty(Mono.fromRunnable(() ->
        log.info("Long-term memory disabled, skipping episodic persistence")))
```

#### 5.4 现有数据迁移到 Qdrant

提供应用启动时自动执行的迁移任务（`ApplicationRunner`），仅在 `vector.enabled=true` 时运行：

- 检查 Qdrant collection 是否存在，不存在则创建（指定维度和距离函数）
- 扫描 `agent_memory` 表所有记录
- 按 Qdrant 中是否已存在对应 `mysql_id` 的 point 过滤，跳过已迁移的（幂等）
- 批量 embed + 写入 Qdrant（每批 50 条，批次间间隔 200ms，避免 Embedding API rate limit）
- 记录迁移进度到日志（每 100 条输出一次进度）
- 迁移失败的记录记录 WARN 日志并跳过，不阻塞启动

### 6. 新增配置项

以下配置项添加到 `memory_config` 表：

| 配置键 | 默认值 | 分组 | 说明 |
|--------|--------|------|------|
| `vector.enabled` | `true` | vector | 向量检索主开关 |
| `embedding.model` | `text-embedding-v3` | vector | Embedding 模型名称 |
| `embedding.dimensions` | `1024` | vector | 向量维度 |
| `retrieval.strategy` | `hybrid` | retrieval | 检索策略：hybrid / vector-only / keyword-only |
| `retrieval.vector_weight` | `0.6` | retrieval | 向量得分权重 α |
| `retrieval.keyword_weight` | `0.4` | retrieval | 关键词得分权重 β |
| `scoring.semantic_weight` | `1.2` | scoring | semantic 类型权重 |
| `scoring.episodic_weight` | `0.8` | scoring | episodic 类型权重 |
| `scoring.procedural_weight` | `1.0` | scoring | procedural 类型权重 |
| `scoring.semantic_decay_lambda` | `0.03` | scoring | semantic 衰减系数 |
| `scoring.episodic_decay_lambda` | `0.10` | scoring | episodic 衰减系数 |
| `scoring.procedural_decay_lambda` | `0.05` | scoring | procedural 衰减系数 |

同时需要新增 Flyway migration 插入这些默认配置。

### 7. 受影响的模块

| 模块 | 变更类型 | 涉及文件 |
|------|---------|---------|
| intellimate-memory | 新增 `VectorMemoryStore` 接口、`VectorSearchResult` 记录、`HybridMemoryRetrieval` 类；修改 `ScoringFunction`、`MemoryEntry` | retrieval/, model/ |
| intellimate-gateway | 新增 `QdrantVectorStoreImpl`、Qdrant 配置类、数据迁移任务；修改 `LongTermMemoryImpl`、`SessionHistoryController`、`MemoryConfigService`；新增 Flyway migration | service/, config/, http/, repository/ |
| intellimate-agent | 修改 `AgentLoopExecutor` 记忆注入逻辑和依赖注入；修改 `AgentMemoryLifecycle` | runtime/ |
| intellimate-web | 配置面板新增向量/评分相关配置项 | MemoryManagerPage.tsx, memoryStore.ts |
| Docker / 启动脚本 | 新增 Qdrant 容器配置 | docker-compose.yml, start.sh |

### 8. 测试策略

- `ScoringFunction` 单元测试：验证类型差异化评分
- `HybridMemoryRetrieval` 单元测试：验证融合排序、降级逻辑
- `QdrantVectorStoreImpl` 集成测试：使用 Testcontainers 启动 Qdrant
- `LongTermMemoryImpl` 修复验证：userId 隔离、min_chunks 检查、enabled 开关
- 数据迁移任务测试：幂等性验证
- 端到端测试：完整的存储 → 检索 → 注入流程

### 9. 向后兼容

- Qdrant 为可选依赖，不可用时自动降级为纯关键词模式
- 现有 `MemoryRetrieval` 类保持不变，可独立使用
- 所有新配置项都有合理默认值
- 现有 MySQL 数据结构不变，仅新增 Flyway migration 插入配置
- 前端配置面板向后兼容，新配置项以新分组展示
