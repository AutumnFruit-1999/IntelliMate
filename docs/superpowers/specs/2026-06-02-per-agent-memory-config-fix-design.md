# Per-Agent 记忆配置运行时生效修复

**日期**: 2026-06-02
**状态**: Draft
**优先级**: P1（功能性 Bug）

## 1. 问题描述

用户通过 UI 为特定 Agent（如 GroupChat）单独配置了记忆功能（`long_term.enabled=true`），
配置正确写入了 `memory_config` 表（`agent_name='GroupChat'`），但 Agent 执行时
**并未读取 per-agent 配置**，始终读取 `_global_` 全局配置。

由于全局默认 `long_term.enabled=false`，导致记忆检索分支被跳过，Agent 无法使用长期记忆。

## 2. 根因分析

### 2.1 数据流断裂点

```
┌──────────────────────┐     ✅ 按 agentName 写入
│   UI 保存记忆配置      │──────────────────────────→ memory_config 表
│  (memoryStore.ts)    │                             agent_name = 'GroupChat'
└──────────────────────┘                             long_term.enabled = true

┌──────────────────────┐     ❌ 仅读 _global_
│  AgentMemoryLifecycle │──────────────────────────→ memory_config 表
│  loadMemoryInit...() │                             agent_name = '_global_'
└──────────────────────┘                             long_term.enabled = false
                                                     ↓
                                                  ltEnabled = false
                                                  记忆检索被跳过
```

### 2.2 具体代码问题

**问题 1: 接口缺失方法**

`MemoryConfigProvider` 接口仅定义了 `resolve()` 方法（全局配置），
没有 `resolveForAgent(agentName)` 方法：

```java
// intellimate-memory/.../config/MemoryConfigProvider.java
public interface MemoryConfigProvider {
    Mono<ResolvedMemoryConfig> resolve();  // 仅全局
    // 缺少: resolveForAgent(String agentName)
}
```

**问题 2: 运行时仅调用全局配置**

`AgentMemoryLifecycle.loadMemoryInitReactive()` 不接受 agentName 参数：

```java
// intellimate-agent/.../AgentMemoryLifecycle.java:122
public Mono<MemoryInit> loadMemoryInitReactive(TokenEstimator tokenEstimator) {
    return memoryConfigProvider.resolve();  // 仅 _global_
}
```

**问题 3: 同样的问题存在于其他调用点**

- `SessionHistoryController.persistFromTranscript()` — 清除会话时持久化记忆，也只读全局配置
- `PlanExecutionOrchestrator.schedulePlanCompletionMemoryExtraction()` — 计划完成时提取记忆，也只读全局配置

## 3. 修复方案

### 设计原则

**删除全局 `_global_` 配置回退，每个 Agent 只读自己的配置，缺失的 key 使用代码默认值 (DEFAULTS)**。

### 3.1 修改 `MemoryConfigProvider` 接口

增加 `resolveForAgent` 方法，保留 `resolve()` 的默认实现兼容旧调用：

```java
public interface MemoryConfigProvider {
    Mono<ResolvedMemoryConfig> resolve();

    default Mono<ResolvedMemoryConfig> resolveForAgent(String agentName) {
        return resolve();
    }
}
```

### 3.2 修改 `MemoryConfigService.resolveForAgent()`

移除 `_global_` 回退，只读 agent 自己的配置，缺失 key 用 DEFAULTS 兜底：

```java
public Mono<ResolvedMemoryConfig> resolveForAgent(String agentName) {
    return configRepo.findByAgentName(agentName)
            .collectMap(e -> e.getConfigKey(), e -> e.getConfigValue())
            .map(agentMap -> {
                Map<String, String> merged = new HashMap<>(DEFAULTS);
                merged.putAll(agentMap);
                return ResolvedMemoryConfig.fromMap(merged);
            });
}
```

### 3.3 修改 `AgentMemoryLifecycle.loadMemoryInitReactive()`

增加 agentName 参数，使用 `resolveForAgent`：

```java
public Mono<MemoryInit> loadMemoryInitReactive(
        TokenEstimator tokenEstimator, String agentName) {
    if (memoryConfigProvider == null) {
        return Mono.just(new MemoryInit(null, null));
    }
    return memoryConfigProvider.resolveForAgent(agentName)
            .timeout(Duration.ofSeconds(2))
            .map(memConfig -> new MemoryInit(memConfig,
                    createMemoryConsolidator(memConfig, tokenEstimator)))
            .defaultIfEmpty(new MemoryInit(null, null))
            .onErrorResume(e -> {
                log.warn("Failed to load memory config for agent '{}': {}",
                        agentName, e.getMessage());
                return Mono.just(new MemoryInit(null, null));
            });
}
```

### 3.4 修改 `AgentLoopExecutor.executeAgentLoop()`

传入 agentId：

```java
return agentMemoryLifecycle.loadMemoryInitReactive(tokenEstimator, agentId)
```

### 3.5 修改 `SessionHistoryController.persistFromTranscript()`

使用 `resolveForAgent`：

```java
return memoryConfigProvider.resolveForAgent(session.getAgentName())
        .flatMap(config -> { ... });
```

### 3.6 修改 `PlanExecutionOrchestrator.schedulePlanCompletionMemoryExtraction()`

使用 `resolveForAgent`：

```java
memoryConfigProvider.resolveForAgent(effectiveAgentId)
        .filter(config -> config.longTermEnabled())
        ...
```

### 3.7 同步修改 `resolveGroupedForAgent()`

该方法也不再回退 `_global_`，直接读 agent 配置 + DEFAULTS：

```java
public Mono<Map<String, ConfigItem>> resolveGroupedForAgent(String agentName) {
    return configRepo.findByAgentName(agentName)
            .collectMap(e -> e.getConfigKey(), e -> e.getConfigValue())
            .map(agentMap -> {
                Map<String, ConfigItem> result = new LinkedHashMap<>();
                DEFAULTS.forEach((key, defVal) -> {
                    String value = agentMap.getOrDefault(key, defVal);
                    result.put(key, new ConfigItem(value, defVal,
                            DESCRIPTIONS.getOrDefault(key, ""), inferType(key)));
                });
                return result;
            });
}
```

## 4. 影响范围

| 文件 | 模块 | 修改内容 |
|------|------|----------|
| `MemoryConfigProvider.java` | intellimate-memory | 增加 `resolveForAgent` 默认方法 |
| `MemoryConfigService.java` | intellimate-gateway | `resolveForAgent` 不再读 `_global_`，用 DEFAULTS 兜底 |
| `AgentMemoryLifecycle.java` | intellimate-agent | `loadMemoryInitReactive` 增加 agentName 参数 |
| `AgentLoopExecutor.java` | intellimate-agent | 传入 agentId |
| `SessionHistoryController.java` | intellimate-gateway | 使用 `resolveForAgent` |
| `PlanExecutionOrchestrator.java` | intellimate-gateway | 使用 `resolveForAgent` |
| `MemoryConfigServiceTest.java` | intellimate-gateway (test) | 更新 `resolveForAgent` 测试 |

## 5. 向后兼容性

- `resolveForAgent` 使用 Java `default` 方法，不破坏 `MemoryConfigProvider` 的其他实现
- `resolve()` 保留不变，仍读 `_global_` — 但运行时不再作为 Agent 记忆配置的来源
- 当 agentName 为 null/空时，`resolveForAgent` 回退到代码 DEFAULTS（因为 DB 中无匹配记录）
- 前端配置页面已按 Agent 维度保存配置，无需修改前端

## 6. 测试要点

1. Agent 配置了 `long_term.enabled=true` → 执行时应启用记忆检索
2. Agent 未在 DB 中配置任何记录 → 使用代码 DEFAULTS（`long_term.enabled=false`）
3. per-agent 覆盖 `max_injection_tokens` 等参数 → 运行时应使用 per-agent 值
4. agentName 为 null/空 → 使用代码 DEFAULTS，不报错
