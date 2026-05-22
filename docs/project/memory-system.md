# IntelliMate 记忆系统

## 概述

记忆系统赋予 Agent "记住"的能力。它不是简单地保存聊天记录，而是模拟人类记忆的分层结构：短期对话内容保存在工作记忆中，重要事实经过提炼后沉淀到长期记忆，长期不用的记忆会逐渐遗忘或归档。当用户再次对话时，系统能从长期记忆中召回相关内容，让 Agent 表现出跨会话的连续性。

记忆系统的核心模块位于 `intellimate-memory` 工程，持久化实现在 `intellimate-gateway`，运行时编排在 `intellimate-agent`。

## 记忆层次结构

系统采用三层记忆架构，每层有不同的存储方式、生命周期和用途。

### 工作记忆（Working Memory）

工作记忆是 Agent 在单次对话运行中的"短期记忆"，存储在 JVM 内存中。每次 Agent 处理用户消息时创建一个新的工作记忆实例，对话结束后销毁。工作记忆是构建 LLM 输入的唯一入口：系统提示、对话历史、工具调用结果、召回的长期记忆——所有信息最终都汇入工作记忆，由它组装成发送给 LLM 的完整 prompt。

工作记忆有 token 预算限制（默认 128,000 tokens）。当使用率超过阈值（默认 75%）时，系统会触发压缩机制，将旧的对话内容浓缩成摘要，腾出空间给新内容。

### 长期记忆（Long-Term Memory）

长期记忆是跨会话持久化的知识库，存储在 MySQL 的 `agent_memory` 表中。长期记忆分为三种类型：

情景记忆（episodic）：记录"发生了什么"。例如"用户今天讨论了数据库迁移方案"、"用户完成了前端重构计划"。来源于会话结束时的摘要提取和工作记忆压缩时的事实抽取。

语义记忆（semantic）：记录"知道什么"。例如"用户偏好使用 TypeScript"、"项目使用 React + Zustand 技术栈"。来源于对话中提取的知识性事实。

程序记忆（procedural）：记录"怎么做"。例如"部署流程：先跑测试、再构建镜像、最后推送到 Fly.io"。来源于成功完成的操作模式，特别是计划执行完成后的步骤总结。

长期记忆默认关闭（`long_term.enabled = false`），需要在记忆配置中手动开启。

### 冷归档（Cold Archive）

长期不被访问且重要性较低的记忆会被定期归档到 `agent_memory_archive` 表，不再参与检索。这是记忆系统的"遗忘"机制，防止记忆库无限膨胀。

## 数据流全景

```
用户消息
  │
  ▼
MessagePipeline ──────── 持久化到 transcript_message
  │
  ▼
AgentRuntime
  │
  ├─ 1. 构建系统提示 ─────────────────────────────┐
  ├─ 2. 加载记忆配置（MemoryConfigProvider）         │
  ├─ 3. 创建 WorkingMemory                         │
  ├─ 4. 注入对话历史 ─── transcript_message          │
  ├─ 5. 长期记忆召回 ─── agent_memory ──── 计分排序  │
  │     │                                           │
  │     └── 注入 RECALLED 类型 chunk ──────────────────┤
  │                                                  │
  ├─ 6. buildLLMInput() ←──── 全部 chunk 组装 ────────┘
  │     │
  │     ▼
  │   LLM 调用（ChatModel.stream）
  │     │
  │     ▼
  ├─ 7. 处理工具调用 ──── 评估重要性 ──── accept()
  │
  ├─ 8. 使用率超阈值？──── 触发压缩（MemoryConsolidator）
  │     │                    │
  │     │                    ├─ 生成摘要 chunk 替换旧 chunk
  │     │                    └─ 抽取事实 ──── 存入 agent_memory
  │     │
  ├─ 9. 发送 memory.snapshot 事件 ──── 前端记忆观测面板
  │
  └─ 10. 会话结束
        │
        ├── WebSocket 断开 ──── 刷出延迟情景记忆
        └── 计划完成 ──── 提取程序记忆 + 情景记忆
```

## 工作记忆详解

### 内部结构

工作记忆由一个固定的系统 chunk 和一个有序的 chunk 列表组成。每个 chunk 代表一段对话内容，有明确的类型标识。

chunk 类型包括：SYSTEM（系统提示，固定不动）、USER（用户消息）、ASSISTANT（Agent 回复）、TOOL_INTERACTION（工具调用及结果）、CONSOLIDATED（压缩后的摘要）、RECALLED（从长期记忆召回的内容）。

chunk 列表是按时间顺序追加的。当构建 LLM 输入时，系统 chunk 始终放在最前，随后按顺序拼接其余 chunk。RECALLED 类型的 chunk 会以 SystemMessage 角色注入，带有 `[历史记忆]` 前缀。

### Token 管理

工作记忆维护一个 token 预算（默认 128,000）。token 计数有两个来源：LLM API 返回的实际 prompt token 数（精确值，每次 LLM 调用后更新）和内部的增量估算（基于启发式规则：CJK 字符约 1.5 字符/token，ASCII 约 4 字符/token）。

每次 accept 新 chunk 时，系统计算当前使用率。如果超过压缩阈值（默认 75%），异步触发压缩。如果正处于压缩冷却期但使用率超过了溢出容忍度（默认 110%），执行紧急截断——直接丢弃最旧的非系统 chunk。

### 压缩机制（MemoryConsolidator）

压缩是工作记忆的核心自我管理能力。当触发条件满足时：

1. 选择压缩候选：排除系统 chunk，保留最近约 3 个 chunk，其余按综合评分（重要性 x 时间衰减 x 任务相关性）从低到高排序，评分最低的优先被压缩。

2. 调用压缩 LLM：使用专门的压缩模型（默认 qwen-turbo，可配置），将候选 chunk 的内容发送给 LLM，要求返回 JSON 格式的压缩结果，包含一段摘要文本和若干提取的事实。

3. 替换 chunk：将被选中的候选 chunk 替换为一个 CONSOLIDATED 类型的摘要 chunk。

4. 存入长期记忆：提取的事实按类型（episodic/semantic/procedural）存入 `agent_memory` 表。

压缩 prompt 引导 LLM 进行三类事实提取：情景记忆（这次会话中发生的重要事件）、语义记忆（关于项目、代码、用户偏好的知识）、程序记忆（成功的操作模式或工作流）。

压缩 LLM 与主 Agent LLM 是分离的，可以使用更轻量的模型以降低延迟和成本。压缩有超时限制（默认 5 秒）和最大重试次数（默认 2 次）。

### LLM 输入构建

`buildLLMInput()` 方法将工作记忆中的所有 chunk 转换为 LLM 可消费的 Message 列表。转换规则：SYSTEM chunk 生成 SystemMessage，USER chunk 生成 UserMessage，ASSISTANT chunk 生成 AssistantMessage，TOOL_INTERACTION chunk 生成 AssistantMessage（含工具调用）+ ToolResponseMessage，CONSOLIDATED chunk 生成 UserMessage，RECALLED chunk 生成 SystemMessage。

如果当前有压缩正在进行，`buildLLMInput()` 会等待压缩完成后再构建，确保输出的一致性。

## 长期记忆详解

### 存储与去重

当新的事实需要存入长期记忆时，系统首先检查是否已存在高度相似的记忆（Jaccard 相似度 > 0.85）。如果存在，合并内容并提升重要性而非创建新条目。如果不存在，创建新的记忆条目。

每条记忆包含：内容文本、记忆类型、重要性评分（0-1）、访问次数、最后访问时间、创建时间、来源会话 ID（可选）、元数据 JSON（可选）。

### 检索与召回

长期记忆的检索发生在每次 Agent 运行开始时（如果长期记忆已启用）。系统以用户消息（加上当前计划步骤描述，如果有的话）作为检索线索。

检索流程根据记忆数量采取不同策略：

记忆数量大于 1000 条时：使用分阶段检索。先通过 MySQL FULLTEXT 全文索引搜索匹配的前 100 条候选，然后在内存中对候选进行评分排序，选取前 20 条，最后按 token 预算截断。

记忆数量小于等于 1000 条时：加载该 Agent 的所有记忆到内存，在内存中完成评分和排序。使用 Caffeine 缓存（200 条目，5 分钟 TTL）减少数据库访问。

### 评分函数

每条候选记忆的得分由四个因子相乘计算：

```
score = relevance × importance × recencyDecay × accessBoost
```

相关性（relevance）：用户查询与记忆内容之间的 Jaccard 关键词相似度。低于 0.20 的记忆直接过滤掉。关键词通过 `KeywordExtractor` 提取，对中文使用 jieba 风格的分词，对英文使用空格分割。

重要性（importance）：存储在数据库中的重要性评分，范围 0-1。由创建时的评估决定，合并时会累加。

时间衰减（recencyDecay）：基于最后访问时间的指数衰减，公式为 `e^(-λ × daysSinceLastAccess)`，其中 λ 默认为 0.1。这意味着 10 天未访问的记忆权重衰减约 63%，30 天未访问的衰减约 95%。

访问加成（accessBoost）：`1 + log(1 + access_count)`。被多次召回的记忆获得加成，模拟人类的"提取练习"效应。

每次成功召回后，系统调用 `recordAccess()` 更新访问次数和最后访问时间，实现检索强化——常被想起的记忆不容易被遗忘。

### 注入到工作记忆

召回的记忆以 RECALLED 类型 chunk 注入工作记忆，内容带有 `[历史记忆]` 前缀。注入受 `max_injection_tokens` 限制（默认 2,048 tokens），按评分从高到低注入直到预算耗尽。在 LLM 看来，这些是额外的系统级信息，为当前对话提供历史背景。

## 记忆生成路径

长期记忆通过三条路径产生。

### 路径一：工作记忆压缩

在对话过程中，当工作记忆使用率超过阈值触发压缩时，压缩 LLM 会从被压缩的内容中提取事实，这些事实直接存入长期记忆。这是最实时的记忆生成路径，发生在对话进行中。

### 路径二：会话结束

当用户断开 WebSocket 连接时，系统检查当前会话的 chunk 数量。如果超过最小阈值（默认 4 个 chunk），系统会生成情景记忆：

如果会话期间没有触发过压缩，调用 LLM 对完整对话进行摘要，提取事实。这是最全面的情景记忆生成方式。

如果会话期间已经触发过压缩（意味着重要事实已经在压缩时被提取），只生成一条简单的会话概述记忆。

情景记忆的生成是延迟执行的：Agent 运行结束时先注册一个延迟任务，等到 WebSocket 真正断开时才执行。这避免了在用户可能立即发送下一条消息时过早提取记忆。

### 路径三：计划完成

当一个执行计划的所有步骤都完成时，系统生成两种记忆：

程序记忆（importance 0.7）：记录"问题 + 解决步骤 + 结果"，格式化为可复用的操作流程。

情景记忆（importance 0.6）：简要记录"完成了什么计划，共几步，结果如何"。

计划完成的记忆特别有价值，因为它捕捉了成功的问题解决模式，在未来遇到类似问题时可以被召回。

## 遗忘与维护

### 夜间维护任务

`ForgettingScheduler` 作为定时任务在每天凌晨 3 点执行，对每个 Agent 的记忆库进行三步维护。

遗忘（Forget）：计算每条记忆的保留分数（综合重要性、访问频率、时间衰减），删除保留分数低于 0.1 的记忆。如果记忆总数超过上限（默认 500 条/Agent），从评分最低的开始删除直到降到上限以内。

压实（Compact）：当记忆数量超过压实阈值（默认 300 条）时，在同一类型的记忆中查找高度相似的条目（Jaccard > 0.85），将它们合并为一条更综合的记忆。

归档（Archive）：将超过 30 天未访问且重要性低于 0.35 的记忆移入 `agent_memory_archive` 表，从主表删除。归档的记忆不参与检索，但可以在管理界面查看。

### 数据清理

`DataCleanupJob` 负责清理过期的系统日志，与记忆维护任务独立运行。记忆系统自身的清理完全由 `ForgettingScheduler` 负责。

## 数据模型

### memory_config 表

存储记忆系统的全局配置，采用 key-value 格式。配置项覆盖工作记忆预算、压缩参数、长期记忆开关及各项阈值。修改后下次 Agent 运行时即生效，支持热更新。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| config_key | VARCHAR(64) | 配置键名，唯一 |
| config_value | VARCHAR(512) | 配置值 |
| description | VARCHAR(256) | 配置说明 |
| updated_at | DATETIME | 更新时间 |

### agent_memory 表

存储所有活跃的长期记忆。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| user_id | VARCHAR(64) | 用户标识 |
| agent_id | VARCHAR(64) | Agent 标识 |
| memory_type | VARCHAR(16) | 记忆类型：episodic/semantic/procedural |
| content | TEXT | 记忆内容文本，建有 FULLTEXT ngram 索引 |
| importance | FLOAT | 重要性评分，范围 0-1，默认 0.5 |
| access_count | INT | 被召回次数 |
| last_accessed_at | DATETIME | 最后一次被召回的时间 |
| created_at | DATETIME | 创建时间 |
| source_session_id | BIGINT | 来源会话 ID（可选） |
| metadata_json | TEXT | 扩展元数据（可选） |

索引包括：(user_id, agent_id, memory_type) 复合索引、importance 索引、last_accessed_at 索引、content 的 FULLTEXT ngram 索引。

### agent_memory_archive 表

结构与 agent_memory 相同，额外包含 `archived_at` 字段记录归档时间。主键使用原始记忆的 ID。

## 配置参数

所有记忆配置存储在 `memory_config` 数据库表中，通过 REST API 管理。

### 工作记忆配置

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| working.token_budget | 128000 | 工作记忆 token 预算上限 |
| working.consolidation_threshold | 0.75 | 触发压缩的使用率阈值 |

### 压缩配置

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| consolidation.model | qwen-turbo | 压缩使用的 LLM 模型 |
| consolidation.fallback_model | qwen-lite | 压缩备选模型 |
| consolidation.max_summary_tokens | 1024 | 摘要最大 token 数 |
| consolidation.timeout_ms | 5000 | 压缩 LLM 调用超时 |
| consolidation.max_retries | 2 | 压缩失败重试次数 |
| consolidation.overflow_tolerance | 1.10 | 触发紧急截断的使用率阈值 |

### 长期记忆配置

| 配置键 | 默认值 | 说明 |
|--------|--------|------|
| long_term.enabled | false | 长期记忆总开关 |
| long_term.max_memories_per_user | 500 | 每个 Agent 的最大记忆条数 |
| long_term.max_injection_tokens | 2048 | 每次召回注入的最大 token 数 |
| long_term.decay_lambda | 0.1 | 时间衰减系数 |
| long_term.compaction_threshold | 300 | 触发记忆压实的数量阈值 |
| long_term.archive_after_days | 30 | 未访问多少天后归档 |
| long_term.min_chunks_for_episodic | 4 | 触发会话结束情景记忆提取的最小 chunk 数 |

### application.yml 中的关联配置

```yaml
intellimate:
  agent:
    max-context-tokens: 128000   # 工作记忆 token 预算的兜底值
    history-limit: 50            # 加载到工作记忆的对话历史消息数
```

## API 接口

### REST API

记忆管理 API 统一挂载在 `/api/memory` 路径下。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/memory/config | 获取记忆配置，按 working/consolidation/longTerm 分组返回 |
| PUT | /api/memory/config | 批量更新配置（key-value map） |
| POST | /api/memory/config/reset | 重置为默认配置 |
| GET | /api/memory/long-term | 查询长期记忆列表，支持 userId/type/agentId 过滤 |
| GET | /api/memory/long-term/{id} | 获取单条记忆详情 |
| DELETE | /api/memory/long-term/{id} | 删除指定记忆 |
| GET | /api/memory/archive | 查询归档记忆 |
| GET | /api/memory/stats | 获取记忆统计（按类型分组计数） |
| GET | /api/memory/working/{sessionId} | 获取指定会话的工作记忆快照 |

### WebSocket 事件

| 事件名 | 方向 | 说明 |
|--------|------|------|
| memory.snapshot | 服务端 → 客户端 | 工作记忆实时快照，包含 token 使用量、chunk 列表等 |
| memory.consolidation | 服务端 → 客户端 | 压缩事件，包含压缩前后 token 数、选中的 chunk 数、提取的事实 |

`memory.snapshot` 事件在每次 Agent 运行的关键节点发出（如 LLM 调用前后、工具调用完成后），前端记忆观测面板据此实时更新显示。

## 前端记忆观测

前端侧边栏提供"记忆观测"入口，点击后展示四个标签页。

### 记忆总览

实时显示当前工作记忆的状态：token 使用率进度条（区分 API 精确值和增量估算值）、按类型分组的 chunk 列表（SYSTEM/USER/ASSISTANT/TOOL_INTERACTION/CONSOLIDATED/RECALLED）、最近的压缩日志、长期记忆总量统计。数据来源于 WebSocket 推送的 `memory.snapshot` 和 `memory.consolidation` 事件。

### 长期记忆

通过 REST API 展示持久化的长期记忆，按情景/语义/程序三个子标签页切换。支持按 Agent 筛选、删除操作。每条记忆显示内容、类型、重要性评分、访问次数、创建时间和最后访问时间。

### 记忆配置

直接编辑数据库中的记忆配置项，包括工作记忆预算、压缩模型选择、长期记忆开关等。修改通过 REST API 提交，下次 Agent 运行时生效。

### 遗忘日志

展示被归档到 `agent_memory_archive` 的冷记忆，是夜间维护任务的执行结果。帮助理解系统的遗忘行为。

## 关键设计决策

### 为什么长期记忆默认关闭

长期记忆涉及额外的 LLM 调用（事实提取、会话摘要）和数据库操作，对于简单场景可能是不必要的开销。默认关闭让用户可以先体验基本功能，在需要跨会话连续性时再开启。

### 为什么不使用向量数据库

当前版本使用 MySQL FULLTEXT 索引 + Jaccard 关键词相似度进行检索，而非 embedding 向量检索。这是为了降低部署复杂度（无需额外的向量数据库服务），在记忆数量不超过数千条的规模下，基于关键词的检索已经足够有效。未来如果需要处理大规模记忆或更细粒度的语义匹配，可以引入 embedding 层。

### 工作记忆为什么是每次运行新建

工作记忆不跨会话持久化，每次 Agent 运行时从对话历史重建。这简化了状态管理——不需要处理工作记忆与数据库对话历史之间的一致性问题。长期记忆的召回机制弥补了跨会话的知识延续需求。

### 压缩 LLM 为什么独立配置

压缩任务对 LLM 的要求与主对话不同：需要快速、低成本、擅长结构化输出（JSON），但不需要强大的推理能力。使用独立的轻量模型（如 qwen-turbo）既降低成本，又避免因压缩操作占用主模型的并发额度。

### 对话记录与记忆系统的关系

`transcript_message` 存储原始对话记录，是工作记忆的数据来源之一（通过 `history-limit` 控制加载量）。`agent_memory` 存储经过提炼的知识事实，是对原始对话的"蒸馏"。两套存储各有用途：对话记录用于上下文恢复（短期），长期记忆用于知识召回（跨会话）。

## 核心类索引

| 类名 | 所属模块 | 职责 |
|------|----------|------|
| MemorySystem | intellimate-memory | 记忆系统统一门面 |
| WorkingMemory | intellimate-memory | 工作记忆，管理 chunk 列表和 token 预算 |
| MemoryConsolidator | intellimate-memory | 工作记忆压缩执行器，调用 LLM 生成摘要和事实 |
| ConsolidationPromptBuilder | intellimate-memory | 构建压缩 LLM 的 prompt |
| MemoryRetrieval | intellimate-memory | 长期记忆检索和评分 |
| ScoringFunction | intellimate-memory | 记忆检索评分函数 |
| KeywordExtractor | intellimate-memory | 关键词提取（用于 Jaccard 相似度计算） |
| TokenEstimator | intellimate-memory | Token 数量启发式估算 |
| ImportanceAssessor | intellimate-memory | 工具调用结果重要性评估 |
| ForgettingScheduler | intellimate-memory | 遗忘/压实/归档逻辑 |
| LongTermMemoryImpl | intellimate-gateway | 长期记忆 MySQL 持久化实现 |
| MemoryConfigService | intellimate-gateway | 数据库配置读写 |
| MemoryMaintenanceJob | intellimate-gateway | 夜间维护定时任务封装 |
| MemoryController | intellimate-gateway | REST API 控制器 |
| AgentRuntime | intellimate-agent | 运行时编排（创建工作记忆、触发召回、延迟情景存储） |
| MessagePipeline | intellimate-gateway | WebSocket 事件映射、断连刷出、计划完成记忆提取 |
