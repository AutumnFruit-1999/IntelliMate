# Agent 工具循环优化 — Changelog

> 日期: 2026-03-13
> 前置文档: [AgentRuntime工具循环优化方案.md](AgentRuntime工具循环优化方案.md), [工具循环优化_QA与补充.md](工具循环优化_QA与补充.md)

---

## 新增文件 (6)

| 文件 | 模块 | 描述 |
|------|------|------|
| `ToolCallLoopDetector.java` | agent/runtime | 滑动窗口工具调用循环检测器，支持 OK/WARN/TERMINATE 渐进式响应和白名单机制 |
| `ContextWindowTracker.java` | agent/runtime | Token 追踪器，API Usage 精确值优先 + 字符估算增量兜底 |
| `ContextCondenser.java` | agent/runtime | 上下文压缩器，70%阈值触发，仅压缩旧 ToolResponseMessage 至摘要长度 |
| `ToolResultCache.java` | agent/runtime | Per-run LRU 缓存（50条），只缓存读操作，exec/writeFile 触发缓存失效 |
| `ToolApprovalGate.java` | agent/runtime | 人工审批门控，可配置审批工具列表，Sinks.one() 无限等待用户响应 |
| `ToolExecutionResult.java` | agent/runtime | 工具执行结果 record (id, name, result, success) |

---

## 修改文件 (7)

### AgentRuntime.java (核心重构)

- **System.out.println 移除**: L140 的 `System.out.println` 替换为 `log.trace`
- **工具结果截断**: 新增 `truncateToolResult()` 静态方法，在 `doExecuteTool()` 中对工具结果进行 Runtime 级兜底截断
- **循环检测集成**: `executeSingleTool()` 中调用 `loopDetector.check()`，TERMINATE 时不执行工具并返回终止提示，WARN 时追加警告到结果
- **Token 追踪集成**: `executeLoopTurn()` streaming 完成后从 `ChatResponse.getMetadata().getUsage()` 获取精确 token 数；`processToolCalls()` 中工具结果加入时调用 `tracker.addToolResultChars()`
- **上下文溢出保护**: `executeLoopTurn()` 开头检查 `tracker.isOverLimit()`，超限时强制 Done
- **上下文压缩集成**: `executeLoopTurn()` 中检查 `condenser.shouldCondense(turn, tracker)`，触发时压缩并重新计算 token
- **并行执行**: `processToolCalls()` 中将 `concatMap` 改为 `flatMap(..., maxParallel)` 并行执行非审批工具
- **统一超时**: `doExecuteTool()` 中对工具执行 Mono 加 `.timeout(toolTimeout)`
- **重试策略**: `doExecuteTool()` 中对非 nonRetryableTools 使用 `retryWhen(Retry.backoff(2, 1s))`
- **缓存集成**: `doExecuteTool()` 中执行前检查 `cache.get()`，执行后调用 `cache.put()` 和 `cache.invalidateForWrite()`
- **审批分流**: `processToolCalls()` 中将审批工具和直接工具分开处理，审批工具通过 `ToolApprovalGate.requestApproval()` 等待用户响应
- **审批响应通道**: per-session `ConcurrentMap<Long, ToolApprovalGate>` 管理审批门控，`resolveApproval()` 公共方法供 MessagePipeline 调用
- **System Prompt 增强**: `buildSystemPrompt()` 末尾新增 TOOL USAGE GUIDELINES 段落

### AgentEvent.java

- 新增 `ToolCall(toolCallId, name, arguments, turn)` — 增加 `turn` 字段
- 新增 `ToolResult(toolCallId, name, result, success, turn)` — 增加 `turn` 字段
- 新增 `ApprovalRequired(toolCallId, toolName, arguments)` record
- 新增 `ApprovalResponse(toolCallId, approved, modifiedArguments)` record

### FileReadTool.java

- 新增 `MAX_LINES_PER_READ = 500` 常量
- 无分页参数且文件 > 500 行时自动分页（返回前 500 行 + 分页提示）
- 更新 `@Tool` description，告知 LLM 大文件分页能力和 `startLine`/`lineCount` 参数

### ExecTool.java

- 新增 `MAX_OUTPUT_CHARS = 8_000` 常量
- 输出超限时头尾保留截断（各保留 4000 字符）
- 更新 `@Tool` description，告知 LLM 截断行为

### IntelliMateProperties.java

Agent 类新增 14 个配置字段：

| 字段 | 默认值 | 用途 |
|------|--------|------|
| `maxToolResultChars` | 16000 | Runtime 级工具结果截断上限 |
| `maxContextTokens` | 128000 | 上下文窗口 token 上限 |
| `toolExecutionTimeoutSeconds` | 60 | 工具执行统一超时 |
| `maxParallelToolCalls` | 8 | 并行执行最大并发度 |
| `historyLimit` | 50 | 历史消息查询条数 |
| `loopDetectorWindowSize` | 8 | 循环检测滑动窗口大小 |
| `loopDetectorWarnThreshold` | 3 | 循环检测警告阈值 |
| `loopDetectorTerminateThreshold` | 5 | 循环检测终止阈值 |
| `loopDetectorExcludedTools` | [] | 循环检测白名单 |
| `nonRetryableTools` | [] | 不重试工具列表 |
| `approvalRequiredTools` | [] | 需审批工具列表 |
| `condenserKeepRecent` | 20 | 压缩时保留最近消息数 |
| `condenserSummaryLength` | 200 | 压缩后摘要字符数 |
| `condenserMinTurnsBetween` | 5 | 最小压缩间隔 turn 数 |

### MessagePipeline.java

- `getHistory()` 硬编码 50 改为 `properties.getAgent().getHistoryLimit()`
- `mapAgentEvent()` 新增 `agent.approval_required` EventFrame 映射
- `mapAgentEvent()` 中 `agent.tool_call` 和 `agent.tool_result` 新增 `turn` 字段
- 新增 `processApprovalResponse()` 方法处理前端审批结果
- `processRequest()` 新增 `conversation.approve_tool` method 路由

### GatewayWebSocketHandler.java

- `handleRequest()` 新增 `conversation.approve_tool` 请求路由到 `messagePipeline.processApprovalResponse()`

### application.yml

- `intellimate.agent` 下新增全部 14 个新配置项及默认值

---

## 功能完成状态

### P0 — 安全与稳定性 (全部完成)

| # | 功能 | 状态 |
|---|------|------|
| 1 | 工具结果截断（两层：工具级智能截断 + Runtime 兜底） | 已完成 |
| 2 | 工具循环检测（滑动窗口 + WARN/TERMINATE + 白名单） | 已完成 |
| 3 | Token 追踪（API Usage 优先 + 字符估算兜底） | 已完成 |
| 4 | 工具执行统一超时 | 已完成 |
| 5 | System.out.println 修复 | 已完成 |
| 6 | history limit 配置化 | 已完成 |
| 7 | 全部配置字段 + application.yml | 已完成 |

### P1 — 性能与体验 (全部完成)

| # | 功能 | 状态 |
|---|------|------|
| 1 | 并行工具执行（flatMap + maxParallel） | 已完成 |
| 2 | 工具重试策略（retryWhen + isRetryableError） | 已完成 |
| 3 | 工具结果持久化 (Q6) | 按要求跳过 |

### P2 — 高级能力 (全部完成)

| # | 功能 | 状态 |
|---|------|------|
| 1 | 上下文压缩（ContextCondenser，参数可配置） | 已完成 |
| 2 | 工具结果缓存（ToolResultCache） | 已完成 |
| 3 | 人工审批（ToolApprovalGate + 审批响应通道） | 已完成 |

### QA 修正项 (全部完成)

| # | 修正 | 状态 |
|---|------|------|
| Q2 | 两层截断体系 | 已完成 |
| Q3 | 循环检测白名单 | 已完成 |
| Q4 | API Usage 优先 | 已完成 |
| Q5 | EventFrame turn 字段 | 已完成 |
| Q7 | nonRetryableTools 配置 | 已完成 |
| Q8 | 上下文压缩防频繁 + 参数可配置 | 已完成 |
| Q9 | 可配置审批列表 + 无超时 + 审批响应通道 | 已完成 |
