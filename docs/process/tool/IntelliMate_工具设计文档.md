# IntelliMate 工具管理：现状分析与改造方案

> 本文档分析 IntelliMate 当前工具管理现状，对标 OpenClaw 的能力，提出改造方案。
> 包含后端 API、工具分组、前端 UI 的完整设计。

---

## 1. 现状分析

### 1.1 工具清单

当前注册 6 个工具：

| 工具名 | 类 | 功能 |
|--------|---|------|
| `exec` | `ExecTool` | 执行 Shell 命令 |
| `readFile` | `FileReadTool` | 读取文件内容 |
| `writeFile` | `FileWriteTool` | 写入文件内容 |
| `editFile` | `FileEditTool` | 编辑文件（字符串替换） |
| `webSearch` | `WebSearchTool` | 网页搜索（SerpAPI） |
| `webFetch` | `WebFetchTool` | 抓取网页内容 |

### 1.2 工具定义方式

使用 Spring AI 的 `@Tool` + `@ToolParam` 注解：

```java
// intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/ExecTool.java
@Tool(description = "Execute a shell command on the host machine and return the output")
public String exec(
        @ToolParam(description = "Shell command to execute") String command,
        @ToolParam(description = "Working directory (optional)", required = false) String workingDirectory,
        @ToolParam(description = "Timeout in seconds (default 30)", required = false) Integer timeoutSeconds
) { ... }
```

### 1.3 工具注册

`ToolAutoConfiguration` 将所有工具 Bean 注册为 `ToolCallbackProvider`：

```java
// intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/ToolAutoConfiguration.java
@Bean
public ToolCallbackProvider toolCallbackProvider(
        ExecTool execTool, FileReadTool fileReadTool, FileWriteTool fileWriteTool,
        FileEditTool fileEditTool, WebSearchTool webSearchTool, WebFetchTool webFetchTool) {
    return MethodToolCallbackProvider.builder()
            .toolObjects(execTool, fileReadTool, fileWriteTool, fileEditTool, webSearchTool, webFetchTool)
            .build();
}
```

`ToolsEngine` 从所有 `ToolCallbackProvider` 收集回调并提供统一访问：

```java
// intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/ToolsEngine.java
public ToolsEngine(List<ToolCallbackProvider> providers) {
    this.allToolCallbacks = providers.stream()
            .flatMap(p -> Arrays.stream(p.getToolCallbacks()))
            .toArray(ToolCallback[]::new);
}
```

### 1.4 工具过滤（ToolProfile）

`ToolProfile` 枚举定义四种预设：

```java
// intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/ToolProfile.java
public enum ToolProfile {
    FULL(Set.of("exec", "readFile", "writeFile", "editFile", "webSearch", "webFetch")),
    CODING(Set.of("exec", "readFile", "writeFile", "editFile")),
    MESSAGING(Set.of("webSearch", "webFetch")),
    MINIMAL(Set.of());
}
```

`ToolsEngine.getToolCallbacksFor()` 支持三种规格：

| `toolsEnabledSpec` 值 | 行为 |
|----------------------|------|
| `null` / 空 / `"full"` | 返回所有工具 |
| `"coding"` / `"messaging"` / `"minimal"` | 按 Profile 预设过滤 |
| JSON 数组 `["exec","readFile"]` | 按名称精确过滤 |

### 1.5 数据库存储

`agent` 表已有 `tools_enabled` JSON 字段：

```sql
-- V1__init_schema.sql
CREATE TABLE IF NOT EXISTS `agent` (
    ...
    `tools_enabled`    JSON  NULL COMMENT 'List of enabled tool names',
    ...
);
```

### 1.6 Agent Runtime 使用

Agent Loop 中，`tools_enabled` 规格传入 `ToolsEngine` 获取过滤后的回调数组：

```java
// intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java
ToolCallback[] tools = toolsEngine.getToolCallbacksFor(request.toolsEnabled());

ToolCallingChatOptions chatOptions = ToolCallingChatOptions.builder()
        .toolCallbacks(tools)
        .internalToolExecutionEnabled(false)
        .build();
```

### 1.7 当前缺失

| 方面 | 现状 | 问题 |
|------|------|------|
| **API** | `AgentController` 的 GET/PUT 不包含 `toolsEnabled` | 前端无法读写 |
| **前端** | `AgentConfigModal` 只编辑 SOUL/USER/AGENTS | 无工具配置入口 |
| **分组** | 无 Group 概念 | 不能批量管理 |
| **allow/deny** | 不支持 | 无法叠加黑白名单 |

---

## 2. 与 OpenClaw 对标差异

| 维度 | OpenClaw | IntelliMate 现状 | 改造目标 |
|------|----------|--------------|---------|
| 工具定义 | `api.registerTool()` + 插件 | `@Tool` + `ToolCallbackProvider` | 不变 |
| 配置存储 | `~/.openclaw/openclaw.json` (JSON5) | MySQL `agent.tools_enabled` (JSON) | 不变 |
| 预设 Profile | full/coding/messaging/minimal | 同 | 同 |
| 分组 | `group:fs` 等 10 组 | 无 | 新增 3 组 |
| allow/deny | 支持，deny 优先 | 不支持 | 视需求后续 |
| byProvider | 按模型限制工具 | 不支持 | 视需求后续 |
| UI 控制 | Schema 驱动的 Config 表单 | 无 | AgentConfigModal 新增标签页 |
| API | CLI + Control UI WebSocket | 未暴露 toolsEnabled | REST API |
| 循环检测 | loopDetection | 仅 maxTurns 限制 | 视需求后续 |

---

## 3. 改造方案

### Step 1: 工具分组

#### 3.1.1 定义 ToolGroup

新增 `ToolGroup` 枚举，对标 OpenClaw 的 `group:*` 概念：

```java
// intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/ToolGroup.java
public enum ToolGroup {

    FS("文件系统", Set.of("readFile", "writeFile", "editFile")),
    RUNTIME("运行时", Set.of("exec")),
    WEB("网络", Set.of("webSearch", "webFetch"));

    private final String displayName;
    private final Set<String> toolNames;

    ToolGroup(String displayName, Set<String> toolNames) {
        this.displayName = displayName;
        this.toolNames = toolNames;
    }

    public String getDisplayName() { return displayName; }
    public Set<String> getToolNames() { return toolNames; }

    /** 根据工具名找到所属 group */
    public static ToolGroup groupOf(String toolName) {
        for (ToolGroup g : values()) {
            if (g.toolNames.contains(toolName)) return g;
        }
        return null;
    }
}
```

IntelliMate 工具分组对照：

| Group | 工具 | 对标 OpenClaw |
|-------|------|-------------|
| `FS` | `readFile`, `writeFile`, `editFile` | `group:fs` |
| `RUNTIME` | `exec` | `group:runtime` |
| `WEB` | `webSearch`, `webFetch` | `group:web` |

#### 3.1.2 扩展 ToolsEngine

`getToolCallbacksFor()` 增加 `group:*` 前缀展开支持（未来扩展方向，当前不实现，保持 Profile + 显式列表两种模式）。

### Step 2: 后端 API 扩展

#### 3.2.1 新增工具列表 API

```
GET /api/tools
```

返回所有已注册工具的元信息（含分组）：

```json
{
  "tools": [
    {
      "name": "readFile",
      "description": "Read file contents",
      "group": "FS",
      "groupDisplayName": "文件系统"
    },
    {
      "name": "exec",
      "description": "Execute a shell command...",
      "group": "RUNTIME",
      "groupDisplayName": "运行时"
    }
  ],
  "profiles": [
    { "name": "full", "tools": ["exec","readFile","writeFile","editFile","webSearch","webFetch"] },
    { "name": "coding", "tools": ["exec","readFile","writeFile","editFile"] },
    { "name": "messaging", "tools": ["webSearch","webFetch"] },
    { "name": "minimal", "tools": [] }
  ],
  "groups": [
    { "name": "FS", "displayName": "文件系统", "tools": ["readFile","writeFile","editFile"] },
    { "name": "RUNTIME", "displayName": "运行时", "tools": ["exec"] },
    { "name": "WEB", "displayName": "网络", "tools": ["webSearch","webFetch"] }
  ]
}
```

实现要点：
- 在 `ToolsEngine` 新增方法返回工具元信息（name + description）
- 在 `intellimate-gateway` 新增 `ToolController`（或扩展 `AgentController`）
- 从 `ToolCallback.getToolDefinition()` 获取 name 和 description
- 从 `ToolGroup.groupOf()` 获取分组

#### 3.2.2 Agent API 暴露 toolsEnabled

修改 `AgentController`：

**GET /api/agent/{name}** — `entityToDto()` 增加 `toolsEnabled` 字段：

```java
private Map<String, Object> entityToDto(AgentEntity entity) {
    Map<String, Object> dto = new LinkedHashMap<>();
    dto.put("name", entity.getName());
    dto.put("model", entity.getModel());
    dto.put("soulMd", entity.getSoulMd());
    dto.put("userMd", entity.getUserMd());
    dto.put("agentsMd", entity.getAgentsMd());
    dto.put("toolsEnabled", entity.getToolsEnabled());  // 新增
    return dto;
}
```

**GET /api/agents** — `entityToSummaryDto()` 增加 `toolsEnabled`：

```java
private Map<String, Object> entityToSummaryDto(AgentEntity entity) {
    // ... 现有字段 ...
    dto.put("toolsEnabled", entity.getToolsEnabled());  // 新增
    return dto;
}
```

**PUT /api/agent/{name}** — 接受 `toolsEnabled` 参数：

```java
if (body.containsKey("toolsEnabled")) {
    entity.setToolsEnabled(body.get("toolsEnabled") instanceof String s ? s : null);
}
```

#### 3.2.3 数据流

```
前端 ToolsTab
    │
    ▼
PUT /api/agent/{name}  { "toolsEnabled": "coding" }
    │                  or { "toolsEnabled": "[\"exec\",\"readFile\"]" }
    ▼
AgentController → AgentEntity.tools_enabled (DB)
    │
    ▼
下次对话时：
AgentConfigService.resolve(agentName)
    │
    ▼
ResolvedAgentConfig { agent, toolsEnabled }
    │
    ▼
AgentRunRequest → AgentRuntime.dispatch()
    │
    ▼
ToolsEngine.getToolCallbacksFor(toolsEnabled)
    │
    ▼
ChatModel + ToolCallingChatOptions(toolCallbacks=filtered)
```

### Step 3: 前端工具配置 UI

#### 3.3.1 页面设计

对标 OpenClaw 的 Config 表单（Schema 驱动），IntelliMate 采用更直观的**工具列表 + Profile 选择器**方式（因为工具数量少，无需 Schema 驱动的通用方案）。

在 `AgentConfigModal` 中新增"工具"标签页：

```
┌─ AgentConfigModal ──────────────────────────────────────────┐
│  [上下文]  [工具]  ← 标签切换                                │
│ ─────────────────────────────────────────────────────────── │
│                                                              │
│  ┌─ 快速预设 ─────────────────────────────────────────────┐ │
│  │  Profile:  ○ Full  ● Coding  ○ Messaging  ○ Minimal    │ │
│  │            ○ 自定义                                     │ │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌─ 工具列表 (按分组) ───────────────────────────────────┐  │
│  │                                                        │  │
│  │  ▼ 文件系统 (FS)                         [全选/全取消]  │  │
│  │    ☑ readFile   — 读取文件内容                         │  │
│  │    ☑ writeFile  — 写入文件内容                         │  │
│  │    ☑ editFile   — 编辑文件（字符串替换）               │  │
│  │                                                        │  │
│  │  ▼ 运行时 (RUNTIME)                     [全选/全取消]  │  │
│  │    ☑ exec       — 执行 Shell 命令                      │  │
│  │                                                        │  │
│  │  ▼ 网络 (WEB)                            [全选/全取消]  │  │
│  │    ☐ webSearch  — 网页搜索                             │  │
│  │    ☐ webFetch   — 抓取网页内容                         │  │
│  │                                                        │  │
│  └────────────────────────────────────────────────────────┘ │
│                                                              │
│  ┌─ 高级 (JSON) ──────────────── [展开/折叠] ────────────┐  │
│  │  当前值: "coding"                                      │  │
│  │  或: ["exec", "readFile", "writeFile", "editFile"]    │  │
│  └────────────────────────────────────────────────────────┘  │
│                                                              │
│                            [取消]  [保存]                    │
└──────────────────────────────────────────────────────────────┘
```

#### 3.3.2 交互逻辑

| 操作 | 行为 |
|------|------|
| 选择 Profile 预设 | 自动勾选/取消对应工具，`toolsEnabled` = `"coding"` |
| 手动勾选/取消某个工具 | 自动切换到"自定义"模式，`toolsEnabled` = `["exec","readFile"]` |
| 点击分组标题的"全选" | 该组所有工具勾选 |
| 点击分组标题的"全取消" | 该组所有工具取消 |
| 高级 JSON 编辑 | 直接修改 `toolsEnabled` 值，双向同步表单 |
| 保存 | 调用 `PUT /api/agent/{name}` 写入 DB |

#### 3.3.3 前端组件结构

```
AgentConfigModal
├── TabBar ["上下文", "工具"]
├── ContextTab (现有，SOUL/USER/AGENTS 编辑器)
└── ToolsTab (新建)
    ├── ProfileSelector
    │   └── RadioGroup: full / coding / messaging / minimal / 自定义
    ├── ToolGroupList
    │   ├── ToolGroupItem (FS)
    │   │   ├── GroupHeader: "文件系统" + 全选/全取消
    │   │   ├── ToolSwitch: readFile
    │   │   ├── ToolSwitch: writeFile
    │   │   └── ToolSwitch: editFile
    │   ├── ToolGroupItem (RUNTIME)
    │   │   └── ToolSwitch: exec
    │   └── ToolGroupItem (WEB)
    │       ├── ToolSwitch: webSearch
    │       └── ToolSwitch: webFetch
    └── JsonEditor (可折叠，高级模式)
```

#### 3.3.4 前端数据流

```
页面加载:
  GET /api/tools → 获取工具列表、分组、Profile 预设
  GET /api/agent/{name} → 获取当前 toolsEnabled 值

用户操作:
  ProfileSelector / ToolSwitch → 本地 state 更新
  保存 → PUT /api/agent/{name} { toolsEnabled: "coding" 或 ["exec","readFile"] }

下次对话时自动生效（AgentConfigService 每次从 DB 读取最新配置）
```

#### 3.3.5 前端 TypeScript 类型

```typescript
// api.ts 扩展
interface ToolInfo {
  name: string;
  description: string;
  group: string;
  groupDisplayName: string;
}

interface ToolProfileInfo {
  name: string;
  tools: string[];
}

interface ToolGroupInfo {
  name: string;
  displayName: string;
  tools: string[];
}

interface ToolsMetadata {
  tools: ToolInfo[];
  profiles: ToolProfileInfo[];
  groups: ToolGroupInfo[];
}

// AgentConfig 扩展
interface AgentConfig {
  name: string;
  model: string;
  soulMd: string;
  userMd: string;
  agentsMd: string;
  toolsEnabled: string | null;  // 新增
}
```

---

## 4. 实施优先级

| 优先级 | 任务 | 改动文件 |
|--------|------|---------|
| P0 | 后端 API 暴露 `toolsEnabled` | `AgentController.java` |
| P0 | 前端 `AgentConfig` 类型扩展 | `api.ts` |
| P1 | 新增 `GET /api/tools` API | `ToolsEngine.java` + 新建 `ToolController.java` |
| P1 | 新增 `ToolGroup` 枚举 | 新建 `ToolGroup.java` |
| P1 | 前端 `ToolsTab` 组件 | 新建 `ToolsTab.tsx` |
| P1 | `AgentConfigModal` 增加标签页 | `AgentConfigModal.tsx` |
| P2 | 高级 JSON 编辑器 | `ToolsTab.tsx` 内部 |
| P3 | `group:*` 语法支持 | `ToolsEngine.java` |
| P3 | allow/deny 黑白名单 | `ToolsEngine.java` |

---

## 5. 与 OpenClaw 的设计选择差异

| 设计点 | OpenClaw | IntelliMate | 原因 |
|--------|----------|----------|------|
| UI 方式 | Schema 驱动的通用表单 | 专用的工具列表 + 分组 | 工具少（6个），专用 UI 体验更好 |
| 存储方式 | 本地 JSON5 文件 | MySQL JSON 字段 | IntelliMate 是 Web 服务，需要 DB 持久化 |
| 分组定义 | 框架层硬编码映射 | Java 枚举 | 同为静态映射，Java 枚举更类型安全 |
| 通信方式 | WebSocket 直连 Gateway | REST API | IntelliMate 已有成熟的 REST API 体系 |
| byProvider | 支持 | 暂不支持 | IntelliMate 当前只用 DashScope，无需按 provider 区分 |
| 循环检测 | 内置 loopDetection | maxTurns 限制 | 简单场景下 maxTurns 足够，后续按需扩展 |

---

## 6. 未来扩展方向

1. **allow/deny 黑白名单** — 支持 `group:*` 语法在 allow/deny 中使用
2. **byProvider 模型级别控制** — 当接入多个 LLM provider 时按需启用
3. **插件工具** — 支持用户自定义工具（类似 OpenClaw 的 `api.registerTool()`）
4. **工具循环检测** — 在 Agent Loop 中检测重复无效的工具调用
5. **工具使用统计** — 记录每个工具的调用频率和成功率
