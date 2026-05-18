# Agent Loop 技术设计文档

## 1. 现状分析

### 1.1 当前执行模式

IntelliMate 当前采用**单轮一问一答 + 流式输出**模式：

```
用户消息 → 单次 LLM 调用 → 流式 token 输出 → 完成
```

核心调用链路：

```
MessagePipeline.processMessageStreaming()
  → AgentRuntime.dispatch()
    → RunQueueManager.enqueue()
      → AgentRuntime.executeRun()
        → ChatClient.prompt().stream().content()   ← 单次调用
          → Flux<String>                            ← 只有文本 token
```

**关键代码（当前）：**

```java
// AgentRuntime.executeRun() — 当前实现
private Flux<String> executeRun(AgentRunRequest request) {
    String systemPrompt = buildSystemPrompt(request.agent());
    ToolCallback[] tools = toolsEngine.getToolCallbacksFor(request.toolsEnabled());

    return chatClient.prompt()
            .system(systemPrompt)
            .messages(request.history())
            .user(request.userMessage())
            .toolCallbacks(tools)
            .stream()
            .content()   // 只输出文本 token，工具调用被框架内部消化
            .timeout(Duration.ofSeconds(request.agent().getTimeoutSeconds()));
}
```

### 1.2 当前问题

| 问题 | 说明 |
|------|------|
| 工具调用不可见 | Spring AI 内部自动处理 tool_call 循环，前端完全看不到 |
| maxTurns 未使用 | 配置了 `maxTurns = 128` 但代码中从未引用 |
| 无法控制循环 | 框架自动循环不可控，无法中断、观测或限制 |
| 前端无法展示思考过程 | 用户只能看到最终文本，无法看到 Agent 的推理和工具使用过程 |
| 历史记录不含工具上下文 | `convertToAiMessages()` 只处理 `user`/`assistant`，跳过 `tool` role |

### 1.3 数据库现状

`transcript_message` 表已预留了工具相关字段（无需 DDL 变更）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `role` | VARCHAR(16) | 已支持 `user / assistant / system / tool` |
| `tool_call_id` | VARCHAR(128) | 工具调用 ID（role=tool 时使用） |
| `tool_name` | VARCHAR(64) | 工具名称 |
| `metadata_json` | JSON | 扩展元数据（可存储工具参数、token usage 等） |

`TranscriptMessageEntity` 也已包含 `toolCallId`、`toolName`、`metadataJson` 字段。

---

## 2. 目标架构

### 2.1 Agent Loop 流程

改造为**多轮自主 Agent Loop**，LLM 可以自主决定是否调用工具、调用哪些工具、何时给出最终回复。每一轮的"思考"、"工具调用"、"工具结果"都实时推送到前端展示。

```
用户消息
  → Turn 1: LLM 思考 + 决定调用工具
    → 执行工具 → 获取结果
  → Turn 2: LLM 基于工具结果继续思考
    → 决定调用另一个工具
    → 执行工具 → 获取结果
  → Turn 3: LLM 基于所有信息生成最终回复
    → 流式输出文本 → 完成
```

### 2.2 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│                     MessagePipeline                             │
│  processMessageStreaming()                                      │
│    ┌──────────────────────────────────────────────────────────┐ │
│    │  AgentRuntime.dispatch()                                 │ │
│    │    ┌──────────────────────────────────────────────────┐  │ │
│    │    │  executeAgentLoop()                              │  │ │
│    │    │                                                  │  │ │
│    │    │  ┌─ Turn 1 ──────────────────────────────────┐  │  │ │
│    │    │  │ StreamingChatModel.stream(prompt)          │  │  │ │
│    │    │  │   → 收集 ChatResponse 流                  │  │  │ │
│    │    │  │   → 发射 TextChunk 事件 (流式)            │  │  │ │
│    │    │  │   → 检测 tool_call                        │  │  │ │
│    │    │  │   → 发射 ToolCall 事件                    │  │  │ │
│    │    │  │   → 执行 ToolCallback                     │  │  │ │
│    │    │  │   → 发射 ToolResult 事件                  │  │  │ │
│    │    │  └────────────────────────────────────────────┘  │  │ │
│    │    │  ┌─ Turn 2 ──────────────────────────────────┐  │  │ │
│    │    │  │ (同上循环, 直到无 tool_call 或达 maxTurns) │  │  │ │
│    │    │  └────────────────────────────────────────────┘  │  │ │
│    │    │  → 发射 Done 事件                               │  │ │
│    │    └──────────────────────────────────────────────────┘  │ │
│    │                                                          │ │
│    │  Flux<AgentEvent> → 映射为 EventFrame → WebSocket 推送  │ │
│    └──────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
         │
         ▼
┌──────────────────────┐
│  前端 useWebSocket   │
│  agent.turn_start    │──→ 更新轮次显示
│  agent.chunk         │──→ 实时追加文本
│  agent.tool_call     │──→ 显示工具调用卡片
│  agent.tool_result   │──→ 更新工具结果
│  agent.done          │──→ 完成，解锁输入
└──────────────────────┘
```

---

## 3. 后端改造

### 3.1 新增 AgentEvent 类型体系

**文件**: `intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentEvent.java`（新建）

Agent Loop 的所有事件通过一个 sealed interface 统一建模：

```java
package com.atm.intellimate.agent.runtime;

public sealed interface AgentEvent {

    /** 每个 Turn 开始时发射 */
    record TurnStart(int turn, int maxTurns) implements AgentEvent {}

    /** LLM 流式输出的文本片段（每个 token） */
    record TextChunk(String text) implements AgentEvent {}

    /** LLM 决定调用一个工具 */
    record ToolCall(
            String toolCallId,
            String name,
            String arguments
    ) implements AgentEvent {}

    /** 工具执行结果 */
    record ToolResult(
            String toolCallId,
            String name,
            String result,
            boolean success
    ) implements AgentEvent {}

    /** Agent Loop 正常完成 */
    record Done(String fullText, int totalTurns) implements AgentEvent {}

    /** Agent Loop 异常 */
    record Error(String message) implements AgentEvent {}
}
```

### 3.2 AgentRuntime 改造

**文件**: `intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java`

核心变更：从 `ChatClient` 切换到 `StreamingChatModel` + `ToolCallingManager`，实现用户控制的 Agent Loop。

#### 3.2.1 依赖注入变更

```java
// 改造前
private final ChatClient chatClient;

public AgentRuntime(ChatClient.Builder chatClientBuilder, ...) {
    this.chatClient = chatClientBuilder.build();
}

// 改造后
private final StreamingChatModel chatModel;
private final ToolCallingManager toolCallingManager;

public AgentRuntime(StreamingChatModel chatModel,
                    ToolCallingManager toolCallingManager,
                    ToolsEngine toolsEngine,
                    RunQueueManager runQueueManager,
                    ObjectMapper objectMapper) {
    this.chatModel = chatModel;
    this.toolCallingManager = toolCallingManager;
    // ...
}
```

#### 3.2.2 dispatch 签名变更

```java
// 改造前
public Flux<String> dispatch(AgentRunRequest request) {
    return runQueueManager.enqueue(request.sessionId(), () -> executeRun(request));
}

// 改造后
public Flux<AgentEvent> dispatch(AgentRunRequest request) {
    return runQueueManager.enqueue(request.sessionId(), () -> executeAgentLoop(request));
}
```

#### 3.2.3 Agent Loop 核心实现

```java
private Flux<AgentEvent> executeAgentLoop(AgentRunRequest request) {
    String systemPrompt = buildSystemPrompt(request.agent());
    ToolCallback[] tools = toolsEngine.getToolCallbacksFor(request.toolsEnabled());
    int maxTurns = request.agent().getMaxTurns();
    Duration timeout = Duration.ofSeconds(request.agent().getTimeoutSeconds());

    // 构建初始消息列表
    List<Message> conversationHistory = new ArrayList<>();
    conversationHistory.add(new SystemMessage(systemPrompt));
    if (request.history() != null) {
        conversationHistory.addAll(request.history());
    }
    conversationHistory.add(new UserMessage(request.userMessage()));

    // 构建 ChatOptions，禁用框架自动工具执行
    ToolCallingChatOptions chatOptions = ToolCallingChatOptions.builder()
            .toolCallbacks(tools)
            .internalToolExecutionEnabled(false)
            .build();

    // Agent Loop 上下文
    AgentLoopContext context = new AgentLoopContext(
            conversationHistory, chatOptions, maxTurns, timeout
    );

    return executeLoopTurn(context, 1);
}
```

#### 3.2.4 单轮执行（reactive 递归）

```java
private Flux<AgentEvent> executeLoopTurn(AgentLoopContext ctx, int turn) {
    if (turn > ctx.maxTurns()) {
        log.warn("Agent loop reached maxTurns={}", ctx.maxTurns());
        return Flux.just(new AgentEvent.Done("[max turns reached]", turn - 1));
    }

    Prompt prompt = new Prompt(ctx.history(), ctx.options());
    StringBuilder turnText = new StringBuilder();

    return Flux.concat(
            // 1. 发射 TurnStart
            Flux.just(new AgentEvent.TurnStart(turn, ctx.maxTurns())),

            // 2. 流式调用 LLM，收集响应
            chatModel.stream(prompt)
                    .timeout(ctx.timeout())
                    .collectList()
                    .flatMapMany(chunks -> {
                        // 聚合完整 ChatResponse
                        ChatResponse aggregated = mergeChunks(chunks);

                        // 提取文本 delta
                        List<AgentEvent> textEvents = new ArrayList<>();
                        for (ChatResponse chunk : chunks) {
                            String delta = extractTextDelta(chunk);
                            if (delta != null && !delta.isEmpty()) {
                                turnText.append(delta);
                                textEvents.add(new AgentEvent.TextChunk(delta));
                            }
                        }

                        // 检查是否有工具调用
                        if (aggregated.hasToolCalls()) {
                            return processToolCalls(ctx, aggregated, turn, turnText, textEvents);
                        }

                        // 无工具调用 → 发射文本 + Done
                        textEvents.add(new AgentEvent.Done(turnText.toString(), turn));
                        return Flux.fromIterable(textEvents);
                    })
    );
}
```

> **关于流式实时性的说明**：上方的 `collectList()` 会等待一轮 LLM 调用完成后再统一发射文本事件。
> 如果需要真正的逐 token 实时推送（即使在中间轮次），可以改为两阶段管道——先通过 `doOnNext` 逐个发射 `TextChunk`，同时用 `reduce` 聚合完整响应。下文 3.2.5 给出了实时流式变体。

#### 3.2.5 实时流式变体（推荐）

```java
private Flux<AgentEvent> executeLoopTurn(AgentLoopContext ctx, int turn) {
    if (turn > ctx.maxTurns()) {
        return Flux.just(new AgentEvent.Done("[max turns reached]", turn - 1));
    }

    Prompt prompt = new Prompt(ctx.history(), ctx.options());
    StringBuilder turnText = new StringBuilder();
    List<ChatResponse> allChunks = new ArrayList<>();

    Flux<AgentEvent> turnStart = Flux.just(new AgentEvent.TurnStart(turn, ctx.maxTurns()));

    // 流式发射每个 token，同时收集完整响应
    Flux<AgentEvent> streamAndCollect = chatModel.stream(prompt)
            .timeout(ctx.timeout())
            .concatMap(chunk -> {
                allChunks.add(chunk);
                String delta = extractTextDelta(chunk);
                if (delta != null && !delta.isEmpty()) {
                    turnText.append(delta);
                    return Flux.just((AgentEvent) new AgentEvent.TextChunk(delta));
                }
                return Flux.empty();
            });

    // 流结束后决定下一步
    Flux<AgentEvent> afterStream = Flux.defer(() -> {
        ChatResponse aggregated = mergeChunks(allChunks);

        if (aggregated.hasToolCalls()) {
            return processToolCalls(ctx, aggregated, turn, turnText);
        }

        return Flux.just(new AgentEvent.Done(turnText.toString(), turn));
    });

    return Flux.concat(turnStart, streamAndCollect, afterStream);
}
```

#### 3.2.6 工具调用处理

```java
private Flux<AgentEvent> processToolCalls(
        AgentLoopContext ctx,
        ChatResponse aggregated,
        int turn,
        StringBuilder turnText) {

    // 提取所有 tool_call
    List<ToolCallInfo> toolCalls = extractToolCalls(aggregated);

    // 将 assistant 消息（含 tool_calls）加入上下文
    ctx.history().add(aggregated.getResult().getOutput());

    // 依次执行每个工具，发射事件，添加结果到历史
    Flux<AgentEvent> toolExecution = Flux.fromIterable(toolCalls)
            .concatMap(tc -> {
                // 发射 ToolCall 事件
                AgentEvent callEvent = new AgentEvent.ToolCall(
                        tc.id(), tc.name(), tc.arguments()
                );

                // 执行工具
                Mono<AgentEvent> resultEvent = Mono.fromCallable(() -> {
                    try {
                        ToolCallback callback = toolsEngine.getCallbackByName(tc.name());
                        String result = callback.call(tc.arguments());

                        // 工具结果加入上下文
                        ctx.history().add(new ToolResponseMessage(
                                result, Map.of("id", tc.id(), "name", tc.name())
                        ));

                        return (AgentEvent) new AgentEvent.ToolResult(
                                tc.id(), tc.name(), result, true
                        );
                    } catch (Exception e) {
                        String errorMsg = "Tool execution failed: " + e.getMessage();
                        ctx.history().add(new ToolResponseMessage(
                                errorMsg, Map.of("id", tc.id(), "name", tc.name())
                        ));
                        return (AgentEvent) new AgentEvent.ToolResult(
                                tc.id(), tc.name(), errorMsg, false
                        );
                    }
                }).subscribeOn(Schedulers.boundedElastic());

                return Flux.concat(Flux.just(callEvent), resultEvent.flux());
            });

    // 工具执行完毕后，进入下一轮
    Flux<AgentEvent> nextTurn = Flux.defer(() -> executeLoopTurn(ctx, turn + 1));

    return Flux.concat(toolExecution, nextTurn);
}
```

#### 3.2.7 辅助方法

```java
/** 从 ChatResponse chunk 中提取文本增量 */
private String extractTextDelta(ChatResponse chunk) {
    if (chunk == null || chunk.getResults().isEmpty()) return null;
    Generation gen = chunk.getResults().get(0);
    if (gen.getOutput() == null) return null;
    return gen.getOutput().getText();
}

/** 合并多个 chunk 为一个完整的 ChatResponse */
private ChatResponse mergeChunks(List<ChatResponse> chunks) {
    // Spring AI 提供 ChatResponseMetadata.merge 或手动合并
    // 关键是聚合所有 Generation 的 tool_call 信息
    return ChatResponse.builder()
            .from(chunks.get(chunks.size() - 1)) // 最后一个 chunk 通常包含完整的 tool_calls
            .build();
}

/** 从聚合的 ChatResponse 提取工具调用列表 */
private List<ToolCallInfo> extractToolCalls(ChatResponse response) {
    return response.getResult().getOutput().getToolCalls().stream()
            .map(tc -> new ToolCallInfo(tc.id(), tc.name(), tc.arguments()))
            .toList();
}

private record ToolCallInfo(String id, String name, String arguments) {}
```

#### 3.2.8 Agent Loop 上下文

```java
private record AgentLoopContext(
        List<Message> history,
        ToolCallingChatOptions options,
        int maxTurns,
        Duration timeout
) {}
```

### 3.3 RunQueueManager 泛型改造

**文件**: `intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/RunQueueManager.java`

将 `Flux<String>` 泛型改为 `Flux<AgentEvent>`：

```java
// 改造前
public synchronized Flux<String> enqueue(
    Long sessionId, Supplier<Flux<String>> runSupplier)

// 改造后
public synchronized Flux<AgentEvent> enqueue(
    Long sessionId, Supplier<Flux<AgentEvent>> runSupplier)
```

内部 `Sinks.Many<String>` 也相应改为 `Sinks.Many<AgentEvent>`。

### 3.4 ToolsEngine 扩展

**文件**: `intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/ToolsEngine.java`

新增按名称查找单个 ToolCallback 的方法，供 Agent Loop 执行工具时使用：

```java
public ToolCallback getCallbackByName(String toolName) {
    return Arrays.stream(allToolCallbacks)
            .filter(cb -> cb.getToolDefinition().name().equals(toolName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                    "Unknown tool: " + toolName));
}
```

---

## 4. MessagePipeline 适配

**文件**: `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/pipeline/MessagePipeline.java`

### 4.1 事件映射

将 `Flux<AgentEvent>` 映射为 WebSocket `GatewayFrame`：

```java
private Flux<GatewayFrame> processMessageStreaming(
        SessionEntity session, String userText, String requestId, String wsSessionId) {

    // ... 保留现有的消息持久化和历史加载逻辑 ...

    return /* 加载历史 + 配置 */
            .flatMapMany(tuple -> {
                // ... 构建 AgentRunRequest (不变) ...

                StringBuilder fullResponse = new StringBuilder();

                Flux<GatewayFrame> events = agentRuntime.dispatch(runRequest)
                        .concatMap(event -> mapAgentEvent(event, requestId, fullResponse));

                Flux<GatewayFrame> tail = Flux.defer(() -> {
                    String completeText = fullResponse.toString();
                    return persistAndFinish(session, completeText, requestId, wsSessionId);
                });

                return Flux.concat(events, tail);
            });
}

private Flux<GatewayFrame> mapAgentEvent(
        AgentEvent event, String requestId, StringBuilder fullResponse) {

    return switch (event) {
        case AgentEvent.TurnStart ts -> Flux.just(new EventFrame(
                "agent.turn_start",
                Map.of("turn", ts.turn(),
                       "maxTurns", ts.maxTurns(),
                       "requestId", requestId),
                seqGenerator.incrementAndGet()
        ));

        case AgentEvent.TextChunk tc -> {
            fullResponse.append(tc.text());
            yield Flux.just(new EventFrame(
                    "agent.chunk",
                    Map.of("text", tc.text(), "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));
        }

        case AgentEvent.ToolCall tc -> Flux.just(new EventFrame(
                "agent.tool_call",
                Map.of("toolCallId", tc.toolCallId(),
                       "name", tc.name(),
                       "arguments", tc.arguments(),
                       "requestId", requestId),
                seqGenerator.incrementAndGet()
        ));

        case AgentEvent.ToolResult tr -> Flux.just(new EventFrame(
                "agent.tool_result",
                Map.of("toolCallId", tr.toolCallId(),
                       "name", tr.name(),
                       "result", tr.result(),
                       "success", tr.success(),
                       "requestId", requestId),
                seqGenerator.incrementAndGet()
        ));

        case AgentEvent.Done done -> Flux.just(new EventFrame(
                "agent.done",
                Map.of("text", done.fullText(),
                       "totalTurns", done.totalTurns(),
                       "requestId", requestId),
                seqGenerator.incrementAndGet()
        ));

        case AgentEvent.Error err -> Flux.just(
                ResponseFrame.failure(requestId, err.message())
        );
    };
}
```

### 4.2 持久化扩展

持久化逻辑需要同时保存工具调用记录。有两种策略：

**策略 A（推荐）：Agent Loop 内部持久化**

在 `AgentRuntime` 的每次工具调用时，立即持久化 `tool_call`（作为 assistant 消息的元数据）和 `tool_result`（作为 tool role 消息）。这样即使中途异常，已执行的工具结果不会丢失。

**策略 B：Pipeline 层批量持久化**

在 `mapAgentEvent()` 中收集所有工具事件，在 `tail` 阶段一次性持久化。实现更简单，但中途异常会丢失数据。

**持久化示例（策略 A）：**

```java
// 在 processToolCalls() 中执行工具后
TranscriptMessageEntity toolMsg = new TranscriptMessageEntity();
toolMsg.setRole("tool");
toolMsg.setToolCallId(tc.id());
toolMsg.setToolName(tc.name());
toolMsg.setContent(result);
toolMsg.setMetadataJson("{\"arguments\":" + tc.arguments() + "}");
toolMsg.setCreatedAt(LocalDateTime.now());
sessionManager.appendMessage(sessionId, toolMsg).subscribe();
```

### 4.3 历史消息还原

`convertToAiMessages()` 需要增加 `tool` role 的处理：

```java
private List<Message> convertToAiMessages(List<TranscriptMessageEntity> history) {
    List<Message> messages = new ArrayList<>();
    for (TranscriptMessageEntity msg : history) {
        switch (msg.getRole()) {
            case "user" -> messages.add(new UserMessage(msg.getContent()));
            case "assistant" -> messages.add(new AssistantMessage(msg.getContent()));
            case "tool" -> messages.add(new ToolResponseMessage(
                    msg.getContent(),
                    Map.of("id", msg.getToolCallId(), "name", msg.getToolName())
            ));
            default -> log.debug("Skipping message with role: {}", msg.getRole());
        }
    }
    return messages;
}
```

---

## 5. WebSocket 协议扩展

### 5.1 新增事件类型

在现有 `agent.chunk` 和 `agent.done` 基础上，新增三种事件：

| 事件名 | 方向 | Payload | 说明 |
|--------|------|---------|------|
| `agent.turn_start` | Server→Client | `{ turn, maxTurns, requestId }` | 每轮开始时发送 |
| `agent.tool_call` | Server→Client | `{ toolCallId, name, arguments, requestId }` | LLM 决定调用工具 |
| `agent.tool_result` | Server→Client | `{ toolCallId, name, result, success, requestId }` | 工具执行结果 |

### 5.2 完整消息流示例

#### 场景 1：无工具调用（退化为单轮）

```
→ agent.turn_start  { turn: 1, maxTurns: 128 }
→ agent.chunk       { text: "你好" }
→ agent.chunk       { text: "！很高兴" }
→ agent.chunk       { text: "为你服务。" }
→ agent.done        { text: "你好！很高兴为你服务。", totalTurns: 1 }
→ response          { ok: true }
```

#### 场景 2：单次工具调用

```
→ agent.turn_start  { turn: 1, maxTurns: 128 }
→ agent.chunk       { text: "让我搜索" }
→ agent.chunk       { text: "一下..." }
→ agent.tool_call   { toolCallId: "tc_1", name: "webSearch", arguments: '{"query":"Spring AI"}' }
→ agent.tool_result { toolCallId: "tc_1", name: "webSearch", result: "Spring AI 是...", success: true }
→ agent.turn_start  { turn: 2, maxTurns: 128 }
→ agent.chunk       { text: "根据搜索" }
→ agent.chunk       { text: "结果，" }
→ agent.chunk       { text: "Spring AI 是一个..." }
→ agent.done        { text: "根据搜索结果，Spring AI 是一个...", totalTurns: 2 }
→ response          { ok: true }
```

#### 场景 3：多次工具调用（同一轮内）

```
→ agent.turn_start  { turn: 1, maxTurns: 128 }
→ agent.tool_call   { toolCallId: "tc_1", name: "readFile", arguments: '{"path":"pom.xml"}' }
→ agent.tool_result { toolCallId: "tc_1", name: "readFile", result: "<project>...", success: true }
→ agent.tool_call   { toolCallId: "tc_2", name: "webSearch", arguments: '{"query":"Spring AI 1.0.0"}' }
→ agent.tool_result { toolCallId: "tc_2", name: "webSearch", result: "...", success: true }
→ agent.turn_start  { turn: 2, maxTurns: 128 }
→ agent.chunk       { text: "分析完毕：..." }
→ agent.done        { text: "分析完毕：...", totalTurns: 2 }
→ response          { ok: true }
```

### 5.3 时序图

```
Frontend                    WebSocket                   Backend
────────                    ─────────                   ───────

sendMessage("搜索 X")
    │
    ├──── RequestFrame ─────────────────────► MessagePipeline
    │                                              │
    │                                         AgentRuntime.dispatch()
    │                                              │
    │     ◄─── agent.turn_start {turn:1} ─────────┤
    │     ◄─── agent.chunk {text:"让我"} ─────────┤ ← LLM streaming
    │     ◄─── agent.chunk {text:"搜索"} ─────────┤
    │     ◄─── agent.tool_call {webSearch} ───────┤ ← LLM 返回 tool_call
    │                                              │
    │                                         执行 webSearch 工具
    │                                              │
    │     ◄─── agent.tool_result {result} ────────┤
    │                                              │
    │     ◄─── agent.turn_start {turn:2} ─────────┤ ← 进入下一轮
    │     ◄─── agent.chunk {text:"根据"} ─────────┤ ← LLM 继续生成
    │     ◄─── agent.chunk {text:"结果"} ─────────┤
    │     ◄─── agent.done {fullText} ─────────────┤
    │     ◄─── response {ok:true} ────────────────┤
    │
    ▼
finishStreaming()
解锁输入框
```

---

## 6. 前端改造

### 6.1 ChatMessage 模型扩展

**文件**: `intellimate-web/src/stores/chatStore.ts`

```typescript
export interface ToolCallInfo {
  toolCallId: string;
  name: string;
  arguments: string;
  result?: string;
  success?: boolean;
  status: "calling" | "done" | "error";
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "system";
  content: string;
  streaming: boolean;
  timestamp: number;
  requestId?: string;
  // ─── Agent Loop 扩展 ───
  toolCalls?: ToolCallInfo[];
  currentTurn?: number;
  maxTurns?: number;
  totalTurns?: number;
}
```

### 6.2 新增 Store Actions

```typescript
interface ChatState {
  // ... 现有字段 ...

  // ─── Agent Loop 新增 ───
  setTurnStart: (requestId: string, turn: number, maxTurns: number) => void;
  addToolCall: (requestId: string, toolCall: Omit<ToolCallInfo, "status">) => void;
  updateToolResult: (
    requestId: string,
    toolCallId: string,
    result: string,
    success: boolean,
  ) => void;
}

// 实现
setTurnStart: (requestId, turn, maxTurns) => {
  set((state) => ({
    messages: state.messages.map((msg) =>
      msg.id === `assistant-${requestId}`
        ? { ...msg, currentTurn: turn, maxTurns }
        : msg,
    ),
  }));
},

addToolCall: (requestId, toolCall) => {
  set((state) => ({
    messages: state.messages.map((msg) =>
      msg.id === `assistant-${requestId}`
        ? {
            ...msg,
            toolCalls: [
              ...(msg.toolCalls ?? []),
              { ...toolCall, status: "calling" as const },
            ],
          }
        : msg,
    ),
  }));
},

updateToolResult: (requestId, toolCallId, result, success) => {
  set((state) => ({
    messages: state.messages.map((msg) =>
      msg.id === `assistant-${requestId}`
        ? {
            ...msg,
            toolCalls: msg.toolCalls?.map((tc) =>
              tc.toolCallId === toolCallId
                ? {
                    ...tc,
                    result,
                    success,
                    status: (success ? "done" : "error") as ToolCallInfo["status"],
                  }
                : tc,
            ),
          }
        : msg,
    ),
  }));
},
```

### 6.3 useWebSocket 事件处理

**文件**: `intellimate-web/src/hooks/useWebSocket.ts`

在 `onEvent` switch 中新增三种事件处理：

```typescript
onEvent: (event: EventFrame) => {
  const store = useChatStore.getState();
  switch (event.event) {
    // ... 现有 session.welcome, agent.chunk, agent.done 不变 ...

    case "agent.turn_start": {
      store.setTurnStart(
        event.payload.requestId as string,
        event.payload.turn as number,
        event.payload.maxTurns as number,
      );
      break;
    }

    case "agent.tool_call": {
      store.addToolCall(event.payload.requestId as string, {
        toolCallId: event.payload.toolCallId as string,
        name: event.payload.name as string,
        arguments: event.payload.arguments as string,
      });
      break;
    }

    case "agent.tool_result": {
      store.updateToolResult(
        event.payload.requestId as string,
        event.payload.toolCallId as string,
        event.payload.result as string,
        event.payload.success as boolean,
      );
      break;
    }
  }
},
```

### 6.4 UI 渲染

#### 6.4.1 ToolCallCard 组件（新建）

**文件**: `intellimate-web/src/components/ToolCallCard.tsx`

```typescript
import { useState } from "react";
import { Wrench, ChevronDown, ChevronRight, Check, X, Loader2 } from "lucide-react";
import type { ToolCallInfo } from "../stores/chatStore";

interface ToolCallCardProps {
  toolCall: ToolCallInfo;
}

export default function ToolCallCard({ toolCall }: ToolCallCardProps) {
  const [expanded, setExpanded] = useState(false);

  const statusIcon = {
    calling: <Loader2 size={14} className="animate-spin text-blue-500" />,
    done: <Check size={14} className="text-green-500" />,
    error: <X size={14} className="text-red-500" />,
  }[toolCall.status];

  return (
    <div className="my-2 rounded-lg border border-slate-200 dark:border-slate-700 overflow-hidden text-sm">
      {/* Header */}
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center gap-2 px-3 py-2 bg-slate-50 dark:bg-slate-800/50
                   hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
      >
        <Wrench size={14} className="text-slate-500" />
        <span className="font-mono font-medium">{toolCall.name}</span>
        {statusIcon}
        <span className="ml-auto">
          {expanded ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        </span>
      </button>

      {/* Expandable content */}
      {expanded && (
        <div className="px-3 py-2 space-y-2 border-t border-slate-200 dark:border-slate-700">
          <div>
            <div className="text-xs text-slate-500 mb-1">Arguments</div>
            <pre className="text-xs bg-slate-100 dark:bg-slate-800 p-2 rounded overflow-x-auto">
              {formatJson(toolCall.arguments)}
            </pre>
          </div>
          {toolCall.result && (
            <div>
              <div className="text-xs text-slate-500 mb-1">Result</div>
              <pre className="text-xs bg-slate-100 dark:bg-slate-800 p-2 rounded overflow-x-auto max-h-48 overflow-y-auto">
                {toolCall.result.length > 2000
                  ? toolCall.result.substring(0, 2000) + "..."
                  : toolCall.result}
              </pre>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function formatJson(str: string): string {
  try { return JSON.stringify(JSON.parse(str), null, 2); }
  catch { return str; }
}
```

#### 6.4.2 MessageBubble 改造

**文件**: `intellimate-web/src/components/MessageBubble.tsx`

在 assistant 消息气泡中，插入 ToolCallCard 组件：

```typescript
import ToolCallCard from "./ToolCallCard";

export default function MessageBubble({ message }: MessageBubbleProps) {
  // ... 现有 isUser / isSystem 逻辑 ...

  return (
    <div className={`flex gap-3 my-4 ${isUser ? "flex-row-reverse" : ""}`}>
      {/* avatar */}
      <div className={`max-w-[75%] rounded-2xl px-4 py-2.5 ...`}>
        {isUser ? (
          <p className="text-sm whitespace-pre-wrap">{message.content}</p>
        ) : (
          <>
            {/* Turn 指示器（可选） */}
            {message.currentTurn && message.currentTurn > 1 && (
              <div className="text-xs text-slate-400 mb-1">
                Turn {message.currentTurn}/{message.maxTurns}
              </div>
            )}

            {/* 流式文本 */}
            <StreamingText
              content={message.content}
              streaming={message.streaming}
            />

            {/* 工具调用卡片 */}
            {message.toolCalls?.map((tc) => (
              <ToolCallCard key={tc.toolCallId} toolCall={tc} />
            ))}
          </>
        )}
      </div>
    </div>
  );
}
```

#### 6.4.3 渲染效果

assistant 消息气泡中，工具调用以卡片形式嵌入文本流之间：

```
┌─────────────────────────────────────────────────────┐
│  🤖 Assistant                       Turn 2/128      │
│                                                     │
│  让我搜索一下相关信息                               │
│                                                     │
│  ┌─────────────────────────────────────────────┐    │
│  │ 🔧 webSearch                          ✅    │    │
│  │  ▸ Arguments: {"query":"Spring AI..."}      │    │
│  │  ▸ Result: Spring AI 是一个用于...          │    │
│  └─────────────────────────────────────────────┘    │
│                                                     │
│  ┌─────────────────────────────────────────────┐    │
│  │ 🔧 readFile                           ✅    │    │
│  │  ▸ Arguments: {"path":"pom.xml"}            │    │
│  │  ▸ Result: <project>...                     │    │
│  └─────────────────────────────────────────────┘    │
│                                                     │
│  根据搜索结果和项目配置，Spring AI 是一个           │
│  构建 AI 应用的 Java 框架...                        │
└─────────────────────────────────────────────────────┘
```

---

## 7. 兼容性与降级

### 7.1 无工具场景

当 agent 没有配置任何工具（`toolCallbacks` 为空数组），LLM 不会返回 `tool_call`，Agent Loop 在第一轮就直接输出文本并完成，行为与当前单轮模式完全一致。

### 7.2 maxTurns 控制

| maxTurns | 行为 |
|----------|------|
| 1 | 强制单轮：即使 LLM 返回 tool_call 也直接结束 |
| 128（默认） | 最多 128 轮 LLM 调用 |
| 达到上限 | 返回已有文本 + `[max turns reached]` 提示 |

### 7.3 前端向后兼容

前端 `useWebSocket.ts` 的 `onEvent` 使用 `switch` 处理已知事件，未知事件自动忽略（`default` 分支或无匹配）。因此：

- 旧前端 + 新后端：前端会忽略 `agent.turn_start`、`agent.tool_call`、`agent.tool_result`，仍能正常显示 `agent.chunk` 和 `agent.done`
- 新前端 + 旧后端：新事件处理分支不会触发，行为与当前一致

### 7.4 超时处理

| 层级 | 超时机制 | 作用域 |
|------|----------|--------|
| 前端 | `REQUEST_TIMEOUT_MS = 60s` | 整个请求（含所有轮次） |
| 后端 每轮 | `chatModel.stream().timeout(Duration)` | 单轮 LLM 调用 |
| 后端 maxTurns | `turn > maxTurns` 检查 | 防止无限循环 |

建议将前端超时延长至 `180s ~ 300s`，因为多轮 Agent Loop 耗时远大于单轮。

---

## 8. 变更影响矩阵

| 模块 | 文件 | 变更类型 | 说明 |
|------|------|----------|------|
| **intellimate-agent** | `AgentEvent.java` | **新建** | Agent Loop 事件类型 |
| **intellimate-agent** | `AgentRuntime.java` | **重写** | 从 ChatClient 切换到 ChatModel + Agent Loop |
| **intellimate-agent** | `RunQueueManager.java` | **修改** | 泛型 `String` → `AgentEvent` |
| **intellimate-agent** | `ToolsEngine.java` | **扩展** | 新增 `getCallbackByName()` |
| **intellimate-agent** | `AgentRunRequest.java` | 不变 | — |
| **intellimate-gateway** | `MessagePipeline.java` | **修改** | 消费 `Flux<AgentEvent>`，映射为 `EventFrame` |
| **intellimate-gateway** | `MessagePipeline.java` | **扩展** | `convertToAiMessages()` 支持 `tool` role |
| **intellimate-core** | `IntelliMateProperties.java` | 不变 | `maxTurns` 已存在 |
| **intellimate-web** | `chatStore.ts` | **扩展** | ChatMessage 新增 toolCalls 等字段和 actions |
| **intellimate-web** | `useWebSocket.ts` | **扩展** | 新增 3 种事件处理 |
| **intellimate-web** | `ToolCallCard.tsx` | **新建** | 工具调用卡片组件 |
| **intellimate-web** | `MessageBubble.tsx` | **修改** | 嵌入 ToolCallCard |
| **数据库** | `transcript_message` | 不变 | 已有 `tool_call_id`、`tool_name`、`metadata_json` |

---

## 9. Spring AI 版本注意事项

当前使用 Spring AI **1.0.0** + Spring AI Alibaba **1.0.0.2**。

### 9.1 关键 API

| API | 所在包 | 说明 |
|-----|--------|------|
| `StreamingChatModel` | `org.springframework.ai.chat.model` | 流式调用接口 |
| `ChatResponse` | `org.springframework.ai.chat.model` | 包含 `hasToolCalls()` |
| `ToolCallingChatOptions` | `org.springframework.ai.chat.model` | 设置 `internalToolExecutionEnabled(false)` |
| `ToolCallingManager` | `org.springframework.ai.tool` | 框架提供的工具执行管理器（可选使用） |
| `ToolCallback.call()` | `org.springframework.ai.tool` | 手动执行单个工具 |

### 9.2 DashScope 兼容性

DashScope（通义千问）的 `qwen-max`、`qwen-plus` 等模型均支持 function calling / tool_call。Spring AI Alibaba 的 `DashScopeChatModel` 实现了 `StreamingChatModel` 接口。

### 9.3 升级路径

Spring AI **1.1.0+** 提供了 `ToolCallAdvisor`（递归 Advisor），可替代手动 Agent Loop。但 1.0.0 不可用。当前方案在 1.0.0 上手动实现，未来升级到 1.1.x 后可迁移到 Advisor 模式。

---

## 10. 测试要点

| 场景 | 验证点 |
|------|--------|
| 纯文本回复 | Agent Loop 退化为单轮，前端行为不变 |
| 单工具调用 | 前端显示 ToolCallCard，结果正确 |
| 多工具调用 | 多个 ToolCallCard 按序显示 |
| 工具执行失败 | ToolCallCard 显示错误状态 |
| maxTurns 达上限 | 返回 `[max turns reached]` |
| 流式实时性 | 每轮的文本 token 逐字出现 |
| 历史对话还原 | 下一次对话能还原 tool role 消息 |
| 超时 | 单轮超时正确触发错误 |
| WebSocket 重连 | 重连后新请求正常工作 |
