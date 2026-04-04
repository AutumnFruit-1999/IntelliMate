# JavaClaw Skills 设计方案

> 版本: v2.0  
> 日期: 2026-03-12  
> 状态: Draft  
> 参考: [Skills运行机制深度解析.md](Skills运行机制深度解析.md)

---

## 1. Skills 的本质

### 1.1 Skill = 写给 Agent 的入职指南

Skill 不是工具的包装器，不是 Tool 的绑定层。**Skill 是一份自包含的知识包**，教 Agent 在特定场景下应该怎么思考、按什么流程执行、遵循什么规范。

| 维度 | Tools（工具） | Skills（技能） |
|------|-------------|--------------|
| **本质** | 可执行的原子操作 | 知识 + 工作流 + 脚本的集合 |
| **回答的问题** | "能做什么操作？" | "应该怎么做？" |
| **形式** | 函数/API/MCP | Markdown 指令 + 可选脚本 |
| **执行者** | 框架回调 | Agent 自主阅读并执行 |
| **粒度** | 单一操作 | 完整工作流 |

**类比**：工具 = 给你锤子和螺丝刀；技能 = 给你《装修指南》（指南里说"用锤子敲钉子"，但锤子不属于指南）。

### 1.2 Skills 在 JavaClaw 中的定位

```
┌──────────────────────────────────────────┐
│  System Prompt (Agent 启动时注入)         │
│  ┌────────┬────────┬──────────┐          │
│  │  SOUL  │  USER  │  AGENTS  │          │
│  └────────┴────────┴──────────┘          │
│  ┌──────────────────────────────┐        │
│  │  SKILLS (name + description) │ ← 仅索引，不含完整指令  │
│  │  Agent 据此判断何时激活      │        │
│  └──────────────────────────────┘        │
├──────────────────────────────────────────┤
│  Skill 目录 (文件系统)                    │
│  /skills/{name}/                         │
│  ├── SKILL.md        ← Agent 激活后读取   │
│  ├── scripts/        ← Agent 按需执行     │
│  ├── references/     ← Agent 按需读取     │
│  └── assets/         ← 模板/资源         │
├──────────────────────────────────────────┤
│  Tool Callbacks (Agent 可用的原子操作)     │
│  ┌──────────┬──────────┬──────────┐      │
│  │ Builtin  │ Dynamic  │   MCP    │      │
│  └──────────┴──────────┴──────────┘      │
└──────────────────────────────────────────┘
```

### 1.3 三阶段渐进式加载

这是 AgentSkills 标准的核心设计，JavaClaw 将完整实现:

| 阶段 | 何时 | 加载什么 | Token 开销 |
|------|------|----------|-----------|
| **Discovery** | Session 启动 | 仅 name + description | ~30 tokens/skill |
| **Activation** | Agent 判断任务匹配 | 完整 SKILL.md | <5000 tokens |
| **Execution** | 执行过程中 | scripts/ references/ | 脚本输出进 context，脚本本身不消耗 |

**关键**: Activation 和 Execution 阶段由 **Agent 自主决策**，不是框架自动注入。

### 1.4 设计目标

1. **Skill 是自包含的目录**，包含指令、脚本、引用文件、资源
2. **Agent 自主激活**，框架仅提供 Discovery 信息
3. **不绑定工具**，Skill 指令中可提及工具名，但 Agent 自行决定是否调用
4. **兼容 AgentSkills 开放标准**（SKILL.md 格式），支持导入/导出
5. **混合存储**: 数据库存元数据，文件系统存完整目录（含脚本）

---

## 2. Skill 的完整结构

### 2.1 目录结构

每个 Skill 是一个独立目录:

```
/skills/code-reviewer/
├── SKILL.md                ← 必需: 主指令文件
├── scripts/                ← 可选: 可执行脚本
│   └── lint-check.sh
├── references/             ← 可选: 补充文档（Agent 按需读取）
│   └── security-criteria.md
└── assets/                 ← 可选: 模板/资源
    └── report-template.md
```

### 2.2 SKILL.md 格式

兼容 AgentSkills 标准:

```markdown
---
name: code-reviewer
description: Conducts structured code reviews with categorized feedback.
  Use when user asks to "review this code", "check my PR",
  "look over this function", or "give me feedback on this implementation".
---

# Code Reviewer

## Step 1: Understand context
- What is this code supposed to do?
- What language and framework is it using?

## Step 2: Run the review
For detailed review criteria by category, see [references/security-criteria.md].

## Step 3: Structure the output

### Summary
[2-3 sentence overview]

### Blocking Issues
[Must-fix issues. If none, write "None found."]

### Suggestions
[Non-blocking improvements, numbered.]

### Positive Notes
[What the code does well. Always include at least one.]
```

### 2.3 `description` 字段的写法（最关键）

`description` 不是给用户看的功能说明，它是 **Agent 的触发条件**。

**错误写法**:
```
description: 帮助处理文档
```

**正确写法**:
```
description: Creates and writes professional README.md files for software projects.
  Use when user asks to "write a README", "create a readme",
  "document this project", or "help me write a README.md".
```

正确写法包含:
1. **做什么** — 一句话说清功能
2. **什么时候触发** — 列出用户可能说的话

---

## 3. 用户场景

### 场景 1: 创建一个"代码审查"Skill

**操作流程**:
1. 打开"Skills 管理"弹窗
2. 点击"创建 Skill"
3. 填写元数据:
   - 名称: `code-reviewer`
   - 显示名称: `代码审查`
   - 描述（触发条件）: `Conducts structured code reviews. Use when user asks to "review this code", "check my PR", "look over this function".`
   - 标签: `开发, 质量`
4. 在 Markdown 编辑区编写完整的 SKILL.md 正文（工作流程、输出格式、质量标准）
5. 可选: 上传脚本文件（如 `lint-check.sh`）和参考文档（如 `security-criteria.md`）
6. 保存

### 场景 2: 为 Agent 启用 Skills

1. 点击目标 Agent 卡片
2. 切换到 "Skills" Tab
3. 勾选 `code-reviewer` 和 `readme-writer`
4. 保存

### 场景 3: Agent 运行时自主激活

1. 用户对 Agent 说: "帮我审查这段代码"
2. Agent 在 system prompt 中看到 `code-reviewer` 的 description 匹配当前任务
3. Agent 自主读取 `/skills/code-reviewer/SKILL.md` 完整内容
4. Agent 按指令流程执行审查（如需要，读取 `references/security-criteria.md`）
5. Agent 按指定格式输出审查结果

### 场景 4: 导入外部 SKILL.md

1. 从 ClawHub 获取一个 Skill
2. 在"Skills 管理"弹窗点击"导入"
3. 粘贴 SKILL.md 内容
4. 系统解析 frontmatter 中的 name、description
5. 用户可上传附带的 scripts/ 和 references/ 文件
6. 保存

---

## 4. 功能设计

### 4.1 Skill 数据模型

```
skill_definition {
  id:               BIGINT (PK)
  name:             VARCHAR(128) UNIQUE   -- 唯一标识
  display_name:     VARCHAR(256)          -- 显示名称
  description:      TEXT NOT NULL         -- 触发描述（最关键字段）
  content:          MEDIUMTEXT            -- SKILL.md 正文
  tags:             VARCHAR(512)          -- 逗号分隔标签
  metadata:         JSON                  -- 兼容 AgentSkills metadata
  has_scripts:      TINYINT DEFAULT 0     -- 是否有 scripts/ 目录
  has_references:   TINYINT DEFAULT 0     -- 是否有 references/ 目录
  enabled:          TINYINT DEFAULT 1     -- 全局启用/禁用
  created_at:       DATETIME
  updated_at:       DATETIME
}
```

**与旧版区别**: 移除了 `allowed_tools` 字段，新增 `has_scripts` / `has_references`。

### 4.2 混合存储方案

```
数据库 (skill_definition 表)
  └── 存储: name, description, content(SKILL.md正文), tags, metadata, 状态

文件系统 (/skills/{name}/ 目录)
  └── 存储: SKILL.md, scripts/, references/, assets/
  └── 数据库的 content 字段与文件系统的 SKILL.md 保持同步
  └── scripts/ 和 references/ 只存在文件系统中
```

Agent 在 Activation 阶段可以:
- 从数据库读取 `content`（适用于纯文本 Skill）
- 从文件系统读取 `/skills/{name}/SKILL.md`（适用于带脚本的完整 Skill）

### 4.3 Agent 级别 Skills 选择

在 `AgentConfigModal` 中新增 "Skills" Tab:

```
┌──────────────────────────────────────────────┐
│ Agent 配置 - Skills                          │
├──────────────────────────────────────────────┤
│                                              │
│  ☑ code-reviewer                             │
│    代码审查                                   │
│    Conducts structured code reviews...       │
│    标签: 开发, 质量  |  有脚本  有引用文件     │
│                                              │
│  ☑ readme-writer                             │
│    README 生成器                              │
│    Creates professional README.md files...   │
│    标签: 文档                                │
│                                              │
│  ☐ sprint-planner                            │
│    Sprint 规划器                              │
│    Automates sprint planning...              │
│    标签: 项目管理  |  需要 Linear MCP         │
│                                              │
│  已选 2 / 共 3 个 Skills                      │
└──────────────────────────────────────────────┘
```

**存储**: Agent 表的 `skills_enabled` 字段（TEXT）:
- `null` — 不使用任何 Skill
- `"full"` — 使用所有已启用的 Skills
- `["code-reviewer","readme-writer"]` — JSON 数组

### 4.4 System Prompt 注入策略（Discovery 阶段）

框架**仅注入索引信息**到 system prompt:

```
## SKILLS

You have the following skills available. Each skill is a directory containing
instructions, scripts, and reference files.

When a user's request matches a skill's description, read the skill's full
instructions before proceeding:
  - Read: /skills/{skill-name}/SKILL.md

Available skills:
- code-reviewer: Conducts structured code reviews. Use when user asks to
  "review this code", "check my PR", "look over this function".
- readme-writer: Creates professional README.md files. Use when user asks
  to "write a README", "document this project".
```

**Agent 后续行为由 Agent 自主决策**:
- 匹配到 skill → 读取 SKILL.md（Activation）
- 指令中引用了 references/ → 读取引用文件（Execution）
- 指令中包含脚本命令 → 执行脚本（Execution）

### 4.5 Skills 管理弹窗

```
┌──────────────────────────────────────────────┐
│ Skills 管理                              [X] │
├──────────────────────────────────────────────┤
│                                              │
│ [+ 创建 Skill]  [导入 SKILL.md]              │
│                                              │
│ ┌──────────────────────────────────────────┐ │
│ │ code-reviewer              ☑ 已启用      │ │
│ │ 代码审查                                  │ │
│ │ Conducts structured code reviews...      │ │
│ │ 标签: 开发, 质量                          │ │
│ │ 包含: SKILL.md | scripts/ | references/  │ │
│ │ [编辑] [导出] [删除]                      │ │
│ └──────────────────────────────────────────┘ │
│                                              │
│ ← 1 / 1 →                                   │
├──────────────────────────────────────────────┤
│                                      [关闭]  │
└──────────────────────────────────────────────┘
```

### 4.6 Skill 编辑器

```
┌──────────────────────────────────────────────┐
│ 创建 Skill                               [X] │
├──────────────────────────────────────────────┤
│                                              │
│ 名称 (唯一标识, 小写+连字符):                  │
│ ┌──────────────────────────────────────────┐ │
│ │ code-reviewer                            │ │
│ └──────────────────────────────────────────┘ │
│                                              │
│ 显示名称:                                     │
│ ┌──────────────────────────────────────────┐ │
│ │ 代码审查                                  │ │
│ └──────────────────────────────────────────┘ │
│                                              │
│ 触发描述 (Agent 用来判断何时激活此 Skill):     │
│ ⚠️ 必须包含: ① 做什么 ② 什么时候触发         │
│ ┌──────────────────────────────────────────┐ │
│ │ Conducts structured code reviews. Use    │ │
│ │ when user asks to "review this code",    │ │
│ │ "check my PR", or "look over this..."    │ │
│ └──────────────────────────────────────────┘ │
│                                              │
│ 标签 (逗号分隔):                              │
│ ┌──────────────────────────────────────────┐ │
│ │ 开发, 质量                               │ │
│ └──────────────────────────────────────────┘ │
│                                              │
│ SKILL.md 正文 (Markdown 工作流指令):          │
│ ┌──────────────────────────────────────────┐ │
│ │ # Code Reviewer                          │ │
│ │                                          │ │
│ │ ## Step 1: Understand context            │ │
│ │ - What is this code supposed to do?      │ │
│ │ ...                                      │ │
│ │                                          │ │
│ │ ## Step 2: Run the review                │ │
│ │ See [references/criteria.md] for...      │ │
│ └──────────────────────────────────────────┘ │
│                                              │
│ 附件 (可选):                                  │
│ [+ 上传脚本]  [+ 上传引用文件]                 │
│ ┌──────────────────────────────────────────┐ │
│ │ scripts/lint-check.sh        [删除]      │ │
│ │ references/criteria.md       [删除]      │ │
│ └──────────────────────────────────────────┘ │
│                                              │
├──────────────────────────────────────────────┤
│                          [取消]    [保存]     │
└──────────────────────────────────────────────┘
```

---

## 5. 数据流

### 5.1 Skills CRUD

```
SkillManagerModal (前端)
  ↓ POST/PUT/DELETE /api/skills
SkillDefinitionController (后端)
  ↓
SkillDefinitionRepository → MySQL skill_definition 表
  +
SkillFileService → 文件系统 /skills/{name}/ 目录
```

### 5.2 Agent Skills 选择

```
AgentConfigModal > SkillsTab
  ↓ PUT /api/agent/{name} { skillsEnabled: [...] }
AgentController
  ↓
MySQL agent.skills_enabled
```

### 5.3 运行时三阶段加载

```
Session 启动 (Discovery)
  ↓
AgentRuntime.buildSystemPrompt()
  ├── SOUL / USER / AGENTS sections
  └── SKILLS section:
      仅注入 name + description 索引列表
      + Skill 目录路径提示
  ↓
用户消息到达
  ↓
Agent 判断任务匹配 "code-reviewer"

Agent 自主 Activation (Level 2):
  → file_read("/skills/code-reviewer/SKILL.md")
  → 完整指令进入 context

Agent 自主 Execution (Level 3):
  → file_read("/skills/code-reviewer/references/criteria.md")
  → exec("bash /skills/code-reviewer/scripts/lint-check.sh src/")
  → 脚本输出进入 context（脚本本身不消耗 tokens）
```

---

## 6. 与现有体系的关系

| 现有功能 | 与 Skills 的关系 |
|----------|----------------|
| **SOUL/USER/AGENTS** | 并列注入 system prompt；Skills 仅注入索引，不抢占 Token |
| **Builtin Tools** | Skill 指令中可提及工具名，Agent 自行决定是否调用 |
| **Dynamic Tools** | 同上，Skills 不绑定工具 |
| **MCP Tools** | Skill 可声明 MCP 依赖（metadata），Agent 通过 MCP 完成工作 |
| **Model** | Skills 不依赖特定模型 |

**Skills 与 Tools/MCP 的正确关系**:
- MCP 给 Agent "连接了外部服务"
- Tools 给 Agent "原子操作能力"
- **Skills 教 Agent "如何利用这些能力完成工作流"**

---

## 7. 分阶段实施

### P0: 核心功能（MVP）

| 任务 | 说明 |
|------|------|
| 数据库 Schema | `skill_definition` 表（无 allowed_tools）+ `agent.skills_enabled` |
| Skill 文件目录 | `/skills/{name}/` 目录管理 |
| 后端 CRUD API | SkillController: 列表/创建/更新/删除（含文件上传） |
| 后端注入逻辑 | buildSystemPrompt 注入 Discovery 索引 |
| 前端 SkillManagerModal | 列表、创建、编辑、删除 |
| 前端 SkillsTab | AgentConfigModal 中选择 Skills |
| 前端 SkillEditor | Markdown 编辑 + 文件上传 |

### P1: 增强功能

| 任务 | 说明 |
|------|------|
| SKILL.md 导入/导出 | 标准格式互通 |
| 标签过滤 | 列表筛选 |
| `get_skill_content` 内置工具 | 10+ Skills 时 Agent 按需获取（备选方案） |
| 内置 Skills 包 | 预置代码审查、README 生成等 |

### P2: 高级功能

| 任务 | 说明 |
|------|------|
| Skill 版本管理 | Git-like 版本追踪 |
| 执行统计 | 记录激活频率和效果 |
| 社区分享 | 导入/导出生态 |
