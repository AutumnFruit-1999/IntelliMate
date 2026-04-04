package com.atm.javaclaw.gateway.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class SkillFileService {

    private static final Logger log = LoggerFactory.getLogger(SkillFileService.class);

    @Value("${javaclaw.skills.dir:./skills}")
    private String skillsDir;

    public Path getSkillPath(String skillName) {
        return Path.of(skillsDir, skillName);
    }

    public void createSkillDirectory(String name, String content) {
        Path dir = getSkillPath(name);
        try {
            Files.createDirectories(dir);
            if (content != null && !content.isBlank()) {
                Files.writeString(dir.resolve("SKILL.md"), content);
            }
            log.info("Created skill directory: {}", dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create skill directory: " + dir, e);
        }
    }

    public void updateSkillContent(String name, String content) {
        Path dir = getSkillPath(name);
        try {
            Files.createDirectories(dir);
            if (content != null) {
                Files.writeString(dir.resolve("SKILL.md"), content);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to update skill content: " + name, e);
        }
    }

    public void deleteSkillDirectory(String name) {
        Path dir = getSkillPath(name);
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try { Files.delete(p); } catch (IOException e) {
                        log.warn("Failed to delete: {}", p, e);
                    }
                });
            log.info("Deleted skill directory: {}", dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to walk skill directory: " + dir, e);
        }
    }

    public SkillFiles listFiles(String skillName) {
        Path dir = getSkillPath(skillName);
        return new SkillFiles(
                listSubdir(dir, "scripts"),
                listSubdir(dir, "references"),
                listSubdir(dir, "assets")
        );
    }

    public void saveFile(String skillName, String type, String filename, byte[] bytes) {
        validateType(type);
        Path target = getSkillPath(skillName).resolve(type).resolve(filename);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
            log.info("Saved file: {}", target);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to save file: " + target, e);
        }
    }

    public void deleteFile(String skillName, String type, String filename) {
        validateType(type);
        Path target = getSkillPath(skillName).resolve(type).resolve(filename);
        try {
            if (Files.deleteIfExists(target)) {
                log.info("Deleted file: {}", target);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete file: " + target, e);
        }
    }

    public boolean hasSubdir(String skillName, String subdir) {
        Path sub = getSkillPath(skillName).resolve(subdir);
        if (!Files.isDirectory(sub)) return false;
        try (Stream<Path> files = Files.list(sub)) {
            return files.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            return false;
        }
    }

    public String readContent(String skillName) {
        Path skillMd = getSkillPath(skillName).resolve("SKILL.md");
        if (!Files.exists(skillMd)) return null;
        try {
            return Files.readString(skillMd);
        } catch (IOException e) {
            log.warn("Failed to read SKILL.md for {}: {}", skillName, e.getMessage());
            return null;
        }
    }

    public byte[] zipSkillDirectory(String skillName) {
        Path skillDir = getSkillPath(skillName);
        if (!Files.isDirectory(skillDir)) {
            throw new IllegalArgumentException("Skill directory not found: " + skillName);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos);
             Stream<Path> walk = Files.walk(skillDir)) {
            walk.filter(Files::isRegularFile)
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
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to zip skill directory: " + skillName, e);
        }
        return baos.toByteArray();
    }

    private void validateType(String type) {
        if (!"scripts".equals(type) && !"references".equals(type) && !"assets".equals(type)) {
            throw new IllegalArgumentException("Invalid file type: " + type + ". Must be scripts, references, or assets.");
        }
    }

    private List<String> listSubdir(Path dir, String subdir) {
        Path sub = dir.resolve(subdir);
        if (!Files.isDirectory(sub)) return List.of();
        try (Stream<Path> files = Files.list(sub)) {
            return files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public record SkillFiles(List<String> scripts, List<String> references, List<String> assets) {}
}
