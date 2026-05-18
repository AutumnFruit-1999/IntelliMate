# 模型执行流程文档

## 1. 执行模式总结

当前 IntelliMate 的模型执行是**单轮一问一答 + 流式输出**模式：

- 用户发送一条消息 → LLM 返回一条完整回复
- 回复通过 **流式 token** 实时推送到前端（非等待完成后一次性返回）
- 每轮对话携带**最近 50 条历史消息**作为上下文
- 同一会话内的请求**串行执行**（FIFO 队列），不会并发调用 LLM
- 支持 **Tool Calling**：LLM 可以在回复过程中调用工具，Spring AI 框架自动处理工具调用循环

**不是**多轮自主 Agent Loop（即 LLM 不会自发发起多轮思考或连续调用多个工具再汇总）。Spring AI 的 `ChatClient.stream()` 内部会自动处理 tool_call → tool_result → continue 的循环，但对外表现为单次请求的流式输出。

---

## 2. 完整执行链路

```
前端 (React)                  WebSocket                后端 (Spring WebFlux)
─────────────                 ─────────                ────────────────────

用户输入"你好"
    │
    ▼
useWebSocket.sendMessage()
    │ 构建 RequestFrame
    │ {type:"request", method:"conversation.message",
    │  params:{text:"你好", agentName:"intellimate"}}
    │
    ├─── WebSocket ──────────────────► GatewayWebSocketHandler
    │                                       │
    │                                       ▼
    │                                 MessagePipeline.processRequest()
    │                                       │
    │                                       ├─ 1. 解析参数 + 会话隔离
    │                                       │    contextId = wsSessionId::agentName
    │                                       │
    │                                       ├─ 2. SessionManager.getOrCreate()
    │                                       │    创建或复用会话
    │                                       │
    │                                       ├─ 3. 持久化用户消息到 transcript_message
    │                                       │
    │                                       ├─ 4. 加载最近 50 条历史消息
    │                                       │
    │                                       ├─ 5. AgentConfigService.resolve()
    │                                       │    解析 agent 配置 (model, SOUL, tools等)
    │                                       │
    │                                       ▼
    │                                 AgentRuntime.dispatch()
    │                                       │
    │                                       ├─ 6. RunQueueManager.enqueue()
    │                                       │    同一会话串行，不同会话并行
    │                                       │
    │                                       ▼
    │                                 AgentRuntime.executeRun()
    │                                       │
    │                                       ├─ 7. buildSystemPrompt()
    │                                       │    组装: SOUL + USER + AGENTS + Instructions
    │                                       │
    │                                       ├─ 8. ChatClient.prompt()
    │                                       │    .system(systemPrompt)
    │                                       │    .messages(history)     ← 50条历史
    │                                       │    .user(userMessage)     ← 本次输入
    │                                       │    .toolCallbacks(tools)  ← 可用工具
    │                                       │    .stream()             ← 流式调用
    │                                       │    .content()            ← 只取文本
    │                                       │
    │                                       │         ┌───────────────────┐
    │                                       │         │   DashScope API   │
    │                                       │         │   (qwen-plus等)   │
    │                                       │         └───────┬───────────┘
    │                                       │                 │
    │                                       │    token1, token2, token3...
    │                                       │                 │
    │                                       ▼                 ▼
    │                                 Flux<String> 逐 token 发射
    │                                       │
    │                   ┌───────────────────┤
    │                   │                   │
    │              每个 token:              全部 token 结束后:
    │              EventFrame              │
    │              "agent.chunk"           ├─ 9. 持久化 assistant 消息
    │              {text:"你", reqId}       ├─ 10. 审计日志
    │                   │                  ├─ EventFrame "agent.done"
    │                   │                  └─ ResponseFrame success
    │                   │                         │
    ◄── WebSocket ──────┘─────────────────────────┘
    │
    ▼
前端接收事件:
    agent.chunk → appendChunk() → 实时渲染 token
    agent.chunk → appendChunk()
    agent.chunk → appendChunk()
    ...
    agent.done  → finishStreaming() → 标记完成
    response    → addResponse() → 解锁输入框
```

---

## 3. 核心代码走读

### 3.1 前端：发送消息

```typescript
// useWebSocket.ts — sendMessage
const sendMessage = useCallback((text: string) => {
  const client = clientRef.current;
  if (!client) return;

  const store = useChatStore.getState();
  if (store.isWaiting) return;  // 防止并发请求

  const agentName = useAgentStore.getState().activeAgent ?? "";

  // 构建 WebSocket 请求帧
  const req = createRequest("conversation.message", {
    text,
    channelId: "webchat",
    contextType: "dm",
    agentName,  // 指定智能体
  });

  store.addUserMessage(text, req.id);  // 立即显示用户消息
  client.send(req);                    // 发送到后端

  // 60秒超时保护
  const timer = setTimeout(() => {
    useChatStore.getState().timeoutRequest(req.id);
  }, REQUEST_TIMEOUT_MS);
  timeoutTimers.current.set(req.id, timer);
}, []);
```

### 3.2 前端：接收流式响应

```typescript
// useWebSocket.ts — onEvent 回调
onEvent: (event: EventFrame) => {
  const store = useChatStore.getState();
  switch (event.event) {
    case "agent.chunk":
      // 每个 token 实时追加到消息气泡
      store.appendChunk(
        event.payload.requestId as string,
        event.payload.text as string,
      );
      break;
    case "agent.done":
      // 流式完成，替换为完整文本
      store.finishStreaming(requestId, event.payload.text as string);
      clearRequestTimeout(requestId);
      break;
  }
},
```

### 3.3 后端：消息管道（编排层）

```java
// MessagePipeline.processMessageStreaming() — 核心流程
private Flux<GatewayFrame> processMessageStreaming(
    SessionEntity session, String userText, String requestId, String wsSessionId) {

  // 1. 持久化用户消息
  TranscriptMessageEntity userMsg = new TranscriptMessageEntity();
  userMsg.setRole("user");
  userMsg.setContent(userText);

  return sessionManager.appendMessage(session.getId(), userMsg)
      // 2. 审计日志
      .then(auditService.log("user_message", wsSessionId, session.getId(), userText))
      // 3. 并行加载：历史消息 + Agent 配置
      .then(Mono.zip(
          sessionManager.getHistory(session.getId(), 50).collectList(),
          agentConfigService.resolve(session.getAgentName())
      ))
      .flatMapMany(tuple -> {
          List<Message> messages = convertToAiMessages(tuple.getT1());
          ResolvedAgentConfig resolved = tuple.getT2();

          // 4. 构建 AgentRunRequest
          AgentRunRequest runRequest = new AgentRunRequest(
              session.getId(),
              resolved.agent(),       // agent 配置 (model, SOUL, etc.)
              userText,               // 用户输入
              messages,               // 历史消息 (最近50条)
              resolved.toolsEnabled() // 工具权限
          );

          StringBuilder fullResponse = new StringBuilder();

          // 5. 流式 token → EventFrame("agent.chunk")
          Flux<GatewayFrame> chunks = agentRuntime.dispatch(runRequest)
              .doOnNext(fullResponse::append)
              .map(token -> new EventFrame(
                  "agent.chunk",
                  Map.of("text", token, "requestId", requestId),
                  seqGenerator.incrementAndGet()
              ));

          // 6. 流结束后 → 持久化 + done + response
          Flux<GatewayFrame> tail = Flux.defer(() -> {
              String completeText = fullResponse.toString();

              TranscriptMessageEntity assistantMsg = new TranscriptMessageEntity();
              assistantMsg.setRole("assistant");
              assistantMsg.setContent(completeText);

              return sessionManager.appendMessage(session.getId(), assistantMsg)
                  .thenMany(Flux.just(
                      new EventFrame("agent.done", Map.of("text", completeText, "requestId", requestId), ...),
                      ResponseFrame.success(requestId, Map.of("text", completeText))
                  ));
          });

          return Flux.concat(chunks, tail);
      });
}
```

### 3.4 后端：Agent 运行时（LLM 调用层）

```java
// AgentRuntime.executeRun() — 实际调用 LLM
private Flux<String> executeRun(AgentRunRequest request) {
    // 1. 组装 System Prompt（SOUL + USER + AGENTS + Instructions）
    String systemPrompt = buildSystemPrompt(request.agent());

    // 2. 获取该 agent 允许使用的工具
    ToolCallback[] tools = toolsEngine.getToolCallbacksFor(request.toolsEnabled());

    // 3. 调用 LLM（流式）
    return chatClient.prompt()
        .system(systemPrompt)          // 系统提示词
        .messages(request.history())   // 历史对话
        .user(request.userMessage())   // 用户本次输入
        .toolCallbacks(tools)          // 可调用的工具
        .stream()                      // 流式调用 DashScope API
        .content()                     // 只取文本 token
        .timeout(Duration.ofSeconds(request.agent().getTimeoutSeconds()));
}
```

### 3.5 后端：运行队列（并发控制层）

```java
// RunQueueManager.enqueue() — 同一会话串行执行
public synchronized Flux<String> enqueue(Long sessionId, Supplier<Flux<String>> runSupplier) {
    // 获取该会话的上一个任务（可能还在执行）
    Mono<Void> previous = sessionChains.getOrDefault(sessionId, Mono.empty());

    // 创建 replay sink 用于向外发射 token
    Sinks.Many<String> replaySink = Sinks.many().replay().all();

    // 等上一个任务完成后，再执行本次任务
    Mono<Void> run = previous
        .onErrorComplete()
        .then(Mono.defer(() -> {
            return runSupplier.get()
                .doOnNext(token -> replaySink.tryEmitNext(token))   // 转发 token
                .doOnComplete(() -> replaySink.tryEmitComplete())
                .doOnError(e -> replaySink.tryEmitError(e))
                .then();
        }));

    sessionChains.put(sessionId, run.cache());
    run.cache().subscribe();  // 触发执行

    return replaySink.asFlux();  // 返回 token 流
}
```

---

## 4. Tool Calling 机制

Spring AI 的 `ChatClient.stream()` 内部自动处理 Tool Calling 循环：

```
用户消息 → LLM
               ├─ 直接回复文本 → token 流输出
               └─ 返回 tool_call → Spring AI 自动执行:
                   1. 调用对应 ToolCallback
                   2. 将 tool_result 追加到上下文
                   3. 重新调用 LLM（携带 tool_result）
                   4. LLM 继续回复 → token 流输出
                   （可能多轮 tool_call 循环）
```

这个循环对 `AgentRuntime` 是透明的 —— `stream().content()` 只输出最终的文本 token，工具调用过程被 Spring AI 框架内部消化。

### 当前注册的工具

| 工具名 | 功能 | 文件 |
|--------|------|------|
| exec | 执行 shell 命令 | `ExecTool.java` |
| readFile | 读取文件内容 | `FileReadTool.java` |
| writeFile | 写入文件 | `FileWriteTool.java` |
| editFile | 编辑文件 | `FileEditTool.java` |
| webSearch | 网页搜索 | `WebSearchTool.java` |
| webFetch | 抓取网页内容 | `WebFetchTool.java` |

---

## 5. 数据持久化

每轮对话会持久化两条 `transcript_message` 记录：

| 顺序 | role | 时机 | 内容 |
|------|------|------|------|
| 1 | user | LLM 调用**前** | 用户输入原文 |
| 2 | assistant | LLM 调用**后**（流式完成） | LLM 完整回复 |

历史消息加载策略：`SELECT ... WHERE session_id = ? ORDER BY created_at DESC LIMIT 50`

---

## 6. 超时与错误处理

| 层级 | 机制 | 时长 |
|------|------|------|
| 前端 | `setTimeout` + `timeoutRequest()` | 60 秒 |
| 后端 Agent | `Flux.timeout()` | agent.timeoutSeconds（默认 300 秒） |
| 后端 Pipeline | `onErrorResume` | 捕获异常返回 `ResponseFrame.failure` |

---

## 7. 与 OpenClaw 的对比

| 方面 | OpenClaw | IntelliMate (当前) |
|------|----------|-----------------|
| 执行模式 | Agent Loop（多轮自主思考） | 单轮一问一答 + Tool Calling |
| 流式输出 | 支持 | 支持 |
| Tool Calling | 自定义 Pi Agent Loop | Spring AI 内置循环 |
| 工具执行可见性 | 前端可看到每个 tool_call | 工具调用对前端透明（只看到最终文本） |
| 多轮思考 | 支持 thinking/reasoning | 依赖模型能力（qwen 支持） |
| 运行队列 | 每会话串行 + 全局队列 | 每会话串行 |
| 历史窗口 | 动态 | 固定最近 50 条 |
