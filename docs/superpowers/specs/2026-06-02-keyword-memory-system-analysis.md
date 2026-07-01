# 关键词记忆检索系统详细分析

**日期**: 2026-06-02
**目的**: 记录当前关键词记忆检索系统的完整工作流程、已知问题和改进方向

## 1. 系统架构概览

```
用户消息 → AgentLoopExecutor
                │
                ├─ AgentMemoryLifecycle.loadMemoryInitReactive(agentName)
                │   └─ MemoryConfigProvider.resolveForAgent(agentName)
                │       └─ ResolvedMemoryConfig（检索策略、token 预算、衰减参数等）
                │
                ├─ 构建 cue = userMessage（+ planStep）
                │
                └─ MemorySystem.retrieveMemories(cue, userId, agentId, resolvedMem)
                    └─ HybridMemoryRetrieval.retrieve(cue, ..., strategy)
                        │
                        ├─ KEYWORD_ONLY → MemoryRetrieval.retrieve()
                        ├─ VECTOR_ONLY  → vectorStore.search()
                        └─ HYBRID       → Mono.zip(vector, keyword) → mergeAndRank()
```

当前测试配置：`vector.enabled = false` → 策略强制为 `KEYWORD_ONLY`

## 2. 关键词检索完整数据流

### 2.1 入口：HybridMemoryRetrieval.retrieve()

**文件**: `intellimate-memory/.../retrieval/HybridMemoryRetrieval.java`

```java
if (vectorStore == null || strategy == Strategy.KEYWORD_ONLY) {
    return keywordRetrieval.retrieve(cue, userId, agentId, maxInjectionTokens, lambda);
}
```

策略为 KEYWORD_ONLY 时，直接委托给 `MemoryRetrieval`。

### 2.2 核心：MemoryRetrieval.retrieve()

**文件**: `intellimate-memory/.../retrieval/MemoryRetrieval.java`

```
countByUserId(userId, agentId)
    │
    ├─ count > 1000 ─→ [大规模路径]
    │   longTermMemory.search(cue, userId, agentId)
    │       .take(100)
    │       .collectList()
    │       → 按 Jaccard 初步排序 → top 20
    │       → selectAndScore()
    │
    └─ count ≤ 1000 ─→ [小规模路径] ⚠️ 当前走这条
        longTermMemory.findByUserId(userId, agentId)
            .collectList()                     ← 全量加载所有记忆！
            → selectAndScore()
```

**关键问题**：count ≤ 1000 时，**不做任何搜索过滤**，直接全量加载。
所有记忆都成为候选，与查询内容无关。

### 2.3 搜索层：LongTermMemoryImpl.search()

**文件**: `intellimate-gateway/.../service/LongTermMemoryImpl.java`

**仅在 count > 1000 时被调用**。

```
extract keywords from cue
    │
    ├─ keywords 为空 → findByUserIdAndAgentId（全量加载）
    │
    └─ keywords 非空
        │
        ├─ FULLTEXT 搜索: MATCH(content) AGAINST(:expr) OR MATCH(keywords) AGAINST(:expr)
        │   └─ 有结果 → 返回
        │
        └─ FULLTEXT 无结果 → contains 回退
            findByUserIdAndAgentId 全量加载
            → Java 层 content.contains(keyword) 过滤
```

### 2.4 关键词提取：KeywordExtractor.extract()

**文件**: `intellimate-memory/.../retrieval/KeywordExtractor.java`

- 使用 **jieba** 中文分词（SEARCH + INDEX 模式）
- 停用词过滤（"的"、"了"、"在"、"是"、"你"、"我" 等）
- 单字白名单：`谁、何、哪、吗、呢`
- 最多保留 **15** 个关键词

**示例**：
| 输入 | 提取的关键词 |
|------|-------------|
| "你是谁" | `["谁"]` |
| "你的名字是什么？" | `["名字"]`（"你"、"的"、"是"、"什么" 均为停用词） |
| "苹果为什么是红的" | `["苹果", "红"]`（"为什么"、"是"、"的" 为停用词） |
| "用户将助手名称设定为张三" | `["用户", "助手", "名称", "设定", "张三"]` |

### 2.5 评分：selectAndScore()

**文件**: `intellimate-memory/.../retrieval/MemoryRetrieval.java`

```
对每条候选记忆:
    1. relevance = Jaccard(extract(cue), extract(memory.content))
    2. score = relevance × importance × typeWeight × recencyDecay × accessBoost
    3. 过滤: relevance >= MIN_RELEVANCE_THRESHOLD (0.20)
    4. 按 score 降序排序
    5. 按 token 预算选取
```

**Jaccard 相似度公式**：
```
Jaccard(A, B) = |A ∩ B| / |A ∪ B|
```

**综合评分公式**：
```
score = max(0.1, relevance) × importance × typeWeight × recencyDecay × accessBoost

其中:
- typeWeight: semantic=1.2, episodic=0.8, procedural=1.0
- recencyDecay = e^(-typeLambda × daysSinceLastAccess)
  - semantic lambda=0.03, episodic=0.10, procedural=0.05
- accessBoost = 1 + ln(1 + accessCount)
```

### 2.6 注入：MemoryChunk → WorkingMemory

每条通过评分的记忆被格式化为：
```
[历史记忆 | 知识 | 2026-06-02 | 相关度:0.85] 记忆内容
```

作为 `SystemMessage` 注入到 LLM 对话的 WorkingMemory 中。

## 3. 日志分析：实际执行示例

### 3.1 查询 "你的名字是什么？"

```
[记忆检索-详情] 查询='你的名字是什么？', 策略=KEYWORD_ONLY, 向量启用=false
[记忆评分] cue='你的名字是什么？', 候选数=2, 阈值=0.2, tokenBudget=2048
[记忆评分]   >> id=68, Jaccard=0.000, score=0.1625, content='用户将助手名称设定为张三，助手确认接受该名称'
[记忆评分]   >> id=69, Jaccard=0.000, score=0.0480, content='用户将助手名称从Claude更改为默默，当前有效名称为默默'
[记忆评分] 通过阈值 0 条 / 候选 2 条
[记忆评分] 最终注入 0 条, 消耗 0 tokens
```

**分析**：
1. 总记忆数=2，走 ≤1000 路径 → 全量加载 2 条
2. Jaccard("名字", {"用户","助手","名称","设定","张三","确认","接受"}) = 0/8 = **0.000**
   - "名字" ≠ "名称"（jieba 词级别不同）
3. 0.000 < 0.20 → 被过滤
4. 结果：**0 条注入**（尽管 id=68 在语义上高度相关）

### 3.2 查询 "苹果为什么是红的"

```
[记忆评分] cue='苹果为什么是红的', 候选数=2, 阈值=0.2, tokenBudget=2048
[记忆评分]   >> id=68, Jaccard=0.000, score=0.1625
[记忆评分]   >> id=69, Jaccard=0.000, score=0.0480
[记忆评分] 通过阈值 0 条 / 候选 2 条
```

**分析**：
1. 同样全量加载 2 条（与查询内容无关的记忆也被加载）
2. Jaccard("苹果","红") vs 任何记忆 = 0.000
3. 结果：**0 条注入**（正确行为 — 这些记忆确实不相关）

## 4. 已识别的问题

### P1: ≤1000 路径不做搜索过滤（根因）

**现象**: 不管查什么，所有记忆都被加载为候选
**影响**: 依赖 Jaccard 作为唯一相关性判断，而 Jaccard 对中文短查询无效
**位置**: `MemoryRetrieval.retrieve()` 的 `count ≤ 1000` 分支

### P2: Jaccard 对中文语义匹配无效

**现象**: "名字" ≠ "名称"，Jaccard = 0；"你是谁" 与 "助手名称" 无交集
**原因**: Jaccard 是词级别的集合交集比，不理解同义词、近义词
**影响**: 语义相关但词面不同的查询-记忆对得分为 0

### P3: FULLTEXT 和 Jaccard 使用不同的匹配粒度

**现象**: FULLTEXT（ngram 字符级）能匹配 "名字" → "名称"（共享 "名"），但 Jaccard（jieba 词级）认为两者无关
**影响**: FULLTEXT 找到的记忆可能被 Jaccard 过滤掉（在 >1000 路径中）

### P4: V38 keywords 字段检索已启用但 ≤1000 路径不使用

**现象**: 改动 1 已让 FULLTEXT 联合搜索 content + keywords，但 ≤1000 路径跳过了 FULLTEXT
**影响**: keywords 字段对小记忆集无效

## 5. 已完成的改动

| 改动 | 文件 | 状态 |
|------|------|------|
| FULLTEXT 联合搜索 content + keywords | `AgentMemoryRepository.java` | ✅ 已实现 |
| 前端标签数据分离 | `MemoryManagerPage.tsx` | ✅ 已实现 |
| 关键词检索日志 | `LongTermMemoryImpl.java` | ✅ 已实现 |
| 记忆评分日志 | `MemoryRetrieval.java` | ✅ 已实现 |

## 6. 待改进方向

### 方向 A: 统一使用 FULLTEXT 搜索（消除 ≤1000 全量加载）

不管记忆数量多少，都先用 FULLTEXT 搜索过滤，只有无结果时才全量加载。
这样 keywords 字段和 ngram 匹配能力对所有场景生效。

### 方向 B: 去掉或降低 MIN_RELEVANCE_THRESHOLD

让 Jaccard 仅用于排序（不做过滤），FULLTEXT 的召回结果全部参与排序。
优点：简单；缺点：不相关记忆可能混入。

### 方向 C: 启用向量搜索

向量搜索通过 embedding 相似度天然解决语义匹配问题。
这是 v3 设计的核心能力，需要配置 embedding 模型。

### 方向 D: LLM 查询扩展

对短查询调用 LLM 扩展关键词，"你的名字是什么？" → "名字 名称 身份 角色 称呼"。
优点：真正解决语义鸿沟；缺点：增加延迟和成本。

## 7. 配置参考

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| `long_term.enabled` | false | 是否启用长期记忆检索 |
| `long_term.max_injection_tokens` | 2048 | 单次注入的最大 token 数 |
| `long_term.decay_lambda` | 0.1 | 遗忘曲线基础衰减系数 |
| `vector.enabled` | false | 是否启用向量存储和检索 |
| `retrieval.strategy` | keyword_only | 检索策略: keyword_only / vector_only / hybrid |
| `vector.similarity_threshold` | 0.35 | 向量相似度阈值（当前未接入） |

## 8. 关键代码文件索引

| 文件 | 模块 | 职责 |
|------|------|------|
| `HybridMemoryRetrieval.java` | intellimate-memory | 策略路由（hybrid/vector/keyword） |
| `MemoryRetrieval.java` | intellimate-memory | 关键词检索 + Jaccard 评分 |
| `KeywordExtractor.java` | intellimate-memory | jieba 分词 + 关键词提取 |
| `ScoringFunction.java` | intellimate-memory | 综合评分公式 |
| `LongTermMemoryImpl.java` | intellimate-gateway | FULLTEXT 搜索 + contains 回退 |
| `AgentMemoryRepository.java` | intellimate-gateway | MySQL 查询（FULLTEXT / findByUserId） |
| `QdrantVectorStoreImpl.java` | intellimate-gateway | 向量存储/搜索（当前禁用） |
| `AgentLoopExecutor.java` | intellimate-agent | 记忆注入到 LLM 对话 |
