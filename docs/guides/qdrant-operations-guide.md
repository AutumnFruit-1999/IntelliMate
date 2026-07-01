# Qdrant 向量数据库操作指南

## 概述

Qdrant 是 IntelliMate 记忆系统 v2 引入的向量数据库，用于存储记忆的语义向量表示，实现基于**语义相似度**的记忆检索。它与现有的 MySQL 关键词检索互补，组成**混合检索**（Hybrid Retrieval）架构。

### 架构位置

```
用户提问 → AgentLoopExecutor
                ↓
         HybridMemoryRetrieval（编排器）
           ┌──────┴──────┐
     Qdrant 向量检索   MySQL 关键词检索
     （语义相似度）    （Jaccard + jieba 分词）
           └──────┬──────┘
          合并 → 加权排序 → 注入上下文
```

### 核心特性

- **可选依赖**：Qdrant 不可用时自动降级到纯关键词检索，不影响系统运行
- **双写模式**：记忆写入 MySQL（主存储）后异步写入 Qdrant，向量写入失败不阻塞主流程
- **启动迁移**：首次启动时自动将 MySQL 中已有记忆向量化到 Qdrant

---

## 部署

### Docker Compose（推荐）

项目根目录已包含 `docker-compose.yml`：

```bash
# 仅启动 Qdrant
docker compose up -d qdrant

# 启动 Qdrant + MySQL
docker compose up -d
```

默认端口：
| 端口 | 协议 | 用途 |
|------|------|------|
| 6333 | HTTP/REST | Web UI、REST API、健康检查 |
| 6334 | gRPC | Spring AI 连接（应用默认使用此端口） |

### 独立 Docker 运行

```bash
docker run -d \
  --name intellimate-qdrant \
  -p 6333:6333 \
  -p 6334:6334 \
  -v qdrant_data:/qdrant/storage \
  qdrant/qdrant:v1.14.0
```

### 健康检查

```bash
curl http://localhost:6333/healthz
# 返回: healthz check passed
```

---

## 配置说明

### 应用配置（application.yml）

```yaml
spring:
  ai:
    vectorstore:
      qdrant:
        host: ${QDRANT_HOST:localhost}    # Qdrant 地址
        port: ${QDRANT_GRPC_PORT:6334}    # gRPC 端口
        collection-name: intellimate_memories  # 集合名称
        initialize-schema: true            # 自动创建集合
```

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `QDRANT_HOST` | `localhost` | Qdrant 服务地址 |
| `QDRANT_GRPC_PORT` | `6334` | gRPC 连接端口 |
| `QDRANT_REST_PORT` | `6333` | REST API 端口（Docker 映射用） |
| `DASHSCOPE_API_KEY` | — | DashScope API Key（生成 Embedding 向量） |

### 运行时配置（记忆配置面板）

以下配置可在 IntelliMate 前端「记忆观测 → 记忆配置」页面修改：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `vector.enabled` | `true` | 是否启用向量检索 |
| `embedding.model` | `text-embedding-v3` | Embedding 模型（DashScope） |
| `embedding.dimensions` | `1024` | 向量维度 |
| `retrieval.strategy` | `hybrid` | 检索策略：`hybrid` / `vector_only` / `keyword_only` |
| `retrieval.vector_weight` | `0.6` | 混合检索中向量结果的权重 |
| `retrieval.keyword_weight` | `0.4` | 混合检索中关键词结果的权重 |

---

## 常用操作

### 查看集合信息

```bash
# 列出所有集合
curl http://localhost:6333/collections

# 查看 intellimate_memories 集合详情
curl http://localhost:6333/collections/intellimate_memories
```

返回内容包括：向量数量、索引状态、配置参数等。

### 查看存储的向量数据

```bash
# 按条件检索（查看某用户的记忆向量）
curl -X POST http://localhost:6333/collections/intellimate_memories/points/scroll \
  -H "Content-Type: application/json" \
  -d '{
    "filter": {
      "must": [
        {"key": "user_id", "match": {"value": "default"}}
      ]
    },
    "limit": 10,
    "with_payload": true,
    "with_vector": false
  }'
```

每条向量的 payload 包含：
- `mysql_id`：对应 MySQL 中 `agent_memory.id`
- `user_id`：用户 ID
- `agent_id`：Agent ID
- `memory_type`：`semantic` / `episodic` / `procedural`
- `importance`：重要度分数
- `created_at`：创建时间

### 统计向量数量

```bash
curl -X POST http://localhost:6333/collections/intellimate_memories/points/count \
  -H "Content-Type: application/json" \
  -d '{"exact": true}'
```

### 按用户统计

```bash
curl -X POST http://localhost:6333/collections/intellimate_memories/points/count \
  -H "Content-Type: application/json" \
  -d '{
    "filter": {
      "must": [
        {"key": "user_id", "match": {"value": "你的用户ID"}}
      ]
    },
    "exact": true
  }'
```

### 删除集合（谨慎操作）

```bash
# 删除集合（会清空所有向量数据，应用重启后会重新创建并迁移）
curl -X DELETE http://localhost:6333/collections/intellimate_memories
```

---

## 数据迁移

### 自动迁移（启动时）

应用启动时，`MemoryVectorMigrator` 自动执行：
1. 检查 Qdrant 是否可用
2. 从 MySQL 加载所有长期记忆
3. 检查每条记忆是否已在 Qdrant 中（幂等）
4. 批量写入缺失的记忆（每批 50 条，并发 5）

日志关键字：
```
# 迁移开始
MemoryVectorMigrator : Starting memory vector migration...

# 进度（每 100 条）
MemoryVectorMigrator : Migration progress: 100 processed...

# 完成
MemoryVectorMigrator : Memory vector migration completed: migrated=150, skipped=0, failed=0, total=150
```

### 手动重新迁移

如果需要重新迁移（比如更换了 Embedding 模型）：
1. 删除 Qdrant 集合
2. 重启应用

```bash
curl -X DELETE http://localhost:6333/collections/intellimate_memories
# 然后重启 IntelliMate 应用
```

---

## 故障排查

### Qdrant 未启动

**症状**：应用日志出现 `Vector store unavailable, falling back to keyword retrieval`

**处理**：
```bash
# 检查容器状态
docker ps -a --filter name=qdrant

# 启动容器
docker compose up -d qdrant

# 查看容器日志
docker logs intellimate-qdrant
```

**影响**：系统自动降级到纯关键词检索，功能不受影响，但检索质量可能下降。

### Embedding API 不可用

**症状**：向量写入失败，日志出现 `writeToVector failed`

**处理**：
1. 检查 `DASHSCOPE_API_KEY` 是否正确配置
2. 检查网络是否能访问 DashScope API
3. 确认 API 额度是否充足

**影响**：新记忆只写入 MySQL 不写入 Qdrant，不影响现有功能。

### 向量数据与 MySQL 不一致

**症状**：删除了 MySQL 中的记忆但 Qdrant 中仍存在

**处理**：应用的 `deleteById()` 会同步删除 Qdrant 中的对应向量。如果出现不一致，可以删除 Qdrant 集合并重启应用触发重新迁移。

### 磁盘空间

Qdrant 数据存储在 Docker volume `qdrant_data` 中：

```bash
# 查看 volume 大小
docker system df -v | grep qdrant_data

# 查看 volume 路径
docker volume inspect qdrant_data
```

---

## 性能参考

| 指标 | 参考值 |
|------|--------|
| 单次向量检索延迟 | < 50ms（万级向量量） |
| Embedding 生成延迟 | 100-300ms（取决于 DashScope API） |
| 混合检索总延迟 | 200-500ms（向量+关键词并行） |
| 内存占用 | ~100MB（万级向量量） |
| 磁盘占用 | ~50MB/万条记忆（1024 维向量） |

---

## 容器管理

```bash
# 启动
docker compose up -d qdrant

# 停止（保留数据）
docker compose stop qdrant

# 停止并删除容器（保留数据卷）
docker compose down

# 停止并删除容器和数据卷（清空所有数据）
docker compose down -v

# 查看日志
docker logs -f intellimate-qdrant

# 进入容器
docker exec -it intellimate-qdrant /bin/bash
```

---

## Web UI

Qdrant 内置了一个 Web UI，可在浏览器访问：

```
http://localhost:6333/dashboard
```

在这里可以：
- 浏览集合和向量数据
- 执行搜索查询
- 查看集群状态
- 管理快照

---

## 与记忆系统的关系

| 操作 | MySQL | Qdrant | 说明 |
|------|-------|--------|------|
| 记忆存储 | 主写入（同步） | 异步双写 | Qdrant 写入失败不影响主流程 |
| 记忆检索 | 关键词匹配 | 语义相似度 | 两路结果按权重合并排序 |
| 记忆删除 | 主删除 | 同步删除 | 删除失败只 warn |
| 数据源 | 唯一真实来源 | 索引副本 | 可随时从 MySQL 重建 |

**核心原则**：MySQL 是数据的唯一真实来源（Source of Truth），Qdrant 是加速语义检索的索引。任何时候都可以通过删除 Qdrant 集合并重启应用来重建向量索引。
