# 非标准 Skill 导入增强 -- 设计文档

> 日期：2026-05-28
> 状态：已批准，待实施

## 目标

让 IntelliMate 能够导入任意结构的 skill，特别是以 Git 仓库形式存在的项目型 skill（如包含 `Package.swift`、`Sources/`、`docs/` 等的 Swift 包项目）。同时增强现有的 SKILL.md 解析器，支持多行 YAML、非标准字段、无 frontmatter 文档等场景。

## 背景

### 当前限制

前端解析层（`skillImporter.ts`）：

- 手写逐行 `key: value` 解析，不支持多行 YAML 值（如 `description: |`）
- 非标准字段（`read_when`、`allowed-tools`、`homepage`）被丢弃
- YAML 列表值解析错乱
- 名称含空格不符合校验正则
- 仅支持粘贴文本导入

后端存储层（`SkillFileService.java`）：

- 文件管理硬编码只允许 `scripts`、`references`、`assets` 三种子目录
- 无法存储任意目录结构
- 没有 Git 克隆能力

### 已确认需求

| 决策点 | 结论 |
|--------|------|
| 核心场景 | Git 仓库型 skill（如 Swift 包项目） |
| 更新策略 | 保留 `.git`，UI 提供「同步更新」按钮 |
| 仓库访问 | 只支持公开仓库（https:// 协议） |
| SKILL.md 缺失 | 拒绝导入，必须有 SKILL.md |
| 优先级 | 前端解析增强与 Git 导入同等优先 |
| 技术方案 | 全栈一体化（后端执行 Git 操作） |

## 架构设计

### 系统交互流

```
用户输入 Git URL
    |
    v
前端 POST /api/skills/import/git { gitUrl, branch? }
    |
    v
后端 SkillGitService
    |-- 校验 URL（仅 https://）
    |-- git clone --depth 1 到 skills/{name}/
    |-- 检查 SKILL.md 是否存在（不存在则 400）
    |-- SnakeYAML 解析 frontmatter
    |-- normalizeSkillName（空格转连字符等）
    |-- 创建 DB 记录（含 git_url 字段）
    |-- 返回 SkillDefinition
    |
    v
前端展示导入结果 + 文件树预览
```

### 同步更新流

```
用户点击「同步更新」
    |
    v
前端 POST /api/skills/{id}/git/sync
    |
    v
后端 SkillGitService
    |-- 找到 skill 目录
    |-- 执行 git pull
    |-- 重新读取 SKILL.md 更新 DB（description 等）
    |-- 返回更新后的 SkillDefinition
```

## 详细设计

### 1. 数据库变更

新增迁移文件 `V{n}__skill_git_fields.sql`：

```sql
ALTER TABLE skill_definition ADD COLUMN git_url VARCHAR(512) NULL COMMENT 'Git 仓库来源 URL';
ALTER TABLE skill_definition ADD COLUMN git_sub_path VARCHAR(256) NULL COMMENT 'monorepo 中的子目录路径';
```

`SkillDefinitionEntity` 新增 `gitUrl` 和 `gitSubPath` 字段。

### 2. 后端新增 SkillGitService

文件：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/SkillGitService.java`

职责：

- `cloneAndRegister(gitUrl, branch, subPath, nameOverride, descOverride)` -- 克隆并注册
- `syncSkill(skillName)` -- 同步更新（重新 clone + 提取 subPath）
- `parseSkillMdFrontmatter(Path skillMdPath)` -- 用 SnakeYAML 解析 frontmatter
- `normalizeSkillName(String raw)` -- 名称规范化

子目录导入流程：

1. `git clone --depth 1` 到临时目录
2. 如果指定了 `subPath`，校验子目录存在且包含 `SKILL.md`
3. 将 `subPath` 子目录内容复制到 `skills/{name}/`（不含 `.git`）
4. DB 中保存 `git_url` + `git_sub_path`，用于后续同步
5. 同步时：重新 clone 到临时目录 -> 提取 subPath -> 覆盖目标（保留本地 `.git` 不覆盖）
6. 整仓库导入（无 subPath）：直接 clone 到 `skills/{name}/`，保留 `.git`，同步用 `git pull`

安全约束：

- 只允许 `https://` 协议
- clone 超时 30 秒
- 目录大小上限 100MB（可配置）
- subPath 路径遍历防护（不允许 `..`）

Git 操作使用 `ProcessBuilder` 执行 `git` 命令（无需引入 JGit 依赖，保持轻量）。

### 3. 后端 Controller 新增端点

文件：`SkillDefinitionController.java`

```
POST /api/skills/import/git
  请求体：{ gitUrl: string, branch?: string, subPath?: string, name?: string, description?: string }
  响应：SkillDefinition

POST /api/skills/{id}/git/sync
  响应：SkillDefinition（更新后）
```

### 4. 后端 SkillFileService 扩展

新增方法：

```java
// 返回完整文件树（排除 .git, .build, node_modules）
public FileNode listAllFiles(String skillName)

// 读取任意相对路径的文件内容（只读预览）
public String readFileByPath(String skillName, String relativePath)
```

新增 Controller 端点：

```
GET /api/skills/{id}/tree
  响应：FileNode（树形结构）

GET /api/skills/{id}/files/read?path=Sources/main.swift
  响应：文件内容（纯文本）
```

`FileNode` 结构：

```java
public record FileNode(
    String name,
    String path,
    boolean isDirectory,
    long size,
    List<FileNode> children
) {}
```

排除目录白名单（可配置）：`.git`, `.build`, `node_modules`, `__pycache__`, `.DS_Store`

路径安全：`readFileByPath` 必须校验 `relativePath` 解析后仍在 skill 目录内。

### 5. 前端解析增强

文件：`intellimate-web/src/lib/skillImporter.ts`

新增依赖：`yaml`（yaml@2.x）

`ParsedSkill` 接口扩展：

```typescript
export interface ParsedSkill {
  name?: string;
  displayName?: string;
  description?: string;
  content: string;
  tags?: string;
  metadata?: Record<string, unknown>;
  extraFields?: Record<string, unknown>;
  warnings?: string[];
}
```

`parseSkillMd()` 重写逻辑：

1. 用正则 `/^---\n([\s\S]*?)\n---\n([\s\S]*)$/` 分离 frontmatter 和 body
2. 有 frontmatter：用 `yaml.parse()` 解析
   - 标准字段直接映射：`name`, `displayName`/`display_name`, `description`, `tags`, `metadata`
   - 非标准字段收集到 `extraFields`
3. 无 frontmatter：智能推断
   - name：从 `# Title` 标题提取，转 kebab-case
   - description：标题下方第一段非空文本，截断 500 字符
   - 添加 warnings
4. name 经过 `normalizeSkillName()` 处理

新增 `normalizeSkillName(raw: string): string`：

- 空格、下划线转连字符
- 移除 `[^a-zA-Z0-9_-]` 字符
- 确保以字母开头
- 截断到 128 字符
- 如果修改了原始值，返回 warning

### 6. 前端导入 UI

文件：`intellimate-web/src/components/SkillManagerModal.tsx`

导入页面改为 3 个 Tab：

| Tab | 入口 | 处理 |
|-----|------|------|
| 粘贴文本 | textarea | 前端 parseSkillMd() |
| 上传 .md | file input (accept=".md") | FileReader 读取后走 parseSkillMd() |
| Git 导入 | URL input + branch input | 调用 POST /api/skills/import/git |

粘贴/上传模式：解析后展示预览卡片（name、description、warnings），确认后预填充到 SkillEditor。

Git 模式：输入 URL + 可选 branch + 可选 subPath（monorepo 子目录）-> 点击克隆 -> loading -> 成功后展示 skill 信息 + 文件树 -> 跳转到 skill 列表。

### 7. 前端文件树 + 同步按钮

文件：`intellimate-web/src/components/SkillEditor.tsx`

对于有 `git_url` 的 skill：

- 文件管理区调用 `GET /api/skills/{id}/tree` 展示完整文件树
- 树形可折叠组件，`SKILL.md` 高亮标记
- 点击文件可预览内容（调用 `/files/read?path=...`）
- 顶部显示「同步更新」按钮（调用 `POST /api/skills/{id}/git/sync`）

对于无 `git_url` 的普通 skill：保持现有的 scripts/references/assets 三类管理不变。

### 8. 前端 API 层新增

文件：`intellimate-web/src/lib/api.ts`

```typescript
// Git 导入
export async function importSkillFromGit(params: {
  gitUrl: string;
  branch?: string;
  subPath?: string;
  name?: string;
  description?: string;
}): Promise<SkillDefinition>;

// Git 同步
export async function syncSkillFromGit(id: number): Promise<SkillDefinition>;

// 文件树
export interface FileNode {
  name: string;
  path: string;
  isDirectory: boolean;
  size: number;
  children: FileNode[];
}
export function fetchSkillTree(id: number): Promise<FileNode>;

// 文件内容读取
export function readSkillFile(id: number, path: string): Promise<string>;
```

`SkillDefinition` 接口新增 `gitUrl: string | null` 和 `gitSubPath: string | null` 字段。

## 安全考虑

- Git URL 只允许 `https://` 协议，禁止 `file://`、`ssh://`、`git://`
- clone 操作设 30 秒超时
- 目录大小限制 100MB
- 文件路径读取严格校验，防止路径遍历
- name 去重校验，防止覆盖已有 skill

## 不改动的部分

- 导出 API（`/export` 和 `/export/zip`）保持不变
- Agent 运行时读取逻辑不变（`getSkillContent` 仍读 SKILL.md）
- 现有 scripts/references/assets 上传/删除接口保持向后兼容
- DB 原有字段和表结构不变（仅新增 `git_url` 列）

## 实施任务拆分

1. 添加 `yaml` 前端依赖 + DB 迁移（git_url 字段）
2. 重写 `parseSkillMd()` + `normalizeSkillName()` + `parsedToCreate()` 适配
3. 后端 `SkillGitService`（克隆 + 解析 + 同步）
4. 后端 `SkillFileService` 扩展（listAllFiles + readFileByPath）
5. 后端 Controller 新增端点（import/git、git/sync、tree、files/read）
6. 前端导入 UI 改版（3 Tab + 预览 + warnings）
7. 前端文件树组件 + 同步按钮
8. 前端 API 层新增函数 + `SkillDefinition` 接口扩展
