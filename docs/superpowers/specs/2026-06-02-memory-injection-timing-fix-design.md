# 记忆注入时序修复设计

**日期**: 2026-06-02
**状态**: 待实现
**优先级**: P0（阻塞性 bug，记忆系统完全失效）

## 1. 问题描述

`AgentLoopExecutor` 中存在 Reactor 响应式链组装时序 bug，导致第一轮 LLM 调用永远不包含召回的长期记忆。

### 现象

日志显示记忆检索成功（6 条记忆），但 LLM 请求参数中 messages 仅包含 system + user，无任何 RECALLED 类型的消息：

```
16:44:10.642 [reactor-tcp-nio-4] [LLM请求-详细参数] messages: [system, user]  ← 无记忆
16:44:12.210 [boundedElastic-4]  [记忆注入] 6 条记忆, 耗时 1569ms           ← 晚了 1.5 秒
```

### 影响范围

- 每次对话的第一轮 LLM 调用都不包含长期记忆
- 仅当第一轮触发工具调用产生第二轮时，记忆才可能被包含
- 这意味着在无工具调用的普通对话场景中，长期记忆系统完全失效

## 2. 根因分析

**文件**: `intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentLoopExecutor.java`

### 2.1 有问题的代码（第 280 行）

```java
return retrievalMono
    .then(workingMemory.awaitPendingConsolidation()...)
    .thenMany(executeLoopTurn(resolved.chatModel(), conversationHistory, ...));
//           ^^^^^^^^^^^^^^^^^ 方法调用在组装阶段立即执行
```

`executeLoopTurn(...)` 是一个普通的 Java 方法调用，在 Reactor 链的**组装阶段**（assembly time）就会执行。方法内部立刻执行了：

```java
history.clear();
history.addAll(workingMemory.buildLLMInputSync());  // workingMemory 此时无记忆
Prompt prompt = new Prompt(new ArrayList<>(history), options);  // Prompt 不含记忆
```

虽然 `thenMany` 会等待上游 `retrievalMono` 完成后才**订阅**返回的 Flux，但 Prompt 已经在组装阶段用不含记忆的 history 快照构建完毕。

### 2.2 正确的代码（第 729 行，递归调用）

同文件的递归调用正确使用了 `Flux.defer()`：

```java
nextTurn = Flux.defer(() ->
    executeLoopTurn(chatModel, history, options, ...));
```

`Flux.defer()` 将方法调用延迟到**订阅阶段**（subscription time），此时 workingMemory 已经包含了注入的记忆。

## 3. 修复方案

### 改动范围

1 个文件，1 处修改。

### 具体改动

**文件**: `AgentLoopExecutor.java` 第 280 行

将：

```java
.thenMany(executeLoopTurn(resolved.chatModel(), conversationHistory, chatOptions, ...))
```

改为：

```java
.thenMany(Flux.defer(() -> executeLoopTurn(resolved.chatModel(), conversationHistory, chatOptions, ...)))
```

### 执行时序对比

**修复前**:

```
组装阶段 → executeLoopTurn() 被调用 → history 构建（无记忆）→ Prompt 创建
      ↓
订阅阶段 → retrievalMono → 记忆检索 → workingMemory.accept()
      ↓
thenMany 订阅 → chatModel.stream(stale prompt)  ← 无记忆
```

**修复后**:

```
组装阶段 → Flux.defer() 创建惰性包装器
      ↓
订阅阶段 → retrievalMono → 记忆检索 → workingMemory.accept()
      ↓
thenMany 订阅 → Flux.defer 触发 → executeLoopTurn() 被调用
              → history 构建（包含记忆）→ Prompt 创建
              → chatModel.stream(correct prompt)  ← 包含记忆
```

## 4. 验证方法

修复后通过日志验证：

1. `[记忆注入]` 时间戳应早于 `[LLM请求-详细参数]`
2. `[LLM请求-详细参数]` 的 messages 数组应包含 RECALLED 记忆内容（role=system 的历史记忆消息）
3. tokenUsage 应包含记忆 token 的计数

### 测试场景

1. 发送简单对话消息（不触发工具调用），确认 LLM 响应能引用长期记忆内容
2. 检查日志时间线是否正确：记忆注入 → LLM 请求构建 → LLM 调用

## 5. 改动二：FULLTEXT 搜索按相关性排序 + LIMIT 10

### 问题

原 SQL 查询 `LIMIT :maxResults`（默认 100）返回大量候选，Java 端再做排序和评分。
数据传输浪费且不利于精准匹配。

### 改动

**文件**: `AgentMemoryRepository.java`

两个 `fulltextSearch` 方法改为：
- 在 SELECT 中添加 `ft_score` 计算列（content MATCH 分 + keywords MATCH 分）
- 添加 `ORDER BY ft_score DESC`
- `LIMIT` 从参数化 `:maxResults` 改为固定 `10`
- 去掉 `maxResults` 参数

**文件**: `LongTermMemoryImpl.java`
- 去掉 `fulltextSearch` 调用中的 `100` 参数

**文件**: `MemoryRetrieval.java`
- 去掉 `.take(100)` 和 `.limit(20)` 中间排序步骤
- SQL 已按相关性排序取 top 10，直接进入 `selectAndScore` 综合评分

**文件**: `MemoryRetrievalTest.java`
- 更新测试 DisplayName 描述

## 6. 风险评估

- **风险等级**: 低
- **改动一（Flux.defer）**: 仅添加包装，与递归调用模式一致，Reactor 标准操作符
- **改动二（SQL 排序）**: 减少候选范围从 100 到 10，可能遗漏某些综合评分高但 FULLTEXT 分低的记忆。但 FULLTEXT 分数与实际相关性强相关，10 条足够覆盖高质量候选
