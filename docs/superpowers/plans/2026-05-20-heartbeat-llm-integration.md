# 心跳系统 LLM 接入实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 HeartbeatEngine 的占位符回复替换为通过 AgentRuntime.dispatch() 的 LLM 调用，实现 Agent 心跳消息的自然语言生成。

**架构：** 在 HeartbeatEngine 中注入 AgentRuntime、AgentConfigService、AgentRepository，新增 `generateLlmResponse()` 方法调用 LLM，保留 `generatePlaceholderResponse()` 作为降级回退。LLM 超时 30 秒，失败时自动降级到占位符。

**技术栈：** Spring WebFlux (R2DBC)、Reactor、Spring AI、AgentRuntime

**规格文档：** [`docs/superpowers/specs/2026-05-20-heartbeat-llm-integration-design.md`](../specs/2026-05-20-heartbeat-llm-integration-design.md)

---

## 文件清单

| 文件 | 操作 | 职责 |
|------|------|------|
| `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/heartbeat/HeartbeatEngine.java` | 修改 | 核心变更：注入 3 个新依赖，新增 LLM 调用方法，修改执行流程，修复 deliver 名称 |
| `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/heartbeat/HeartbeatContextBuilder.java` | 修改 | `buildPrompt()` 接收真实 agent 名称替代 "Agent#id" |
| `intellimate-gateway/src/test/java/com/atm/intellimate/gateway/heartbeat/HeartbeatEngineTest.java` | 创建 | HeartbeatEngine LLM 集成的单元测试 |

---

### 任务 1：HeartbeatContextBuilder 接收真实 agent 名称

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/heartbeat/HeartbeatContextBuilder.java:16-18`

- [ ] **步骤 1：修改 buildPrompt 签名和内部引用**

当前 `buildPrompt()` 第一个参数 `agentName` 由调用方传入 `"Agent#" + agentId`。此参数实际已经是 String 类型，无需改签名——只需确保调用方传入真实名称即可。但为了向后兼容，如果传入的名称以 `"Agent#"` 开头则保持原样（降级场景下可能仍用旧格式）。

验证：此步骤不需要独立代码变更。`buildPrompt()` 签名不变，改动在调用方（任务 3）。

- [ ] **步骤 2：Commit**

```bash
# 此任务可能不产生独立 commit，与任务 3 合并提交
```

---

### 任务 2：编写 HeartbeatEngine 单元测试

**文件：**
- 创建：`intellimate-gateway/src/test/java/com/atm/intellimate/gateway/heartbeat/HeartbeatEngineTest.java`

- [ ] **步骤 1：编写测试骨架和 Mock 依赖**

```java
package com.atm.intellimate.gateway.heartbeat;

import com.atm.intellimate.agent.runtime.AgentEvent;
import com.atm.intellimate.agent.runtime.AgentRunRequest;
import com.atm.intellimate.agent.runtime.AgentRuntime;
import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.gateway.config.AgentConfigService;
import com.atm.intellimate.gateway.config.ResolvedAgentConfig;
import com.atm.intellimate.gateway.entity.AgentEntity;
import com.atm.intellimate.gateway.entity.HeartbeatConfigEntity;
import com.atm.intellimate.gateway.entity.HeartbeatLogEntity;
import com.atm.intellimate.gateway.repository.*;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HeartbeatEngineTest {

    @Mock private HeartbeatLogRepository logRepo;
    @Mock private AgentTaskRepository taskRepo;
    @Mock private OfflineMessageRepository offlineMsgRepo;
    @Mock private SessionRegistry sessionRegistry;
    @Mock private HeartbeatContextBuilder contextBuilder;
    @Mock private AgentRuntime agentRuntime;
    @Mock private AgentConfigService agentConfigService;
    @Mock private AgentRepository agentRepository;

    private HeartbeatEngine engine;

    @BeforeEach
    void setUp() {
        engine = new HeartbeatEngine(
                logRepo, taskRepo, offlineMsgRepo, sessionRegistry,
                contextBuilder, agentRuntime, agentConfigService, agentRepository);
    }

    private HeartbeatConfigEntity wakingConfig() {
        HeartbeatConfigEntity config = new HeartbeatConfigEntity();
        config.setAgentId(1L);
        config.setEnabled(1);
        config.setTimezone("Asia/Shanghai");
        config.setWakeTime("06:00");
        config.setSleepTime("23:00");
        config.setHeartbeatIntervalMinutes(60);
        config.setPersonalityPrompt("你是一个温暖的伙伴");
        return config;
    }
}
```

- [ ] **步骤 2：编写测试 — LLM 正常回复**

```java
@Test
void processHeartbeat_llmRespondsNormally_shouldDeliverLlmResponse() {
    HeartbeatConfigEntity config = wakingConfig();

    // Agent 配置解析
    AgentEntity agentEntity = new AgentEntity();
    agentEntity.setId(1L);
    agentEntity.setName("小助手");
    when(agentRepository.findById(1L)).thenReturn(Mono.just(agentEntity));

    IntelliMateProperties.Agent agentProps = new IntelliMateProperties.Agent();
    agentProps.setName("小助手");
    ResolvedAgentConfig resolved = new ResolvedAgentConfig(agentProps, null, null, null, null);
    when(agentConfigService.resolve("小助手")).thenReturn(Mono.just(resolved));

    // 触发条件：WAKING，今天未发送过
    when(logRepo.findTodayByAgentIdAndState(1L, "WAKING")).thenReturn(Mono.empty());

    // 无待办任务
    when(taskRepo.findUpcomingTasks(eq(1L), any())).thenReturn(Flux.empty());

    // 构建 prompt
    when(contextBuilder.buildPrompt(eq("小助手"), eq(LifecycleState.WAKING), any(), any(), any()))
            .thenReturn("你是小助手...");

    // LLM 返回正常回复
    when(agentRuntime.dispatch(any(AgentRunRequest.class)))
            .thenReturn(Flux.just(new AgentEvent.Done("早安！今天天气不错，祝你有愉快的一天！")));

    // 保存日志
    when(logRepo.save(any(HeartbeatLogEntity.class))).thenReturn(Mono.just(new HeartbeatLogEntity()));

    // WebSocket 推送成功
    when(sessionRegistry.pushToAgent(eq("小助手"), eq("heartbeat.message"), any())).thenReturn(true);

    StepVerifier.create(engine.processHeartbeat(config))
            .verifyComplete();

    // 验证 LLM 被调用
    verify(agentRuntime).dispatch(any(AgentRunRequest.class));
    // 验证日志中记录了 LLM 回复
    verify(logRepo).save(argThat(log -> log.getResponse().contains("早安")));
    // 验证 WebSocket 推送
    verify(sessionRegistry).pushToAgent(eq("小助手"), eq("heartbeat.message"), any());
}
```

注意：此测试依赖时间（WAKING 状态需要当前时间在 06:00-07:00 之间）。实际编写时需要提取时间依赖为可注入的 `Clock`，或者直接 Mock `LifecycleState.compute()` 调用。更务实的做法是在 `processHeartbeat` 内部将时间计算结果暴露为可测试的接口。**实现时需要评估最佳方式。**

- [ ] **步骤 3：编写测试 — LLM 返回 SILENT**

```java
@Test
void processHeartbeat_llmReturnsSilent_shouldLogButNotDeliver() {
    // 与上方类似的 mock 设置...
    // LLM 返回 [SILENT]
    when(agentRuntime.dispatch(any(AgentRunRequest.class)))
            .thenReturn(Flux.just(new AgentEvent.Done("[SILENT]")));

    when(logRepo.save(any(HeartbeatLogEntity.class))).thenReturn(Mono.just(new HeartbeatLogEntity()));

    StepVerifier.create(engine.processHeartbeat(config))
            .verifyComplete();

    // 验证日志被记录
    verify(logRepo).save(any());
    // 验证 WebSocket 未被调用
    verify(sessionRegistry, never()).pushToAgent(any(), any(), any());
}
```

- [ ] **步骤 4：编写测试 — LLM 超时降级到占位符**

```java
@Test
void processHeartbeat_llmTimesOut_shouldFallbackToPlaceholder() {
    // 与上方类似的 mock 设置...
    // LLM 超时
    when(agentRuntime.dispatch(any(AgentRunRequest.class)))
            .thenReturn(Flux.never()); // 永不完成，触发 timeout

    when(logRepo.save(any(HeartbeatLogEntity.class))).thenReturn(Mono.just(new HeartbeatLogEntity()));
    when(sessionRegistry.pushToAgent(any(), any(), any())).thenReturn(true);

    StepVerifier.create(engine.processHeartbeat(config))
            .verifyComplete();

    // 验证使用了占位符回复
    verify(logRepo).save(argThat(log -> log.getResponse().contains("早上好")));
}
```

- [ ] **步骤 5：编写测试 — Agent 配置不存在**

```java
@Test
void processHeartbeat_agentNotFound_shouldSkipWithWarning() {
    HeartbeatConfigEntity config = wakingConfig();

    when(logRepo.findTodayByAgentIdAndState(1L, "WAKING")).thenReturn(Mono.empty());
    when(taskRepo.findUpcomingTasks(eq(1L), any())).thenReturn(Flux.empty());
    when(contextBuilder.buildPrompt(any(), any(), any(), any(), any())).thenReturn("prompt");

    // Agent 不存在
    when(agentRepository.findById(1L)).thenReturn(Mono.empty());

    when(logRepo.save(any(HeartbeatLogEntity.class))).thenReturn(Mono.just(new HeartbeatLogEntity()));
    when(sessionRegistry.pushToAgent(any(), any(), any())).thenReturn(true);

    StepVerifier.create(engine.processHeartbeat(config))
            .verifyComplete();

    // 验证降级到占位符
    verify(agentRuntime, never()).dispatch(any());
    verify(logRepo).save(argThat(log -> log.getResponse().contains("早上好")));
}
```

- [ ] **步骤 6：运行测试确认全部失败**

运行：

```bash
cd intellimate-gateway
mvn test -pl . -Dtest=HeartbeatEngineTest -Dsurefire.useFile=false
```

预期：全部 FAIL（HeartbeatEngine 构造函数签名不匹配，因为还没添加新的依赖注入）

- [ ] **步骤 7：Commit 测试文件**

```bash
git add intellimate-gateway/src/test/java/com/atm/intellimate/gateway/heartbeat/HeartbeatEngineTest.java
git commit -m "test: add HeartbeatEngine LLM integration tests (failing)"
```

---

### 任务 3：HeartbeatEngine 注入 AgentRuntime 并实现 LLM 调用

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/heartbeat/HeartbeatEngine.java`

- [ ] **步骤 1：添加新的依赖注入**

在构造函数中添加 `AgentRuntime`、`AgentConfigService`、`AgentRepository`：

```java
import com.atm.intellimate.agent.runtime.AgentEvent;
import com.atm.intellimate.agent.runtime.AgentRunRequest;
import com.atm.intellimate.agent.runtime.AgentRuntime;
import com.atm.intellimate.gateway.config.AgentConfigService;
import com.atm.intellimate.gateway.repository.AgentRepository;
// ... 现有 import

@Service
public class HeartbeatEngine {

    // ... 现有字段
    private final AgentRuntime agentRuntime;
    private final AgentConfigService agentConfigService;
    private final AgentRepository agentRepository;

    public HeartbeatEngine(HeartbeatLogRepository logRepo,
                           AgentTaskRepository taskRepo,
                           OfflineMessageRepository offlineMsgRepo,
                           SessionRegistry sessionRegistry,
                           HeartbeatContextBuilder contextBuilder,
                           AgentRuntime agentRuntime,
                           AgentConfigService agentConfigService,
                           AgentRepository agentRepository) {
        // ... 现有赋值
        this.agentRuntime = agentRuntime;
        this.agentConfigService = agentConfigService;
        this.agentRepository = agentRepository;
    }
```

- [ ] **步骤 2：新增 generateLlmResponse() 方法**

在 `generatePlaceholderResponse()` 之后添加：

```java
private Mono<String> generateLlmResponse(HeartbeatConfigEntity config,
                                          String prompt,
                                          List<AgentTaskEntity> tasks,
                                          LifecycleState state) {
    Long agentId = config.getAgentId();

    return agentRepository.findById(agentId)
            .switchIfEmpty(Mono.defer(() -> {
                log.warn("Agent {} not found, skipping LLM heartbeat", agentId);
                return Mono.empty();
            }))
            .flatMap(agentEntity -> agentConfigService.resolve(agentEntity.getName()))
            .flatMap(resolved -> {
                AgentRunRequest request = new AgentRunRequest(
                        System.currentTimeMillis(),
                        "heartbeat",
                        resolved.agent(),
                        prompt,
                        java.util.Collections.emptyList(),
                        null, null, null, null, null,
                        false, null, null, null,
                        resolved.bridgeNode()
                );

                java.util.concurrent.atomic.AtomicReference<String> responseText =
                        new java.util.concurrent.atomic.AtomicReference<>("");

                return agentRuntime.dispatch(request)
                        .doOnNext(event -> {
                            if (event instanceof AgentEvent.TextChunk chunk) {
                                responseText.updateAndGet(s -> s + chunk.text());
                            } else if (event instanceof AgentEvent.Done done) {
                                responseText.set(done.fullText());
                            }
                        })
                        .then(Mono.fromSupplier(() -> {
                            String text = responseText.get();
                            return text.isBlank() ? SILENT_MARKER : text;
                        }));
            })
            .timeout(Duration.ofSeconds(30))
            .switchIfEmpty(Mono.fromSupplier(() -> generatePlaceholderResponse(state, tasks)))
            .onErrorResume(e -> {
                log.warn("LLM heartbeat failed for agent {}, using placeholder: {}",
                         agentId, e.getMessage());
                return Mono.just(generatePlaceholderResponse(state, tasks));
            });
}
```

- [ ] **步骤 3：修改 executeBeat() 使用 LLM 调用**

替换 `executeBeat()` 方法：

```java
private Mono<Void> executeBeat(HeartbeatConfigEntity config, LifecycleState state, LocalDateTime now) {
    Long agentId = config.getAgentId();

    return taskRepo.findUpcomingTasks(agentId, now.plusHours(2))
            .collectList()
            .flatMap(tasks -> agentRepository.findById(agentId)
                    .map(entity -> entity.getName())
                    .defaultIfEmpty("Agent#" + agentId)
                    .flatMap(agentName -> {
                        String prompt = contextBuilder.buildPrompt(
                                agentName, state,
                                config.getPersonalityPrompt(), tasks, now);

                        return generateLlmResponse(config, prompt, tasks, state)
                                .flatMap(response -> {
                                    if (SILENT_MARKER.equals(response.trim())) {
                                        log.debug("Heartbeat for agent {} decided to stay silent", agentId);
                                        return saveLog(config, state, prompt, response).then();
                                    }
                                    return saveLog(config, state, prompt, response)
                                            .then(deliver(config, agentName, response));
                                });
                    }));
}
```

- [ ] **步骤 4：修改 deliver() 接收 agentName 参数**

```java
private Mono<Void> deliver(HeartbeatConfigEntity config, String agentName, String response) {
    boolean delivered = sessionRegistry.pushToAgent(agentName, "heartbeat.message",
            Map.of("content", response, "agentId", config.getAgentId()));

    if (delivered) {
        log.info("Heartbeat message delivered to agent {} via WebSocket", agentName);
        return Mono.empty();
    }

    log.info("Agent {} offline, caching heartbeat message", agentName);
    OfflineMessageEntity msg = new OfflineMessageEntity();
    msg.setAgentId(config.getAgentId());
    msg.setContent(response);
    msg.setMessageType("heartbeat");
    msg.setCreatedAt(LocalDateTime.now());
    msg.setDelivered(0);
    return offlineMsgRepo.save(msg).then();
}
```

- [ ] **步骤 5：运行测试验证通过**

```bash
cd intellimate-gateway
mvn test -pl . -Dtest=HeartbeatEngineTest -Dsurefire.useFile=false
```

预期：全部 PASS

- [ ] **步骤 6：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/heartbeat/HeartbeatEngine.java
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/heartbeat/HeartbeatContextBuilder.java
git commit -m "feat: integrate AgentRuntime into HeartbeatEngine for LLM-powered heartbeat messages"
```

---

### 任务 4：集成验证

**文件：** 无新文件

- [ ] **步骤 1：编译整个项目**

```bash
mvn compile -DskipTests
```

预期：BUILD SUCCESS，无编译错误

- [ ] **步骤 2：运行全部相关测试**

```bash
mvn test -pl intellimate-gateway -Dsurefire.useFile=false
```

预期：所有测试通过，包括 HeartbeatEngineTest 和既有测试

- [ ] **步骤 3：手动验证（可选）**

1. 启动应用：`mvn spring-boot:run -pl intellimate-gateway`
2. 确保有一个 Agent 已创建且心跳已启用
3. 手动触发心跳：`curl -X POST http://localhost:8080/api/scheduled-jobs/heartbeat-tick/trigger`
4. 查询心跳日志：`curl http://localhost:8080/api/heartbeat/{agentId}/logs?limit=1`
5. 验证 `response` 字段包含 LLM 生成的自然语言内容

- [ ] **步骤 4：最终 Commit**

```bash
git add -A
git commit -m "chore: integration verification for heartbeat LLM feature"
```
