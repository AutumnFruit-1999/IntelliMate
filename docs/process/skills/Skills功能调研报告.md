# Skills 功能调研报告

> 调研时间: 2026-03-12  
> 调研对象: OpenClaw、DeerFlow 2.0、AgentSkills 开放标准  
> 调研目的: 为 JavaClaw 项目引入 Skills 功能提供设计参考

---

## 1. 什么是 Skills

Skills 是 AI Agent 系统中位于 **Tools (原子工具)** 之上的一层能力抽象。

| 层次 | 说明 | 举例 |
|------|------|------|
| **Tools** | 原子操作，单一功能 | `file_read`, `web_search`, `exec_shell` |
| **Skills** | 高级能力包，编排多工具 + 注入领域知识 | "研究报告生成"、"PDF 处理"、"代码审查" |

核心区别:
- **工具**回答 "能做什么操作"（What can I do?）
- **技能**回答 "应该怎么做"（How should I do it?）

Skill 本质上是一份**结构化的 Markdown 指令**，告诉 Agent 在特定场景下如何组合工具完成任务。

---

## 2. OpenClaw Skills 体系

### 2.1 整体架构

OpenClaw 的 Skills 系统采用**声明式、基于文件**的设计，每个 Skill 是一个目录，包含一个 `SKILL.md` 文件。

```
~/.openclaw/skills/
├── pdf-processing/
│   └── SKILL.md
├── code-review/
│   └── SKILL.md
└── research/
    └── SKILL.md
```

### 2.2 SKILL.md 格式

```markdown
---
name: pdf-processing
description: Extract text and tables from PDF files, merge documents, fill forms.
license: MIT
metadata: {"openclaw": {"requires": {"bins": ["uv"], "env": ["GEMINI_API_KEY"]}}}
---

# PDF Processing

When the user asks you to work with PDF files, follow these instructions:

1. Use the `file_read` tool to read the PDF content
2. Extract tables using structured output format
3. ...
```

**Frontmatter 字段:**

| 字段 | 必需 | 说明 |
|------|------|------|
| `name` | 是 | 唯一标识符，最长 64 字符，小写+连字符 |
| `description` | 是 | 功能描述，最长 1024 字符 |
| `license` | 否 | SPDX 许可证标识 |
| `compatibility` | 否 | 兼容的 Agent 列表 |
| `metadata` | 否 | 任意 key-value 元数据 |
| `allowed-tools` | 否 | 预授权的工具列表（空格分隔） |
| `user-invocable` | 否 | 是否可作为用户斜杠命令（默认 true） |
| `disable-model-invocation` | 否 | 是否从模型 prompt 中排除（默认 false） |
| `command-dispatch` | 否 | 设为 `tool` 时绕过模型直接调用工具 |
| `command-tool` | 否 | 直接调用的工具名 |

### 2.3 加载优先级（三级）

```
Workspace /skills (最高优先级)
    ↓
Managed ~/.openclaw/skills (中优先级)
    ↓
Bundled Skills (最低优先级, 随安装包分发)
```

同名 Skill 按优先级覆盖：workspace > managed > bundled。

额外目录可通过 `skills.load.extraDirs` 配置（最低优先级）。

### 2.4 Gating 机制（加载时过滤）

通过 `metadata.openclaw` 定义依赖条件，不满足则跳过:

| 过滤条件 | 说明 |
|----------|------|
| `requires.bins` | 所有列出的二进制文件必须存在于 PATH |
| `requires.anyBins` | 至少一个二进制文件存在 |
| `requires.env` | 环境变量必须存在或已在 config 中提供 |
| `requires.config` | `openclaw.json` 中的路径必须为 truthy |
| `os` | 限定操作系统平台 (darwin / linux / win32) |
| `always: true` | 跳过所有过滤，始终加载 |

### 2.5 注入方式

OpenClaw 在构建 system prompt 时，将符合条件的 Skills 以紧凑 XML 格式注入:

```xml
<skills>
  <skill name="pdf-processing" description="Extract text..." location="/path/to/skill" />
  <skill name="code-review" description="Review code..." location="/path/to/skill" />
</skills>
```

**Token 开销**: 基础开销 195 字符（有 >=1 个 skill 时），每个 skill 约 97 + 字段长度字符。

### 2.6 热重载

通过 skills watcher 监控文件变更:

```json
{
  "skills": {
    "load": {
      "watch": true,
      "watchDebounceMs": 250
    }
  }
}
```

变更在下一个 Agent turn 生效。

### 2.7 多 Agent 支持

- 每个 Agent 有独立 workspace，`/skills` 对该 Agent 私有
- `~/.openclaw/skills` 对同一机器上所有 Agent 共享
- 通过 `extraDirs` 可配置共享 Skills 目录

### 2.8 注册中心: ClawHub

ClawHub 是 OpenClaw 的公共 Skills 注册表:

```bash
clawhub install <skill-name>   # 安装 skill 到 workspace
clawhub update --all           # 更新所有已安装 skills
clawhub sync --all             # 同步
```

### 2.9 配置覆盖

在 `~/.openclaw/openclaw.json` 中可按 skill 名称进行配置:

```json
{
  "skills": {
    "entries": {
      "pdf-processing": {
        "enabled": true,
        "apiKey": {"source": "env", "provider": "default", "id": "API_KEY"},
        "env": {"API_KEY": "xxx"},
        "config": {"endpoint": "https://..."}
      }
    }
  }
}
```

### 2.10 安全机制

- Workspace/extra-dir 的 skill discovery 使用 realpath 验证，防止路径穿越
- 第三方 skills 视为不可信代码
- `skills.entries.*.env` 和 `apiKey` 仅在 Agent turn 期间注入 process.env
- Skill 执行完毕后恢复原始环境

---

## 3. DeerFlow 2.0 Skills 体系

### 3.1 整体架构

DeerFlow 2.0 由 ByteDance 开源，是一个完整的 Agent 运行时（Super Agent Harness），Skills 是其四大支柱之一:

1. **Skills & Tools** — 动态任务分配
2. **Sub-Agents** — 复杂任务分解
3. **Sandbox & File System** — 隔离执行环境
4. **Context Engineering** — 优化信息展示

### 3.2 Skills 目录结构

Skills 存储在沙箱容器内:

```
/mnt/skills/
├── public/
│   ├── research/SKILL.md
│   ├── report-generation/SKILL.md
│   └── slide-creation/SKILL.md
└── custom/
    └── my-custom-skill/SKILL.md
```

### 3.3 Skill 定义格式

每个 Skill 是一个 Markdown 文件，包含:
- 工作流定义（Workflow definitions）
- 最佳实践（Best practices）
- 资源引用（Resource references）

DeerFlow 的 Skill 格式更偏向于**模板化的 Prompt 指令**，而非 OpenClaw 那样严格的 YAML frontmatter 元数据。

### 3.4 核心特性

| 特性 | 说明 |
|------|------|
| **渐进加载** | 按需加载，任务需要时才加载对应 skill，保持 context window 精简 |
| **无代码扩展** | 通过编辑 Markdown 文件自定义，无需修改 Python 代码 |
| **内置 Skills** | 研究、报告生成、幻灯片创建、网页生成、图片/视频生成 |
| **自定义 Skills** | 放入 `/mnt/skills/custom/` 目录自动识别加载 |
| **沙箱隔离** | Skills 在 Docker 容器内执行，安全隔离 |
| **复合工作流** | 可将多个 skills 组合为复合工作流 |

### 3.5 与 OpenClaw 的关键差异

| 维度 | OpenClaw | DeerFlow |
|------|----------|----------|
| **运行环境** | Node.js 进程内 | Docker 沙箱容器内 |
| **格式标准** | 严格 AgentSkills 兼容 | 较松散的 Markdown |
| **注册中心** | ClawHub 公共注册表 | 无公共注册表 |
| **多级优先级** | 三级 (workspace > managed > bundled) | 两级 (public > custom) |
| **Gating** | 丰富的依赖过滤 | 无显式 gating |
| **热重载** | 支持 (watcher) | 运行时自动识别 |

---

## 4. AgentSkills 开放标准

### 4.1 背景

AgentSkills 是由 Anthropic 于 2025 年底发起的开放标准，旨在为 AI Agent 提供一种**通用的、可移植的能力包格式**。标准维护在 [agentskills.io](https://agentskills.io/)。

核心理念: **"Write once, works everywhere"** — 一个 skill 目录可在所有兼容 Agent 中使用。

### 4.2 兼容 Agent 列表 (27+)

Claude Code, Cursor, OpenAI Codex, Gemini CLI, VS Code, GitHub Copilot, Amp, Roo Code, Goose, Windsurf, Continue 等。

### 4.3 目录结构

```
my-skill/
├── SKILL.md          # 必需: frontmatter + 指令
├── scripts/          # 可选: Agent 可调用的脚本
├── references/       # 可选: RAG/few-shot 示例和文档
└── assets/           # 可选: 模板和资源
```

### 4.4 SKILL.md 格式规范

```markdown
---
name: pdf-processing
description: Extract text and tables from PDF files,
  merge documents, fill forms, and convert to images.
license: MIT
compatibility:
  - Claude Code
  - Cursor
allowed-tools: Bash(python:*) Read Write
---

# PDF Processing

When the user asks you to work with PDF files, follow
these instructions...
```

**Frontmatter 字段规范:**

| 字段 | 必需 | 约束 |
|------|------|------|
| `name` | 是 | 最长 64 字符, 小写字母+数字+连字符 |
| `description` | 是 | 最长 1024 字符 |
| `license` | 否 | SPDX 许可证标识 |
| `compatibility` | 否 | 兼容 Agent 列表 |
| `metadata` | 否 | 任意 key-value (author, version, tags) |
| `allowed-tools` | 否 | 空格分隔的预授权工具列表 |

### 4.5 三阶段渐进式加载

这是 AgentSkills 标准最核心的设计:

```
┌─────────────────────────────────────────────────────┐
│  Phase 1: Discovery (~100 tokens)                   │
│  只读取 name + description                           │
│  Agent 据此判断是否需要激活该 skill                    │
├─────────────────────────────────────────────────────┤
│  Phase 2: Activation (<5000 tokens)                 │
│  任务匹配时加载完整 SKILL.md                          │
│  Agent 获得详细指令                                   │
├─────────────────────────────────────────────────────┤
│  Phase 3: Execution                                 │
│  按需加载 scripts/, references/, assets/              │
│  Agent 执行具体操作                                   │
└─────────────────────────────────────────────────────┘
```

**优势**: 即使安装了数百个 skills，对 context window 的影响也极小（Discovery 阶段每个 skill 仅 ~100 tokens）。

### 4.6 与 MCP 的关系

| 维度 | AgentSkills (SKILL.md) | MCP |
|------|------------------------|-----|
| **定位** | 教 Agent "怎么做" | 给 Agent 连接外部"工具" |
| **格式** | Markdown 指令 | JSON-RPC 协议 |
| **加载** | 静态文件，按需注入 prompt | 运行时连接远程服务 |
| **粒度** | 高层次工作流 | 原子操作/API 调用 |

二者互补: Skill 可以引用 MCP 工具完成任务。

---

## 5. 三大方案对比总结

| 维度 | OpenClaw | DeerFlow 2.0 | AgentSkills 标准 |
|------|----------|--------------|-----------------|
| **Skill 格式** | SKILL.md (YAML frontmatter + MD) | SKILL.md (MD 为主) | SKILL.md (YAML frontmatter + MD) |
| **存储位置** | 文件系统（三级目录） | 沙箱容器内 | 文件系统 |
| **加载策略** | Session 快照 + 热重载 | 渐进按需加载 | 三阶段渐进式 |
| **Gating/过滤** | 丰富（bins/env/config/os） | 无显式 gating | compatibility 字段 |
| **多 Agent** | 每 Agent 独立 workspace | 每 Sub-Agent 独立上下文 | 未规定 |
| **注册/分发** | ClawHub 公共注册表 | 无 | agentskills.io |
| **安全模型** | realpath 验证 + 环境隔离 | Docker 沙箱 | 未规定 |
| **Token 优化** | XML 紧凑列表 (~97 char/skill) | 渐进加载 | 三阶段渐进式 |
| **配置覆盖** | JSON 配置 per-skill | 无 | 未规定 |
| **扩展性** | 插件 + ClawHub | 自定义目录 | 脚本 + 引用 + 资源 |
| **开源协议** | 专有 + 开源 | MIT | 开放标准 |

### 5.1 各方案优劣分析

**OpenClaw**:
- 优势: 最成熟完整的 Skills 体系，丰富的 gating 和配置，有公共注册中心
- 劣势: 与 OpenClaw 运行时深度耦合，复杂度较高

**DeerFlow 2.0**:
- 优势: 沙箱隔离安全性高，无代码扩展门槛低
- 劣势: Skills 格式较松散，无标准化 gating/配置机制

**AgentSkills 标准**:
- 优势: 跨平台通用性最强，三阶段加载设计精巧，27+ Agent 兼容
- 劣势: 仅定义格式规范，具体运行时需自行实现

### 5.2 对 JavaClaw 的启示

1. **格式兼容 AgentSkills 标准**: 确保 JavaClaw 的 Skills 格式与开放标准兼容，便于生态互通
2. **借鉴 OpenClaw 的 Gating 和配置**: 实现依赖检查和 per-skill 配置覆盖
3. **参考 DeerFlow 的渐进加载**: 减少 Skills 对 Token 预算的消耗
4. **结合自身特点**: JavaClaw 使用数据库存储 + 前端管理界面，比纯文件系统更适合 Web 场景
5. **与现有 Tools/MCP 体系协同**: Skills 作为 Tools 上层编排，不替代而是增强

---

## 6. 参考资料

- [OpenClaw Skills 文档](https://docs.openclaw.ai/skills)
- [OpenClaw Creating Skills](https://docs.openclaw.ai/tools/creating-skills)
- [OpenClaw Skills Config](https://docs.openclaw.ai/tools/skills-config)
- [DeerFlow GitHub](https://github.com/bytedance/deer-flow)
- [DeerFlow 2.0 架构分析](https://www.xugj520.cn/en/archives/deerflow-super-agent-architecture.html)
- [AgentSkills 标准 (mdskills.ai)](https://www.mdskills.ai/specs/skill-md)
- [Anthropic AgentSkills 仓库](https://github.com/anthropics/skills)
- [AgentSkills 官网](https://agentskills.io/)
