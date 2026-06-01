# 记忆系统 v2 测试指南

## 概述

记忆系统 v2 引入了混合向量检索、中文分词优化、类型差异化评分等重大变更。本文档说明如何运行测试、各测试的覆盖范围以及手动验证流程。

---

## 快速运行

### 运行全部记忆相关测试

```bash
# 核心记忆模块（64 个测试）
mvn test -pl intellimate-memory -DfailIfNoTests=false

# Gateway 集成层（含配置、迁移等）
mvn test -pl intellimate-gateway -DfailIfNoTests=false

# Agent 运行时
mvn test -pl intellimate-agent -DfailIfNoTests=false
```

### 运行特定测试类

```bash
# 混合检索
mvn test -pl intellimate-memory -Dtest="HybridMemoryRetrievalTest"

# 关键词提取（jieba 分词）
mvn test -pl intellimate-memory -Dtest="KeywordExtractorTest"

# 评分函数
mvn test -pl intellimate-memory -Dtest="ScoringFunctionTest"

# 配置解析
mvn test -pl intellimate-memory -Dtest="ResolvedMemoryConfigTest"

# 向量迁移
mvn test -pl intellimate-gateway -Dtest="MemoryVectorMigratorTest"
```

---

## 自动化测试清单

### intellimate-memory 模块（64 个测试）

#### HybridMemoryRetrievalTest（2 个）

| 测试 | 验证内容 |
|------|---------|
| `retrieve_hybridMode_mergesVectorAndKeywordResults` | HYBRID 策略下，向量和关键词结果合并返回 |
| `retrieve_vectorUnavailable_fallsBackToKeyword` | 向量库不可用时降级为纯关键词检索 |

#### KeywordExtractorTest（2 个）

| 测试 | 验证内容 |
|------|---------|
| `extract_chineseText_usesJiebaSegmentation` | 中文用 jieba 分词提取关键词（非 bigram） |
| `jaccardSimilarity_withJieba_improvedAccuracy` | jieba 分词后语义相关文本的 Jaccard 相似度 > 0.2 |

#### ScoringFunctionTest（6 个）

| 测试 | 验证内容 |
|------|---------|
| `score_recentHighImportance_scoresHigh` | 新近 + 高重要性 → 高分 |
| `score_oldLowImportance_scoresLow` | 30 天前 + 低重要性 → 低分 |
| `recencyDecay_sevenDays_halfLife` | 时间衰减半衰期验证 |
| `accessBoost_logarithmic` | 访问次数对数增长 |
| `recencyDecay_nullLastAccessed` | 最后访问为 null 时的默认行为 |
| `computeRetrievalScore_semanticMemory_hasHigherWeightAndSlowerDecay` | 知识型记忆权重高、衰减慢 vs 情景型 |

#### MemoryEntryTest（4 个）

| 测试 | 验证内容 |
|------|---------|
| `toRecalledChunk_withRelevanceScore_includesTypeAndDatePrefix` | 增强标注格式：`[历史记忆 \| 知识 \| 日期 \| 相关度:0.82]` |
| `toRecalledChunk_episodicType_showsEventLabel` | 情景型显示「事件」标签 |
| `toRecalledChunk_proceduralType_showsProcessLabel` | 程序型显示「流程」标签 |
| `toRecalledChunk_original_stillWorks` | 向后兼容：旧 API 不带标注 |

#### ResolvedMemoryConfigTest（4 个）

| 测试 | 验证内容 |
|------|---------|
| `fromMap_parsesAllFields` | 全部 29 个配置项正确解析 |
| `fromMap_optionalFieldsUseDefaults` | 可选字段（vector/scoring 等）缺失时使用默认值 |
| `fromMap_missingKey_throwsDescriptiveError` | 必填字段缺失时抛出描述性异常 |
| `fromMap_invalidNumber_throwsDescriptiveError` | 数值格式错误时抛出描述性异常 |

#### MemoryRetrievalTest（3 个）

| 测试 | 验证内容 |
|------|---------|
| `retrieve_returnsTopScoredWithinBudget` | 返回最高评分记忆且不超 token 预算 |
| `retrieve_emptyMemory_returnsEmpty` | 无记忆时返回空列表 |
| `retrieve_largeCorpus_usesStagedSearchNotFullScan` | 超 1000 条时走分阶段检索 |

#### 其他基础测试

| 测试类 | 数量 | 覆盖范围 |
|--------|------|---------|
| MemoryConsolidatorTest | 5 | 记忆巩固阈值、LLM 调用、fallback、超时 |
| ConsolidationPromptBuilderTest | 3 | 巩固提示词构建 |
| ImportanceAssessorTest | 9 | 重要性评估 |
| MemoryChunkTest | 8 | Chunk 工厂方法、消息映射 |
| WorkingMemoryTest | 8 | Token 预算、巩固候选、快照 |
| TokenEstimatorTest | 10 | Token 估算（ASCII/CJK/混合） |

### intellimate-gateway 模块

#### MemoryConfigServiceTest（5 个）

| 测试 | 验证内容 |
|------|---------|
| `resolve_returnsResolvedConfig` | 从数据库构建 ResolvedMemoryConfig |
| `resolveGrouped_containsAllKeysWithMetadata` | 分组配置含完整元数据 |
| `updateConfig_upsertsEachEntry` | 更新配置调用 upsertForAgent |
| `resetToDefaults_deletesAndReinserts` | 重置配置先删后插 |
| `getDefaults_returnsExpectedSize` | 默认配置共 29 项 |

#### MemoryVectorMigratorTest（5 个）

| 测试 | 验证内容 |
|------|---------|
| `migrate_skipsWhenVectorStoreUnavailable` | 向量库不可用 → 跳过 |
| `migrate_storesMissingMemories` | 缺失记忆 → 写入向量库 |
| `migrate_skipsExistingMemories` | 已存在 → 跳过 |
| `migrate_continuesAfterSingleFailure` | 单条失败不中断 |
| `migrate_handlesEmptyRepository` | 空仓库 → 零统计 |

#### MemoryControllerTest（15 个）

| 测试 | 验证内容 |
|------|---------|
| `getConfig_returnsGroupedConfig` | API 返回 7 组配置（含 vector/retrieval/scoring） |
| `updateConfig_returnsSuccess` | PUT 更新成功 |
| `resetConfig_returnsSuccess` | POST 重置成功 |
| 其余 12 个 | 长期记忆 CRUD、统计、工作记忆快照等 |

### 已知预先存在的失败（非 v2 引入）

| 测试类 | 失败数 | 原因 |
|--------|--------|------|
| HeartbeatEngineTest | 5 | Mock 未覆盖 `isAgentReachable()`，返回 null 导致 NPE |
| MemoryControllerTest | 2 | `updateConfig`/`resetConfig` 的 mock 方法签名与实际不一致 |

---

## 手动验证流程

### 1. 验证 Qdrant 连接

```bash
# 确保 Qdrant 运行中
docker compose up -d qdrant

# 健康检查
curl http://localhost:6333/healthz

# 启动应用后检查日志
# 应看到：Starting memory vector migration...
# 或：Vector store unavailable（如果 Qdrant 未启动）
```

### 2. 验证混合检索

1. 启动应用（确保 Qdrant 已运行）
2. 与 Agent 进行几轮对话，确保产生长期记忆
3. 开始新会话，提问与之前对话相关的内容
4. 在应用日志中观察：
   ```
   Injected X recalled memories (Y tokens) for agent 'xxx' session zzz
   ```
5. 对比 v1（纯关键词）和 v2（混合检索）的召回结果质量

### 3. 验证降级行为

1. 停止 Qdrant：`docker compose stop qdrant`
2. 启动应用或发起新对话
3. 日志应显示：`Vector store unavailable, falling back to keyword retrieval`
4. 确认系统正常工作（纯关键词检索模式）

### 4. 验证前端配置

1. 访问 IntelliMate 前端
2. 进入「记忆观测 → 记忆配置」页面
3. 确认显示 7 个配置分组：
   - 工作记忆
   - 记忆巩固
   - 长期记忆
   - 向量数据库
   - Embedding 模型
   - 检索策略
   - 评分参数
4. 修改 `retrieval.strategy` 为 `keyword_only`，保存
5. 验证后续检索只走关键词模式

### 5. 验证数据迁移

```bash
# 查看 Qdrant 中的向量数量
curl -s -X POST http://localhost:6333/collections/intellimate_memories/points/count \
  -H "Content-Type: application/json" \
  -d '{"exact": true}' | python3 -m json.tool

# 对比 MySQL 中的长期记忆数量
# 两者应基本一致（启动迁移完成后）
```

### 6. 验证召回标注增强

1. 与 Agent 对话，当召回历史记忆时
2. 在工作记忆快照中观察 chunk 内容
3. 应看到类似格式的前缀：
   ```
   [历史记忆 | 知识 | 2026-05-28 | 相关度:0.82] 用户的名字叫小张
   [历史记忆 | 事件 | 2026-05-30 | 相关度:0.65] 讨论了数据库迁移方案
   ```

### 7. 验证 jieba 中文分词

检索包含中文关键词的记忆（如「数据库连接池配置」），确认：
- 能匹配「数据库」「连接池」「配置」等有意义的词
- 不再出现 bigram 的随机碎片匹配

---

## 测试覆盖缺口

以下组件缺少专用单元测试，当前通过集成层间接覆盖：

| 组件 | 建议补充 |
|------|---------|
| `LongTermMemoryImpl` | store/search/recordAccess 的 mock 测试 |
| `AgentMemoryLifecycle` | episodic 存储阈值、重要度调整、元数据写入 |
| `QdrantVectorStoreImpl` | Testcontainers 集成测试（可选） |
| `HybridMemoryRetrieval` | VECTOR_ONLY / KEYWORD_ONLY 策略、边界条件 |

---

## 配置参数调优建议

在测试不同配置时，以下参数对检索效果影响最大：

| 参数 | 调优方向 | 验证方式 |
|------|---------|---------|
| `retrieval.vector_weight` | 增大 → 更依赖语义匹配 | 观察同义词/意图匹配召回率 |
| `retrieval.keyword_weight` | 增大 → 更依赖关键词精确匹配 | 观察精确关键词匹配召回率 |
| `scoring.semantic_decay_lambda` | 减小 → 知识型记忆保持更久 | 观察旧知识的召回率 |
| `scoring.episodic_decay_lambda` | 增大 → 旧对话更快淡化 | 观察旧对话的召回频率 |
| `long_term.min_fact_importance` | 增大 → 更严格过滤低质事实 | 观察持久化的 fact 数量和质量 |
