# JavaClaw Skills 技术架构

> 版本: v2.0  
> 日期: 2026-03-12  
> 状态: Draft  
> 前置文档: [Skills_设计方案.md](Skills_设计方案.md), [Skills运行机制深度解析.md](Skills运行机制深度解析.md)

---

## 1. 核心设计原则

1. **Skill 是自包含目录**，不是一段存在数据库里的文本
2. **Agent 自主激活**，框架仅在 system prompt 中注入 Discovery 索引
3. **不绑定工具**，没有 `allowed_tools` 字段
4. **三阶段渐进式加载**: Discovery → Activation → Execution
5. **混合存储**: 数据库存元数据 + 文件系统存完整 Skill 目录

---

## 2. 实施阶段

| 阶段 | 内容 | 涉及模块 |
|------|------|----------|
| **P0** | DB Schema + 文件目录 + 后端 CRUD + 前端管理 + Discovery 注入 | gateway, agent, web |
| **P1** | SKILL.md 导入/导出 + 标签过滤 + get_skill_content 内置工具 + 内置 Skills 包 | gateway, agent, web |
| **P2** | Skill 版本管理 + 执行统计 + 社区分享 (远期) | gateway, agent, web |

---

## 3. P0: 数据库 Schema

### 3.1 Flyway 迁移: `V9__skill_definition.sql`

```sql
CREATE TABLE IF NOT EXISTS `skill_definition` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `name`             VARCHAR(128) NOT NULL COMMENT 'Skill 唯一标识（AgentSkills 兼容, 小写+连字符）',
    `display_name`     VARCHAR(256) NULL COMMENT '显示名称',
    `description`      TEXT         NOT NULL COMMENT '触发描述（Agent 用来判断何时激活）',
    `content`          MEDIUMTEXT   NULL COMMENT 'SKILL.md 正文（Markdown 指令）',
    `tags`             VARCHAR(512) NULL COMMENT '逗号分隔的标签',
    `metadata`         JSON         NULL COMMENT '兼容 AgentSkills metadata',
    `has_scripts`      TINYINT      NOT NULL DEFAULT 0 COMMENT '是否包含 scripts/ 目录',
    `has_references`   TINYINT      NOT NULL DEFAULT 0 COMMENT '是否包含 references/ 目录',
    `enabled`          TINYINT      NOT NULL DEFAULT 1 COMMENT '全局启用/禁用',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skill_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Skill 定义';

ALTER TABLE `agent`
    ADD COLUMN `skills_enabled` TEXT NULL
    COMMENT 'JSON array of enabled skill names, null=none, "full"=all'
    AFTER `mcp_tools_enabled`;
```

### 3.2 字段说明

| 字段 | 类型 | 说明 |
|------|------|------|
| `name` | VARCHAR(128) | 唯一标识, 小写+连字符, 同时作为文件系统目录名 |
| `display_name` | VARCHAR(256) | 前端显示名称 |
| `description` | TEXT | **最关键字段**: Agent 的触发条件, 必须包含"做什么"+"什么时候触发" |
| `content` | MEDIUMTEXT | SKILL.md 正文 (与文件系统 `/skills/{name}/SKILL.md` 保持同步) |
| `tags` | VARCHAR(512) | 逗号分隔标签 |
| `metadata` | JSON | 兼容 AgentSkills metadata (如 mcp-server 依赖声明) |
| `has_scripts` | TINYINT | 标记是否有 scripts/ 目录 |
| `has_references` | TINYINT | 标记是否有 references/ 目录 |

### 3.3 `agent.skills_enabled` 语义

| 值 | 含义 |
|----|------|
| `null` | 不使用任何 Skill |
| `"full"` | 使用所有全局启用的 Skills |
| `["name1","name2"]` | JSON 数组, 指定启用的 Skill 名称 |

---

## 4. P0: 文件系统布局

### 4.1 Skills 根目录

配置项: `javaclaw.skills.dir` (默认: `./skills`)

```
{skills.dir}/
├── code-reviewer/
│   ├── SKILL.md
│   ├── scripts/
│   │   └── lint-check.sh
│   └── references/
│       └── security-criteria.md
├── readme-writer/
│   └── SKILL.md
└── sprint-planner/
    ├── SKILL.md
    └── references/
        ├── linear-api.md
        └── error-handling.md
```

### 4.2 SkillFileService

文件: `javaclaw-gateway/src/main/java/com/atm/javaclaw/gateway/service/SkillFileService.java`

负责 Skill 目录的文件系统操作:

```java
@Service
public class SkillFileService {

    @Value("${javaclaw.skills.dir:./skills}")
    private String skillsDir;

    /** 创建 Skill 目录, 写入 SKILL.md */
    public void createSkillDirectory(String name, String content) { ... }

    /** 更新 SKILL.md 内容 */
    public void updateSkillContent(String name, String content) { ... }

    /** 删除 Skill 目录 */
    public void deleteSkillDirectory(String name) { ... }

    /** 保存上传的脚本文件到 scripts/ */
    public void saveScript(String skillName, String fileName, byte[] data) { ... }

    /** 保存上传的引用文件到 references/ */
    public void saveReference(String skillName, String fileName, byte[] data) { ... }

    /** 列出 Skill 目录下的 scripts/ 和 references/ 文件 */
    public SkillFiles listFiles(String skillName) { ... }

    /** 读取 SKILL.md 完整内容 */
    public String readContent(String skillName) { ... }

    /** 获取 Skill 目录的绝对路径 */
    public Path getSkillPath(String skillName) {
        return Path.of(skillsDir, skillName);
    }

    record SkillFiles(List<String> scripts, List<String> references, List<String> assets) {}
}
```

---

## 5. P0: 后端实现

### 5.1 Entity

```java
@Table("skill_definition")
public class SkillDefinitionEntity {

    @Id
    private Long id;
    private String name;
    private String displayName;
    private String description;
    private String content;
    private String tags;
    private String metadata;
    private Integer hasScripts;
    private Integer hasReferences;
    private Integer enabled;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // getter / setter ...
}
```

### 5.2 Repository

```java
public interface SkillDefinitionRepository extends ReactiveCrudRepository<SkillDefinitionEntity, Long> {
    Flux<SkillDefinitionEntity> findAllByEnabled(Integer enabled);
    Mono<SkillDefinitionEntity> findByName(String name);
    Flux<SkillDefinitionEntity> findAllByNameIn(Collection<String> names);
}
```

### 5.3 Controller

路径: `/api/skills`

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/skills` | 查询所有 Skills |
| `GET` | `/api/skills/{id}` | 查询单个 Skill (含文件列表) |
| `POST` | `/api/skills` | 创建 Skill (DB + 文件系统) |
| `PUT` | `/api/skills/{id}` | 更新 Skill |
| `DELETE` | `/api/skills/{id}` | 删除 Skill (DB + 文件系统) |
| `POST` | `/api/skills/{id}/files` | 上传脚本/引用文件 |
| `DELETE` | `/api/skills/{id}/files/{type}/{filename}` | 删除单个文件 |
| `GET` | `/api/skills/{id}/files` | 列出 Skill 目录下的文件 |

**请求体 (创建)**:

```json
{
    "name": "code-reviewer",
    "displayName": "代码审查",
    "description": "Conducts structured code reviews. Use when user asks to...",
    "content": "# Code Reviewer\n\n## Step 1: ...",
    "tags": "开发,质量",
    "metadata": {}
}
```

**校验规则**:
- `name`: 非空, 正则 `^[a-zA-Z][a-zA-Z0-9_-]{0,127}$`
- `description`: 非空
- `name` 唯一性

**Controller 核心**:

```java
@RestController
@RequestMapping("/api/skills")
public class SkillDefinitionController {

    private final SkillDefinitionRepository repository;
    private final SkillFileService fileService;

    @PostMapping
    public Mono<SkillDefinitionEntity> create(@RequestBody Map<String, Object> body) {
        // 1. 校验 name + description
        // 2. 唯一性检查
        // 3. 保存到数据库
        // 4. 创建文件系统目录 + 写入 SKILL.md
        // 5. 返回
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(@PathVariable Long id) {
        return repository.findById(id)
            .flatMap(entity -> {
                fileService.deleteSkillDirectory(entity.getName());
                return repository.deleteById(id);
            });
    }

    @PostMapping("/{id}/files")
    public Mono<Map<String, Object>> uploadFile(
            @PathVariable Long id,
            @RequestParam String type,
            @RequestPart("file") FilePart filePart) {
        // type = "scripts" | "references" | "assets"
        // 保存到 /skills/{name}/{type}/{filename}
        // 更新 entity 的 has_scripts / has_references 标志
    }
}
```

### 5.4 AgentEntity / ResolvedAgentConfig / AgentRunRequest 扩展

与旧版相同，新增 `skillsEnabled` 字段:

```java
// AgentEntity.java
private String skillsEnabled;

// ResolvedAgentConfig.java
public record ResolvedAgentConfig(
    JavaClawProperties.Agent agent,
    String toolsEnabled,
    String mcpToolsEnabled,
    String skillsEnabled
) {}

// AgentRunRequest.java
public record AgentRunRequest(
    Long sessionId,
    JavaClawProperties.Agent agent,
    String userMessage,
    List<Message> history,
    String toolsEnabled,
    String mcpToolsEnabled,
    String skillsEnabled
) { ... }
```

### 5.5 SkillContentProvider (SPI)

接口定义在 `javaclaw-agent`:

```java
package com.atm.javaclaw.agent.skills;

import java.util.List;

public interface SkillContentProvider {
    /**
     * 返回 Discovery 阶段需要的 Skill 索引信息。
     * @param skillsEnabledSpec null=无, "full"=全部, JSON array=指定
     */
    List<SkillSummary> resolveSkillSummaries(String skillsEnabledSpec);

    /** 获取 Skill 文件存储的根路径 */
    String getSkillsBasePath();

    record SkillSummary(String name, String description) {}
}
```

**注意**: SPI 只返回 `name + description`（Discovery 阶段），**不返回完整 content**。完整内容由 Agent 在 Activation 阶段自主读取文件。

实现类在 `javaclaw-gateway`:

```java
@Component
public class SkillContentProviderImpl implements SkillContentProvider {

    private final SkillDefinitionRepository repository;
    private final SkillFileService fileService;

    @Override
    public List<SkillSummary> resolveSkillSummaries(String skillsEnabledSpec) {
        if (skillsEnabledSpec == null || skillsEnabledSpec.isBlank()) {
            return List.of();
        }

        List<SkillDefinitionEntity> skills;
        if ("full".equalsIgnoreCase(skillsEnabledSpec.trim())) {
            skills = repository.findAllByEnabled(1).collectList().block();
        } else {
            Set<String> names = parseNames(skillsEnabledSpec);
            if (names.isEmpty()) return List.of();
            skills = repository.findAllByNameIn(names)
                    .filter(s -> s.getEnabled() == 1)
                    .collectList().block();
        }

        return skills.stream()
                .map(s -> new SkillSummary(s.getName(), s.getDescription()))
                .toList();
    }

    @Override
    public String getSkillsBasePath() {
        return fileService.getSkillPath("").getParent().toAbsolutePath().toString();
    }
}
```

### 5.6 AgentRuntime — Discovery 注入

修改 `buildSystemPrompt` 方法, **仅注入索引**:

```java
private String buildSystemPrompt(JavaClawProperties.Agent agentConfig, String skillsEnabled) {
    StringBuilder sb = new StringBuilder();

    appendSection(sb, "SOUL", agentConfig.getSoulMd());
    appendSection(sb, "USER", agentConfig.getUserMd());
    appendSection(sb, "AGENTS", agentConfig.getAgentsMd());

    if (skillContentProvider != null && skillsEnabled != null && !skillsEnabled.isBlank()) {
        String skillsSection = buildSkillsDiscovery(skillsEnabled);
        if (skillsSection != null) {
            appendSection(sb, "SKILLS", skillsSection);
        }
    }

    String prompt = sb.toString();
    if (prompt.length() > TOTAL_MAX_CHARS) {
        prompt = prompt.substring(0, TOTAL_MAX_CHARS) + "\n...[truncated]";
    }
    return prompt;
}

private String buildSkillsDiscovery(String skillsEnabled) {
    List<SkillContentProvider.SkillSummary> skills =
            skillContentProvider.resolveSkillSummaries(skillsEnabled);
    if (skills.isEmpty()) return null;

    String basePath = skillContentProvider.getSkillsBasePath();

    StringBuilder sb = new StringBuilder();
    sb.append("You have the following skills installed. ");
    sb.append("Each skill is a directory containing a SKILL.md with detailed instructions, ");
    sb.append("and optionally scripts/ and references/ directories.\n\n");
    sb.append("When a user's request matches a skill's description, ");
    sb.append("activate it by reading the skill's SKILL.md file first.\n\n");
    sb.append("Available skills:\n");

    for (var skill : skills) {
        sb.append("- **").append(skill.name()).append("**: ")
          .append(skill.description()).append('\n');
        sb.append("  Read: ").append(basePath).append('/').append(skill.name())
          .append("/SKILL.md\n");
    }

    return sb.toString();
}
```

**与旧版的关键区别**:
- 旧版: 框架直接把完整 instructions 拼进 system prompt
- 新版: 框架仅注入 name + description + 文件路径, Agent 自己读取完整内容

### 5.7 MessagePipeline / AgentController

与旧版相同，传递 `skillsEnabled` 到 `AgentRunRequest` 和 API 响应中。

---

## 6. P0: 前端实现

### 6.1 API 层

```typescript
// api.ts

export interface SkillDefinition {
  id: number;
  name: string;
  displayName: string | null;
  description: string;
  content: string | null;
  tags: string | null;
  metadata: string | null;
  hasScripts: number;
  hasReferences: number;
  enabled: number;
  createdAt: string;
  updatedAt: string;
}

export interface SkillDefinitionCreate {
  name: string;
  displayName?: string;
  description: string;
  content?: string;
  tags?: string;
  metadata?: object;
}

export interface SkillFiles {
  scripts: string[];
  references: string[];
  assets: string[];
}

export function fetchSkillDefinitions(): Promise<SkillDefinition[]> {
  return request<SkillDefinition[]>("/api/skills");
}

export function createSkillDefinition(data: SkillDefinitionCreate): Promise<SkillDefinition> {
  return request("/api/skills", { method: "POST", body: JSON.stringify(data) });
}

export function updateSkillDefinition(id: number, data: Partial<SkillDefinitionCreate> & { enabled?: number }): Promise<SkillDefinition> {
  return request(`/api/skills/${id}`, { method: "PUT", body: JSON.stringify(data) });
}

export function deleteSkillDefinition(id: number): Promise<void> {
  return request(`/api/skills/${id}`, { method: "DELETE" });
}

export function fetchSkillFiles(id: number): Promise<SkillFiles> {
  return request<SkillFiles>(`/api/skills/${id}/files`);
}

export function uploadSkillFile(id: number, type: string, file: File): Promise<void> {
  const form = new FormData();
  form.append("file", file);
  return fetch(`${BASE_URL}/api/skills/${id}/files?type=${type}`, {
    method: "POST", body: form
  }).then(r => { if (!r.ok) throw new Error("Upload failed"); });
}

export function deleteSkillFile(id: number, type: string, filename: string): Promise<void> {
  return request(`/api/skills/${id}/files/${type}/${filename}`, { method: "DELETE" });
}
```

**AgentConfig 扩展**:

```typescript
export interface AgentConfig {
  name: string;
  model: string;
  soulMd: string | null;
  userMd: string | null;
  agentsMd: string | null;
  toolsEnabled: string | null;
  mcpToolsEnabled: string | null;
  skillsEnabled: string | null;    // NEW
}
```

### 6.2 AgentStore 扩展

与旧版相同: `skillsEnabledDraft`, `setSkillsEnabled`, `saveSkillsEnabled` 方法。

### 6.3 SkillsTab

Agent 配置中的 Skills 选择 Tab, 展示:
- Skill name + displayName
- description (触发描述)
- tags
- 图标标记: 是否有 scripts/ 和 references/
- 如果 metadata 中声明了 MCP 依赖, 显示提示

### 6.4 SkillManagerModal

全局 Skills 管理弹窗:
- 列表视图 (分页)
- 每个 Skill 卡片显示: name, displayName, description, tags, 文件组成
- 创建/编辑/删除
- 文件上传区域 (scripts/, references/)

### 6.5 SkillEditor

创建/编辑表单:
- name (唯一标识)
- displayName (显示名称)
- description (触发描述, 带写法提示)
- content (SKILL.md 正文, 大尺寸 textarea)
- tags
- 文件上传区: scripts + references

### 6.6 AgentConfigModal

新增 "Skills" Tab:

```typescript
export type ContextTab = "soul" | "user" | "agents" | "tools" | "mcp" | "skills" | "model";

// ALL_TABS 新增:
{ key: "skills", label: "Skills" },
```

---

## 7. 文件清单

### 后端新增

| 文件 | 模块 | 说明 |
|------|------|------|
| `V9__skill_definition.sql` | gateway/migration | DB 迁移 |
| `SkillDefinitionEntity.java` | gateway/entity | 实体类 |
| `SkillDefinitionRepository.java` | gateway/repository | R2DBC Repository |
| `SkillDefinitionController.java` | gateway/http | CRUD + 文件上传 API |
| `SkillFileService.java` | gateway/service | 文件系统操作 |
| `SkillContentProvider.java` | agent/skills | SPI 接口 |
| `SkillContentProviderImpl.java` | gateway/service | SPI 实现 |

### 后端修改

| 文件 | 修改 |
|------|------|
| `AgentEntity.java` | + skillsEnabled |
| `ResolvedAgentConfig.java` | + skillsEnabled |
| `AgentConfigService.java` | resolve() 传递 skillsEnabled |
| `AgentRunRequest.java` | + skillsEnabled |
| `AgentRuntime.java` | buildSystemPrompt 注入 Discovery 索引 |
| `AgentController.java` | getConfig/update 支持 skillsEnabled |
| `MessagePipeline.java` | 传递 skillsEnabled |
| `application.yml` | + javaclaw.skills.dir 配置项 |

### 前端新增

| 文件 | 说明 |
|------|------|
| `SkillsTab.tsx` | Agent 配置中的 Skills 选择 |
| `SkillManagerModal.tsx` | 全局 Skills 管理弹窗 |
| `SkillEditor.tsx` | 创建/编辑表单 + 文件上传 |

### 前端修改

| 文件 | 修改 |
|------|------|
| `api.ts` | Skills CRUD + 文件上传 API |
| `agentStore.ts` | + skillsEnabledDraft |
| `AgentConfigModal.tsx` | + "Skills" Tab |
| `App.tsx` | + SkillManagerModal |
| `Sidebar.tsx` | + "Skills 管理" 按钮 |

---

## 8. P0 实施检查清单

- [ ] `V9__skill_definition.sql`
- [ ] `SkillDefinitionEntity.java`
- [ ] `SkillDefinitionRepository.java`
- [ ] `SkillFileService.java`
- [ ] `SkillDefinitionController.java` (CRUD + 文件上传)
- [ ] `SkillContentProvider.java` (SPI)
- [ ] `SkillContentProviderImpl.java`
- [ ] `AgentEntity.java` + skillsEnabled
- [ ] `ResolvedAgentConfig.java` + skillsEnabled
- [ ] `AgentConfigService.java` resolve
- [ ] `AgentRunRequest.java` + skillsEnabled
- [ ] `AgentRuntime.java` Discovery 注入
- [ ] `AgentController.java` skillsEnabled
- [ ] `MessagePipeline.java` 传递
- [ ] `application.yml` skills.dir
- [ ] `api.ts` Skills API
- [ ] `agentStore.ts` 扩展
- [ ] `SkillsTab.tsx`
- [ ] `SkillManagerModal.tsx`
- [ ] `SkillEditor.tsx`
- [ ] `AgentConfigModal.tsx` 新增 Tab
- [ ] `App.tsx` / `Sidebar.tsx`
- [ ] 编译 + 启动验证

---

## 9. P1: 增强功能

### 9.1 SKILL.md 导入

在 `SkillManagerModal` 中新增"导入 SKILL.md"按钮，弹出 textarea 供用户粘贴标准格式内容。

**前端解析逻辑**:

```typescript
// skillImporter.ts

interface ParsedSkill {
  name?: string;
  description?: string;
  content: string;
  metadata?: Record<string, unknown>;
}

function parseSkillMd(raw: string): ParsedSkill {
  const match = raw.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);
  if (!match) {
    return { content: raw };
  }

  const yamlStr = match[1];
  const content = match[2].trim();
  const meta: Record<string, string> = {};

  for (const line of yamlStr.split("\n")) {
    const colonIdx = line.indexOf(":");
    if (colonIdx > 0) {
      const key = line.slice(0, colonIdx).trim();
      const value = line.slice(colonIdx + 1).trim();
      meta[key] = value;
    }
  }

  return {
    name: meta.name,
    description: meta.description,
    content,
    metadata: meta.metadata ? JSON.parse(meta.metadata) : undefined,
  };
}
```

**导入流程**:

```
用户粘贴 SKILL.md 原始内容
  ↓
parseSkillMd() 解析 frontmatter + body
  ↓
预填充 SkillEditor 表单 (name, description, content)
  ↓
用户确认/修改后保存
  ↓
POST /api/skills → DB + 文件系统
```

**多文件导入**: 如果用户有完整的 Skill 目录（含 scripts/ 和 references/），可以:
1. 先通过 SKILL.md 创建 Skill 基本信息
2. 再通过文件上传接口 `POST /api/skills/{id}/files` 上传附属文件

### 9.2 SKILL.md 导出

将 DB 中的 Skill 转换为 AgentSkills 标准格式:

**前端导出逻辑**:

```typescript
function exportSkillMd(skill: SkillDefinition): string {
  let md = "---\n";
  md += `name: ${skill.name}\n`;
  md += `description: ${skill.description}\n`;
  if (skill.metadata) {
    md += `metadata: ${skill.metadata}\n`;
  }
  md += "---\n\n";
  md += skill.content ?? "";
  return md;
}

function downloadSkillMd(skill: SkillDefinition) {
  const content = exportSkillMd(skill);
  const blob = new Blob([content], { type: "text/markdown" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "SKILL.md";
  a.click();
  URL.revokeObjectURL(url);
}
```

**完整目录导出** (后端): 支持将整个 Skill 目录打包为 zip 下载。

新增 API:

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/skills/{id}/export` | 导出 SKILL.md 文本 |
| `GET` | `/api/skills/{id}/export/zip` | 打包 Skill 整个目录为 zip |

**后端实现**:

```java
// SkillDefinitionController.java

@GetMapping("/{id}/export")
public Mono<ResponseEntity<String>> exportSkillMd(@PathVariable Long id) {
    return repository.findById(id)
        .map(entity -> {
            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("name: ").append(entity.getName()).append('\n');
            sb.append("description: ").append(entity.getDescription()).append('\n');
            if (entity.getMetadata() != null) {
                sb.append("metadata: ").append(entity.getMetadata()).append('\n');
            }
            sb.append("---\n\n");
            if (entity.getContent() != null) {
                sb.append(entity.getContent());
            }
            return ResponseEntity.ok()
                .header("Content-Disposition",
                    "attachment; filename=\"SKILL.md\"")
                .contentType(MediaType.TEXT_MARKDOWN)
                .body(sb.toString());
        });
}

@GetMapping("/{id}/export/zip")
public Mono<ResponseEntity<byte[]>> exportSkillZip(@PathVariable Long id) {
    return repository.findById(id)
        .map(entity -> {
            byte[] zipData = fileService.zipSkillDirectory(entity.getName());
            return ResponseEntity.ok()
                .header("Content-Disposition",
                    "attachment; filename=\"" + entity.getName() + ".zip\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(zipData);
        });
}
```

**SkillFileService 扩展**:

```java
public byte[] zipSkillDirectory(String skillName) {
    Path skillDir = getSkillPath(skillName);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(baos)) {
        Files.walk(skillDir)
            .filter(Files::isRegularFile)
            .forEach(file -> {
                String entryName = skillDir.relativize(file).toString();
                try {
                    zos.putNextEntry(new ZipEntry(entryName));
                    Files.copy(file, zos);
                    zos.closeEntry();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
    }
    return baos.toByteArray();
}
```

### 9.3 标签过滤

在 `SkillManagerModal` 和 `SkillsTab` 中支持按标签筛选:

**前端实现**:

```typescript
// 提取所有标签
const allTags = useMemo(() => {
  const tagSet = new Set<string>();
  skills.forEach(s => {
    if (s.tags) s.tags.split(",").forEach(t => tagSet.add(t.trim()));
  });
  return [...tagSet].sort();
}, [skills]);

const [selectedTag, setSelectedTag] = useState<string | null>(null);

const filteredSkills = useMemo(() =>
  selectedTag
    ? skills.filter(s =>
        s.tags?.split(",").map(t => t.trim()).includes(selectedTag))
    : skills,
  [skills, selectedTag]
);
```

**UI**: 在列表上方显示标签按钮组，点击筛选；再次点击取消筛选。

```
[全部] [开发] [质量] [文档] [项目管理]
```

### 9.4 `get_skill_content` 内置工具

当 Agent 启用了大量 Skills (10+) 时，仅靠 system prompt 中的索引列表可能不够（Agent 无法直接 file_read 时的降级方案）。提供一个内置工具供 Agent 按名称查询 Skill 完整内容。

文件: `javaclaw-agent/src/main/java/com/atm/javaclaw/agent/tools/builtin/GetSkillContentTool.java`

```java
@Component
public class GetSkillContentTool implements ToolCallback {

    private final SkillContentProvider skillContentProvider;

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
            .name("get_skill_content")
            .description("Read the full SKILL.md content for a specific skill by name. "
                + "Use this when you identify a matching skill from the available skills list "
                + "and need its detailed instructions.")
            .inputSchema("""
                {
                    "type": "object",
                    "properties": {
                        "skillName": {
                            "type": "string",
                            "description": "The skill name to look up"
                        }
                    },
                    "required": ["skillName"]
                }
                """)
            .build();
    }

    @Override
    public String call(String arguments) {
        // 解析 arguments 获取 skillName
        String skillName = objectMapper.readTree(arguments).get("skillName").asText();
        String content = skillContentProvider.readSkillContent(skillName);
        if (content == null) {
            return "Skill not found: " + skillName;
        }
        return content;
    }
}
```

**SkillContentProvider SPI 扩展**:

```java
public interface SkillContentProvider {
    List<SkillSummary> resolveSkillSummaries(String skillsEnabledSpec);
    String getSkillsBasePath();

    /** P1: 按名称读取 Skill 完整内容 */
    String readSkillContent(String skillName);

    record SkillSummary(String name, String description) {}
}
```

**注册策略**: 此工具仅在 Agent 启用了 Skills 时注册。在 `ToolsEngine` 中:

```java
if (skillsEnabled != null && !skillsEnabled.isBlank() && getSkillContentTool != null) {
    callbacks.add(getSkillContentTool);
}
```

### 9.5 内置 Skills 包

预置一批常用 Skills，随项目一起分发。存放在 `javaclaw-gateway/src/main/resources/builtin-skills/` 下:

```
builtin-skills/
├── code-reviewer/
│   └── SKILL.md
├── readme-writer/
│   └── SKILL.md
├── research-report/
│   └── SKILL.md
└── unit-test-gen/
    └── SKILL.md
```

**启动时加载逻辑**:

```java
@Component
public class BuiltinSkillsLoader implements ApplicationRunner {

    private final SkillDefinitionRepository repository;
    private final SkillFileService fileService;

    @Override
    public void run(ApplicationArguments args) {
        // 扫描 classpath:builtin-skills/ 下的所有 SKILL.md
        // 对于每个 Skill:
        //   1. 解析 frontmatter 获取 name + description
        //   2. 检查 DB 中是否已存在 (按 name 判断)
        //   3. 不存在则创建 (DB + 文件系统)
        //   4. 已存在则跳过 (不覆盖用户修改)
    }
}
```

**内置 Skill 标记**: 在 `metadata` 字段中添加 `{"builtin": true}` 标记，前端据此显示"内置"标签且禁止删除。

### 9.6 P1 新增/修改文件清单

| 文件 | 模块 | 说明 |
|------|------|------|
| `skillImporter.ts` | web/lib | SKILL.md 解析工具函数 |
| `GetSkillContentTool.java` | agent/tools/builtin | 内置工具: 按名称获取 Skill 内容 |
| `BuiltinSkillsLoader.java` | gateway/service | 启动时加载内置 Skills |
| `builtin-skills/*/SKILL.md` | gateway/resources | 内置 Skills 资源文件 |

| 修改文件 | 修改内容 |
|----------|----------|
| `SkillContentProvider.java` | + readSkillContent() 方法 |
| `SkillContentProviderImpl.java` | 实现 readSkillContent() |
| `SkillDefinitionController.java` | + export / export/zip 端点 |
| `SkillFileService.java` | + zipSkillDirectory() |
| `ToolsEngine.java` | 条件注册 GetSkillContentTool |
| `SkillManagerModal.tsx` | + 导入按钮 + 导出按钮 + 标签过滤 |
| `SkillsTab.tsx` | + 标签过滤 |

### 9.7 P1 实施检查清单

- [ ] SKILL.md 导入 (前端解析 + 预填充)
- [ ] SKILL.md 导出 (前端文本 + 后端 zip)
- [ ] `/api/skills/{id}/export` 端点
- [ ] `/api/skills/{id}/export/zip` 端点
- [ ] `SkillFileService.zipSkillDirectory()`
- [ ] 标签过滤 UI (SkillManagerModal + SkillsTab)
- [ ] `GetSkillContentTool.java`
- [ ] `SkillContentProvider` + readSkillContent()
- [ ] `ToolsEngine` 条件注册
- [ ] `BuiltinSkillsLoader.java`
- [ ] 内置 Skills 资源 (code-reviewer, readme-writer, research-report, unit-test-gen)
- [ ] 内置 Skill 前端标记 + 删除保护

---

## 10. P2: 高级功能

### 10.1 Skill 版本管理

为 Skill 的每次修改保留历史版本，支持回滚。

**数据库 Schema**: `V10__skill_version.sql`

```sql
CREATE TABLE IF NOT EXISTS `skill_version` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `skill_id`       BIGINT       NOT NULL COMMENT '关联 skill_definition.id',
    `version`        INT          NOT NULL COMMENT '版本号, 从 1 递增',
    `content`        MEDIUMTEXT   NULL COMMENT '该版本的 SKILL.md 正文',
    `description`    TEXT         NULL COMMENT '该版本的触发描述',
    `change_note`    VARCHAR(512) NULL COMMENT '变更说明',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_skill_version` (`skill_id`, `version`),
    FOREIGN KEY (`skill_id`) REFERENCES `skill_definition` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Skill 版本历史';
```

**版本记录时机**: 每次通过 `PUT /api/skills/{id}` 更新 Skill 时，将**修改前**的内容快照写入 `skill_version` 表。

**Entity + Repository**:

```java
@Table("skill_version")
public class SkillVersionEntity {
    @Id
    private Long id;
    private Long skillId;
    private Integer version;
    private String content;
    private String description;
    private String changeNote;
    private LocalDateTime createdAt;
}

public interface SkillVersionRepository extends ReactiveCrudRepository<SkillVersionEntity, Long> {
    Flux<SkillVersionEntity> findAllBySkillIdOrderByVersionDesc(Long skillId);
    Mono<SkillVersionEntity> findBySkillIdAndVersion(Long skillId, Integer version);
    Mono<SkillVersionEntity> findTopBySkillIdOrderByVersionDesc(Long skillId);
}
```

**API**:

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/skills/{id}/versions` | 查询版本列表 |
| `GET` | `/api/skills/{id}/versions/{version}` | 查询特定版本内容 |
| `POST` | `/api/skills/{id}/rollback/{version}` | 回滚到指定版本 |

**回滚逻辑**:

```java
@PostMapping("/{id}/rollback/{version}")
public Mono<SkillDefinitionEntity> rollback(@PathVariable Long id,
                                            @PathVariable Integer version) {
    return Mono.zip(
        repository.findById(id),
        versionRepository.findBySkillIdAndVersion(id, version)
    ).flatMap(tuple -> {
        var current = tuple.getT1();
        var target = tuple.getT2();

        // 先将当前版本存入历史
        saveCurrentAsVersion(current);

        // 恢复目标版本
        current.setContent(target.getContent());
        current.setDescription(target.getDescription());

        // 同步文件系统
        fileService.updateSkillContent(current.getName(), target.getContent());

        return repository.save(current);
    });
}
```

**前端**: 在 SkillEditor 中增加"版本历史"侧边栏，展示版本列表和 diff 对比。

### 10.2 Skills 执行统计

记录 Agent 激活 Skill 的频率和效果，帮助用户优化 Skill 配置。

**数据库 Schema**:

```sql
CREATE TABLE IF NOT EXISTS `skill_usage_log` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `skill_name`     VARCHAR(128) NOT NULL,
    `agent_name`     VARCHAR(128) NOT NULL,
    `session_id`     BIGINT       NULL,
    `activated_at`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `activation_type` VARCHAR(32)  NOT NULL COMMENT 'file_read | tool_call',
    PRIMARY KEY (`id`),
    INDEX `idx_skill_usage_name` (`skill_name`),
    INDEX `idx_skill_usage_time` (`activated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Skill 使用统计';
```

**统计方式**: 在 Agent 执行过程中，拦截以下事件:
1. `file_read` 的参数路径匹配 `{skills.dir}/{name}/SKILL.md` → 记录 Activation
2. `get_skill_content` 工具调用 → 记录 Activation

**实现**: 在 `AgentRuntime.executeAgentLoop` 的 tool call 结果处理链中添加拦截器:

```java
private void recordSkillActivation(String toolName, String arguments,
                                   String agentName, Long sessionId) {
    if ("file_read".equals(toolName)) {
        // 从 arguments 中提取路径, 检查是否匹配 skills 目录
        String path = extractPath(arguments);
        String skillName = extractSkillNameFromPath(path);
        if (skillName != null) {
            logActivation(skillName, agentName, sessionId, "file_read");
        }
    } else if ("get_skill_content".equals(toolName)) {
        String skillName = extractSkillName(arguments);
        logActivation(skillName, agentName, sessionId, "tool_call");
    }
}
```

**查询 API**:

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/skills/stats` | 汇总统计 (每个 Skill 的激活次数) |
| `GET` | `/api/skills/{id}/stats` | 单个 Skill 的详细统计 (按时间/Agent 维度) |

**汇总统计返回**:

```json
[
  {
    "skillName": "code-reviewer",
    "totalActivations": 47,
    "lastActivatedAt": "2026-03-10T14:30:00",
    "topAgents": ["coding-assistant", "qa-helper"]
  },
  {
    "skillName": "readme-writer",
    "totalActivations": 12,
    "lastActivatedAt": "2026-03-09T09:15:00",
    "topAgents": ["doc-writer"]
  }
]
```

**前端**: 在 `SkillManagerModal` 每个 Skill 卡片上显示激活次数徽标，点击进入详情可看趋势图。

### 10.3 社区分享（远期规划）

基于导入/导出基础，构建 Skill 生态。

**SkillHub Registry**: 类似 ClawHub 的远程 Skill 仓库:

```
GET  /api/skillhub/search?q=code+review     → 搜索社区 Skills
GET  /api/skillhub/{name}                    → 获取 Skill 详情
POST /api/skillhub/publish                   → 发布本地 Skill
GET  /api/skillhub/{name}/download           → 下载 Skill
```

**一键安装**: 用户在社区浏览器中搜索到 Skill 后，点击"安装"自动:
1. 下载 SKILL.md + 附属文件
2. 创建到本地 DB + 文件系统
3. 可选: 自动为指定 Agent 启用

**发布流程**: 用户在 SkillManagerModal 中点击"发布到社区":
1. 校验必填字段 (name, description, content)
2. 打包为标准格式
3. 上传到 SkillHub

**技术方案**: 后端新增 `SkillHubClient` 服务与远程 Registry 交互。具体协议和认证机制在此阶段再详细设计。

### 10.4 P2 新增文件清单

| 文件 | 模块 | 说明 |
|------|------|------|
| `V10__skill_version.sql` | gateway/migration | 版本历史表 |
| `SkillVersionEntity.java` | gateway/entity | 版本实体 |
| `SkillVersionRepository.java` | gateway/repository | 版本 Repository |
| `SkillUsageLogEntity.java` | gateway/entity | 使用统计实体 |
| `SkillUsageLogRepository.java` | gateway/repository | 使用统计 Repository |
| `SkillUsageInterceptor.java` | agent/skills | tool call 拦截, 记录 Skill 激活 |

| 修改文件 | 修改内容 |
|----------|----------|
| `SkillDefinitionController.java` | + versions/rollback/stats 端点 |
| `AgentRuntime.java` | + 执行链中 Skill 激活拦截 |
| `SkillManagerModal.tsx` | + 版本历史侧栏 + 激活次数显示 |

### 10.5 P2 实施检查清单

- [ ] `V10__skill_version.sql`
- [ ] `SkillVersionEntity.java` + `SkillVersionRepository.java`
- [ ] 版本记录逻辑 (更新时自动快照)
- [ ] `/api/skills/{id}/versions` 端点
- [ ] `/api/skills/{id}/rollback/{version}` 端点
- [ ] `skill_usage_log` 表
- [ ] `SkillUsageInterceptor.java`
- [ ] `/api/skills/stats` 端点
- [ ] 前端版本历史 UI
- [ ] 前端激活统计 UI

---

## 11. 运行时序列图

```
用户 → "帮我审查一下这段代码"

┌──────────────────────────────────────────────────┐
│ System Prompt (Session 启动时已注入)              │
│                                                  │
│ ## SKILLS                                        │
│ Available skills:                                │
│ - code-reviewer: Conducts structured code...     │
│   Read: /skills/code-reviewer/SKILL.md           │
│ - readme-writer: Creates professional README...  │
│   Read: /skills/readme-writer/SKILL.md           │
└──────────────────────────────────────────────────┘

Agent 思考: "用户要审查代码" → 匹配 code-reviewer

Agent 执行 (Activation):
  → file_read("/skills/code-reviewer/SKILL.md")
  → 获得完整指令: Step 1 理解上下文, Step 2 审查, Step 3 输出...

Agent 执行 (Execution):
  → 指令说 "see references/security-criteria.md"
  → file_read("/skills/code-reviewer/references/security-criteria.md")
  → 获得详细安全审查标准

Agent 执行审查:
  → 阅读用户提供的代码
  → 按 SKILL.md 的流程逐项检查
  → 按指定格式输出: Summary → Blocking Issues → Suggestions → Positive Notes

Agent 返回审查结果给用户
```

---

## 12. 风险与注意事项

| 风险 | 缓解措施 |
|------|----------|
| Skill 目录路径注入 | Agent 只能读取 skills.dir 下的文件, realpath 验证 |
| SKILL.md 内容过大 | Agent 使用 file_read 工具读取, 天然受 tool output 截断限制 |
| 文件系统与 DB 不同步 | 创建/删除操作同时操作 DB + 文件系统, 原子性通过 try-finally 保证 |
| scripts/ 中脚本的安全性 | 与现有 ExecTool 共享安全策略, 沙箱环境 |
| Agent 不读取 SKILL.md | 优化 description 写法, 包含明确触发短语 |
| SPI 跨模块依赖 | agent 模块定义接口, gateway 实现, Spring 自动注入 |
