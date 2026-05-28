package com.atm.intellimate.gateway.service;

import com.atm.intellimate.core.exception.ErrorCode;
import com.atm.intellimate.core.exception.IntelliMateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.Comparator;
import java.util.List;
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

    // ─── Records ───

    public record SkillMdMeta(String name, String description, String displayName, String tags, String content) {}

    public record GitImportResult(String name, String description, String displayName,
                                   String tags, String content, Path skillDir) {}

    // ─── Name Normalization ───

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

    // ─── Frontmatter Parsing ───

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

    // ─── Git Clone ───

    public Path cloneRepository(String gitUrl, String branch) {
        if (!HTTPS_GIT_URL.matcher(gitUrl).matches()) {
            throw new IntelliMateException(ErrorCode.VALIDATION_FAILED,
                    "Only HTTPS Git URLs are allowed");
        }

        try {
            Path tempDir = Files.createTempDirectory("skill-clone-");
            ProcessBuilder pb = new ProcessBuilder("git", "clone", "--depth", "1");
            if (branch != null && !branch.isBlank()) {
                pb.command().addAll(List.of("--branch", branch));
            }
            pb.command().addAll(List.of(gitUrl, tempDir.resolve("repo").toString()));
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

    // ─── Import from Git ───

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

    // ─── Sync from Git ───

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

    // ─── File Utilities ───

    private String inferNameFromUrl(String gitUrl) {
        String[] parts = gitUrl.split("/");
        String last = parts[parts.length - 1];
        if (last.endsWith(".git")) last = last.substring(0, last.length() - 4);
        return normalizeSkillName(last);
    }

    private void deleteDirectory(Path dir) {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
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
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
