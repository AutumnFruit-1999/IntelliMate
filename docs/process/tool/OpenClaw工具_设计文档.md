# OpenClaw 工具管理机制分析

> 本文档分析 OpenClaw 的工具（Tool）管理体系，包括定义、注册、配置、分组和 UI 页面控制。
> 供 JavaClaw 改造参考。

---

## 1. 工具定义与注册

### 1.1 内置工具

OpenClaw 内置 ~20 个工具，按功能分为 10 个 Group：

| Group | 工具列表 | 说明 |
|-------|---------|------|
| `group:fs` | `read`, `write`, `edit`, `apply_patch` | 文件系统操作 |
| `group:runtime` | `exec`, `bash`, `process` | Shell 命令执行 |
| `group:sessions` | `sessions_list`, `sessions_history`, `sessions_send`, `sessions_spawn`, `session_status` | 会话管理 |
| `group:memory` | `memory_search`, `memory_get` | 记忆/向量检索 |
| `group:web` | `web_search`, `web_fetch` | 网页搜索与抓取 |
| `group:ui` | `browser`, `canvas` | 浏览器和画布 |
| `group:automation` | `cron`, `gateway` | 定时任务和网关 |
| `group:messaging` | `message` | 消息发送 |
| `group:nodes` | `nodes` | 远程节点操作 |
| `group:openclaw` | （所有内置工具） | 不含插件工具的全集 |

### 1.2 插件工具

第三方通过 Plugin API 注册工具：

```typescript
import { Type } from "@sinclair/typebox";

export default function (api) {
  api.registerTool({
    name: "my_tool",
    description: "Do a thing",
    parameters: Type.Object({
      input: Type.String(),
    }),
    // optional: true → 需在 tools.allow 中显式启用
    async execute(_id, params) {
      return { content: [{ type: "text", text: params.input }] };
    },
  });
}
```

关键特性：
- `name` — 工具唯一标识，用于 allow/deny 匹配
- `description` — 传递给模型的工具描述
- `parameters` — TypeBox JSON Schema，定义输入参数
- `optional: true` — 可选工具，必须在 `tools.allow` 中显式列出才能使用
- `execute()` — 异步执行函数，返回 MCP 风格的 content 数组

### 1.3 工具呈现给模型

工具通过两种方式传递给 LLM：
1. **Tool Schema** — JSON Schema 格式，作为 API 的 `tools` 参数
2. **System Prompt** — 人类可读的工具列表和使用指南，嵌入系统提示词

两处都必须包含该工具，模型才能使用。

---

## 2. 工具配置模型

### 2.1 存储

- **文件**：`~/.openclaw/openclaw.json`（JSON5 格式，支持注释和尾逗号）
- **Schema**：https://config.clawi.sh/
- 支持热重载（文件修改后自动生效）

### 2.2 四层控制结构

```
┌──────────────────────────────────────────────────┐
│  Layer 1: tools.profile (基础预设)                │
│  ┌────────────────────────────────────────────┐  │
│  │  Layer 2: tools.byProvider (按模型覆盖)    │  │
│  │  ┌────────────────────────────────────┐    │  │
│  │  │  Layer 3: tools.allow / deny       │    │  │
│  │  │  (全局白名单/黑名单)                │    │  │
│  │  └────────────────────────────────────┘    │  │
│  └────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────┐  │
│  │  Layer 4: agents.list[].tools             │  │
│  │  (Per-Agent 独立覆盖，结构与上面相同)      │  │
│  └────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘
```

### 2.3 策略优先级

**deny > allow > byProvider > profile**

具体规则：
1. `tools.profile` 设置基础工具集
2. `tools.byProvider` 按 provider/model 进一步收窄（只能缩小，不能扩大）
3. `tools.allow` 在上述基础上追加允许的工具
4. `tools.deny` 最终否决（deny 始终优先于 allow）
5. 匹配不区分大小写，支持 `*` 通配符

### 2.4 配置示例

**全局 coding profile，禁用运行时工具：**
```json5
{
  tools: {
    profile: "coding",
    deny: ["group:runtime"],
  },
}
```

**全局 coding，某个 agent 切换为 messaging：**
```json5
{
  tools: { profile: "coding" },
  agents: {
    list: [
      {
        id: "support",
        tools: {
          profile: "messaging",
          allow: ["slack"],
        },
      },
    ],
  },
}
```

**按 provider 限制工具：**
```json5
{
  tools: {
    profile: "coding",
    byProvider: {
      "google-antigravity": { profile: "minimal" },
      "openai/gpt-5.2": { allow: ["group:fs", "sessions_list"] },
    },
  },
}
```

---

## 3. 预设 Profile 详情

| Profile | 包含的工具 |
|---------|-----------|
| `full` | 无限制（等同于未设置） |
| `coding` | `group:fs`, `group:runtime`, `group:sessions`, `group:memory`, `image` |
| `messaging` | `group:messaging`, `sessions_list`, `sessions_history`, `sessions_send`, `session_status` |
| `minimal` | 仅 `session_status` |

---

## 4. Group 分组机制

### 4.1 原理

Group 是**静态名称映射**，不是动态标签系统。每个 `group:xxx` 在策略引擎中被展开为一组工具名：

```
group:fs → ["read", "write", "edit", "apply_patch"]
```

### 4.2 展开流程

1. 遍历 `allow` / `deny` 列表中的每一项
2. 若以 `group:` 前缀开头，替换为对应的工具名数组
3. 普通名称保持原样
4. 合并去重后得到最终工具集

### 4.3 示例

配置：
```json5
{ tools: { allow: ["group:fs", "browser"] } }
```

策略引擎处理：
```
"group:fs"  → ["read", "write", "edit", "apply_patch"]
"browser"   → ["browser"]
最终允许集  = {"read", "write", "edit", "apply_patch", "browser"}
```

---

## 5. UI 页面管理

### 5.1 技术栈

- **框架**：Vite + Lit Web Components 单页应用
- **通信**：WebSocket（与 Gateway 同端口 18789）
- **访问**：`http://localhost:18789/`（可通过 `gateway.controlUi.basePath` 配置前缀）
- **认证**：Token 认证，可选 Tailscale 集成

### 5.2 Config 标签页

OpenClaw Control UI 不是为工具设置独立页面，而是**将所有配置嵌入统一的 Config 标签页**中。工具配置是整体配置树的一个子节点。

**核心机制：JSON Schema 驱动表单自动渲染**

- 整个 `openclaw.json` 的结构由 JSON Schema 定义
- Config 标签页读取 Schema，为每个属性自动生成对应的表单控件
- 无需为每个配置项写专门的前端代码

**Schema → 表单的映射规则：**

| Schema 类型 | 渲染的表单元素 |
|-------------|--------------|
| `enum` (如 `"minimal" \| "coding" \| "messaging" \| "full"`) | 下拉选择框 |
| `string[]` (如 `allow`, `deny`) | 标签输入框（可输入 `group:*` 语法） |
| `boolean` | 开关 Toggle |
| `number` / `integer` | 数字输入框（带类型自动转换） |
| `object` (嵌套) | 可折叠区域 |
| literal union (`"off" \| "on"`) | 下拉选择框（index-based 值以保留类型） |
| primitive union (`string \| number`) | 文本输入 + 智能类型转换 |
| 复杂 anyOf/oneOf | 最近版本已修复支持 |

### 5.3 工具配置在页面中的呈现

```
┌─ Config Tab ──────────────────────────────────────────┐
│                                                        │
│  ▼ tools                                [展开/折叠]    │
│  ┌─────────────────────────────────────────────────┐  │
│  │ profile:    [▼ coding        ]  (下拉选择)      │  │
│  │                                                  │  │
│  │ allow:      [ group:fs, browser ]  (标签输入)    │  │
│  │ deny:       [ group:runtime     ]  (标签输入)    │  │
│  │                                                  │  │
│  │ ▶ byProvider                    [点击展开]       │  │
│  │ ▶ elevated                      [点击展开]       │  │
│  │ ▶ exec                          [点击展开]       │  │
│  │ ▶ web                           [点击展开]       │  │
│  │ ▶ loopDetection                 [点击展开]       │  │
│  │ ▶ agentToAgent                  [点击展开]       │  │
│  │ ▶ sessions                      [点击展开]       │  │
│  └─────────────────────────────────────────────────┘  │
│                                                        │
│  [Raw JSON Editor]  ←  高级模式切换                    │
│  [Apply & Restart]  ←  保存按钮                        │
└────────────────────────────────────────────────────────┘
```

### 5.4 Per-Agent 工具配置

在 Config 标签页的 `agents → list` 区域，每个 agent 都有独立的 `tools` 子节点：

```
▼ agents
  ▼ list
    ▼ [0] support
      ▶ tools                         ← 与全局 tools 结构完全相同
        profile: [▼ messaging ]
        allow: [ slack ]
        deny: []
        ▶ byProvider
```

结构与全局 `tools` 完全相同（profile / allow / deny / byProvider），但作用域限定到单个 agent。

### 5.5 交互特点

| 特点 | 说明 |
|------|------|
| **Schema 驱动** | 新增配置字段时只需更新 Schema，无需修改前端代码 |
| **双模式编辑** | 表单模式（用户友好）+ Raw JSON 编辑器（高级用户） |
| **并发保护** | base-hash 校验，防止多人同时编辑冲突 |
| **即时生效** | Apply & Restart 后自动重启生效 |
| **嵌入式** | 不是独立的工具管理页面，而是整体配置的一部分 |
| **CLI 辅助** | `openclaw config get/set/unset` 可在终端操作 |

### 5.6 开发模式

```bash
pnpm ui:dev                    # 启动开发服务器（http://localhost:5173）
# 通过 query string 连接已有 Gateway：
# http://localhost:5173/?gatewayUrl=ws://localhost:18789&token=xxx
```

需要在 Gateway 配置中允许跨域：
```json5
{
  gateway: {
    controlUi: {
      allowedOrigins: ["http://localhost:5173"],
    },
  },
}
```

---

## 6. 工具循环检测（附加能力）

OpenClaw 内置了工具调用循环检测机制，防止 Agent 陷入无意义的重复调用：

```json5
{
  tools: {
    loopDetection: {
      enabled: true,
      warningThreshold: 10,
      criticalThreshold: 20,
      globalCircuitBreakerThreshold: 30,
      historySize: 30,
      detectors: {
        genericRepeat: true,        // 相同工具+相同参数重复
        knownPollNoProgress: true,  // poll 类工具无进展
        pingPong: true,             // A/B/A/B 交替无进展
      },
    },
  },
}
```

---

## 7. 总结

OpenClaw 工具管理的核心设计思想：

1. **分层控制** — Profile → byProvider → allow/deny → Per-Agent，逐层细化
2. **Group 别名** — `group:*` 语法简化批量工具管理
3. **deny 优先** — 安全优先，否决列表始终生效
4. **Schema 驱动 UI** — 配置表单由 JSON Schema 自动生成，零前端改动即可扩展
5. **统一入口** — 工具配置嵌入整体配置，而非独立页面
6. **循环保护** — 内置 loop detection 防止无限工具调用
