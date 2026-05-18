# AgentRuntime 工具循环优化方案

> 日期: 2026-03-10  
> 状态: Draft  
> 前置文档: [工具循环调研报告.md](工具循环调研报告.md)  
> 核心文件: `intellimate-agent/.../runtime/AgentRuntime.java`

---

## 1. 现状分析

### 1.1 当前工具循环流程

```
AgentRuntime.dispatch(request)
  └── RunQueueManager.enqueue(sessionId, () -> executeAgentLoop(request))
        └── executeAgentLoop(request)
              ├── 解析 Skills → buildSystemPrompt()
              ├── 获取工具 → toolsEngine.getToolCallbacksFor()
              ├── 构建 history: [SystemMessage, ...history, UserMessage]
              └── executeLoopTurn(turn=1)
                    ├── Prompt → chatModel.stream(prompt) → 收集 chunks
                    ├── 检查 tool calls:
                    │     └── processToolCalls()
                    │           ├── history.add(assistantMsg)  // 含 tool_calls
                    │           ├── concatMap(toolCalls):      // 串行执行
                    │           │     ├── emit ToolCall event
                    │           │     ├── callback.call(args)  // 阻塞执行
                    │           │     ├── history.add(ToolResponseMessage)
                    │           │     └── emit ToolResult event
                    │           └── 递归 → executeLoopTurn(turn + 1)
                    └── 无 tool calls → Done(fullText, turn)
```

### 1.2 已识别问题清单

| # | 问题 | 严重度 | 当前代码位置 | 影响 |
|---|------|--------|-------------|------|
| 1 | 工具结果无截断 | **严重** | `processToolCalls()` L207 | 大文件直接撑爆 context window |
| 2 | 无循环检测 | **严重** | `executeLoopTurn()` | Agent 可能无限重复调用同一工具 |
| 3 | 无 token 追踪 | **高** | 无 | 无法预测 context window 溢出 |
| 4 | 工具串行执行 | **中** | `processToolCalls()` L200 `concatMap` | 多文件读取性能低 |
| 5 | 中间过程不持久化 | **中** | `MessagePipeline` L135-152 | tool call/result 丢失，无法审计 |
| 6 | 无工具重试 | **中** | `processToolCalls()` L220-229 | 临时错误导致 Agent 收到无用错误文本 |
| 7 | 无人工审批 | **中** | 无 | exec/writeFile 等高危工具无拦截 |
| 8 | 历史无压缩 | **低-中** | `executeAgentLoop()` L89-94 | 长对话 token 持续膨胀 |
| 9 | 无工具结果缓存 | **低** | 无 | 相同 readFile 重复读取浪费 token |

---

## 2. P0 — 安全与稳定性

### 2.1 工具结果截断

**问题**: `callback.call(tc.arguments())` 返回值直接作为 `ToolResponseMessage` 加入 history。读取一个 10,000 行的文件会产生 ~300KB 文本，按 1 token ≈ 4 chars 计约 75,000 tokens，轻松超出大多数模型的 context window。

**参考**: OpenHands 的 MAX_CHARS 头尾保留策略。

**改动文件**: `AgentRuntime.java`

**具体方案**:

在 `processToolCalls()` 中，工具执行后、加入 history 前，对 result 进行截断：

```java
// AgentRuntime.java — processToolCalls() 内部

private static final int MAX_TOOL_RESULT_CHARS = 12_000;

// 在 callback.call() 之后添加:
String result = callback.call(tc.arguments());
result = truncateToolResult(result, MAX_TOOL_RESULT_CHARS);

// 新增方法:
private static String truncateToolResult(String result, int maxChars) {
    if (result == null || result.length() <= maxChars) {
        return result;
    }
    int half = maxChars / 2;
    return result.substring(0, half)
            + "\n\n... [truncated: showing first and last "
            + half + " chars of " + result.length() + " total] ...\n\n"
            + result.substring(result.length() - half);
}
```

**为什么头尾保留**:
- 文件开头含 import/class 定义等结构信息
- 文件结尾含 return/错误信息等关键结果
- 命令输出的最后几行通常是 exit code 或错误摘要

**可配置化**: `MAX_TOOL_RESULT_CHARS` 应从 `IntelliMateProperties.Agent` 读取：

```java
// IntelliMateProperties.Agent 新增:
private int maxToolResultChars = 12_000;
```

**影响分析**:
- 侵入性低：仅在 result 返回后加一行截断
- 无破坏性：截断标记明确告知 LLM，LLM 可以用 startLine/lineCount 参数分段读取
- 预期效果：单条 tool result 最大 ~3,000 tokens，大幅降低 context overflow 风险

---

### 2.2 工具循环检测

**问题**: Agent 使用 Qwen 等模型时，可能陷入重复调用相同工具相同参数的死循环。当前仅有 `maxTurns` 作为硬限制（默认 128），在 128 轮重复后才会停止——已经浪费了大量 token。

**参考**: DeerFlow 的 LoopDetectionMiddleware（滑动窗口 + 渐进式响应）。

**改动文件**: 新增 `ToolCallLoopDetector.java`，修改 `AgentRuntime.java`

**具体方案**:

新增循环检测器：

```java
// 新建: intellimate-agent/.../runtime/ToolCallLoopDetector.java

public class ToolCallLoopDetector {

    private final int windowSize;
    private final int warnThreshold;
    private final int terminateThreshold;
    private final Deque<String> recentSignatures;

    public ToolCallLoopDetector(int windowSize, int warnThreshold, int terminateThreshold) {
        this.windowSize = windowSize;
        this.warnThreshold = warnThreshold;
        this.terminateThreshold = terminateThreshold;
        this.recentSignatures = new ArrayDeque<>(windowSize);
    }

    public ToolCallLoopDetector() {
        this(8, 3, 5);
    }

    public enum LoopStatus { OK, WARN, TERMINATE }

    public LoopStatus check(String toolName, String arguments) {
        String signature = toolName + "::" + arguments.hashCode();

        if (recentSignatures.size() >= windowSize) {
            recentSignatures.pollFirst();
        }
        recentSignatures.addLast(signature);

        long count = recentSignatures.stream()
                .filter(s -> s.equals(signature))
                .count();

        if (count >= terminateThreshold) {
            return LoopStatus.TERMINATE;
        } else if (count >= warnThreshold) {
            return LoopStatus.WARN;
        }
        return LoopStatus.OK;
    }

    public void reset() {
        recentSignatures.clear();
    }
}
```

在 `AgentRuntime.executeLoopTurn()` 中集成：

```java
// AgentRuntime.java

// 在 executeAgentLoop() 中创建 detector (per-run 生命周期):
ToolCallLoopDetector loopDetector = new ToolCallLoopDetector();

// 在 processToolCalls() 中，执行工具前检查:
LoopStatus status = loopDetector.check(tc.name(), tc.arguments());

switch (status) {
    case TERMINATE:
        log.warn("Tool call loop detected ({}x): {}({})", 
                terminateThreshold, tc.name(), tc.arguments());
        // 不执行工具，注入终止提示
        String terminateMsg = "Loop detected: you've called " + tc.name() 
                + " with identical arguments " + terminateThreshold 
                + " times. Please use the information you already have to respond.";
        history.add(new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), terminateMsg))));
        // 跳过后续 tool calls，直接进入下一轮 LLM 推理
        break;

    case WARN:
        // 执行工具，但在结果后追加警告
        String result = callback.call(tc.arguments());
        result += "\n\n[WARNING: You've called this tool with identical arguments "
                + "multiple times. Consider a different approach.]";
        break;

    case OK:
        // 正常执行
        break;
}
```

**渐进式策略的优势**:
- WARN 阶段给 LLM 自我修正机会（可能换参数或换工具）
- TERMINATE 阶段不硬报错，而是强制 LLM 用现有信息回答（优雅降级）
- 滑动窗口确保 "读同一文件两次" 这种合理场景不会误触发

**影响分析**:
- 新增独立类，对现有代码侵入很小
- 每个 Agent Run 创建一个 detector 实例（无并发问题）
- 预期效果：5 次重复后强制终止，节省 ~90% 的循环浪费

---

### 2.3 Token 追踪与上下文窗口保护

**问题**: `history` 列表只追踪消息条数（由 `maxTurns` 限制），不追踪 token 总量。128 个 turn 如果每个 turn 有 3000 tokens 的工具结果，总量可达 384,000 tokens——远超任何模型的 context window。

**参考**: OpenHands 的 MAX_CHARS 字符级限制 + Cursor 的 token budget。

**改动文件**: 新增 `ContextWindowTracker.java`，修改 `AgentRuntime.java`

**具体方案**:

新增 token 追踪器（字符级近似，避免依赖 tokenizer）：

```java
// 新建: intellimate-agent/.../runtime/ContextWindowTracker.java

public class ContextWindowTracker {

    private static final double CHARS_PER_TOKEN = 3.5;

    private final int maxContextTokens;
    private int currentCharCount = 0;

    public ContextWindowTracker(int maxContextTokens) {
        this.maxContextTokens = maxContextTokens;
    }

    public void addChars(int charCount) {
        currentCharCount += charCount;
    }

    public int estimatedTokens() {
        return (int) (currentCharCount / CHARS_PER_TOKEN);
    }

    public int remainingTokens() {
        return maxContextTokens - estimatedTokens();
    }

    public boolean isNearLimit(double threshold) {
        return estimatedTokens() > maxContextTokens * threshold;
    }

    public boolean isOverLimit() {
        return estimatedTokens() > maxContextTokens;
    }
}
```

在 `AgentRuntime` 中集成：

```java
// AgentRuntime.java — executeAgentLoop() 中:

// 从模型配置获取 context window 大小（需要在 model_definition 中配置）
int maxContextTokens = resolveMaxContextTokens(resolved.modelId()); // 默认 128_000
ContextWindowTracker tracker = new ContextWindowTracker(maxContextTokens);

// 初始化: 追踪 system prompt + history
tracker.addChars(systemPrompt.length());
for (Message msg : conversationHistory) {
    tracker.addChars(msg.getText() != null ? msg.getText().length() : 0);
}

// 在 processToolCalls() 中，每次加入 ToolResponseMessage 后:
tracker.addChars(result.length());

if (tracker.isNearLimit(0.85)) {
    log.warn("Context window 85% full: ~{} / {} tokens",
            tracker.estimatedTokens(), maxContextTokens);
    // 选项 1: 触发上下文压缩 (P2)
    // 选项 2: 限制后续 tool result 长度
    // 选项 3: 提前终止循环
}

if (tracker.isOverLimit()) {
    log.error("Context window exceeded: ~{} / {} tokens, forcing completion",
            tracker.estimatedTokens(), maxContextTokens);
    return Flux.just(new AgentEvent.Done(fullText.toString(), turn));
}
```

**模型 context window 配置**: 在 `model_definition` 表中新增 `context_window` 字段，或在 `IntelliMateProperties` 中配置默认值。

**影响分析**:
- 字符级近似足够准确（3.5 chars/token 对中英文混合偏保守）
- 85% 预警 + 100% 硬停止，双重保护
- 硬停止时返回已有文本，不丢失已完成的推理结果

---

### 2.4 工具超时统一管理

**问题**: 当前只有 `ExecTool` 有自己的超时（默认 30s），而 `WebFetchTool`（15s）、`FileReadTool`（无超时）各自管理。Agent 级别的 `timeoutSeconds` 只控制 LLM 调用超时，不控制工具执行超时。

**改动文件**: `AgentRuntime.java` — `processToolCalls()`

**具体方案**:

在工具执行层加统一超时包装：

```java
// AgentRuntime.java — processToolCalls() 中:

private static final Duration TOOL_EXECUTION_TIMEOUT = Duration.ofSeconds(60);

Mono<AgentEvent> resultEvent = Mono.fromCallable(() -> {
    ToolCallback callback = toolsEngine.getCallbackByName(tc.name());
    String result = callback.call(tc.arguments());
    // ...
}).subscribeOn(Schedulers.boundedElastic())
  .timeout(TOOL_EXECUTION_TIMEOUT)  // 统一超时
  .onErrorResume(TimeoutException.class, e -> {
      String errorMsg = "Tool execution timed out after " 
              + TOOL_EXECUTION_TIMEOUT.getSeconds() + "s";
      log.warn("Tool {} timed out", tc.name());
      history.add(new ToolResponseMessage(List.of(
              new ToolResponseMessage.ToolResponse(tc.id(), tc.name(), errorMsg))));
      return Mono.just((AgentEvent) new AgentEvent.ToolResult(
              tc.id(), tc.name(), errorMsg, false));
  });
```

**影响分析**:
- 在 Reactor 层加 `.timeout()` 比在工具内部加更可靠
- 超时后优雅返回错误消息而非终止整个 Agent run
- `TOOL_EXECUTION_TIMEOUT` 应可配置（per-agent 或 per-tool）

---

## 3. P1 — 性能与体验

### 3.1 并行工具执行

**问题**: 当前 `processToolCalls()` 使用 `concatMap` 串行执行。LLM 返回 3 个 readFile 调用时，总耗时 = 3 × 单个耗时。实际上这 3 个读取完全无依赖，可以并行。

**参考**: Cursor 的并行执行（同 turn 最多 25 个 tool calls 并行）。

**改动文件**: `AgentRuntime.java` — `processToolCalls()`

**具体方案**:

将 `concatMap` 改为 `flatMap` + `collectList`，先并行执行所有工具，再统一加入 history：

```java
// AgentRuntime.java — processToolCalls() 改造

private static final int MAX_PARALLEL_TOOL_CALLS = 8;

private Flux<AgentEvent> processToolCalls(...) {
    AssistantMessage assistantMsg = toolCallResponse.getResult().getOutput();
    history.add(assistantMsg);

    List<AssistantMessage.ToolCall> toolCalls = assistantMsg.getToolCalls();
    if (toolCalls == null || toolCalls.isEmpty()) {
        return Flux.just(new AgentEvent.Done(fullText.toString(), turn));
    }

    log.debug("Turn {} has {} tool call(s), executing in parallel", turn, toolCalls.size());

    // 阶段 1: 发射所有 ToolCall 事件
    Flux<AgentEvent> callEvents = Flux.fromIterable(toolCalls)
            .map(tc -> (AgentEvent) new AgentEvent.ToolCall(tc.id(), tc.name(), tc.arguments()));

    // 阶段 2: 并行执行所有工具，收集结果
    Mono<List<ToolExecutionResult>> resultsMono = Flux.fromIterable(toolCalls)
            .flatMap(tc -> executeToolAsync(tc, agentName, sessionId, skillsBasePath),
                    MAX_PARALLEL_TOOL_CALLS)
            .collectList();

    // 阶段 3: 结果加入 history + 发射 ToolResult 事件
    Flux<AgentEvent> resultEvents = resultsMono.flatMapMany(results -> {
        // 按原始顺序排列 ToolResponseMessage
        List<ToolResponseMessage.ToolResponse> responses = results.stream()
                .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), r.result()))
                .toList();
        history.add(new ToolResponseMessage(responses));

        return Flux.fromIterable(results)
                .map(r -> (AgentEvent) new AgentEvent.ToolResult(
                        r.id(), r.name(), r.result(), r.success()));
    });

    // 阶段 4: 递归下一轮
    Flux<AgentEvent> nextTurn = Flux.defer(() ->
            executeLoopTurn(chatModel, history, options, maxTurns, timeout,
                    turn + 1, fullText, agentName, sessionId, skillsBasePath));

    return Flux.concat(callEvents, resultEvents, nextTurn);
}

private record ToolExecutionResult(
        String id, String name, String result, boolean success) {}

private Mono<ToolExecutionResult> executeToolAsync(
        AssistantMessage.ToolCall tc,
        String agentName, Long sessionId, String skillsBasePath) {

    return Mono.fromCallable(() -> {
        try {
            ToolCallback callback = toolsEngine.getCallbackByName(tc.name());
            String result = callback.call(tc.arguments());
            result = truncateToolResult(result, MAX_TOOL_RESULT_CHARS);
            recordSkillActivationIfApplicable(tc.name(), tc.arguments(),
                    agentName, sessionId, skillsBasePath);
            return new ToolExecutionResult(tc.id(), tc.name(), result, true);
        } catch (Exception e) {
            String errorMsg = "Tool execution failed: " + e.getMessage();
            log.warn("Tool {} failed: {}", tc.name(), e.getMessage(), e);
            return new ToolExecutionResult(tc.id(), tc.name(), errorMsg, false);
        }
    }).subscribeOn(Schedulers.boundedElastic())
      .timeout(TOOL_EXECUTION_TIMEOUT)
      .onErrorResume(e -> {
          String errorMsg = "Tool " + tc.name() + " error: " + e.getMessage();
          return Mono.just(new ToolExecutionResult(tc.id(), tc.name(), errorMsg, false));
      });
}
```

**关键设计决策**:
- `flatMap(..., MAX_PARALLEL_TOOL_CALLS)` 限制最大并行度为 8
- 先并行执行，再按顺序组装 `ToolResponseMessage`（顺序对 LLM 有意义）
- 单个工具失败不影响其他并行工具
- 所有结果收集后统一加入 history（而非逐个加入）

**写操作冲突处理**:
- P1 阶段暂不处理（写冲突概率低，LLM 通常不会并行写同一文件）
- P2 可以加锁机制：检测到多个 writeFile 目标相同时降级为串行

**影响分析**:
- 3 个并行 readFile 耗时从 ~3s 降为 ~1s
- 对 LLM 返回单个 tool call 的场景无影响
- 需要确保 `ToolResponseMessage` 的 tool_call_id 对应正确

---

### 3.2 Tool Call/Result 持久化

**问题**: 当前 `MessagePipeline` 只持久化最终的 assistant 文本。tool call 和 tool result 通过 WebSocket EventFrame 发给前端后即丢失，数据库中无记录。这导致：
- 无法审计 Agent 的推理过程
- 页面刷新后 tool 执行细节丢失
- 无法从中间状态恢复

**参考**: OpenHands 的 EventStream 全量持久化。

**改动文件**: `MessagePipeline.java`、`TranscriptMessageEntity.java`

**具体方案**:

方案 A（推荐）: 在 MessagePipeline 的 `mapAgentEvent` 中增加持久化逻辑：

```java
// MessagePipeline.java — mapAgentEvent()

case AgentEvent.ToolCall tc -> {
    // 持久化 tool call
    TranscriptMessageEntity toolCallMsg = new TranscriptMessageEntity();
    toolCallMsg.setRole("assistant_tool_call");
    toolCallMsg.setToolCallId(tc.toolCallId());
    toolCallMsg.setToolName(tc.name());
    toolCallMsg.setContent(tc.arguments());
    toolCallMsg.setCreatedAt(LocalDateTime.now());

    yield sessionManager.appendMessage(currentSessionId, toolCallMsg)
            .thenMany(Flux.just(new EventFrame(
                    "agent.tool_call",
                    Map.of("toolCallId", tc.toolCallId(),
                           "name", tc.name(),
                           "arguments", tc.arguments(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            )));
}

case AgentEvent.ToolResult tr -> {
    // 持久化 tool result
    TranscriptMessageEntity toolResultMsg = new TranscriptMessageEntity();
    toolResultMsg.setRole("tool");
    toolResultMsg.setToolCallId(tr.toolCallId());
    toolResultMsg.setToolName(tr.name());
    toolResultMsg.setContent(truncateForStorage(tr.result(), 32_000));
    toolResultMsg.setMetadataJson("{\"success\":" + tr.success() + "}");
    toolResultMsg.setCreatedAt(LocalDateTime.now());

    yield sessionManager.appendMessage(currentSessionId, toolResultMsg)
            .thenMany(Flux.just(new EventFrame(
                    "agent.tool_result",
                    Map.of("toolCallId", tr.toolCallId(),
                           "name", tr.name(),
                           "result", tr.result(),
                           "success", tr.success(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            )));
}
```

**传递 sessionId 到 mapAgentEvent**: 当前 `mapAgentEvent` 没有 sessionId 参数，需要从 `processMessageStreaming` 中传入。可以将其改为内部类或 lambda 捕获。

**存储空间考虑**:
- tool result 存储前截断为 32KB（`truncateForStorage`）
- `content` 字段类型已经是 `MEDIUMTEXT`（最大 16MB），足够
- 可选：大结果只存摘要，原始结果写文件系统

**前端历史恢复**:
- `SessionManager.getHistory()` 已有 `role="tool"` 的处理
- 前端 ChatPanel 需要增加 tool call/result 的渲染支持

**影响分析**:
- 每次 tool call 多一次异步 DB 写入（非阻塞）
- 存储量增加（按每条 tool result 平均 4KB，100 次调用 = 400KB）
- 历史回放可以还原 Agent 的完整推理过程

---

### 3.3 工具重试策略

**问题**: 当前工具执行失败后直接将错误文本返回给 LLM。某些临时性错误（网络超时、文件锁、API 限流）是可以通过重试解决的。

**参考**: LangGraph 的 `handle_tool_errors` 可配置策略。

**改动文件**: `AgentRuntime.java`

**具体方案**:

对特定异常类型自动重试：

```java
// AgentRuntime.java — executeToolAsync() 中:

private static final int MAX_RETRIES = 2;
private static final Duration RETRY_DELAY = Duration.ofSeconds(1);

private Mono<ToolExecutionResult> executeToolAsync(...) {
    return Mono.fromCallable(() -> {
        ToolCallback callback = toolsEngine.getCallbackByName(tc.name());
        String result = callback.call(tc.arguments());
        result = truncateToolResult(result, MAX_TOOL_RESULT_CHARS);
        return new ToolExecutionResult(tc.id(), tc.name(), result, true);
    })
    .subscribeOn(Schedulers.boundedElastic())
    .timeout(TOOL_EXECUTION_TIMEOUT)
    .retryWhen(Retry.backoff(MAX_RETRIES, RETRY_DELAY)
            .filter(this::isRetryableError)
            .doBeforeRetry(signal ->
                    log.info("Retrying tool {} (attempt {})", tc.name(), signal.totalRetries() + 1)))
    .onErrorResume(e -> {
        String errorMsg = "Tool execution failed after retries: " + e.getMessage();
        return Mono.just(new ToolExecutionResult(tc.id(), tc.name(), errorMsg, false));
    });
}

private boolean isRetryableError(Throwable e) {
    // 网络超时、连接失败、API 限流等临时性错误
    return e instanceof java.net.SocketTimeoutException
            || e instanceof java.net.ConnectException
            || e instanceof java.util.concurrent.TimeoutException
            || (e.getMessage() != null && e.getMessage().contains("429"));
}
```

**不重试的场景**:
- 文件不存在 (`FileNotFoundException`)
- 参数错误 (`IllegalArgumentException`)
- 权限不足 (`SecurityException`)

**影响分析**:
- 最多增加 2 次重试 × 1s 延迟 = 2s 额外等待
- 只对临时性错误重试，不会浪费时间在确定性错误上
- 使用 Reactor 的 `retryWhen` 运算符，与现有响应式管道无缝集成

---

## 4. P2 — 高级能力

### 4.1 上下文压缩 / 滑动窗口

**问题**: 在多轮工具调用后，history 中积累了大量的 ToolResponseMessage。很多早期的工具结果已经被 LLM 吸收并反映在后续推理中，但它们仍占据 context window。

**参考**: OpenHands 的 Condenser 系统（NoOp / Rolling / LLMSummarizing / Pipeline）。

**改动文件**: 新增 `ContextCondenser.java`，修改 `AgentRuntime.java`

**具体方案 (分阶段)**:

**阶段 1: 滑动窗口 (RollingCondenser)**

当 history 消息超过阈值时，将旧的 tool result 内容替换为摘要占位符：

```java
// 新建: intellimate-agent/.../runtime/ContextCondenser.java

public class ContextCondenser {

    private static final int KEEP_RECENT_MESSAGES = 20;
    private static final int TOOL_RESULT_SUMMARY_LENGTH = 200;

    public static List<Message> condense(List<Message> history) {
        if (history.size() <= KEEP_RECENT_MESSAGES) {
            return history;
        }

        List<Message> condensed = new ArrayList<>();
        int cutoff = history.size() - KEEP_RECENT_MESSAGES;

        for (int i = 0; i < history.size(); i++) {
            Message msg = history.get(i);

            if (i < cutoff && msg instanceof ToolResponseMessage trm) {
                // 旧的 tool result: 截断为摘要
                String summary = summarizeToolResult(trm);
                condensed.add(new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse(
                                extractToolCallId(trm),
                                extractToolName(trm),
                                summary))));
            } else {
                condensed.add(msg);
            }
        }
        return condensed;
    }

    private static String summarizeToolResult(ToolResponseMessage trm) {
        String content = trm.getText();
        if (content == null) return "[no content]";
        if (content.length() <= TOOL_RESULT_SUMMARY_LENGTH) return content;
        return content.substring(0, TOOL_RESULT_SUMMARY_LENGTH) + "... [condensed]";
    }
}
```

在 `executeLoopTurn()` 中，每轮开始前压缩：

```java
// AgentRuntime.java — executeLoopTurn() 开头:

if (tracker.isNearLimit(0.7)) {
    List<Message> condensed = ContextCondenser.condense(history);
    history.clear();
    history.addAll(condensed);
    tracker.recalculate(history); // 重新计算 token 量
    log.info("Context condensed: {} → {} messages", beforeSize, history.size());
}
```

**阶段 2: LLM 摘要 (LLMSummarizingCondenser, 可选)**

对于长时间运行的 Agent，可以使用 LLM 将旧历史压缩为结构化摘要：

```java
public Mono<String> summarizeHistory(List<Message> oldMessages, ChatModel model) {
    String prompt = "Summarize the following agent execution history. "
            + "Focus on: user goals, completed steps, key findings, "
            + "important file paths, and remaining tasks.\n\n"
            + formatMessages(oldMessages);

    return model.call(new Prompt(prompt))
            .map(response -> response.getResult().getOutput().getText());
}
```

此方案成本较高（额外 LLM 调用），适合 maxTurns > 30 的长任务场景。

**影响分析**:
- 滑动窗口压缩几乎无成本
- LLM 摘要每次压缩消耗 ~1000 tokens
- 上下文质量在压缩后可能略有下降（但远好于溢出导致的截断）

---

### 4.2 人工审批节点

**问题**: `ExecTool` 可以在主机上执行任意命令，`FileWriteTool` 可以覆盖任意文件。这些高危操作当前无任何拦截机制。

**参考**: LangGraph 的 `interrupt_before` + Cursor 的 Accept/Reject。

**改动文件**: 新增 `ToolApprovalGate.java`、`AgentEvent` 扩展、`MessagePipeline` 改造

**具体方案**:

**1. 定义高危工具列表**:

```java
// IntelliMateProperties.Agent 新增:
private List<String> approvalRequiredTools = List.of("exec", "writeFile", "fileEdit");
```

**2. 新增 AgentEvent**:

```java
// AgentEvent.java 新增:
record ApprovalRequired(
        String toolCallId,
        String toolName,
        String arguments,
        String requestId
) implements AgentEvent {}

record ApprovalResponse(
        String toolCallId,
        boolean approved,
        String modifiedArguments  // 用户可以修改参数
) implements AgentEvent {}
```

**3. 工具执行前拦截**:

```java
// AgentRuntime.java — processToolCalls() 中:

if (approvalRequiredTools.contains(tc.name())) {
    // 暂停执行，向前端发送审批请求
    // 等待用户通过 WebSocket 响应
    // 审批通过后继续执行
    // 审批拒绝后返回 "User rejected tool execution"
}
```

**4. WebSocket 协议**:

```
→ EventFrame("agent.approval_required", {toolCallId, name, arguments})
← RequestFrame("agent.approve", {toolCallId, approved, modifiedArguments})
```

**实现复杂度**: 这是 P2 中最复杂的功能，因为需要在 Reactor 管道中"暂停"等待外部输入。可以使用 `Sinks.one()` 实现：

```java
Sinks.One<Boolean> approvalSink = Sinks.one();
// 注册到全局的 pending approvals map
pendingApprovals.put(tc.id(), approvalSink);
// 发送审批请求事件
// 等待用户响应
return approvalSink.asMono()
        .timeout(Duration.ofMinutes(5))
        .flatMap(approved -> {
            if (approved) {
                return executeToolAsync(tc, ...);
            } else {
                return Mono.just(rejectedResult(tc));
            }
        });
```

**影响分析**:
- 需要 MessagePipeline 支持双向通信（目前是单向流式输出）
- 审批超时默认拒绝（5 分钟）
- 非审批工具不受影响（零延迟）

---

### 4.3 工具结果缓存

**问题**: Agent 可能在同一次执行中多次读取同一文件（如先读取了解结构，后续修改前再读一次）。每次 `readFile` 都是完整的文件系统 I/O + 全部内容进入 history。

**改动文件**: 新增 `ToolResultCache.java`，修改 `AgentRuntime.java`

**具体方案**:

```java
// 新建: intellimate-agent/.../runtime/ToolResultCache.java

public class ToolResultCache {

    private final Map<String, CachedResult> cache = new LinkedHashMap<>();
    private static final int MAX_ENTRIES = 50;
    
    private record CachedResult(String result, long timestamp) {}

    /**
     * 只缓存读操作（readFile, searchFiles, webFetch）。
     * 写操作（writeFile, exec）永远不缓存。
     */
    private static final Set<String> CACHEABLE_TOOLS = Set.of(
            "readFile", "searchFiles", "listDirectory", "webFetch", "getSkillContent");

    public String get(String toolName, String arguments) {
        if (!CACHEABLE_TOOLS.contains(toolName)) return null;
        String key = toolName + "::" + arguments;
        CachedResult cached = cache.get(key);
        if (cached != null) {
            return cached.result();
        }
        return null;
    }

    public void put(String toolName, String arguments, String result) {
        if (!CACHEABLE_TOOLS.contains(toolName)) return;
        String key = toolName + "::" + arguments;
        if (cache.size() >= MAX_ENTRIES) {
            // 移除最早的条目
            cache.remove(cache.keySet().iterator().next());
        }
        cache.put(key, new CachedResult(result, System.currentTimeMillis()));
    }

    /**
     * writeFile 后使对应 readFile 缓存失效。
     */
    public void invalidate(String toolName, String arguments) {
        if ("writeFile".equals(toolName) || "fileEdit".equals(toolName)) {
            // 从 arguments 提取文件路径，移除对应的 readFile 缓存
            String path = extractPath(arguments);
            if (path != null) {
                cache.remove("readFile::" + /* matching args */);
            }
        }
    }
}
```

在 `processToolCalls()` 中集成：

```java
// 执行前检查缓存
String cachedResult = toolResultCache.get(tc.name(), tc.arguments());
if (cachedResult != null) {
    log.debug("Tool {} cache hit", tc.name());
    result = cachedResult + "\n[cached result]";
} else {
    result = callback.call(tc.arguments());
    toolResultCache.put(tc.name(), tc.arguments(), result);
}
```

**影响分析**:
- 只缓存读操作，写操作触发缓存失效
- 同一次 run 内有效（per-run 生命周期）
- 缓存命中时 LLM 的 ToolResponseMessage 中标注 `[cached result]`，告知内容可能非最新

---

## 5. 实施路线

```
P0 (2-3 天):
  Day 1: 工具结果截断 + 循环检测
  Day 2: Token 追踪 + 上下文窗口保护
  Day 3: 工具超时统一管理 + 测试

P1 (3-5 天):
  Day 4-5: 并行工具执行
  Day 6: Tool call/result 持久化
  Day 7-8: 工具重试策略 + 测试

P2 (1-2 周):
  Week 3: 上下文压缩 + 工具结果缓存
  Week 4: 人工审批节点 (涉及 WebSocket 双向通信改造)
```

---

## 6. 文件变更清单

### P0 新增文件

| 文件 | 模块 | 描述 |
|------|------|------|
| `ToolCallLoopDetector.java` | agent/runtime | 工具调用循环检测器 |
| `ContextWindowTracker.java` | agent/runtime | token 追踪器 |

### P0 修改文件

| 文件 | 改动 |
|------|------|
| `AgentRuntime.java` | processToolCalls() 加截断, executeLoopTurn() 加循环检测 + token 追踪 + 超时 |
| `IntelliMateProperties.java` | Agent 新增 maxToolResultChars, maxContextTokens |

### P1 修改文件

| 文件 | 改动 |
|------|------|
| `AgentRuntime.java` | processToolCalls() 改为并行执行, 加重试 |
| `MessagePipeline.java` | mapAgentEvent() 中增加 tool call/result 持久化 |

### P2 新增文件

| 文件 | 模块 | 描述 |
|------|------|------|
| `ContextCondenser.java` | agent/runtime | 上下文压缩器 |
| `ToolResultCache.java` | agent/runtime | 工具结果缓存 |
| `ToolApprovalGate.java` | agent/runtime | 人工审批门控 |

### P2 修改文件

| 文件 | 改动 |
|------|------|
| `AgentEvent.java` | 新增 ApprovalRequired, ApprovalResponse |
| `MessagePipeline.java` | 新增审批事件映射 + 审批响应处理 |
| `AgentRuntime.java` | 集成压缩器、缓存、审批门控 |

---

## 7. 风险与缓解

| 风险 | 严重度 | 缓解措施 |
|------|--------|----------|
| 工具结果截断导致 LLM 信息不足 | 中 | 截断标记告知 LLM，可通过 startLine/lineCount 分段读取 |
| 并行执行时文件写冲突 | 低 | P1 暂不处理，P2 加锁机制 |
| 循环检测误触发 | 低 | 滑动窗口 + 高阈值（5 次），合理的重复读取不会触发 |
| Token 近似估算不准 | 低 | 3.5 chars/token 偏保守（实际通常 3.2-4.0） |
| 人工审批超时 | 中 | 默认 5 分钟超时拒绝，超时策略可配置 |
| 上下文压缩丢失关键信息 | 低 | 保留最近 20 条不压缩，仅压缩旧 tool result |
