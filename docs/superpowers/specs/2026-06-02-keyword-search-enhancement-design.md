# v3 关键词检索路径补全 + 前端标签修复

**日期**: 2026-06-02
**基础**: 在 v3 记忆系统（双通道存储、层次化记忆）基础上补全关键词路径缺口
**范围**: 2 个改动点，涉及 3 个文件

## 1. 问题描述

### 1.1 关键词搜索未使用 keywords 字段

V38 迁移为 `agent_memory.keywords` 列创建了 `idx_keywords` 全文索引，LLM 存储时会生成结构化关键词。
但检索时 `fulltextSearch` 仅搜索 `content` 字段，`keywords` 字段未参与召回。

对于 LLM 生成的记忆，`keywords` 字段包含比 `content` 更精准的搜索词（如 content 为 "用户要求将助手名称设定为'张三'"，
keywords 可能包含 "助手 名称 张三 命名 身份"）。不搜索 `keywords` 等于丢弃了 v3 存储层提供的检索优势。

### 1.2 前端标签数据未分离

"关键词记忆" 和 "语义记忆" 两个标签共享同一个 `longTermMemories` 数组，
仅在渲染时切换显示 `content` 还是 `enrichedContent`。
当记忆没有 `enrichedContent`（v2 旧记忆或向量未启用时），两个标签看起来完全一样。

用户期望的行为：
- 关键词记忆：显示所有记忆（MySQL 中的 content + keywords）
- 语义记忆：仅显示有 `enrichedContent` 的记忆（已向量化/语义增强的）

## 2. 修复方案

### 2.1 启用 keywords 字段联合搜索

**文件**: `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/AgentMemoryRepository.java`

**当前**:
```java
@Query("SELECT * FROM agent_memory WHERE user_id = :userId AND agent_id = :agentId AND MATCH(content) AGAINST(:searchExpr IN BOOLEAN MODE) LIMIT :maxResults")
Flux<AgentMemoryEntity> fulltextSearch(String userId, String agentId, String searchExpr, int maxResults);
```

**改为**:
```java
@Query("""
    SELECT * FROM agent_memory
    WHERE user_id = :userId AND agent_id = :agentId
      AND (MATCH(content) AGAINST(:searchExpr IN BOOLEAN MODE)
           OR (keywords IS NOT NULL AND MATCH(keywords) AGAINST(:searchExpr IN BOOLEAN MODE)))
    LIMIT :maxResults
    """)
Flux<AgentMemoryEntity> fulltextSearch(String userId, String agentId, String searchExpr, int maxResults);
```

同样修改 `fulltextSearchByAgentId`：

**当前**:
```java
@Query("SELECT * FROM agent_memory WHERE agent_id = :agentId AND MATCH(content) AGAINST(:searchExpr IN BOOLEAN MODE) LIMIT :maxResults")
Flux<AgentMemoryEntity> fulltextSearchByAgentId(String agentId, String searchExpr, int maxResults);
```

**改为**:
```java
@Query("""
    SELECT * FROM agent_memory
    WHERE agent_id = :agentId
      AND (MATCH(content) AGAINST(:searchExpr IN BOOLEAN MODE)
           OR (keywords IS NOT NULL AND MATCH(keywords) AGAINST(:searchExpr IN BOOLEAN MODE)))
    LIMIT :maxResults
    """)
Flux<AgentMemoryEntity> fulltextSearchByAgentId(String agentId, String searchExpr, int maxResults);
```

**说明**：
- `keywords IS NOT NULL` 条件避免对 v2 旧记忆（无 keywords）触发全文匹配错误
- OR 语义：content 或 keywords 任一匹配即召回
- 不改变返回值结构，不影响下游 `MemoryRetrieval` 的评分逻辑
- `idx_keywords`（V38 已创建的全文索引）自动生效

### 2.2 前端标签数据分离

**文件**: `intellimate-web/src/components/MemoryManagerPage.tsx`

在 `LongTermTab` 组件中，根据 `subTab` 过滤显示的记忆列表：

**当前**:
```typescript
const consolidated = filtered.filter((m) => m.memoryLevel === "consolidated");
const detail = filtered.filter((m) => m.memoryLevel !== "consolidated");
```
两个标签都使用 `consolidated` + `detail`，无差异。

**改为**:
```typescript
const displayMemories = subTab === "semantic"
  ? filtered.filter((m) => m.enrichedContent)
  : filtered;

const consolidated = displayMemories.filter((m) => m.memoryLevel === "consolidated");
const detail = displayMemories.filter((m) => m.memoryLevel !== "consolidated");
```

- 关键词记忆标签：显示全部记忆（`filtered` 不做额外过滤）
- 语义记忆标签：仅显示有 `enrichedContent` 的记忆

同时在 "语义记忆" 标签的空状态中增加提示：

```typescript
{displayMemories.length === 0 && subTab === "semantic" ? (
  <div className="text-center py-12 text-slate-400">
    <Database size={36} className="mx-auto mb-2 opacity-30" />
    <p className="text-sm">暂无语义增强记忆</p>
    <p className="text-xs mt-1">启用向量存储后，新记忆会自动生成语义增强版本</p>
  </div>
) : displayMemories.length === 0 ? (
  // 原有的空状态
) : (
  // 记忆列表
)}
```

## 3. 影响范围

| 文件 | 模块 | 修改内容 |
|------|------|----------|
| `AgentMemoryRepository.java` | intellimate-gateway | 2 个 `@Query` 注解修改，联合搜索 keywords |
| `MemoryManagerPage.tsx` | intellimate-web | `LongTermTab` 组件过滤逻辑 + 空状态提示 |

## 4. 不在此次范围

- Jaccard 评分替换（当前保持 v2 的 Jaccard 评分方式不变）
- 检索层去重（同一事实多条记录的合并）
- MIN_RELEVANCE_THRESHOLD 调整
- 向量搜索路径相关改动
- LLM 查询扩展

## 5. 测试要点

1. 搜索包含 `keywords` 中存在但 `content` 中不存在的词 → 应能召回
2. v2 旧记忆（`keywords = NULL`）不受影响，仍通过 content 匹配
3. 前端 "关键词记忆" 标签显示所有记忆
4. 前端 "语义记忆" 标签仅显示有 `enrichedContent` 的记忆
5. 向量未启用时，"语义记忆" 标签显示空状态提示
