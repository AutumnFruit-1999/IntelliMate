# Skills 运行机制深度解析 — 它不是工具的包装器

> 日期: 2026-03-12  
> 目的: 纠正之前设计方案中对 Skills 本质的误解，深入分析 Skills 在各框架中的真实运行机制

---

## 1. 核心问题：Skills 到底是什么？

**Skills 不是工具 (Tools) 的包装器或绑定层。**

之前的设计方案中有一个 `allowed_tools` 字段用于"关联工具"，这是一个概念偏差。让我们从其他框架的实际实现来理解 Skills 的真实本质。

### 1.1 一句话定义

> **Skill = 一份写给 Agent 的"新员工入职指南"**

你不是在给 Agent 绑定工具，而是在**教它一项技能** — 告诉它在特定场景下应该怎么思考、怎么做、按什么流程执行、遵循什么规范。

### 1.2 Tools vs Skills 的根本区别

| 维度 | Tools（工具） | Skills（技能） |
|------|-------------|--------------|
| **本质** | 可执行的原子操作 | 知识 + 工作流 + 脚本的集合 |
| **回答的问题** | "我能做什么操作？" | "在这个场景下我应该怎么做？" |
| **形式** | 代码（函数/API/MCP） | Markdown 指令 + 可选脚本 |
| **执行者** | 框架调用回调函数 | Agent 自主阅读指令并执行 |
| **粒度** | 单一操作（读文件、搜索网页） | 完整工作流（生成报告、审查代码） |
| **谁决定使用** | Agent 选择工具调用 | Agent 匹配描述后自主激活 |

**类比**：
- **工具** = 给你一把锤子、一把螺丝刀、一把锯子
- **技能** = 给你一本《如何装修房间》的指南（指南里可能会说"用锤子敲钉子"，但锤子不属于指南）

---

## 2. 其他框架中 Skills 的真实运行方式

### 2.1 AgentSkills 标准 (Anthropic) — 以 PDF 处理为例

这是 Anthropic 官方文档中的真实案例。Claude 原本就"知道"PDF 是什么，但不知道如何**操作** PDF（填表、合并、提取）。PDF Skill 教会了它。

#### Skill 目录结构

```
pdf/
├── SKILL.md              ← 主指令文件
├── scripts/
│   └── extract_fields.py ← Skill 自带的 Python 脚本
├── references/
│   ├── reference.md      ← 补充文档
│   └── forms.md          ← 表单填充的详细指令
└── assets/
    └── template.pdf      ← 模板资源
```

#### SKILL.md 内容（简化）

```markdown
---
name: pdf-processing
description: Extract PDF text, fill forms, merge files. Use when handling PDFs.
---

# PDF Processing

## When to use this skill
Use this skill when the user needs to work with PDF files.

## How to extract text
1. Use pdfplumber for text extraction:
   ```bash
   uv run scripts/extract_fields.py input.pdf
   ```

## How to fill forms
See [references/forms.md](references/forms.md) for detailed form-filling instructions.
```

**关键观察**：
- Skill **自带了脚本** (`scripts/extract_fields.py`)，不依赖系统预装的工具
- Skill **自带了参考文档** (`references/forms.md`)，Agent 按需读取
- 指令中**没有绑定任何系统工具**，而是直接告诉 Agent 用 bash 运行脚本
- Agent 通过**自己的 bash/文件读写能力**来执行脚本，不需要专门的 Tool Callback

#### 运行时 Context Window 变化

```
初始状态（Session 启动）:
┌──────────────────────────────────┐
│ System Prompt                     │
│ ...                               │
│ Available Skills:                 │  ← Level 1: 仅 name + description (~100 tokens)
│ - pdf-processing: Extract PDF...  │
│ - code-review: Review code...     │
└──────────────────────────────────┘

用户说 "帮我填写这个 PDF 表单":
┌──────────────────────────────────┐
│ System Prompt                     │
│ ...                               │
│ [pdf-processing SKILL.md 全文]    │  ← Level 2: Agent 自己读取完整 SKILL.md
│ ...                               │
│ User: 帮我填写这个 PDF 表单        │
└──────────────────────────────────┘

Agent 发现需要表单指令:
┌──────────────────────────────────┐
│ ...                               │
│ [references/forms.md 内容]        │  ← Level 3: Agent 按需读取引用文件
│ ...                               │
└──────────────────────────────────┘

Agent 执行脚本:
  → bash: uv run scripts/extract_fields.py form.pdf    ← 直接运行脚本，不进入 context
  → 获得结果 JSON → 继续处理
```

### 2.2 真实 Skill 示例 1: README 生成器

```markdown
---
name: readme-writer
description: Creates and writes professional README.md files for software projects.
  Use when user asks to "write a README", "create a readme", "document this project",
  "generate project documentation", or "help me write a README.md".
---

# README Writer

## Step 1: Gather project context
Look for context in the codebase before asking the user:
```bash
ls -la
cat package.json 2>/dev/null || cat pyproject.toml 2>/dev/null || echo "No manifest"
ls .env.example .env.sample 2>/dev/null || echo "No env example"
```

Gather:
- What does this project do?
- What language and main frameworks does it use?
- How do you install and run it?

## Step 2: Write the README
Use this structure:
```
# Project Name
One clear sentence describing what this project does.

## Features
- Feature one (be specific)

## Prerequisites
List what needs to be installed.

## Installation
Step-by-step setup. Every command must be copy-pasteable.

## Usage
Show the most common use case first.

## License
```

## Step 3: Write the file to disk
```bash
cat > README.md << 'EOF'
[full readme content]
EOF
```

## Step 4: Quality check
- [ ] No placeholder text remains
- [ ] Every command in Installation is accurate
- [ ] Prerequisites match actual needs
```

**关键观察**：
- **没有绑定任何工具**
- Skill 直接在指令中写了 bash 命令让 Agent 执行
- Agent 用自己已有的能力（bash、file_write）来完成工作
- Skill 提供的是**工作流程 + 质量标准 + 输出格式**

### 2.3 真实 Skill 示例 2: 代码审查（多文件）

```
code-reviewer/
├── SKILL.md
└── references/
    └── criteria.md      ← 详细的审查标准（仅审查时加载）
```

**SKILL.md** (~40 行，保持精简):

```markdown
---
name: code-reviewer
description: Conducts structured code reviews with categorized feedback. Use when
  user asks to "review this code", "check my PR", "look over this function".
---

# Code Reviewer

## Step 1: Understand context
- What is this code supposed to do?
- What language and framework is it using?

## Step 2: Run the review
For detailed review criteria, see [references/criteria.md](references/criteria.md).

## Step 3: Structure the output
## Summary
[2-3 sentence overview]

## Blocking Issues
[Must-fix: security vulnerabilities, logic errors. If none, write "None found."]

## Suggestions
[Non-blocking improvements, numbered.]

## Positive Notes
[What the code does well. Always include at least one.]
```

**references/criteria.md** (详细标准，仅在审查时加载):

```markdown
# Review Criteria

## Security (Check First)
- SQL injection: are user inputs parameterized?
- XSS: is output properly escaped?
- Auth checks: are protected routes actually protected?
- Secrets: are API keys hardcoded?

## Correctness
- Does the logic match the stated intent?
- Are edge cases handled?
- Are async operations awaited properly?

## Readability
- Can a new team member understand this in 5 minutes?
- Are variable names descriptive?

## Performance
- Are there N+1 query patterns?
- Are expensive operations inside loops?
```

**关键观察**：
- SKILL.md 只有 ~40 行，保持 Level 2 加载精简
- 详细标准放在 `references/` 中，Agent **按需读取**（Level 3）
- 完全没有"绑定"任何工具，Agent 自己读代码、分析、输出结果

### 2.4 真实 Skill 示例 3: Sprint 规划器（MCP 增强型）

这个例子展示了 Skill 如何**与 MCP 协同**（而非"绑定"）:

```markdown
---
name: linear-sprint-planner
description: Automates Linear sprint planning. Use when user says "plan the sprint",
  "set up the next cycle", "prioritize the backlog".
metadata:
  mcp-server: linear
  version: 1.0.0
---

# Linear Sprint Planner

## Prerequisites
Verify the Linear MCP server is connected. If not available, tell the user to
connect it before continuing.

## Step 1: Gather current state
Fetch from Linear in sequence:
1. Current active cycle and completion percentage
2. All backlog issues
3. Team members and current workload

See [references/linear-api.md](references/linear-api.md) for pagination handling.

## Step 2: Analyze capacity
- Count team members
- Estimate points (default: 10 per person per week)
- Subtract planned time off

## Step 3: Prioritize backlog
Sort in this order:
1. P0/P1 bugs and blockers (always include)
2. Items explicitly flagged by the user
3. Items that unblock other teams
4. Features by product priority

## Step 4: Present for approval
**Always wait for confirmation before making changes.**

## Step 5: Create and confirm
Once approved:
1. Create the cycle
2. Add each issue
3. Return summary with the Linear cycle link
```

**关键观察**：
- Skill 的 `metadata` 中**声明**了需要 Linear MCP 服务（这是"声明依赖"，不是"绑定工具"）
- Skill 指令告诉 Agent **如何使用** MCP 提供的能力来完成工作
- 如果 MCP 没连接，Skill 指令里说"告诉用户去连接"
- Skill 提供的是**业务流程**（gather → analyze → prioritize → present → confirm → create）

---

## 3. `allowed-tools` 字段的真实含义

AgentSkills 标准中确实有 `allowed-tools` 字段，但它的含义不是"绑定"，而是**预授权**:

```markdown
---
name: pdf-processing
allowed-tools: Bash(python:*) Read Write
---
```

含义：
- 这个 Skill **被允许**让 Agent 使用 Bash（限制为 python 命令）、Read、Write 这几个工具
- 这是**安全控制**，不是功能绑定
- 不在列表里的工具，Agent 在执行这个 Skill 时不应该调用

**在 JavaClaw 的场景中**：我们的 Agent 已经有了 tool callback 的权限控制（`toolsEnabled`），所以 `allowed-tools` 的安全控制语义可以与现有的 tools 过滤机制结合，**不需要在 Skill 层面额外绑定工具**。

---

## 4. 三阶段渐进式加载的关键

这是 Skills 设计中最精妙的部分，也是与之前设计方案最大的差异:

### Level 1: Discovery（始终加载，~100 tokens/skill）

Session 启动时，只有 name + description 进入 system prompt:

```
Available skills:
- readme-writer: Creates professional README.md files...
- code-reviewer: Conducts structured code reviews...
- sprint-planner: Automates Linear sprint planning...
```

**Agent 据此判断是否激活**。description 写得好不好，决定了 Skill 能不能被正确触发。

### Level 2: Activation（Agent 自主触发，<5000 tokens）

当 Agent 判断当前任务匹配某个 Skill 时，它**自己**读取完整 SKILL.md:

```
# Agent 内部行为（用户看不到）
bash: cat ~/.skills/readme-writer/SKILL.md
→ 完整指令进入 context
```

**关键**: 这不是框架自动注入的，是 **Agent 自主决定** 读取的。

### Level 3: Execution（按需加载，无限扩展）

Agent 在执行过程中按需读取 references、运行 scripts:

```
# Agent 读取引用文件
bash: cat references/criteria.md

# Agent 运行脚本（脚本本身不进入 context）
bash: python scripts/extract_fields.py input.pdf
→ 只有输出结果进入 context
```

**关键**: scripts 运行后只有**输出结果**进入 context，脚本代码本身不消耗 tokens。

---

## 5. 对 JavaClaw 设计方案的修正

### 5.1 移除 `allowed_tools` 字段

**之前**: `skill_definition` 表有 `allowed_tools TEXT` 字段
**修正**: 移除此字段。Skills 不绑定工具，Agent 自主决定使用哪些工具。

### 5.2 Skill 的完整目录结构

之前的设计把 Skill 简化为"一段 Markdown 文本存在数据库里"，这丢失了 Skills 最强大的部分 — **自带脚本和引用文件**。

修正后的 Skill 结构:

```
每个 Skill 在数据库中存储:
  - name           (唯一标识)
  - description     (触发描述，最关键的字段)
  - content         (完整的 SKILL.md 正文，MEDIUMTEXT)
  - metadata        (JSON, 兼容 AgentSkills metadata)

每个 Skill 在文件系统中可选存储:
  /skills/{skill-name}/
  ├── SKILL.md           ← 与 DB content 同步
  ├── scripts/           ← 可选: 可执行脚本
  ├── references/        ← 可选: 补充文档
  └── assets/            ← 可选: 模板/资源
```

### 5.3 修正后的注入策略

**之前（错误）**: 框架把 Skills 的完整指令直接拼接进 system prompt

**修正（正确）**: 采用真正的三阶段渐进式加载

```
System Prompt 仅包含:
  ## SKILLS
  You have the following skills available.
  When a task matches a skill, read its full instructions before proceeding.

  Available skills:
  - readme-writer: Creates and writes professional README.md files...
  - code-reviewer: Conducts structured code reviews...

  Skill files are located at: /skills/{skill-name}/SKILL.md
  To activate a skill, read its SKILL.md file first.
```

**Agent 自主激活流程**:
1. 用户说"帮我审查这段代码"
2. Agent 匹配 description → "code-reviewer" skill
3. Agent 自己执行: `read /skills/code-reviewer/SKILL.md`（Level 2）
4. 如果 SKILL.md 引用了 references，Agent 自己读取（Level 3）
5. 如果 SKILL.md 包含脚本命令，Agent 自己执行（Level 3）

### 5.4 重新理解 `description` 字段的重要性

`description` 不是给人看的文案，它是 **Agent 的触发条件**。

**坏的描述**:
```
description: 帮助处理文档
```

**好的描述**:
```
description: Creates and writes professional README.md files for software projects.
  Use when user asks to "write a README", "create a readme", "document this project",
  "generate project documentation", or "help me write a README.md".
```

好的 description 包含:
1. **做什么** — 一句话说清功能
2. **什么时候触发** — 列出用户可能说的话（触发短语）

### 5.5 修正后的数据模型

```sql
CREATE TABLE `skill_definition` (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    name             VARCHAR(128) NOT NULL UNIQUE,   -- 唯一标识
    display_name     VARCHAR(256) NULL,              -- 显示名称
    description      TEXT NOT NULL,                  -- 触发描述（最关键）
    content          MEDIUMTEXT NULL,                -- SKILL.md 正文
    tags             VARCHAR(512) NULL,              -- 标签
    metadata         JSON NULL,                      -- 额外元数据
    has_scripts      TINYINT DEFAULT 0,              -- 是否有 scripts/ 目录
    has_references   TINYINT DEFAULT 0,              -- 是否有 references/ 目录
    enabled          TINYINT DEFAULT 1,
    created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

**移除了**: `allowed_tools` 字段
**新增了**: `has_scripts`, `has_references` 用于前端展示 Skill 的完整性

---

## 6. Skills 与 Tools/MCP 的正确关系

```
┌──────────────────────────────────────────────────────────┐
│                      Agent Runtime                        │
│                                                          │
│  Skills 教 Agent "怎么做"          Tools 给 Agent "能力"  │
│  ┌──────────────────────┐     ┌──────────────────────┐  │
│  │ "写 README 时先看     │     │ file_read()          │  │
│  │  package.json..."     │     │ file_write()         │  │
│  │                       │     │ web_search()         │  │
│  │ "代码审查先查安全     │     │ exec_shell()         │  │
│  │  再查正确性..."       │     │ mcp_linear_*()       │  │
│  └──────────────────────┘     └──────────────────────┘  │
│         ↓                              ↓                 │
│    注入 system prompt              注册 tool callbacks    │
│    Agent 阅读指令                  Agent 调用工具执行     │
│                                                          │
│  Skill 里可以说"用 web_search 搜索"，但这不是"绑定"，      │
│  而是"指令中提到了工具名"，Agent 自己决定是否真的调用。    │
└──────────────────────────────────────────────────────────┘
```

### Skills 与 MCP 的关系

- **MCP 给 Agent 连接了外部服务**（如 Linear、Jira、GitHub）
- **Skill 教 Agent 如何利用这些服务完成工作流**（如 Sprint 规划）
- Skill 的 `metadata` 中可以**声明依赖**（如 `mcp-server: linear`），但这是"声明前提条件"，不是"绑定"

### Skill 自带脚本的意义

Skills 可以自带 `scripts/` 目录，里面放置 Agent 可以执行的脚本。这意味着:

1. **Skill 可以给 Agent 带来全新的能力**，不依赖系统预装的工具
2. 例如 PDF 处理 Skill 自带 `scripts/extract_fields.py`，Agent 通过 bash 执行它
3. 脚本可以声明自己的依赖（如 Python PEP 723 inline dependencies），通过 `uv run` 自动安装运行
4. **脚本运行不消耗 context tokens** — 只有输出结果进入 context

---

## 7. 修正后的设计要点总结

| 原设计 | 修正后 |
|--------|--------|
| `allowed_tools` 字段绑定工具 | 移除此字段，Skills 不绑定工具 |
| 框架把完整指令拼进 system prompt | 仅注入 name+description 列表，Agent 自主激活 |
| Skill = 一段 Markdown 文本 | Skill = 目录（SKILL.md + scripts/ + references/ + assets/） |
| 只存数据库 | 数据库存元数据 + 文件系统存完整目录（含脚本） |
| 静态注入 | 三阶段渐进式: Discovery → Activation → Execution |
| description 是给用户看的 | description 是 Agent 的触发条件，必须包含触发短语 |

---

## 8. 参考资料

- [Anthropic: Equipping agents for the real world with Agent Skills](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills)
- [AgentSkills: What are skills?](https://agentskills.io/what-are-skills)
- [AgentSkills: Using scripts in skills](https://agentskills.io/skill-creation/using-scripts)
- [The SKILL.md Pattern: How to Write AI Agent Skills That Actually Work](https://bibek-poudel.medium.com/the-skill-md-pattern-how-to-write-ai-agent-skills-that-actually-work-72a3169dd7ee)
- [Anthropic Skills Repository (90k+ stars)](https://github.com/anthropics/skills)
- [OpenClaw Skills Documentation](https://docs.openclaw.ai/skills)
