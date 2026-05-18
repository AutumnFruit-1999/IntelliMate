# 工具循环优化 — Q&A 与方案补充

> 日期: 2026-03-13  
> 状态: Final  
> 前置文档: [AgentRuntime工具循环优化方案.md](AgentRuntime工具循环优化方案.md)  
> 性质: 对原优化方案的 9 个关键设计问题的详细回答、方案修正和新发现问题

---

## Q1: 工具循环的流程是否不变？

### 回答

**不变。** 核心 ReAct 循环逻辑完全保持：

```
LLM 推理 → 判断是否使用工具?
  ├── 使用 → 调用工具 → 结果返回 LLM → 继续推理（循环）
  └── 不使用 → 输出最终回复 → 结束
```

当前 `AgentRuntime` 的递归结构 `executeLoopTurn() → processToolCalls() → executeLoopTurn()` 不做任何修改。所有优化都是**在这个循环的各环节插入中间件/增强层**，类似于给管道加过滤器：

```
原始流程:
  LLM → tool call → 执行工具 → tool result → 加入 history → LLM

优化后流程:
  LLM → tool call
    → [循环检测] 检查是否死循环
    → [人工审批] 高危工具拦截（可选）
    → 执行工具（串行或并行）
    → [结果截断] 防止结果过大
    → [token 追踪] 更新上下文用量
    → tool result → 加入 history
    → [上下文压缩] 超阈值时压缩旧消息
    → LLM
```

每个 `[...]` 环节都是独立的、可插拔的增强，任何一个关闭都不影响主循环正常运行。

---

## Q2: 截断重要信息怎么办？

### 问题本质

原方案的"头尾保留"策略是通用兜底：保留前 6000 + 后 6000 字符。但如果关键信息恰好在中间（比如一个大文件的第 500 行有关键函数定义），截断后 LLM 看不到这部分内容。

### 修正方案：两层截断体系

应该采用**工具级智能截断（优先）+ Runtime 级兜底截断（保底）**的两层设计：

#### 第一层：工具级智能截断（在工具内部）

每个工具根据自身特性做截断，并提供**分页能力**让 LLM 可以二次获取被截断的部分：

```java
// FileReadTool.java — 改造方案

private static final int MAX_LINES_PER_READ = 500;

@Tool(description = """
    Read the contents of a file at the specified path.
    For large files (>500 lines), results will be paginated.
    Use startLine and lineCount parameters to read specific sections.
    The response includes totalLines so you know the file size.""")
public String readFile(String path, Integer startLine, Integer lineCount) {
    var lines = Files.readAllLines(filePath);

    // 无分页参数且文件过大时，自动分页
    if (startLine == null && lineCount == null && lines.size() > MAX_LINES_PER_READ) {
        StringBuilder sb = new StringBuilder();
        // 读取前 MAX_LINES_PER_READ 行
        for (int i = 0; i < MAX_LINES_PER_READ; i++) {
            sb.append(i + 1).append("|").append(lines.get(i)).append("\n");
        }
        sb.append("\n--- [Showing lines 1-").append(MAX_LINES_PER_READ)
          .append(" of ").append(lines.size()).append(" total lines. ")
          .append("Use startLine=").append(MAX_LINES_PER_READ + 1)
          .append(" to read more.] ---");
        return sb.toString();
    }

    // 有分页参数时正常读取
    // ...（现有逻辑）
}
```

同理，`ExecTool` 也应内置输出截断：

```java
// ExecTool.java — 改造方案

private static final int MAX_OUTPUT_CHARS = 8_000;

// 在返回前:
if (output.length() > MAX_OUTPUT_CHARS) {
    int half = MAX_OUTPUT_CHARS / 2;
    output = output.substring(0, half)
            + "\n... [" + output.length() + " chars total, truncated] ...\n"
            + output.substring(output.length() - half);
}
```

**工具级截断的优势**：
- 每个工具知道自己的数据结构，可以做更语义化的截断
- 截断后的元信息（totalLines、hasMore）引导 LLM 进行精确的分段获取
- LLM 不会丢失信息——它可以通过 startLine/lineCount 再次读取被截断的部分

#### 第二层：Runtime 级兜底截断（在 AgentRuntime 中）

即使工具本身没做截断，Runtime 层也要保证不会有超大结果进入 history：

```java
// AgentRuntime.java — processToolCalls() 中

private static final int MAX_TOOL_RESULT_CHARS = 16_000;

String result = callback.call(tc.arguments());
result = truncateToolResultIfNeeded(result, MAX_TOOL_RESULT_CHARS);
```

Runtime 级截断使用头尾保留策略，是最后的安全网。

#### 截断信息不足时 LLM 的行为

关键在于：截断标记中包含足够的元信息让 LLM 能自行弥补。

```
截断标记示例:
"... [Showing first and last 8000 chars of 45000 total.
Original file has 1200 lines. Use readFile with startLine/lineCount
to read specific sections.] ..."
```

LLM 看到这个标记后有两种选择：
1. 已有的头尾信息足够完成任务 → 直接使用
2. 需要中间部分的信息 → 用 startLine/lineCount 重新读取特定区域

**这不会造成信息永久丢失**，只是将"一次读完"变为"按需读取"。

---

## Q3: 工具循环检测的具体机制

### 滑动窗口如何工作

```
维护一个固定大小（默认 8）的队列，存储最近的 tool call 签名:

签名 = toolName + "::" + hash(arguments)

示例 — 正常场景（不触发）:
  [readFile::hash("/a.java")]                          → 计数 1 → OK
  [readFile::hash("/a.java"), readFile::hash("/b.java")]  → 计数 1 → OK
  [..., exec::hash("ls -la")]                            → 计数 1 → OK

示例 — 死循环场景（触发）:
  [readFile::hash("/a.java")]  → 计数 1 → OK
  [readFile::hash("/a.java"), readFile::hash("/a.java")]  → 计数 2 → OK
  [...重复 3 次...]  → 计数 3 → WARN（注入警告到结果末尾）
  [...重复 5 次...]  → 计数 5 → TERMINATE（不执行工具，返回终止提示）
```

**关键区分**：
- `readFile("/a.java")` 然后 `readFile("/b.java")` → 参数不同，签名不同，**不触发**
- `readFile("/a.java")` 连续 5 次 → 参数相同，签名相同，**触发**
- `readFile("/a.java", startLine=1)` 然后 `readFile("/a.java", startLine=100)` → 参数不同（startLine 不同），签名不同，**不触发**

### 如何排除不需要检测的工具

有些工具同参数多次调用是合理的，比如：
- `exec("git status")` — 每次执行后检查状态
- `exec("npm test")` — 修改代码后重新运行测试
- `webFetch(url)` — 轮询接口状态

**方案：白名单机制**

```java
// ToolCallLoopDetector.java

// 方式 1: 配置文件
private Set<String> excludedTools = Set.of(); // 默认空，可通过配置注入

// 方式 2: 工具注解（更优雅）
// 在 @Tool 注解或 ToolCallback 上添加元数据
public LoopStatus check(String toolName, String arguments) {
    if (excludedTools.contains(toolName)) {
        return LoopStatus.OK; // 跳过检测
    }
    // ... 正常检测逻辑
}
```

白名单应在 Agent 配置中可设置（`IntelliMateProperties.Agent` 或数据库 `agent.config_json`），让用户根据场景自行调整。

### 模型侧的辅助处理

在 system prompt 中加入引导（在 `buildSystemPrompt()` 中追加）：

```
Avoid calling the same tool with identical arguments repeatedly. 
If a tool returns unexpected results, try a different approach 
or modify the arguments instead of retrying with the same parameters.
```

这是预防层面的措施，配合 Runtime 层的检测形成双重保护。

---

## Q4: Token 追踪精度问题

### 问题分析

原方案提出用 `3.5 chars/token` 的字符级估算，存在以下问题：
- 中文 1 个字符可能是 1-3 个 token（不同 tokenizer 差异很大）
- 英文代码大约 3-4 chars/token
- JSON/格式化文本比率不同
- 估算误差可能达 30-50%

### 修正方案：API Usage 优先

**当前代码中已经有 usage 数据的获取**——`AgentRuntime.java` L140 有一行 debug 输出：

```java
System.out.println("chunk = " + chunk.getResult().getOutput()
    + "total use:" + chunk.getMetadata().getUsage().getNativeUsage());
```

这说明 Spring AI 的 `ChatResponse` 已经携带了 provider 返回的真实 token usage。

**正确的 token 追踪方案**：

```java
// AgentRuntime.java — executeLoopTurn() 中

// 在 streaming 完成后，从最后一个 chunk 获取 usage
Flux<AgentEvent> afterStream = Flux.defer(() -> {
    // 从 allChunks 的最后一个有效 chunk 获取真实 usage
    ChatResponse lastChunk = allChunks.get(allChunks.size() - 1);
    Usage usage = lastChunk.getMetadata().getUsage();
    
    if (usage != null && usage.getTotalTokens() > 0) {
        // 使用 API 返回的真实 token 数
        tracker.updateFromApiUsage(usage.getTotalTokens());
    } else {
        // 兜底：字符估算
        tracker.estimateFromChars(fullText.length());
    }
    
    // ... 后续 tool call 处理
});
```

```java
// ContextWindowTracker.java — 修正版

public class ContextWindowTracker {

    private final int maxContextTokens;
    private int lastKnownTotalTokens = 0;  // 来自 API 的真实值
    private int estimatedAdditionalChars = 0; // 工具结果等新增字符

    private static final double CHARS_PER_TOKEN_FALLBACK = 3.5;

    /** API 返回了真实 token 数时调用 */
    public void updateFromApiUsage(long totalTokens) {
        this.lastKnownTotalTokens = (int) totalTokens;
        this.estimatedAdditionalChars = 0; // 重置估算增量
    }

    /** 工具结果加入 history 时调用（API 还没有返回新的 usage） */
    public void addToolResultChars(int charCount) {
        this.estimatedAdditionalChars += charCount;
    }

    /** 当前估算的总 token 数 */
    public int estimatedTotalTokens() {
        return lastKnownTotalTokens
                + (int)(estimatedAdditionalChars / CHARS_PER_TOKEN_FALLBACK);
    }

    public boolean isNearLimit(double threshold) {
        return estimatedTotalTokens() > maxContextTokens * threshold;
    }
}
```

**这个设计的精度保证**：
- 每次 LLM 调用后从 API 获取**精确** token 数，重置估算基线
- 工具结果加入 history 时用字符估算**增量**（增量部分较小，误差可控）
- 下一次 LLM 调用又会获取精确值，持续校准

估算只在两次 LLM 调用之间的工具执行阶段使用，此时增量相对小，即使有 30% 误差也不会导致溢出（因为有 85% 预警 + 100% 硬停止的双重保护）。

---

## Q5: 并行执行工具的判断、失败处理与前端显示

### 5.1 如何判断哪些工具需要并行执行

**不需要我们判断。** LLM 在一次推理中返回的所有 tool calls 就是它认为可以并行执行的。

当前代码 `processToolCalls()` 中 `assistantMsg.getToolCalls()` 返回的列表就是同一 turn 的所有 tool calls。比如 LLM 可能一次返回：

```json
[
  {"name": "readFile", "arguments": {"path": "/src/A.java"}},
  {"name": "readFile", "arguments": {"path": "/src/B.java"}},
  {"name": "searchFiles", "arguments": {"query": "TODO"}}
]
```

这三个调用出现在同一个 AssistantMessage 中，说明 LLM 已隐式判断它们之间无依赖。我们的改造只是将现有的 `concatMap`（串行）改为 `flatMap`（并行），不需要额外的依赖分析逻辑。

如果 LLM 认为两个调用有依赖关系（比如先写文件再读文件），它会分成两个 turn 来发出——第一个 turn 只有 writeFile，第二个 turn 才有 readFile。

### 5.2 某个工具失败后模型的行为

```
Turn 3: LLM 返回 3 个 tool calls
  ├── readFile("/src/A.java")  → 成功，返回文件内容
  ├── readFile("/src/B.java")  → 失败，返回 "Error: File not found"
  └── searchFiles("TODO")      → 成功，返回搜索结果

这 3 个结果全部作为 ToolResponseMessage 返回给 LLM

Turn 4: LLM 收到 3 个结果，其中 B.java 是错误
  LLM 自主决定:
  ├── 选择 1: 重新调用 readFile("/src/B.java")（可能路径写错了）
  ├── 选择 2: 用 searchFiles 查找 B.java 的正确路径
  ├── 选择 3: 用已有的 A.java 和搜索结果继续工作，忽略 B.java
  └── 选择 4: 告诉用户 B.java 不存在
```

**关键点**：失败的工具结果（错误文本）和成功的结果一样，都作为 ToolResponseMessage 返回给 LLM。LLM 在 ReAct 循环的下一轮推理中看到错误信息，自主决定是重试、换方法还是放弃。这是 ReAct 循环的天然容错能力，不需要 Runtime 额外干预。

### 5.3 前端显示同一 turn 的多个工具调用

当前前端收到的 EventFrame 序列：

```
agent.turn_start  {turn: 3}
agent.tool_call   {name: "readFile", arguments: {path: "/A.java"}}
agent.tool_call   {name: "readFile", arguments: {path: "/B.java"}}
agent.tool_call   {name: "searchFiles", arguments: {query: "TODO"}}
agent.tool_result {name: "readFile", result: "...A content..."}
agent.tool_result {name: "searchFiles", result: "...results..."}
agent.tool_result {name: "readFile", result: "Error: not found"}
```

**前端渲染方案**：

1. **在 EventFrame 中附加 `turn` 字段**：让前端能按 turn 分组

```java
// MessagePipeline.java — mapAgentEvent() 中
case AgentEvent.ToolCall tc -> Flux.just(new EventFrame(
        "agent.tool_call",
        Map.of("toolCallId", tc.toolCallId(),
               "name", tc.name(),
               "arguments", tc.arguments(),
               "turn", currentTurn,          // 新增
               "requestId", requestId),
        seqGenerator.incrementAndGet()
));
```

2. **前端按 turn 分组显示**：同一 turn 的多个 tool calls 渲染为一组（可折叠的并行组），而非散落的列表项。视觉上用卡片包裹，标注"并行执行 3 个工具"。

3. **并行执行时 tool_result 的顺序不确定**（先完成的先返回），前端通过 `toolCallId` 将 result 匹配到对应的 call 上进行状态更新（loading → completed/failed）。

---

## Q6: 工具结果持久化与记忆空间的关系

### 当前阶段：持久化是必要的

当前 `MessagePipeline` 只持久化最终的 assistant 文本（`transcript_message` 中 `role="assistant"`），tool call 和 tool result 完全丢失。这导致：

- **无法审计**：不知道 Agent 做了什么操作
- **无法回放**：页面刷新后 tool 执行细节消失
- **无法调试**：Agent 行为异常时无法追溯原因

所以即使未来有记忆空间，当前也需要先做 `transcript_message` 级别的持久化。

### 与未来记忆空间的兼容设计

```
┌─────────────────────────────────────────────────────────────┐
│ 当前阶段 (transcript_message 持久化)                          │
│                                                             │
│   tool_call  → role="assistant_tool_call",                  │
│                content=arguments,                           │
│                tool_call_id=xxx, tool_name=yyy              │
│                                                             │
│   tool_result → role="tool",                                │
│                 content=result (截断至 32KB),                │
│                 metadata_json={"success":true,              │
│                                "source":"tool_execution",   │
│                                "original_length":45000}     │
│                                                             │
└────────────────────────┬────────────────────────────────────┘
                         │ 未来迁移
                         ▼
┌─────────────────────────────────────────────────────────────┐
│ 记忆空间阶段                                                 │
│                                                             │
│   transcript_message:  保留不变（审计/回放用途）               │
│                                                             │
│   memory_store:                                             │
│     ├── 短期记忆: 当前会话的 tool result 摘要               │
│     ├── 长期记忆: 跨会话的关键发现                           │
│     └── 语义索引: tool result 中提取的实体/事实              │
│                                                             │
│   归档策略: 超过 30 天的 tool result 原文移到冷存储           │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

**关键**：`metadata_json` 字段中标记 `"source": "tool_execution"`，未来记忆空间系统可以根据这个标记批量处理历史数据，无需逐条判断。

---

## Q7: 工具重试策略的范围与工具切换

### 重试是按异常类型，不是按工具名称

原方案的重试逻辑：

```java
private boolean isRetryableError(Throwable e) {
    return e instanceof SocketTimeoutException
            || e instanceof ConnectException
            || e instanceof TimeoutException
            || (e.getMessage() != null && e.getMessage().contains("429"));
}
```

这意味着：
- **新增任何工具都自动适用**：只要工具执行时抛出了网络超时/连接失败等异常，就会自动重试
- **不需要手动标记**：不是"对 webFetch 重试"，而是"对网络超时类错误重试"
- **确定性错误不重试**：文件不存在、参数格式错误等永远不重试

### 但应支持工具级配置

某些场景需要更细粒度的控制：

```java
// 方式 1: 工具自身声明不重试
@Tool(description = "...")
@ToolConfig(retryable = false) // 新增注解
public String sensitiveOperation(...) { ... }

// 方式 2: 在 agent 配置中指定
// agent.config_json:
// { "nonRetryableTools": ["exec", "writeFile"] }
```

### 不会自动切换到同功能工具

Runtime 层面**不做工具切换**。工具切换是 LLM 的推理能力：

```
Turn 5: LLM 调用 webSearch("best practices")
         → 结果: "Error: Search API rate limited (429)"

Turn 6: LLM 自行推理:
         "webSearch 失败了，我试试用 webFetch 直接访问网页"
         → 调用 webFetch("https://...")
```

这是 ReAct 循环的核心价值——LLM 根据工具执行结果自适应地调整策略。Runtime 不应该代替 LLM 做这个决策。

如果想增强这种能力，应在 system prompt 中引导：

```
When a tool fails, consider alternative approaches:
- If webSearch fails, try webFetch with a specific URL
- If readFile fails, try searchFiles to locate the file
- If exec fails, check the error and adjust the command
```

---

## Q8: 上下文压缩的时机、范围与防频繁机制

### 压缩触发时机

**不是每轮 turn 都压缩**，而是当 token 用量达到阈值时才触发：

```
Turn 1:  token 用量 15%  → 不压缩
Turn 5:  token 用量 35%  → 不压缩
Turn 12: token 用量 68%  → 不压缩
Turn 15: token 用量 72%  → 超过 70% 阈值 → 触发压缩
         压缩后 token 用量降至 40%
Turn 20: token 用量 55%  → 不压缩
Turn 28: token 用量 73%  → 超过 70% → 但距上次压缩不足 5 个 turn → 不压缩
Turn 30: token 用量 78%  → 超过 70% + 距上次 ≥ 5 turn → 触发压缩
```

### 压缩范围

**只压缩旧的 ToolResponseMessage**，不动其他消息类型：

```
history 结构:
  [0]  SystemMessage          — 永远不压缩
  [1]  UserMessage             — 不压缩
  [2]  AssistantMessage (含 tool_calls) — 不压缩（tool_call_id 必须保留）
  [3]  ToolResponseMessage ←── 旧的，压缩目标
  [4]  AssistantMessage        — 不压缩
  [5]  ToolResponseMessage ←── 旧的，压缩目标
  ...
  [N-20] ── 保留区分界线 ──
  [N-19] ToolResponseMessage   — 最近 20 条，不压缩
  [N-18] AssistantMessage      — 不压缩
  ...
  [N]  UserMessage (最新)      — 不压缩
```

### 压缩方式

**阶段 1（滑动窗口截断）**: 将旧 ToolResponseMessage 的 content 截断为 200 字符：

```
原始: "1|package com.atm...\n2|import java...\n..." (10000 chars)
压缩后: "1|package com.atm...\n2|import java...\n... [condensed: 10000→200 chars]"
```

**阶段 2（LLM 摘要，可选）**: 将多条旧消息合并为一条摘要：

```
原始: 5 条 ToolResponseMessage (合计 40000 chars)
压缩后: 1 条 SystemMessage:
  "Previous tool results summary:
   - Read A.java: Class definition with 3 methods (calculate, validate, process)
   - Read B.java: Test class, 2 tests passing
   - exec 'mvn test': Build successful, 15 tests passed"
```

### 防频繁压缩机制

```java
public class ContextCondenser {
    private int lastCondensedAtTurn = -1;
    private static final int MIN_TURNS_BETWEEN_CONDENSATION = 5;

    public boolean shouldCondense(int currentTurn, ContextWindowTracker tracker) {
        // 条件 1: token 用量超过阈值
        if (!tracker.isNearLimit(0.70)) return false;
        // 条件 2: 距上次压缩至少间隔 5 个 turn
        if (currentTurn - lastCondensedAtTurn < MIN_TURNS_BETWEEN_CONDENSATION) return false;
        // 条件 3: 可压缩区有足够的旧消息
        // ...
        return true;
    }
}
```

如果连续压缩后 token 仍然超限（说明最近 20 条消息本身就很大），此时触发**硬停止**而非继续压缩：

```java
if (tracker.isOverLimit()) {
    // 已经压缩过还是超限 → 强制完成
    return Flux.just(new AgentEvent.Done(fullText.toString(), turn));
}
```

---

## Q9: 人工审批的灵活设计

### 原方案的问题

原方案设计了"固定高危工具列表 + 5 分钟超时自动拒绝"，存在两个问题：
1. 用户无法自定义哪些工具需要审批
2. 超时自动拒绝不合理——用户可能只是暂时离开

### 修正方案

#### 1. 审批工具列表完全可配置

在 Agent 配置中新增字段，用户在前端 Agent 编辑器中勾选：

```java
// AgentEntity.java 新增字段:
private String approvalRequiredTools; // JSON array, e.g. ["exec","writeFile"]

// 前端 AgentEditor 中：
// 工具列表旁添加"需要审批"勾选框
// 勾选后该工具名加入 approvalRequiredTools 数组
```

不设默认值（空 = 所有工具无需审批），让用户根据自身安全需求配置。

#### 2. 不设超时，使用审批队列

```
工具执行流程（需审批的工具）:

  LLM 返回 tool_call: exec("rm -rf /tmp/old")
    ↓
  AgentRuntime 检测到 exec 在审批列表中
    ↓
  创建审批记录 → 持久化到 DB（pending_approval 表 或 workflow_step 表）
    ↓
  通过 WebSocket 发送 EventFrame("agent.approval_required", {...})
    ↓
  前端弹出审批面板（显示工具名、参数、上下文）
    ↓
  Agent 执行暂停（Sinks.one<Boolean> 无限等待）
    ↓
  用户操作:
    ├── [批准] → Sinks.one.emitValue(true) → 执行工具 → 继续
    ├── [拒绝] → Sinks.one.emitValue(false) → 返回"User rejected" → LLM 继续
    └── [修改后批准] → 修改参数 → emitValue(true) → 用新参数执行
```

**不设超时**的理由：
- 审批请求已持久化到 DB，不怕丢失
- 用户可能需要几分钟甚至更长时间来审查复杂命令
- 如果用户长时间不操作，可在 UI 上显示"等待审批中"状态

#### 3. 审批队列 UI

前端新增审批面板组件：

```
┌──────────────────────────────────────────────┐
│  待审批操作 (2)                                │
├──────────────────────────────────────────────┤
│  1. exec("rm -rf /tmp/old")                   │
│     Agent: code-assistant                      │
│     Session: #1234                             │
│     等待时间: 2 分钟前                          │
│     [批准] [修改] [拒绝]                        │
│                                                │
│  2. writeFile("/etc/config.yml", "...")         │
│     Agent: deploy-agent                        │
│     Session: #1235                             │
│     等待时间: 30 秒前                           │
│     [批准] [查看完整内容] [拒绝]                │
└──────────────────────────────────────────────┘
```

#### 4. 对话恢复（页面刷新后）

审批状态持久化后，页面刷新时的恢复流程：

```
页面加载
  ↓
前端请求: GET /api/approvals/pending?sessionId=xxx
  ↓
后端返回: [{stepId, toolName, arguments, createdAt, status="pending"}]
  ↓
前端重新显示审批面板
  ↓
用户操作后: POST /api/approvals/{stepId}/approve 或 /reject
  ↓
后端通过 WebSocket 通知 AgentRuntime 继续执行
```

与当前对话历史恢复机制完全一致——通过 `SessionManager.getHistory()` 获取消息历史，加上 pending approvals 的查询，即可还原完整状态。

---

## 10. 原优化方案需修正的要点汇总

| # | 原方案 | 修正后 |
|---|--------|--------|
| 1 | Runtime 级统一截断（头尾保留） | **工具级智能截断（优先）+ Runtime 级兜底截断（保底）** |
| 2 | 字符级 token 估算（3.5 chars/token） | **API Usage 优先 + 字符估算兜底** |
| 3 | 循环检测无白名单 | **新增白名单机制（配置 + 注解）** |
| 4 | 人工审批固定列表 + 5 分钟超时 | **可配置列表 + 不设超时 + 持久化 + 审批队列 UI** |
| 5 | 并行执行前端无分组 | **EventFrame 附加 turn 字段 + 前端按 turn 分组渲染** |
| 6 | 重试策略仅按异常类型 | **异常类型（默认）+ 工具级可配置** |
| 7 | 上下文压缩无防频繁机制 | **最小间隔 5 turn + token 阈值双重条件** |

---

## 11. 新发现的其他问题

### 11.1 streaming 中的 debug println（严重度: 高）

`AgentRuntime.java` L140 存在硬编码的 `System.out.println`：

```java
System.out.println("chunk = " + chunk.getResult().getOutput()
    + "total use:" + chunk.getMetadata().getUsage().getNativeUsage());
```

**问题**:
- 生产环境中每个 streaming chunk 都会打印到 stdout
- 包含 LLM 输出内容，可能泄露敏感信息
- 性能影响：高频 I/O

**修正**: 改为 `log.trace()` 或删除。

### 11.2 history 上限 50 条硬编码（严重度: 中）

`MessagePipeline.java` L113：

```java
sessionManager.getHistory(session.getId(), 50).collectList()
```

`50` 是硬编码的历史消息条数限制。

**问题**:
- 如果一轮对话有 10 次 tool call（10 条 tool result + 10 条 assistant），50 条只能覆盖约 2.5 轮对话
- 无法根据模型 context window 大小动态调整

**修正**: 从 Agent 配置或 `IntelliMateProperties` 读取，默认值可保持 50 但支持调整。

### 11.3 工具 description 缺少大文件引导（严重度: 中）

`FileReadTool` 的 `@Tool` description 只有 "Read the contents of a file"，没有告知 LLM：
- 大文件应使用 startLine/lineCount 分段读取
- 一次读取的结果可能被截断

**修正**: 更新 description：

```java
@Tool(description = """
    Read the contents of a file at the specified path.
    For large files, use startLine and lineCount to read specific sections.
    Without these parameters, large files will be automatically paginated
    (first 500 lines shown with total line count).""")
```

### 11.4 无 Agent Run 级别的 cost 追踪（严重度: 中）

当前无法知道单次 Agent 执行（从用户消息到最终回复）消耗了多少 token 和费用。

**建议**: 在 `executeAgentLoop` 完成后汇总所有 turn 的 usage 数据，写入 `audit_log` 或新建 `agent_run_stats` 表。

### 11.5 RunQueueManager 的 replay sink 内存风险（严重度: 低-中）

`RunQueueManager.java` L29：

```java
Sinks.Many<AgentEvent> replaySink = Sinks.many().replay().all();
```

`replay().all()` 会在内存中保留**所有**已发射的事件直到所有订阅者消费完成。如果一次 Agent 执行产生大量事件（多轮 tool call），会占用较多内存。

**建议**: 考虑改为 `replay().limit(Duration.ofMinutes(5))` 或 `replay().latest()` 根据实际消费模式选择。

### 11.6 工具执行无并发安全标记（严重度: 低）

当前所有工具（readFile、writeFile、exec 等）都没有并发安全标记。引入并行执行后，如果两个 writeFile 同时写同一文件，结果不可预测。

**建议**: P1 阶段的并行执行先只对读操作并行，写操作保持串行；或者在工具上标记 `@ToolConfig(parallelSafe = true/false)`。

### 11.7 对话恢复时 tool call 上下文丢失（严重度: 中）

当前 `MessagePipeline.convertToAiMessages()` 将 `role="assistant"` 恢复为 `AssistantMessage`，但不包含 tool_calls 信息。如果 Agent 在一轮执行中途断开，恢复后的 history 中 tool call 和 tool result 的对应关系丢失。

**建议**: 持久化 tool call/result 后（P1），在 `convertToAiMessages()` 中正确恢复 AssistantMessage 的 tool_calls 字段和对应的 ToolResponseMessage。
