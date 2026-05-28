# 非标准 Skill 导入增强 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让 IntelliMate 能够通过 Git URL 导入任意结构的项目型 skill（含 monorepo 子目录），同时增强 SKILL.md 解析器支持所有非标准格式。

**架构：** 后端新增 SkillGitService 执行 Git 克隆和 SKILL.md 解析，扩展 SkillFileService 支持任意目录文件树。前端重写解析器（引入 yaml 库），导入 UI 增加 Git URL 模式，文件管理改为树形展示。

**技术栈：** Spring Boot WebFlux / R2DBC（后端）、React + TypeScript + Vite（前端）、yaml@2.x（YAML 解析）、SnakeYAML（后端 YAML 解析，Spring Boot 内置）

**设计文档：** `docs/superpowers/specs/2026-05-28-nonstandard-skill-import-design.md`

---

## 文件清单

### 新建文件

| 文件路径 | 职责 |
|----------|------|
| `intellimate-gateway/src/main/resources/db/migration/V34__skill_git_fields.sql` | 新增 git_url, git_sub_path 字段 |
| `intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/SkillGitService.java` | Git 克隆/同步/frontmatter 解析 |

### 修改文件

| 文件路径 | 改动 |
|----------|------|
| `intellimate-gateway/.../entity/SkillDefinitionEntity.java` | 新增 gitUrl, gitSubPath 字段 |
| `intellimate-gateway/.../dto/SkillDTO.java` | 新增 gitUrl, gitSubPath 字段 |
| `intellimate-gateway/.../service/SkillFileService.java` | 新增 listAllFiles(), readFileByPath() |
| `intellimate-gateway/.../http/SkillDefinitionController.java` | 新增 import/git, git/sync, tree, files/read 端点 |
| `intellimate-core/.../exception/ErrorCode.java` | 新增 SKILL_GIT_CLONE_FAILED, SKILL_NO_SKILL_MD |
| `intellimate-web/package.json` | 新增 yaml 依赖 |
| `intellimate-web/src/lib/skillImporter.ts` | 重写 parseSkillMd + 新增工具函数 |
| `intellimate-web/src/lib/api.ts` | 新增 API 函数 + SkillDefinition 接口扩展 |
| `intellimate-web/src/components/SkillManagerModal.tsx` | 导入 UI 改为 3 Tab + Git 导入 |
| `intellimate-web/src/components/SkillEditor.tsx` | 文件树展示 + 同步按钮 |

---

### 任务 1：数据库迁移 + Entity/DTO 扩展

**文件：**
- 创建：`intellimate-gateway/src/main/resources/db/migration/V34__skill_git_fields.sql`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/SkillDefinitionEntity.java`
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/SkillDTO.java`

- [ ] **步骤 1：创建迁移文件**

```sql
-- V34__skill_git_fields.sql
ALTER TABLE skill_definition ADD COLUMN git_url VARCHAR(512) NULL COMMENT 'Git 仓库来源 URL';
ALTER TABLE skill_definition ADD COLUMN git_sub_path VARCHAR(256) NULL COMMENT 'monorepo 中的子目录路径';
```

- [ ] **步骤 2：Entity 新增字段**

在 `SkillDefinitionEntity.java` 的 `enabled` 字段后添加：

```java
private String gitUrl;
private String gitSubPath;
```

以及对应的 getter/setter：

```java
public String getGitUrl() { return gitUrl; }
public void setGitUrl(String gitUrl) { this.gitUrl = gitUrl; }
public String getGitSubPath() { return gitSubPath; }
public void setGitSubPath(String gitSubPath) { this.gitSubPath = gitSubPath; }
```

- [ ] **步骤 3：DTO 新增字段**

`SkillDTO.java` 的 record 定义中新增两个字段：

```java
public record SkillDTO(
        Long id,
        String name,
        String displayName,
        String description,
        String content,
        String tags,
        String metadata,
        Integer hasScripts,
        Integer hasReferences,
        Integer enabled,
        String gitUrl,
        String gitSubPath,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static SkillDTO fromEntity(SkillDefinitionEntity entity) {
        return new SkillDTO(
                entity.getId(),
                entity.getName(),
                entity.getDisplayName(),
                entity.getDescription(),
                entity.getContent(),
                entity.getTags(),
                entity.getMetadata(),
                entity.getHasScripts(),
                entity.getHasReferences(),
                entity.getEnabled(),
                entity.getGitUrl(),
                entity.getGitSubPath(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
```

- [ ] **步骤 4：ErrorCode 新增**

在 `ErrorCode.java` 的 `SKILL_NOT_FOUND` 之后添加：

```java
SKILL_GIT_CLONE_FAILED("SKILL_002", 500, "Git clone failed"),
SKILL_NO_SKILL_MD("SKILL_003", 400, "SKILL.md not found in repository"),
SKILL_GIT_SYNC_FAILED("SKILL_004", 500, "Git sync failed"),
```

- [ ] **步骤 5：验证编译通过**

运行：`cd intellimate-gateway && ../mvnw compile -pl . -am -q`
预期：BUILD SUCCESS

- [ ] **步骤 6：Commit**

```bash
git add intellimate-gateway/src/main/resources/db/migration/V34__skill_git_fields.sql \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/entity/SkillDefinitionEntity.java \
       intellimate-gateway/src/main/java/com/atm/intellimate/gateway/dto/SkillDTO.java \
       intellimate-core/src/main/java/com/atm/intellimate/core/exception/ErrorCode.java
git commit -m "feat(skill): add git_url and git_sub_path fields for Git-based skill import"
```

---

### 任务 2：SkillGitService -- Git 克隆与 SKILL.md 解析

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/SkillGitService.java`

- [ ] **步骤 1：创建 SkillGitService 基础结构**

```java
package com.atm.intellimate.gateway.service;

import com.atm.intellimate.core.exception.ErrorCode;
import com.atm.intellimate.core.exception.IntelliMateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Service
public class SkillGitService {

    private static final Logger log = LoggerFactory.getLogger(SkillGitService.class);
    private static final Pattern HTTPS_GIT_URL = Pattern.compile("^https://[\\w.\\-]+/[\\w.\\-/]+(\\.git)?$");
    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\n([\\s\\S]*?)\\n---\\n([\\s\\S]*)$");
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]{0,127}$");
    private static final long CLONE_TIMEOUT_SECONDS = 30;

    @Value("${intellimate.skills.dir:../skills}")
    private String skillsDir;

    // 后续步骤逐一实现各方法
}
```

- [ ] **步骤 2：实现 normalizeSkillName()**

在 SkillGitService 中添加：

```java
public static String normalizeSkillName(String raw) {
    if (raw == null || raw.isBlank()) return null;
    String normalized = raw.trim()
            .replaceAll("\\s+", "-")
            .replaceAll("[^a-zA-Z0-9_-]", "");
    if (normalized.isEmpty()) return null;
    if (!Character.isLetter(normalized.charAt(0))) {
        normalized = "s-" + normalized;
    }
    if (normalized.length() > 128) {
        normalized = normalized.substring(0, 128);
    }
    return normalized;
}
```

- [ ] **步骤 3：实现 parseSkillMdFrontmatter()**

```java
public record SkillMdMeta(String name, String description, String displayName, String tags, String content) {}

public SkillMdMeta parseSkillMdFrontmatter(Path skillMdPath) throws IOException {
    String raw = Files.readString(skillMdPath);
    Matcher m = FRONTMATTER_PATTERN.matcher(raw);
    if (!m.matches()) {
        String inferredName = inferNameFromTitle(raw);
        String inferredDesc = inferDescriptionFromBody(raw);
        return new SkillMdMeta(inferredName, inferredDesc, null, null, raw.trim());
    }

    String yamlBlock = m.group(1);
    String body = m.group(2).trim();

    Yaml yaml = new Yaml();
    Map<String, Object> meta = yaml.load(yamlBlock);
    if (meta == null) meta = Map.of();

    String name = meta.containsKey("name") ? String.valueOf(meta.get("name")) : null;
    String description = meta.containsKey("description") ? flattenValue(meta.get("description")) : null;
    String displayName = meta.containsKey("displayName") ? String.valueOf(meta.get("displayName"))
            : meta.containsKey("display_name") ? String.valueOf(meta.get("display_name")) : null;
    String tags = meta.containsKey("tags") ? String.valueOf(meta.get("tags")) : null;

    return new SkillMdMeta(name, description, displayName, tags, body);
}

private String flattenValue(Object value) {
    if (value == null) return null;
    if (value instanceof String s) return s.trim();
    return String.valueOf(value).trim();
}

private String inferNameFromTitle(String content) {
    for (String line : content.split("\\n")) {
        line = line.trim();
        if (line.startsWith("# ") && !line.startsWith("## ")) {
            return normalizeSkillName(line.substring(2).trim());
        }
    }
    return null;
}

private String inferDescriptionFromBody(String content) {
    boolean pastTitle = false;
    for (String line : content.split("\\n")) {
        line = line.trim();
        if (!pastTitle && line.startsWith("# ")) { pastTitle = true; continue; }
        if (pastTitle && !line.isEmpty() && !line.startsWith("#")) {
            return line.length() > 500 ? line.substring(0, 500) : line;
        }
    }
    return null;
}
```

- [ ] **步骤 4：实现 cloneRepository()**

```java
public Path cloneRepository(String gitUrl, String branch) {
    if (!HTTPS_GIT_URL.matcher(gitUrl).matches()) {
        throw new IntelliMateException(ErrorCode.VALIDATION_FAILED,
                "Only HTTPS Git URLs are allowed");
    }

    try {
        Path tempDir = Files.createTempDirectory("skill-clone-");
        ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1");
        if (branch != null && !branch.isBlank()) {
            pb.command().addAll(java.util.List.of("--branch", branch));
        }
        pb.command().addAll(java.util.List.of(gitUrl, tempDir.resolve("repo").toString()));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        boolean finished = process.waitFor(CLONE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            deleteDirectory(tempDir);
            throw new IntelliMateException(ErrorCode.SKILL_GIT_CLONE_FAILED,
                    "Git clone timed out after " + CLONE_TIMEOUT_SECONDS + " seconds");
        }
        if (process.exitValue() != 0) {
            String output = new String(process.getInputStream().readAllBytes());
            deleteDirectory(tempDir);
            throw new IntelliMateException(ErrorCode.SKILL_GIT_CLONE_FAILED,
                    "Git clone failed: " + output);
        }

        return tempDir.resolve("repo");
    } catch (IOException | InterruptedException e) {
        throw new IntelliMateException(ErrorCode.SKILL_GIT_CLONE_FAILED,
                "Git clone error: " + e.getMessage());
    }
}

private void deleteDirectory(Path dir) {
    if (!Files.exists(dir)) return;
    try (Stream<Path> walk = Files.walk(dir)) {
        walk.sorted(Comparator.reverseOrder()).forEach(p -> {
            try { Files.delete(p); } catch (IOException ignored) {}
        });
    } catch (IOException ignored) {}
}
```

- [ ] **步骤 5：实现 importFromGit() 核心方法**

```java
public record GitImportResult(String name, String description, String displayName,
                               String tags, String content, Path skillDir) {}

public GitImportResult importFromGit(String gitUrl, String branch, String subPath,
                                      String nameOverride, String descOverride) {
    Path clonedRepo = cloneRepository(gitUrl, branch);

    try {
        Path sourceDir = clonedRepo;
        if (subPath != null && !subPath.isBlank()) {
            if (subPath.contains("..")) {
                throw new IntelliMateException(ErrorCode.VALIDATION_FAILED,
                        "subPath must not contain '..'");
            }
            sourceDir = clonedRepo.resolve(subPath);
            if (!Files.isDirectory(sourceDir)) {
                throw new IntelliMateException(ErrorCode.VALIDATION_FAILED,
                        "subPath directory not found: " + subPath);
            }
        }

        Path skillMd = sourceDir.resolve("SKILL.md");
        if (!Files.exists(skillMd)) {
            throw new IntelliMateException(ErrorCode.SKILL_NO_SKILL_MD,
                    "SKILL.md not found in " + (subPath != null ? subPath : "repository root"));
        }

        SkillMdMeta meta = parseSkillMdFrontmatter(skillMd);
        String name = nameOverride != null ? nameOverride
                : (meta.name() != null ? normalizeSkillName(meta.name()) : inferNameFromUrl(gitUrl));
        String description = descOverride != null ? descOverride
                : (meta.description() != null ? meta.description() : "Imported from " + gitUrl);

        if (name == null || !SKILL_NAME_PATTERN.matcher(name).matches()) {
            throw new IntelliMateException(ErrorCode.VALIDATION_FAILED,
                    "Could not determine a valid skill name. Please provide one.");
        }

        Path targetDir = Path.of(skillsDir, name);
        if (Files.exists(targetDir)) {
            throw new IntelliMateException(ErrorCode.VALIDATION_FAILED,
                    "Skill directory already exists: " + name);
        }

        if (subPath != null && !subPath.isBlank()) {
            copyDirectory(sourceDir, targetDir);
            deleteDirectory(clonedRepo.getParent());
        } else {
            Files.move(clonedRepo, targetDir);
            deleteDirectory(clonedRepo.getParent());
        }

        return new GitImportResult(name, description, meta.displayName(),
                meta.tags(), meta.content(), targetDir);
    } catch (IOException e) {
        deleteDirectory(clonedRepo.getParent());
        throw new IntelliMateException(ErrorCode.SKILL_GIT_CLONE_FAILED,
                "Failed to process cloned repository: " + e.getMessage());
    }
}

private String inferNameFromUrl(String gitUrl) {
    String[] parts = gitUrl.split("/");
    String last = parts[parts.length - 1];
    if (last.endsWith(".git")) last = last.substring(0, last.length() - 4);
    return normalizeSkillName(last);
}

private void copyDirectory(Path source, Path target) throws IOException {
    try (Stream<Path> walk = Files.walk(source)) {
        walk.forEach(src -> {
            Path dest = target.resolve(source.relativize(src));
            try {
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }
}
```

- [ ] **步骤 6：实现 syncFromGit()**

```java
public SkillMdMeta syncFromGit(String skillName, String gitUrl, String subPath) {
    Path skillDir = Path.of(skillsDir, skillName);
    if (!Files.isDirectory(skillDir)) {
        throw new IntelliMateException(ErrorCode.SKILL_NOT_FOUND,
                "Skill directory not found: " + skillName);
    }

    if (subPath != null && !subPath.isBlank()) {
        Path clonedRepo = cloneRepository(gitUrl, null);
        try {
            Path sourceDir = clonedRepo.resolve(subPath);
            if (!Files.isDirectory(sourceDir)) {
                throw new IntelliMateException(ErrorCode.SKILL_GIT_SYNC_FAILED,
                        "subPath not found in remote: " + subPath);
            }
            deleteDirectoryContents(skillDir);
            copyDirectory(sourceDir, skillDir);
            deleteDirectory(clonedRepo.getParent());
        } catch (IOException e) {
            deleteDirectory(clonedRepo.getParent());
            throw new IntelliMateException(ErrorCode.SKILL_GIT_SYNC_FAILED,
                    "Sync failed: " + e.getMessage());
        }
    } else {
        Path gitDir = skillDir.resolve(".git");
        if (!Files.isDirectory(gitDir)) {
            throw new IntelliMateException(ErrorCode.SKILL_GIT_SYNC_FAILED,
                    "No .git directory found — cannot pull");
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "-C", skillDir.toString(), "pull", "--ff-only");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(CLONE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IntelliMateException(ErrorCode.SKILL_GIT_SYNC_FAILED, "Git pull timed out");
            }
            if (process.exitValue() != 0) {
                String output = new String(process.getInputStream().readAllBytes());
                throw new IntelliMateException(ErrorCode.SKILL_GIT_SYNC_FAILED, "Git pull failed: " + output);
            }
        } catch (IOException | InterruptedException e) {
            throw new IntelliMateException(ErrorCode.SKILL_GIT_SYNC_FAILED, "Git pull error: " + e.getMessage());
        }
    }

    Path skillMd = skillDir.resolve("SKILL.md");
    if (Files.exists(skillMd)) {
        try {
            return parseSkillMdFrontmatter(skillMd);
        } catch (IOException e) {
            log.warn("Failed to re-parse SKILL.md after sync: {}", e.getMessage());
        }
    }
    return null;
}

private void deleteDirectoryContents(Path dir) throws IOException {
    try (Stream<Path> entries = Files.list(dir)) {
        entries.forEach(entry -> {
            try {
                if (Files.isDirectory(entry)) {
                    try (Stream<Path> walk = Files.walk(entry)) {
                        walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                            try { Files.delete(p); } catch (IOException ignored) {}
                        });
                    }
                } else {
                    Files.delete(entry);
                }
            } catch (IOException ignored) {}
        });
    }
}
```

- [ ] **步骤 7：验证编译通过**

运行：`cd intellimate-gateway && ../mvnw compile -pl . -am -q`
预期：BUILD SUCCESS

- [ ] **步骤 8：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/SkillGitService.java
git commit -m "feat(skill): add SkillGitService for Git clone/sync/frontmatter parsing"
```

---

### 任务 3：SkillFileService 扩展 -- 文件树与任意路径读取

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/SkillFileService.java`

- [ ] **步骤 1：新增 FileNode record**

在 `SkillFileService` 类内部，在 `SkillFiles` record 之后添加：

```java
public record FileNode(String name, String path, boolean isDirectory, long size, java.util.List<FileNode> children) {}

private static final java.util.Set<String> EXCLUDED_DIRS = java.util.Set.of(
        ".git", ".build", "node_modules", "__pycache__", ".DS_Store");
```

- [ ] **步骤 2：实现 listAllFiles()**

```java
public FileNode listAllFiles(String skillName) {
    Path dir = getSkillPath(skillName);
    if (!Files.isDirectory(dir)) return new FileNode(skillName, "", true, 0, List.of());
    return buildFileTree(dir, dir);
}

private FileNode buildFileTree(Path root, Path current) {
    String relativePath = root.equals(current) ? "" : root.relativize(current).toString();
    String name = current.getFileName().toString();

    if (Files.isDirectory(current)) {
        if (!relativePath.isEmpty() && EXCLUDED_DIRS.contains(name)) {
            return null;
        }
        List<FileNode> children = new java.util.ArrayList<>();
        try (Stream<Path> entries = Files.list(current)) {
            entries.sorted()
                   .map(child -> buildFileTree(root, child))
                   .filter(java.util.Objects::nonNull)
                   .forEach(children::add);
        } catch (IOException e) {
            log.warn("Failed to list directory: {}", current, e);
        }
        return new FileNode(name, relativePath, true, 0, children);
    } else {
        if (EXCLUDED_DIRS.contains(name)) return null;
        long size = 0;
        try { size = Files.size(current); } catch (IOException ignored) {}
        return new FileNode(name, relativePath, false, size, List.of());
    }
}
```

- [ ] **步骤 3：实现 readFileByPath()**

```java
public String readFileByPath(String skillName, String relativePath) {
    if (relativePath == null || relativePath.contains("..")) {
        throw new IllegalArgumentException("Invalid path: must not contain '..'");
    }
    Path file = getSkillPath(skillName).resolve(relativePath).normalize();
    Path skillDir = getSkillPath(skillName).normalize();
    if (!file.startsWith(skillDir)) {
        throw new IllegalArgumentException("Path traversal detected");
    }
    if (!Files.exists(file) || !Files.isRegularFile(file)) {
        return null;
    }
    try {
        long size = Files.size(file);
        if (size > 1_048_576) {
            return "[File too large to preview: " + size + " bytes]";
        }
        return Files.readString(file);
    } catch (IOException e) {
        log.warn("Failed to read file: {}", file, e);
        return null;
    }
}
```

- [ ] **步骤 4：验证编译通过**

运行：`cd intellimate-gateway && ../mvnw compile -pl . -am -q`
预期：BUILD SUCCESS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/service/SkillFileService.java
git commit -m "feat(skill): add file tree listing and arbitrary file reading"
```

---

### 任务 4：Controller 新增 Git 导入/同步/文件树端点

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/SkillDefinitionController.java`

- [ ] **步骤 1：注入 SkillGitService**

在 constructor 中注入 `SkillGitService`：

```java
private final SkillGitService gitService;

public SkillDefinitionController(SkillDefinitionRepository repository,
                                 SkillVersionRepository versionRepository,
                                 SkillUsageLogRepository usageLogRepository,
                                 SkillFileService fileService,
                                 SkillGitService gitService) {
    this.repository = repository;
    this.versionRepository = versionRepository;
    this.usageLogRepository = usageLogRepository;
    this.fileService = fileService;
    this.gitService = gitService;
}
```

- [ ] **步骤 2：新增 import/git 端点**

```java
@PostMapping("/import/git")
public Mono<ApiResponse<SkillDTO>> importFromGit(@RequestBody Map<String, String> body) {
    return Mono.fromCallable(() -> {
        String gitUrl = body.get("gitUrl");
        if (gitUrl == null || gitUrl.isBlank()) {
            throw new IntelliMateException(ErrorCode.VALIDATION_FAILED, "gitUrl is required");
        }
        String branch = body.get("branch");
        String subPath = body.get("subPath");
        String nameOverride = body.get("name");
        String descOverride = body.get("description");

        return gitService.importFromGit(gitUrl, branch, subPath, nameOverride, descOverride);
    }).subscribeOn(Schedulers.boundedElastic())
      .flatMap(result -> {
          return repository.findByName(result.name())
                  .flatMap(existing -> Mono.<SkillDefinitionEntity>error(
                          new IntelliMateException(ErrorCode.VALIDATION_FAILED,
                                  "Skill name already exists: " + result.name())))
                  .switchIfEmpty(Mono.defer(() -> {
                      SkillDefinitionEntity entity = new SkillDefinitionEntity();
                      entity.setName(result.name());
                      entity.setDisplayName(result.displayName());
                      entity.setDescription(result.description());
                      entity.setContent(result.content());
                      entity.setTags(result.tags());
                      entity.setHasScripts(fileService.hasSubdir(result.name(), "scripts") ? 1 : 0);
                      entity.setHasReferences(fileService.hasSubdir(result.name(), "references") ? 1 : 0);
                      entity.setEnabled(1);
                      entity.setGitUrl(body.get("gitUrl"));
                      entity.setGitSubPath(body.get("subPath"));
                      entity.setCreatedAt(LocalDateTime.now());
                      entity.setUpdatedAt(LocalDateTime.now());
                      return repository.save(entity);
                  }));
      })
      .map(SkillDTO::fromEntity)
      .map(ApiResponse::ok);
}
```

- [ ] **步骤 3：新增 git/sync 端点**

```java
@PostMapping("/{id}/git/sync")
public Mono<ApiResponse<SkillDTO>> syncFromGit(@PathVariable Long id) {
    return repository.findById(id)
            .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND)))
            .flatMap(entity -> {
                if (entity.getGitUrl() == null || entity.getGitUrl().isBlank()) {
                    return Mono.error(new IntelliMateException(ErrorCode.VALIDATION_FAILED,
                            "This skill was not imported from Git"));
                }
                return Mono.fromCallable(() ->
                        gitService.syncFromGit(entity.getName(), entity.getGitUrl(), entity.getGitSubPath())
                ).subscribeOn(Schedulers.boundedElastic())
                 .flatMap(meta -> {
                     if (meta != null) {
                         if (meta.description() != null) entity.setDescription(meta.description());
                         if (meta.content() != null) entity.setContent(meta.content());
                     }
                     entity.setUpdatedAt(LocalDateTime.now());
                     return repository.save(entity);
                 });
            })
            .map(SkillDTO::fromEntity)
            .map(ApiResponse::ok);
}
```

- [ ] **步骤 4：新增 tree 端点**

```java
@GetMapping("/{id}/tree")
public Mono<ApiResponse<SkillFileService.FileNode>> getFileTree(@PathVariable Long id) {
    return repository.findById(id)
            .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND)))
            .map(entity -> ApiResponse.ok(fileService.listAllFiles(entity.getName())));
}
```

- [ ] **步骤 5：新增 files/read 端点**

```java
@GetMapping("/{id}/files/read")
public Mono<ResponseEntity<String>> readFile(@PathVariable Long id, @RequestParam String path) {
    return repository.findById(id)
            .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND)))
            .map(entity -> {
                String content = fileService.readFileByPath(entity.getName(), path);
                if (content == null) {
                    return ResponseEntity.notFound().<String>build();
                }
                return ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(content);
            });
}
```

- [ ] **步骤 6：验证编译通过**

运行：`cd intellimate-gateway && ../mvnw compile -pl . -am -q`
预期：BUILD SUCCESS

- [ ] **步骤 7：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/http/SkillDefinitionController.java
git commit -m "feat(skill): add Git import/sync and file tree API endpoints"
```

---

### 任务 5：前端依赖 + 解析器重写

**文件：**
- 修改：`intellimate-web/package.json`（添加 yaml 依赖）
- 修改：`intellimate-web/src/lib/skillImporter.ts`

- [ ] **步骤 1：安装 yaml 依赖**

运行：`cd intellimate-web && npm install yaml`

- [ ] **步骤 2：重写 skillImporter.ts**

完整替换 `skillImporter.ts` 的内容：

```typescript
import { parse as yamlParse } from "yaml";
import type { SkillDefinition, SkillDefinitionCreate } from "./api";

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

const KNOWN_FIELDS = new Set([
  "name", "displayName", "display_name", "description", "tags", "metadata",
]);

export function normalizeSkillName(raw: string): { name: string; changed: boolean } {
  let normalized = raw.trim().replace(/\s+/g, "-").replace(/[^a-zA-Z0-9_-]/g, "");
  if (!normalized) return { name: "", changed: true };
  if (!/^[a-zA-Z]/.test(normalized)) normalized = "s-" + normalized;
  if (normalized.length > 128) normalized = normalized.substring(0, 128);
  return { name: normalized, changed: normalized !== raw.trim() };
}

export function parseSkillMd(raw: string): ParsedSkill {
  const warnings: string[] = [];
  const match = raw.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);

  if (!match) {
    return inferFromContent(raw, warnings);
  }

  const yamlStr = match[1];
  const content = match[2].trim();

  let meta: Record<string, unknown> = {};
  try {
    const parsed = yamlParse(yamlStr);
    if (parsed && typeof parsed === "object") meta = parsed;
  } catch {
    warnings.push("YAML frontmatter 解析失败，尝试智能推断");
    return inferFromContent(raw, warnings);
  }

  const extraFields: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(meta)) {
    if (!KNOWN_FIELDS.has(key)) {
      extraFields[key] = value;
    }
  }
  if (Object.keys(extraFields).length > 0) {
    warnings.push(`额外字段已保留到 metadata: ${Object.keys(extraFields).join(", ")}`);
  }

  let name = typeof meta.name === "string" ? meta.name : undefined;
  if (name) {
    const { name: normalized, changed } = normalizeSkillName(name);
    if (changed) {
      warnings.push(`名称已规范化: "${name}" → "${normalized}"`);
      name = normalized;
    }
  }

  const displayName = typeof meta.displayName === "string" ? meta.displayName
    : typeof meta.display_name === "string" ? meta.display_name : undefined;

  let description: string | undefined;
  if (meta.description != null) {
    description = typeof meta.description === "string"
      ? meta.description.trim()
      : String(meta.description).trim();
  }

  const tags = typeof meta.tags === "string" ? meta.tags : undefined;

  let parsedMetadata: Record<string, unknown> | undefined;
  if (meta.metadata) {
    if (typeof meta.metadata === "string") {
      try { parsedMetadata = JSON.parse(meta.metadata); } catch { /* ignore */ }
    } else if (typeof meta.metadata === "object") {
      parsedMetadata = meta.metadata as Record<string, unknown>;
    }
  }

  return { name, displayName, description, content, tags, metadata: parsedMetadata, extraFields, warnings };
}

function inferFromContent(raw: string, warnings: string[]): ParsedSkill {
  const lines = raw.split("\n");
  let name: string | undefined;
  let description: string | undefined;

  let pastTitle = false;
  for (const line of lines) {
    const trimmed = line.trim();
    if (!name && trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
      const titleText = trimmed.slice(2).trim();
      const { name: normalized } = normalizeSkillName(titleText);
      if (normalized) {
        name = normalized;
        warnings.push(`name 由标题推断: "${titleText}" → "${normalized}"`);
        pastTitle = true;
        continue;
      }
    }
    if (pastTitle && !description && trimmed && !trimmed.startsWith("#")) {
      description = trimmed.length > 500 ? trimmed.slice(0, 500) : trimmed;
      warnings.push("description 由首段推断");
      break;
    }
  }

  return { name, description, content: raw.trim(), warnings };
}

export function parsedToCreate(parsed: ParsedSkill): Partial<SkillDefinitionCreate> {
  let metadata = parsed.metadata;
  if (parsed.extraFields && Object.keys(parsed.extraFields).length > 0) {
    metadata = { ...(metadata ?? {}), _extraFields: parsed.extraFields };
  }
  return {
    name: parsed.name,
    displayName: parsed.displayName,
    description: parsed.description ?? "",
    content: parsed.content || undefined,
    tags: parsed.tags,
    metadata,
  };
}

export function exportSkillMd(skill: SkillDefinition): string {
  let md = "---\n";
  md += `name: ${skill.name}\n`;
  md += `description: ${skill.description}\n`;
  if (skill.displayName) md += `displayName: ${skill.displayName}\n`;
  if (skill.tags) md += `tags: ${skill.tags}\n`;
  if (skill.metadata) md += `metadata: ${skill.metadata}\n`;
  md += "---\n\n";
  md += skill.content ?? "";
  return md;
}

export function downloadSkillMd(skill: SkillDefinition) {
  const content = exportSkillMd(skill);
  const blob = new Blob([content], { type: "text/markdown" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "SKILL.md";
  a.click();
  URL.revokeObjectURL(url);
}

export function downloadBlob(data: Blob, filename: string) {
  const url = URL.createObjectURL(data);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}
```

- [ ] **步骤 3：验证前端编译**

运行：`cd intellimate-web && npx tsc --noEmit`
预期：无错误

- [ ] **步骤 4：Commit**

```bash
git add intellimate-web/package.json intellimate-web/package-lock.json \
       intellimate-web/src/lib/skillImporter.ts
git commit -m "feat(skill): rewrite SKILL.md parser with yaml library and smart inference"
```

---

### 任务 6：前端 API 层扩展

**文件：**
- 修改：`intellimate-web/src/lib/api.ts`

- [ ] **步骤 1：扩展 SkillDefinition 接口**

在 `SkillDefinition` 接口中 `enabled` 字段后添加：

```typescript
gitUrl: string | null;
gitSubPath: string | null;
```

- [ ] **步骤 2：新增 FileNode 接口和 API 函数**

在 `api.ts` 文件的 Skill 相关区域末尾添加：

```typescript
// ─── Skill Git Import ───

export interface GitImportParams {
  gitUrl: string;
  branch?: string;
  subPath?: string;
  name?: string;
  description?: string;
}

export function importSkillFromGit(params: GitImportParams): Promise<SkillDefinition> {
  return apiFetch<SkillDefinition>("/api/skills/import/git", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(params),
  });
}

export function syncSkillFromGit(id: number): Promise<SkillDefinition> {
  return apiFetch<SkillDefinition>(`/api/skills/${id}/git/sync`, { method: "POST" });
}

// ─── Skill File Tree ───

export interface FileNode {
  name: string;
  path: string;
  isDirectory: boolean;
  size: number;
  children: FileNode[];
}

export function fetchSkillTree(id: number): Promise<FileNode> {
  return apiFetch<FileNode>(`/api/skills/${id}/tree`);
}

export async function readSkillFile(id: number, path: string): Promise<string> {
  const res = await apiFetchRaw(`/api/skills/${id}/files/read?path=${encodeURIComponent(path)}`);
  if (!res.ok) throw new Error(`API ${res.status}`);
  return res.text();
}
```

- [ ] **步骤 3：验证 TypeScript 编译**

运行：`cd intellimate-web && npx tsc --noEmit`
预期：无错误

- [ ] **步骤 4：Commit**

```bash
git add intellimate-web/src/lib/api.ts
git commit -m "feat(skill): add Git import/sync and file tree API client functions"
```

---

### 任务 7：前端导入 UI 改版 -- 3 Tab + Git 导入

**文件：**
- 修改：`intellimate-web/src/components/SkillManagerModal.tsx`

- [ ] **步骤 1：新增 import 和 state 引用**

在文件顶部添加导入：

```typescript
import { importSkillFromGit, type GitImportParams } from "../lib/api";
```

新增 state：

```typescript
const [importMode, setImportMode] = useState<"paste" | "upload" | "git">("paste");
const [gitUrl, setGitUrl] = useState("");
const [gitBranch, setGitBranch] = useState("");
const [gitSubPath, setGitSubPath] = useState("");
const [gitImporting, setGitImporting] = useState(false);
```

- [ ] **步骤 2：实现文件上传处理**

```typescript
const fileInputRef = useRef<HTMLInputElement>(null);

const handleFileUpload = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
  const file = e.target.files?.[0];
  if (!file) return;
  e.target.value = "";
  const reader = new FileReader();
  reader.onload = () => {
    const text = reader.result as string;
    setImportText(text);
    handleImportParse();
  };
  reader.readAsText(file);
}, [handleImportParse]);
```

- [ ] **步骤 3：实现 Git 导入处理**

```typescript
const handleGitImport = useCallback(async () => {
  setImportError(null);
  if (!gitUrl.trim()) {
    setImportError("请输入 Git URL");
    return;
  }
  setGitImporting(true);
  try {
    const params: GitImportParams = { gitUrl: gitUrl.trim() };
    if (gitBranch.trim()) params.branch = gitBranch.trim();
    if (gitSubPath.trim()) params.subPath = gitSubPath.trim();
    await importSkillFromGit(params);
    setGitUrl("");
    setGitBranch("");
    setGitSubPath("");
    setView("list");
    loadSkills();
  } catch (e) {
    setImportError(e instanceof Error ? e.message : String(e));
  } finally {
    setGitImporting(false);
  }
}, [gitUrl, gitBranch, gitSubPath, loadSkills]);
```

- [ ] **步骤 4：重写 import 视图 JSX**

将 `{view === "import" && (...)}` 替换为包含 3 个 Tab 的新版 UI：
- Tab 栏：粘贴文本 / 上传文件 / Git URL
- paste 模式：保留现有 textarea + 解析按钮
- upload 模式：`<input type="file" accept=".md">` + 自动解析
- git 模式：URL 输入框 + 分支输入框 + 子路径输入框 + 克隆按钮

（具体 JSX 代码参考现有 UI 风格，使用 tailwindcss 保持一致。由于 JSX 较长，实现时直接替换 `view === "import"` 区块即可。）

- [ ] **步骤 5：解析预览增强**

在 `handleImportParse` 中，解析完成后检查 `warnings`，如果有则在界面上展示黄色提示条：

```typescript
const handleImportParse = useCallback(() => {
  setImportError(null);
  if (!importText.trim()) {
    setImportError("请粘贴 SKILL.md 内容");
    return;
  }
  const parsed = parseSkillMd(importText);
  if (parsed.warnings && parsed.warnings.length > 0) {
    setImportError(`提示: ${parsed.warnings.join("; ")}`);
  }
  const prefill = parsedToCreate(parsed);
  setEditingSkill({
    id: 0,
    name: prefill.name ?? "",
    displayName: prefill.displayName ?? null,
    description: prefill.description ?? "",
    content: prefill.content ?? null,
    tags: prefill.tags ?? null,
    metadata: prefill.metadata ? JSON.stringify(prefill.metadata) : null,
    hasScripts: 0,
    hasReferences: 0,
    enabled: 1,
    gitUrl: null,
    gitSubPath: null,
    createdAt: "",
    updatedAt: "",
  } as SkillDefinition);
  setView("create");
  setImportText("");
}, [importText]);
```

- [ ] **步骤 6：验证前端编译 + 视觉检查**

运行：`cd intellimate-web && npx tsc --noEmit`
预期：无错误

- [ ] **步骤 7：Commit**

```bash
git add intellimate-web/src/components/SkillManagerModal.tsx
git commit -m "feat(skill): add 3-tab import UI with Git URL import support"
```

---

### 任务 8：前端文件树组件 + 同步按钮

**文件：**
- 修改：`intellimate-web/src/components/SkillEditor.tsx`

- [ ] **步骤 1：新增导入和 state**

```typescript
import { fetchSkillTree, readSkillFile, syncSkillFromGit, type FileNode } from "../lib/api";
import { ChevronRight, ChevronDown, GitBranch, RefreshCw } from "lucide-react";
```

新增 state：

```typescript
const [fileTree, setFileTree] = useState<FileNode | null>(null);
const [fileTreeLoading, setFileTreeLoading] = useState(false);
const [syncing, setSyncing] = useState(false);
const [previewPath, setPreviewPath] = useState<string | null>(null);
const [previewContent, setPreviewContent] = useState<string | null>(null);
const [expandedDirs, setExpandedDirs] = useState<Set<string>>(new Set([""]));
```

- [ ] **步骤 2：加载文件树**

```typescript
const isGitSkill = !!skill?.gitUrl;

const loadFileTree = useCallback(() => {
  if (!skill || skill.id === 0) return;
  setFileTreeLoading(true);
  fetchSkillTree(skill.id)
    .then(setFileTree)
    .catch(() => setFileTree(null))
    .finally(() => setFileTreeLoading(false));
}, [skill]);

useEffect(() => {
  if (isEdit && isGitSkill) loadFileTree();
}, [isEdit, isGitSkill, loadFileTree]);
```

- [ ] **步骤 3：实现同步处理**

```typescript
const handleSync = useCallback(async () => {
  if (!skill) return;
  setSyncing(true);
  try {
    await syncSkillFromGit(skill.id);
    loadFileTree();
    window.location.reload();
  } catch (e) {
    setError(e instanceof Error ? e.message : String(e));
  } finally {
    setSyncing(false);
  }
}, [skill, loadFileTree]);
```

- [ ] **步骤 4：实现树形渲染组件**

在 SkillEditor 组件内部（或作为内联函数），创建递归文件树渲染：

```typescript
const renderFileNode = (node: FileNode, depth: number = 0) => {
  const isExpanded = expandedDirs.has(node.path);
  const toggleExpand = () => {
    setExpandedDirs(prev => {
      const next = new Set(prev);
      if (next.has(node.path)) next.delete(node.path);
      else next.add(node.path);
      return next;
    });
  };
  const handlePreview = async () => {
    if (!skill || node.isDirectory) return;
    setPreviewPath(node.path);
    try {
      const content = await readSkillFile(skill.id, node.path);
      setPreviewContent(content);
    } catch { setPreviewContent(null); }
  };

  if (node.isDirectory) {
    return (
      <div key={node.path}>
        <button type="button" onClick={toggleExpand}
          className="flex items-center gap-1 w-full px-2 py-1 text-xs text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800/50 rounded"
          style={{ paddingLeft: `${depth * 16 + 8}px` }}>
          {isExpanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
          <span className="font-medium">{node.name}/</span>
        </button>
        {isExpanded && node.children.map(child => renderFileNode(child, depth + 1))}
      </div>
    );
  }

  const isSkillMd = node.name === "SKILL.md";
  return (
    <button key={node.path} type="button" onClick={handlePreview}
      className={`flex items-center gap-1 w-full px-2 py-1 text-xs rounded hover:bg-slate-50 dark:hover:bg-slate-800/50 ${
        isSkillMd ? "text-blue-600 dark:text-blue-400 font-medium" : "text-slate-500 dark:text-slate-400"
      } ${previewPath === node.path ? "bg-slate-100 dark:bg-slate-800" : ""}`}
      style={{ paddingLeft: `${depth * 16 + 8}px` }}>
      <FileText size={10} />
      {node.name}
      <span className="ml-auto text-[10px] text-slate-400">{formatSize(node.size)}</span>
    </button>
  );
};

const formatSize = (bytes: number): string => {
  if (bytes === 0) return "";
  if (bytes < 1024) return bytes + "B";
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + "KB";
  return (bytes / 1048576).toFixed(1) + "MB";
};
```

- [ ] **步骤 5：修改文件管理区 JSX**

在 SkillEditor 的文件管理区域，根据 `isGitSkill` 条件渲染不同内容：

- 如果 `isGitSkill`：显示 Git 来源信息 + 同步按钮 + 文件树 + 文件预览区
- 否则：保持现有的 scripts/references/assets 三类管理

- [ ] **步骤 6：验证 TypeScript 编译**

运行：`cd intellimate-web && npx tsc --noEmit`
预期：无错误

- [ ] **步骤 7：Commit**

```bash
git add intellimate-web/src/components/SkillEditor.tsx
git commit -m "feat(skill): add file tree display and Git sync button for imported skills"
```

---

### 任务 9：端到端集成验证

- [ ] **步骤 1：启动后端验证迁移**

运行：启动 IntelliMate 后端，检查 Flyway 迁移 V34 是否成功执行。

- [ ] **步骤 2：验证 Git 导入 API**

使用 curl 测试公开仓库导入：

```bash
curl -X POST http://localhost:3007/api/skills/import/git \
  -H "Content-Type: application/json" \
  -d '{"gitUrl":"https://github.com/example/skill-repo.git"}'
```

预期：返回 200 + SkillDefinition JSON

- [ ] **步骤 3：验证前端 UI 流程**

1. 打开 Skills 管理页面
2. 点击「导入 SKILL.md」
3. 验证 3 个 Tab 显示正常
4. 在 Git 导入 Tab 输入公开仓库 URL
5. 点击克隆，验证导入成功
6. 进入编辑页面，验证文件树显示
7. 点击同步按钮，验证更新成功

- [ ] **步骤 4：验证解析增强**

在粘贴 Tab 测试以下场景：
- 带多行 description 的 YAML frontmatter
- 无 frontmatter 的纯 Markdown
- 带非标准字段（read_when、allowed-tools）的 frontmatter

预期：均能正确解析并展示 warnings

- [ ] **步骤 5：最终 Commit**

```bash
git add -A
git commit -m "feat(skill): complete non-standard skill import with Git URL support"
```
