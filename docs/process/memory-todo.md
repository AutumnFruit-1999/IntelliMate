# 记忆系统 TODO

## 接入向量 Embedding 提升检索相关性

**背景**：当前记忆检索使用 Jaccard 相似度（关键词集合交集/并集），无法理解语义。例如 "RGB" 和 "三原色" 语义相关但关键词不重叠，Jaccard = 0。

**方案**：
- 在 `ScoringFunction` 中预留 `embeddingSimilarity` 参数位置
- 存储记忆时同步生成 embedding 向量
- 检索时计算 cue 与记忆的 cosine similarity
- 最终得分 = α × Jaccard + β × embeddingSimilarity（可配置权重）

**候选技术**：
- 向量数据库：Milvus / Qdrant / pgvector
- Embedding 模型：text-embedding-v3 (DashScope) / bge-m3
- 可选轻量方案：直接在 MySQL 中存 JSON 向量 + 应用层计算（小规模场景）

**优先级**：中期规划（记忆条数 > 500 时收益明显）
