# 多智能体系统技术文档

## 1. 架构概览

### 1.1 当前状态（单智能体）

```
Frontend                    Backend
┌──────────┐    WS    ┌──────────────────────┐
│ App.tsx  │◄────────►│ GatewayWebSocketHandler│
│ (硬编码   │         │         │              │
│ javaclaw)│         │    MessagePipeline     │
└──────────┘         │    │    │              │
                     │ SessionMgr AgentRuntime│
                     │    │         │          │
                     │    DB    ChatClient    │
                     └──────────────────────┘
```

问题：
- 前端 `AGENT_NAME` 硬编码为环境变量
- `MessagePipeline` 创建会话时固定使用 `properties.getAgent().getName()`
- 前端 WebSocket 请求不包含 `agentName` 参数
- 无智能体列表 API

### 1.2 目标状态（多智能体）

```
Frontend                        Backend
┌──────────────┐    WS    ┌──────────────────────┐
│ AgentSelector │◄────────►│ GatewayWebSocketHandler│
│ (动态切换)    │         │         │              │
│ agentStore   │  REST   │ AgentController (CRUD) │
│ chatStore    │◄────────►│    MessagePipeline     │
│ (per-agent)  │         │    │    │              │
└──────────────┘         │ SessionMgr AgentRuntime│
                         │    │         │          │
                         │    DB    ChatClient    │
                         └──────────────────────┘
```

---

## 2. 后端改造

### 2.1 AgentController 扩展

当前文件：`javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/http/AgentController.java`

新增接口：

```java
// 列出所有智能体
@GetMapping("")
public Flux<Map<String, Object>> listAgents() {
    return agentRepository.findAllByDeleted(0)
            .map(this::entityToSummaryDto);
}

// 创建智能体
@PostMapping("")
public Mono<Map<String, Object>> createAgent(@RequestBody Map<String, String> body) {
    // 验证 name 唯一性
    // 创建 AgentEntity，使用 yml 默认值填充缺省字段
    // 返回创建结果
}

// 更新智能体基础配置
@PutMapping("/{name}")
public Mono<Map<String, Object>> updateAgent(@PathVariable String name,
                                              @RequestBody Map<String, Object> body) {
    // 更新 model, system_prompt, max_turns, timeout_seconds 等
}

// 删除智能体（软删除）
@DeleteMapping("/{name}")
public Mono<Void> deleteAgent(@PathVariable String name) {
    // 设置 deleted = 1
}
```

摘要 DTO 结构（列表用，不含 SOUL/USER/AGENTS 大文本）：

```java
private Map<String, Object> entityToSummaryDto(AgentEntity entity) {
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("name", entity.getName());
    dto.put("model", entity.getModel());
    dto.put("hasSoul", entity.getSoulMd() != null && !entity.getSoulMd().isBlank());
    dto.put("hasUser", entity.getUserMd() != null && !entity.getUserMd().isBlank());
    dto.put("hasAgents", entity.getAgentsMd() != null && !entity.getAgentsMd().isBlank());
    dto.put("createdAt", entity.getCreatedAt());
    return dto;
}
```

### 2.2 AgentRepository 扩展

```java
Flux<AgentEntity> findAllByDeleted(Integer deleted);

@Modifying
@Query("UPDATE agent SET deleted = 1 WHERE name = :name AND deleted = 0")
Mono<Integer> softDeleteByName(String name);
```

### 2.3 MessagePipeline 改造

核心变更：从请求参数中读取 `agentName`，用于会话隔离。

当前代码（`processRequest` 方法）：

```java
// 当前：固定使用默认 agent
SessionMetadata metadata = new SessionMetadata(
    properties.getAgent().getName(), null,
    channelId, contextType, contextId
);
```

改为：

```java
// 改造后：使用请求中的 agentName，缺省回退默认
String agentName = (String) params.getOrDefault("agentName", "");
if (agentName.isBlank()) {
    agentName = properties.getAgent().getName();
}

// contextId 加入 agentName 以实现会话隔离
String baseContextId = (String) params.getOrDefault("contextId", wsSessionId);
String isolatedContextId = baseContextId + "::" + agentName;

SessionKey sessionKey = new SessionKey(channelId, contextType, isolatedContextId);
SessionMetadata metadata = new SessionMetadata(
    agentName, null, channelId, contextType, isolatedContextId
);
```

会话隔离效果：

```
用户 ws-abc 对 javaclaw 的会话：webchat:dm:ws-abc::javaclaw
用户 ws-abc 对 coder 的会话：   webchat:dm:ws-abc::coder
用户 ws-abc 对 writer 的会话：  webchat:dm:ws-abc::writer
```

### 2.4 CommandHandler 改造

`/model` 命令的语义从"切换模型"改为"查看当前 agent 信息"，智能体切换由前端 UI 驱动。

或保留 `/model` 用于切换当前会话的 LLM 模型（仅影响 model 字段，不切换 agent）。

---

## 3. 前端改造

### 3.1 新增文件

| 文件 | 说明 |
|------|------|
| `src/stores/agentStore.ts` | 重构：智能体列表 + 当前选中 + CRUD |
| `src/components/AgentList.tsx` | Sidebar 中的智能体列表组件 |
| `src/components/CreateAgentModal.tsx` | 新建智能体弹窗 |
| `src/lib/api.ts` | 扩展：智能体 CRUD API |

### 3.2 修改文件

| 文件 | 变更 |
|------|------|
| `src/components/Sidebar.tsx` | 集成 AgentList 组件 |
| `src/components/TopBar.tsx` | 显示当前智能体名称 |
| `src/App.tsx` | 移除硬编码 AGENT_NAME，使用 agentStore |
| `src/hooks/useWebSocket.ts` | sendMessage 时携带 agentName |
| `src/stores/chatStore.ts` | 切换 agent 时清空消息 |

### 3.3 agentStore 重构

```typescript
interface AgentSummary {
  name: string;
  model: string;
  hasSoul: boolean;
  hasUser: boolean;
  hasAgents: boolean;
  createdAt: string;
}

interface AgentState {
  // 列表
  agents: AgentSummary[];
  activeAgent: string | null;
  listLoading: boolean;

  // 配置编辑（沿用现有逻辑）
  config: AgentConfig | null;
  draft: Record<ContextField, string>;
  loading: boolean;
  saving: boolean;
  dirty: boolean;
  error: string | null;

  // Actions
  fetchAgents: () => Promise<void>;
  setActiveAgent: (name: string) => void;
  createAgent: (name: string, model: string) => Promise<void>;
  deleteAgent: (name: string) => Promise<void>;
  fetchConfig: (name: string) => Promise<void>;
  updateField: (field: ContextField, value: string) => void;
  saveConfig: () => Promise<void>;
  reset: () => void;
}
```

### 3.4 api.ts 扩展

```typescript
// 智能体 CRUD
export function fetchAgents(): Promise<AgentSummary[]> {
  return request<AgentSummary[]>("/api/agents");
}

export function createAgent(data: { name: string; model: string }): Promise<{ success: boolean }> {
  return request("/api/agent", { method: "POST", body: JSON.stringify(data) });
}

export function deleteAgent(name: string): Promise<void> {
  return request(`/api/agent/${encodeURIComponent(name)}`, { method: "DELETE" });
}
```

### 3.5 useWebSocket 改造

```typescript
const sendMessage = useCallback((text: string) => {
  const { activeAgent } = useAgentStore.getState();

  const req = createRequest("conversation.message", {
    text,
    channelId: "webchat",
    contextType: "dm",
    agentName: activeAgent ?? "",  // 新增：携带当前智能体名称
  });

  // ...
}, []);
```

### 3.6 AgentList 组件设计

```typescript
interface AgentListProps {
  agents: AgentSummary[];
  activeAgent: string | null;
  onSelect: (name: string) => void;
  onCreateClick: () => void;
}

// 渲染逻辑：
// - 每个 agent 一行：图标 + 名称 + 模型标签
// - 活跃 agent 左侧蓝色竖线 + 背景高亮
// - 底部 "+ 新建智能体" 按钮
// - 空列表时显示引导文案
```

### 3.7 智能体切换交互

```typescript
// App.tsx 或 Sidebar.tsx 中
const handleSelectAgent = (name: string) => {
  const agentStore = useAgentStore.getState();
  agentStore.setActiveAgent(name);

  // 清空当前消息，后续消息会自动携带新的 agentName
  useChatStore.getState().clearMessages();
};
```

---

## 4. 数据库变更

本次无需新增表或列。已有的 `agent` 表和 `session` 表已支持多智能体：

- `agent` 表通过 `name` 唯一标识每个智能体
- `session` 表通过 `context_id`（包含 agentName）隔离不同智能体的会话
- `session.agent_name` 记录会话绑定的智能体

唯一需要确认的是：**默认智能体**是否需要预插入 DB。当前策略是 yml 回退，无需 DB 记录。

---

## 5. 改造点清单

### 后端（3 个文件）

| 优先级 | 文件 | 变更 |
|--------|------|------|
| P0 | `AgentController.java` | 新增 listAgents / createAgent / deleteAgent API |
| P0 | `AgentRepository.java` | 新增 findAllByDeleted / softDeleteByName 方法 |
| P0 | `MessagePipeline.java` | 从请求参数读取 agentName + 会话隔离 |

### 前端（8 个文件）

| 优先级 | 文件 | 变更 |
|--------|------|------|
| P0 | `src/lib/api.ts` | 新增智能体 CRUD API |
| P0 | `src/stores/agentStore.ts` | 重构：列表 + 选中 + CRUD |
| P0 | `src/components/AgentList.tsx` | 新增：智能体列表组件 |
| P0 | `src/components/CreateAgentModal.tsx` | 新增：创建弹窗 |
| P0 | `src/components/Sidebar.tsx` | 集成 AgentList |
| P1 | `src/hooks/useWebSocket.ts` | sendMessage 携带 agentName |
| P1 | `src/App.tsx` | 移除硬编码，管理 CreateAgentModal |
| P2 | `src/components/TopBar.tsx` | 显示当前智能体名称 |

---

## 6. 兼容性

- **向后兼容**：`agentName` 为空时回退到默认智能体，现有前端不传该参数也能正常工作
- **数据兼容**：现有 session 数据的 `context_id` 不含 agent 后缀，会被视为默认智能体的会话
- **API 兼容**：现有 `/api/agent/{name}` 和 `/api/agent/{name}/context` 接口不变
