# 心跳系统 LLM 接入设计规格

## 背景

当前 `HeartbeatEngine` 在触发心跳时使用硬编码占位符文本回复用户（如"早上好！新的一天开始了。"），而 `AgentPromptJob` 已经有完整的 `AgentRuntime.dispatch()` LLM 集成。本次改动将心跳的占位符回复替换为真正的 LLM 调用，让 Agent 的主动消息更加自然和个性化。

## 范围

- **做**：将 `HeartbeatEngine.generatePlaceholderResponse()` 替换为通过 `AgentRuntime.dispatch()` 的 LLM 调用
- **不做**：不改动 UI、不合并心跳与定时任务系统、不修改 `AgentPromptJob`、不添加 token 预算限制、不修复前端 `heartbeat.message` 事件处理、不修复离线消息投递

## 设计

### 核心变更：HeartbeatEngine.java

#### 新增依赖

在构造函数中注入：

- `AgentRuntime` — 调用 LLM
- `AgentConfigService` — 通过 agent name 解析 Agent 配置
- `AgentRepository` — 通过 `agentId`（数字）查找 Agent entity 获取 name

#### 新增方法：generateLlmResponse()

```java
private Mono<String> generateLlmResponse(HeartbeatConfigEntity config,
                                          String prompt,
                                          List<AgentTaskEntity> tasks,
                                          LifecycleState state) {
    Long agentId = config.getAgentId();

    return agentRepository.findById(agentId)
        .flatMap(agentEntity -> {
            String agentName = agentEntity.getName();
            return agentConfigService.resolve(agentName);
        })
        .flatMap(resolved -> {
            AgentRunRequest request = new AgentRunRequest(
                System.currentTimeMillis(),   // sessionId
                "heartbeat",                  // userId
                resolved.agent(),
                prompt,                       // HeartbeatContextBuilder 生成的提示词
                Collections.emptyList(),      // 无历史消息
                null, null, null, null,       // 无 tool/skill 限制
                null,                         // 无 plan context
                false,                        // 不强制 plan
                null, null, null,
                resolved.bridgeNode()
            );

            AtomicReference<String> responseText = new AtomicReference<>("");

            return agentRuntime.dispatch(request)
                .doOnNext(event -> {
                    if (event instanceof AgentEvent.TextChunk chunk) {
                        responseText.updateAndGet(s -> s + chunk.text());
                    } else if (event instanceof AgentEvent.Done done) {
                        responseText.set(done.fullText());
                    }
                })
                .then(Mono.fromSupplier(responseText::get));
        })
        .timeout(Duration.ofSeconds(30))
        .onErrorResume(e -> {
            log.warn("LLM heartbeat call failed for agent {}, falling back to placeholder: {}",
                     agentId, e.getMessage());
            return Mono.just(generatePlaceholderResponse(state, tasks));
        });
}
```

#### 修改方法：executeBeat()

将 `generatePlaceholderResponse()` 调用替换为 `generateLlmResponse()`，改为响应式调用链：

```java
private Mono<Void> executeBeat(HeartbeatConfigEntity config, LifecycleState state, LocalDateTime now) {
    Long agentId = config.getAgentId();

    return taskRepo.findUpcomingTasks(agentId, now.plusHours(2))
        .collectList()
        .flatMap(tasks -> {
            String prompt = contextBuilder.buildPrompt(
                "Agent#" + agentId, state,
                config.getPersonalityPrompt(), tasks, now);

            return generateLlmResponse(config, prompt, tasks, state)
                .flatMap(response -> {
                    if (SILENT_MARKER.equals(response.trim())) {
                        log.debug("Heartbeat for agent {} decided to stay silent", agentId);
                        return saveLog(config, state, prompt, response).then();
                    }
                    return saveLog(config, state, prompt, response)
                        .then(deliver(config, response));
                });
        });
}
```

#### 保留方法：generatePlaceholderResponse()

作为降级回退保留，LLM 调用失败时使用。不删除此方法。

### Agent 名称解析

心跳配置存储 `agent_id`（数字），而 `AgentConfigService.resolve()` 需要 `agentName`（字符串）。解析链：

1. `agentRepository.findById(agentId)` → 获取 `AgentEntity`
2. `agentEntity.getName()` → 获取 agent 名称
3. `agentConfigService.resolve(agentName)` → 获取完整 Agent 配置（包含 model、systemPrompt 等）

如果 `findById` 返回空（Agent 被删除），直接跳过该 Agent 并记录 WARN 日志。

### WebSocket 推送名称修复

当前 `deliver()` 方法使用 `"Agent#" + numericId` 作为推送目标名称，可能与 `SessionRegistry` 中绑定的真实 agent 名称不匹配。

在 `executeBeat()` 中解析到 `agentName` 后，将其传入 `deliver()` 方法替代硬编码的 `"Agent#" + id`。

### 超时与降级

| 场景 | 处理方式 |
|------|---------|
| Agent 配置不存在（被删除） | 跳过该 agent，记 WARN 日志 |
| LLM 调用超时（30s） | `timeout(Duration.ofSeconds(30))` 后降级到占位符回复 |
| LLM 调用异常 | `onErrorResume` 降级到占位符回复 |
| LLM 返回空内容 | 视为 `[SILENT]`，仅记录日志 |

### 不启用 Memory Recall

心跳消息是轻量级的主动问候/提醒，不需要回忆历史记忆：

- `history` 传空列表
- 不调用 `MemorySystem`
- `HeartbeatContextBuilder` 已将任务信息嵌入 prompt，提供了足够的上下文

### HeartbeatContextBuilder 微调

当前 `buildPrompt()` 使用 `"Agent#" + agentId` 作为 agent 名称。改为接收解析后的真实 agent 名称，让 LLM 能正确感知自己的身份。

### 不变的部分

- `shouldTrigger()` 逻辑不变 — 现有频率控制已足够（每天最多 2-3 次 LLM 调用）
- `saveLog()` 不变 — 继续记录 prompt 和 response
- `deliver()` 投递机制不变 — 仍通过 WebSocket 推送或存入 offline_message
- `HeartbeatJob` 不变 — 仍通过 `ReactiveScheduleEngine` 每 60s tick
- 前端不变 — heartbeat.message 事件处理留待后续
- 其他定时任务不受影响

## 变更文件清单

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `intellimate-gateway/.../heartbeat/HeartbeatEngine.java` | 修改 | 注入 AgentRuntime/AgentConfigService/AgentRepository，新增 generateLlmResponse()，修改 executeBeat()，修复 deliver() 名称 |
| `intellimate-gateway/.../heartbeat/HeartbeatContextBuilder.java` | 修改 | buildPrompt() 接收真实 agent 名称替代 "Agent#id" |

## 验证策略

### 单元测试

为 `HeartbeatEngine` 编写测试，Mock AgentRuntime 和 AgentConfigService：

- 正常 LLM 回复：验证 LLM 返回的文本被存入 heartbeat_log 并投递
- `[SILENT]` 处理：验证 LLM 返回 `[SILENT]` 时仅记录不投递
- 超时降级：验证 LLM 超时后使用占位符回复
- Agent 不存在：验证跳过并记录 WARN

### 手动验证

1. 启用一个 Agent 的心跳配置
2. 通过 `POST /api/scheduled-jobs/heartbeat-tick/trigger` 手动触发
3. 查看 `heartbeat_log` 表中的 `response` 字段，确认为 LLM 生成的自然语言内容
