# 功能分析：智能体上下文文件 (SOUL / USER / AGENTS)

## 1. 需求背景

OpenClaw 为每个智能体（Agent）维护三个核心上下文文件，在每次会话开始时自动注入到 System Prompt 中，赋予 AI 助手持久化的"性格"、"用户认知"和"行为规范"。JavaClaw 需要对标实现该功能，并基于数据库存储以支持多智能体独立配置。

---

## 2. OpenClaw 原始设计

### 2.1 三文件定义

| 文件 | 作用 | 类比 | 内容示例 |
|------|------|------|---------|
| **SOUL.md** | 定义助手的性格和行为 | 基因 + 教养 | 语气风格、性格特征、回答边界、禁止事项 |
| **USER.md** | 描述用户是谁 | 简历 + 日记 | 姓名、时区、邮箱、技术栈偏好、工作习惯 |
| **AGENTS.md** | 设定工作习惯和边界 | 员工手册 | 代码规范、回复格式、工具使用策略、记忆管理 |

### 2.2 OpenClaw 生命周期

```
首次启动 (Bootstrap)
│
├─ 运行 Bootstrap 仪式 (Q&A 对话)
│   ├─ 生成 SOUL.md    ← AI 根据用户回答总结性格设定
│   ├─ 生成 USER.md    ← AI 提取用户个人信息
│   └─ Seed AGENTS.md  ← 使用默认模板 + 用户偏好
│
├─ 后续由用户手动编辑更新
│
每次会话开始
│
├─ 读取 SOUL.md   (max 20,000 chars)
├─ 读取 USER.md   (max 20,000 chars)
├─ 读取 AGENTS.md (max 20,000 chars)
│
├─ 拼接为 System Prompt 前缀
│   ├─ [SOUL 内容]
│   ├─ [USER 内容]
│   ├─ [AGENTS 内容]
│   └─ [Custom System Prompt]
│
└─ 总 prompt 上限 150,000 chars
```

### 2.3 OpenClaw 核心代码逻辑

```typescript
// OpenClaw: src/agents/bootstrap.ts (simplified)
async function prepareSessionContext(agent: AgentConfig): Promise<string> {
  const workspace = agent.workspace;  // e.g. ~/.openclaw/workspace/
  
  // 读取三文件，缺失则返回占位符
  const soul   = await readFileOrPlaceholder(`${workspace}/SOUL.md`, 20000);
  const user   = await readFileOrPlaceholder(`${workspace}/USER.md`, 20000);
  const agents = await readFileOrPlaceholder(`${workspace}/AGENTS.md`, 20000);
  
  // 组装 system prompt
  let systemContext = '';
  if (soul)   systemContext += `## SOUL\n${soul}\n\n`;
  if (user)   systemContext += `## USER\n${user}\n\n`;
  if (agents) systemContext += `## AGENTS\n${agents}\n\n`;
  
  // 截断总上限
  if (systemContext.length > 150000) {
    systemContext = systemContext.substring(0, 150000);
  }
  
  return systemContext;
}

// 在 agent run 中使用
async function runAgent(session, userMessage) {
  const contextPrefix = await prepareSessionContext(session.agent);
  const customPrompt  = session.agent.systemPrompt || defaultPrompt;
  
  const fullSystemPrompt = contextPrefix + customPrompt;
  
  return llm.chat({
    system: fullSystemPrompt,
    messages: session.history,
    user: userMessage,
    tools: resolvedTools,
  });
}
```

### 2.4 OpenClaw 关键行为细节

1. **仅 DM/私人会话注入**：群组/频道上下文不注入个人文件（安全考虑）
2. **文件缺失处理**：文件不存在时注入 `<!-- SOUL.md not found -->`
3. **Bootstrap 仪式**：首次运行时通过 Q&A 自动生成初始内容
4. **存储方式**：文件系统（`~/.openclaw/workspace/`），每个 agent 一个 workspace 目录
5. **更新方式**：用户手动编辑文件，或通过 agent 工具自行修改

---

## 3. JavaClaw 实现方案

### 3.1 与 OpenClaw 的差异

| 方面 | OpenClaw | JavaClaw |
|------|----------|----------|
| **存储** | 文件系统 (`~/.openclaw/workspace/*.md`) | MySQL 数据库 (`agent` 表新增字段) |
| **多智能体** | 每个 agent 独立 workspace 目录 | 每个 agent 独立数据库行 |
| **更新方式** | 手动编辑文件 | 通过 API / 管理界面 / 斜杠命令 |
| **Bootstrap** | 首次运行 Q&A 仪式 | 暂不实现（后续可扩展） |
| **注入时机** | 每次会话开始读文件 | 每次调用 LLM 时从 Agent 配置读取 |

### 3.2 数据库设计

在 `agent` 表新增三个 TEXT 字段：

```sql
ALTER TABLE `agent`
    ADD COLUMN `soul_md`   TEXT NULL COMMENT 'SOUL: 性格、语气、边界' AFTER `system_prompt`,
    ADD COLUMN `user_md`   TEXT NULL COMMENT 'USER: 用户画像和偏好' AFTER `soul_md`,
    ADD COLUMN `agents_md` TEXT NULL COMMENT 'AGENTS: 工作规范和行为准则' AFTER `user_md`;
```

完整 `agent` 表结构（变更后）：

```
agent
├── id               BIGINT PK
├── name             VARCHAR(128) UNIQUE   ← 智能体唯一标识
├── model            VARCHAR(64)           ← LLM 模型
├── system_prompt    TEXT                  ← 自定义系统提示词
├── soul_md          TEXT [NEW]            ← 性格与行为设定
├── user_md          TEXT [NEW]            ← 用户信息描述
├── agents_md        TEXT [NEW]            ← 工作规范准则
├── max_turns        INT
├── timeout_seconds  INT
├── tools_enabled    JSON
├── config_json      JSON
├── created_at / updated_at / deleted
```

### 3.3 System Prompt 组装逻辑

```
┌─────────────────────────────────────────────┐
│              完整 System Prompt              │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │ ## SOUL (性格与行为)                 │    │
│  │ {agent.soul_md 内容}                 │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │ ## USER (用户信息)                   │    │
│  │ {agent.user_md 内容}                 │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │ ## AGENTS (工作规范)                 │    │
│  │ {agent.agents_md 内容}               │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │ ## Instructions                      │    │
│  │ {agent.system_prompt 或默认模板}      │    │
│  └─────────────────────────────────────┘    │
└─────────────────────────────────────────────┘
```

组装规则：
- 每个 section **仅在内容非空时注入**（不注入空白占位符）
- 单个文件最大 **20,000 字符**，超出截断并追加 `\n...[truncated]`
- 总 prompt 最大 **150,000 字符**（与 OpenClaw 一致）
- 所有文件均为空时，退化为当前默认模板行为

### 3.4 代码变更清单

| 层级 | 文件 | 变更 |
|------|------|------|
| DB | `V2__agent_context_files.sql` | **新增** — ALTER TABLE 加 3 列 |
| Entity | `AgentEntity.java` | 新增 `soulMd`, `userMd`, `agentsMd` 字段 |
| Config Model | `JavaClawProperties.Agent` | 新增 `soulMd`, `userMd`, `agentsMd` 字段 |
| Config Service | `AgentConfigService.toAgentConfig()` | 映射 DB entity → Agent config 新字段 |
| Agent Runtime | `AgentRuntime.buildSystemPrompt()` | 重写为多段拼接逻辑 |

### 3.5 对应 OpenClaw 功能映射

| OpenClaw 功能 | JavaClaw 对应实现 | 状态 |
|---------------|------------------|------|
| `workspace/SOUL.md` 文件 | `agent.soul_md` 数据库字段 | 本次实现 |
| `workspace/USER.md` 文件 | `agent.user_md` 数据库字段 | 本次实现 |
| `workspace/AGENTS.md` 文件 | `agent.agents_md` 数据库字段 | 本次实现 |
| 每次会话注入 System Prompt | `AgentRuntime.buildSystemPrompt()` 组装 | 本次实现 |
| 单文件 20K / 总量 150K 截断 | 同样的截断策略 | 本次实现 |
| Bootstrap 仪式（首次 Q&A 生成） | 暂不实现 | 后续扩展 |
| 仅 DM 会话注入 | 暂不区分（全量注入） | 后续扩展 |
| 文件缺失占位符 | 空值跳过，不注入 | 本次实现 |
| `workspace/TOOLS.md` | 已有 `tools_enabled` JSON 字段覆盖 | 已实现 |
| `workspace/IDENTITY.md` | 由 `agent.name` + `soul_md` 覆盖 | 本次实现 |

---

## 4. 前端设计

### 4.1 交互入口

在 Sidebar 中新增"智能体配置"区域，点击后打开全屏 Modal 编辑面板。

```
┌─────────── Sidebar ──────────┐
│  JavaClaw                [X] │
│ ─────────────────────────── │
│  快捷命令                     │
│  [↻] 重置会话                 │
│  [i] 会话状态                 │
│  [⚙] 切换模型                │
│  [?] 帮助                    │
│ ─────────────────────────── │
│  智能体配置  [NEW]            │
│  [🧠] SOUL 性格设定           │
│  [👤] USER 用户信息           │
│  [📋] AGENTS 工作规范         │
│ ─────────────────────────── │
│  连接信息                     │
│  ● 已连接                    │
│  Session: abc123...          │
└──────────────────────────────┘
```

点击 SOUL / USER / AGENTS 任一按钮 → 打开对应 Tab 激活的编辑 Modal。

### 4.2 编辑 Modal 布局

```
┌──────────────────────────────────────────────────┐
│  智能体配置 — javaclaw                       [X]  │
├──────────────────────────────────────────────────┤
│  [ SOUL ]   [ USER ]   [ AGENTS ]    ← Tab 切换  │
├──────────────────────────────────────────────────┤
│                                                   │
│  ## SOUL — 性格与行为                              │
│  定义助手的性格、语气风格和回答边界                    │
│                                                   │
│  ┌──────────────────────────────────────────┐    │
│  │ (Markdown 编辑器)                         │    │
│  │                                          │    │
│  │ 你是一个友好且专业的技术助手。              │    │
│  │ 你擅长 Java / Spring 技术栈...           │    │
│  │                                          │    │
│  │                                          │    │
│  │                                    12/20K │    │
│  └──────────────────────────────────────────┘    │
│                                                   │
│              [ 取消 ]    [ 保存 ]                  │
└──────────────────────────────────────────────────┘
```

**Tab 内容定义：**

| Tab | 标题 | 描述文案 | Placeholder |
|-----|------|---------|-------------|
| SOUL | 性格与行为 | 定义助手的性格、语气风格和回答边界 | 例如：你是一个友好且专业的技术助手，擅长 Java/Spring 技术栈... |
| USER | 用户信息 | 描述你是谁，帮助助手更好地理解你 | 例如：我是一名后端开发工程师，使用 Java 21 + Spring Boot... |
| AGENTS | 工作规范 | 设定助手的工作习惯、回复格式和行为准则 | 例如：回复使用中文，代码注释使用英文，优先使用项目现有工具类... |

### 4.3 后端 API 设计

新增 REST API 供前端读写智能体配置。

**读取当前智能体配置：**

```
GET /api/agent/{name}

Response 200:
{
  "name": "javaclaw",
  "model": "qwen-plus",
  "soulMd": "你是一个友好的技术助手...",
  "userMd": "我是一名后端开发...",
  "agentsMd": "回复使用中文..."
}
```

**更新智能体上下文文件：**

```
PUT /api/agent/{name}/context

Request Body:
{
  "soulMd": "...",
  "userMd": "...",
  "agentsMd": "..."
}

Response 200:
{ "success": true }
```

### 4.4 前端组件设计

```
src/components/
├── AgentConfigModal.tsx   [NEW]  ← 主 Modal 容器 + Tab 切换
├── AgentContextEditor.tsx [NEW]  ← 单个 Tab 的 Markdown 编辑区
├── Sidebar.tsx            [MOD]  ← 新增"智能体配置"入口按钮
└── App.tsx                [MOD]  ← 管理 Modal 显隐状态

src/stores/
├── chatStore.ts           [---]  ← 不变
└── agentStore.ts          [NEW]  ← 智能体配置状态管理

src/lib/
└── api.ts                 [NEW]  ← REST API 调用封装
```

**agentStore.ts 状态定义：**

```typescript
interface AgentConfig {
  name: string;
  model: string;
  soulMd: string;
  userMd: string;
  agentsMd: string;
}

interface AgentState {
  config: AgentConfig | null;
  loading: boolean;
  saving: boolean;
  dirty: boolean;          // 是否有未保存的修改

  fetchConfig: (name: string) => Promise<void>;
  updateField: (field: 'soulMd' | 'userMd' | 'agentsMd', value: string) => void;
  saveConfig: () => Promise<void>;
  resetDirty: () => void;
}
```

**AgentConfigModal.tsx 核心逻辑：**

```typescript
interface AgentConfigModalProps {
  open: boolean;
  onClose: () => void;
  initialTab?: 'soul' | 'user' | 'agents';
}

// Tab 定义
const TABS = [
  { key: 'soul', label: 'SOUL', field: 'soulMd', 
    title: '性格与行为', desc: '定义助手的性格、语气风格和回答边界' },
  { key: 'user', label: 'USER', field: 'userMd',
    title: '用户信息', desc: '描述你是谁，帮助助手更好地理解你' },
  { key: 'agents', label: 'AGENTS', field: 'agentsMd',
    title: '工作规范', desc: '设定助手的工作习惯、回复格式和行为准则' },
];

// 打开时自动加载当前 agent 配置
// Tab 切换时保留编辑状态（不丢失未保存内容）
// 关闭时如有未保存修改，弹出确认提示
// 保存成功后显示 Toast 提示
```

**AgentContextEditor.tsx 核心逻辑：**

```typescript
interface AgentContextEditorProps {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  maxLength: number;  // 20000
}

// 纯文本 textarea（Markdown 格式）
// 右下角显示字符计数：{length}/{maxLength}
// 超出 maxLength 时计数变红，阻止继续输入
// 支持 Tab 缩进（不跳转焦点）
// 自适应高度，最小 300px
```

### 4.5 交互流程

```
用户点击 Sidebar "SOUL 性格设定"
        │
        ▼
App.tsx 设置 modalOpen=true, initialTab='soul'
        │
        ▼
AgentConfigModal 渲染，调用 agentStore.fetchConfig('javaclaw')
        │
        ▼
GET /api/agent/javaclaw → 加载数据填充三个 Tab
        │
        ▼
用户编辑 SOUL 内容 → agentStore.updateField('soulMd', newValue)
        │                     dirty = true
        ▼
用户点击 [保存]
        │
        ▼
PUT /api/agent/javaclaw/context → 后端更新 DB
        │
        ▼
下次 LLM 调用时，AgentRuntime.buildSystemPrompt() 
读取最新的 soul_md / user_md / agents_md 组装 prompt
```

### 4.6 样式规范

- Modal 使用半透明黑色遮罩（`bg-black/50`），内容区域 `max-w-2xl` 居中
- Tab 激活态使用蓝色下划线 + 加粗（`border-b-2 border-blue-500`）
- 编辑器使用等宽字体（`font-mono`），背景色与聊天区域一致
- 保存按钮：主色蓝（`bg-blue-600`），loading 时显示旋转图标
- 适配暗色模式：所有颜色使用 `dark:` 变体
- 移动端：Modal 全屏显示（`w-full h-full`），Tab 横向可滚动

### 4.7 后端代码变更

| 层级 | 文件 | 变更 |
|------|------|------|
| Controller | `AgentController.java` [NEW] | REST API: GET/PUT 智能体配置 |
| Repository | `AgentRepository.java` [MOD] | 新增 `updateContext()` 自定义 SQL |

**AgentController 路由定义：**

```java
@RestController
@RequestMapping("/api/agent")
public class AgentController {
    
    @GetMapping("/{name}")
    public Mono<AgentConfigDto> getAgent(@PathVariable String name);
    
    @PutMapping("/{name}/context")
    public Mono<Void> updateContext(@PathVariable String name,
                                    @RequestBody AgentContextDto dto);
}
```

---

## 5. 后续扩展点

1. **Bootstrap 仪式**：新智能体首次对话时自动引导用户完成 SOUL/USER/AGENTS 设定
2. **Markdown 预览**：编辑器支持实时 Markdown 渲染预览（分屏模式）
3. **DM/群组区分**：群组会话不注入 USER.md（保护隐私）
4. **版本历史**：SOUL/USER/AGENTS 修改时记录变更历史，支持回退
5. **斜杠命令**：`/soul`、`/user`、`/agents` 在聊天中快速查看/编辑
6. **多智能体切换**：Sidebar 添加智能体选择器，切换不同 agent 的配置和会话
7. **导入/导出**：支持将三文件导出为 `.md` 文件，或从文件导入
