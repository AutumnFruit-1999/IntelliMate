package com.atm.intellimate.gateway.http;

import com.atm.intellimate.core.exception.ErrorCode;
import com.atm.intellimate.core.exception.IntelliMateException;
import com.atm.intellimate.gateway.dto.ApiResponse;
import com.atm.intellimate.gateway.dto.SkillDTO;
import com.atm.intellimate.gateway.entity.SkillDefinitionEntity;
import com.atm.intellimate.gateway.entity.SkillVersionEntity;
import com.atm.intellimate.gateway.repository.SkillDefinitionRepository;
import com.atm.intellimate.gateway.repository.SkillUsageLogRepository;
import com.atm.intellimate.gateway.repository.SkillVersionRepository;
import com.atm.intellimate.gateway.service.SkillFileService;
import com.atm.intellimate.gateway.service.SkillGitService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/api/skills")
public class SkillDefinitionController {

    private static final Logger log = LoggerFactory.getLogger(SkillDefinitionController.class);
    private static final Pattern SKILL_NAME_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_-]{0,127}$");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillDefinitionRepository repository;
    private final SkillVersionRepository versionRepository;
    private final SkillUsageLogRepository usageLogRepository;
    private final SkillFileService fileService;
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

    @GetMapping
    public Mono<ApiResponse<List<SkillDTO>>> listAll() {
        return repository.findAll()
                .map(SkillDTO::fromEntity)
                .collectList()
                .map(ApiResponse::ok);
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<SkillDTO>> getById(@PathVariable Long id) {
        return repository.findById(id)
                .map(SkillDTO::fromEntity)
                .map(ApiResponse::ok)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND)));
    }

    @PostMapping
    public Mono<ApiResponse<SkillDTO>> create(@RequestBody Map<String, Object> body) {
        return Mono.defer(() -> {
            String name = (String) body.get("name");
            if (name == null || !SKILL_NAME_PATTERN.matcher(name).matches()) {
                return Mono.error(new IntelliMateException(ErrorCode.VALIDATION_FAILED,
                        "Skill 名称必须以字母开头，只能包含字母、数字、下划线和连字符，最长 128 字符"));
            }

            String description = (String) body.get("description");
            if (description == null || description.isBlank()) {
                return Mono.error(new IntelliMateException(ErrorCode.VALIDATION_FAILED,
                        "description（触发描述）不能为空"));
            }

            return repository.findByName(name)
                    .flatMap(existing -> Mono.<SkillDefinitionEntity>error(
                            new IntelliMateException(ErrorCode.VALIDATION_FAILED, "Skill 名称已存在: " + name)))
                    .switchIfEmpty(Mono.defer(() -> {
                        String content = (String) body.get("content");

                        SkillDefinitionEntity entity = new SkillDefinitionEntity();
                        entity.setName(name);
                        entity.setDisplayName((String) body.get("displayName"));
                        entity.setDescription(description);
                        entity.setContent(content);
                        entity.setTags((String) body.get("tags"));
                        entity.setMetadata(toJsonString(body.get("metadata")));
                        entity.setHasScripts(0);
                        entity.setHasReferences(0);
                        entity.setEnabled(1);
                        entity.setCreatedAt(LocalDateTime.now());
                        entity.setUpdatedAt(LocalDateTime.now());

                        return repository.save(entity)
                                .doOnNext(saved -> {
                                    fileService.createSkillDirectory(name, content);
                                });
                    }))
                    .map(SkillDTO::fromEntity)
                    .map(ApiResponse::ok);
        });
    }

    @PutMapping("/{id}")
    public Mono<ApiResponse<SkillDTO>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND)))
                .flatMap(entity -> {
                    if (body.containsKey("name")) {
                        String name = (String) body.get("name");
                        if (name != null && !SKILL_NAME_PATTERN.matcher(name).matches()) {
                            return Mono.error(new IntelliMateException(ErrorCode.VALIDATION_FAILED,
                                    "Skill 名称格式不合法"));
                        }
                        return repository.findByName(name)
                                .filter(existing -> !existing.getId().equals(id))
                                .flatMap(dup -> Mono.<SkillDefinitionEntity>error(
                                        new IntelliMateException(ErrorCode.VALIDATION_FAILED, "Skill 名称已存在: " + name)))
                                .switchIfEmpty(Mono.defer(() -> {
                                    entity.setName(name);
                                    return applyUpdates(entity, body);
                                }));
                    }
                    return applyUpdates(entity, body);
                })
                .map(SkillDTO::fromEntity)
                .map(ApiResponse::ok);
    }

    private Mono<SkillDefinitionEntity> applyUpdates(SkillDefinitionEntity entity, Map<String, Object> body) {
        boolean contentOrDescChanged = body.containsKey("content") || body.containsKey("description");

        Mono<Void> snapshotMono;
        if (contentOrDescChanged && entity.getId() != null) {
            snapshotMono = saveCurrentAsVersion(entity, (String) body.get("changeNote"));
        } else {
            snapshotMono = Mono.empty();
        }

        return snapshotMono.then(Mono.defer(() -> {
            if (body.containsKey("displayName")) entity.setDisplayName((String) body.get("displayName"));
            if (body.containsKey("description")) entity.setDescription((String) body.get("description"));
            if (body.containsKey("content")) {
                String content = (String) body.get("content");
                entity.setContent(content);
                fileService.updateSkillContent(entity.getName(), content);
            }
            if (body.containsKey("tags")) entity.setTags((String) body.get("tags"));
            if (body.containsKey("metadata")) entity.setMetadata(toJsonString(body.get("metadata")));
            if (body.containsKey("enabled")) {
                Object val = body.get("enabled");
                entity.setEnabled(val instanceof Boolean b ? (b ? 1 : 0) : ((Number) val).intValue());
            }
            entity.setUpdatedAt(LocalDateTime.now());
            return repository.save(entity);
        }));
    }

    private Mono<Void> saveCurrentAsVersion(SkillDefinitionEntity entity, String changeNote) {
        return versionRepository.findTopBySkillIdOrderByVersionDesc(entity.getId())
                .map(latest -> latest.getVersion() + 1)
                .defaultIfEmpty(1)
                .flatMap(nextVersion -> {
                    SkillVersionEntity ver = new SkillVersionEntity();
                    ver.setSkillId(entity.getId());
                    ver.setVersion(nextVersion);
                    ver.setContent(entity.getContent());
                    ver.setDescription(entity.getDescription());
                    ver.setChangeNote(changeNote);
                    ver.setCreatedAt(LocalDateTime.now());
                    return versionRepository.save(ver);
                })
                .then();
    }

    @DeleteMapping("/{id}")
    public Mono<ApiResponse<Map<String, Object>>> delete(@PathVariable Long id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND)))
                .flatMap(entity -> repository.delete(entity).thenReturn(entity))
                .doOnNext(deleted -> fileService.deleteSkillDirectory(deleted.getName()))
                .map(deleted -> ApiResponse.ok(Map.<String, Object>of(
                        "success", true,
                        "deletedName", deleted.getName())));
    }

    // ─── Git Import / Sync ───

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

    // ─── Export ───

    @GetMapping("/{id}/export")
    public Mono<ResponseEntity<String>> exportSkillMd(@PathVariable Long id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND)))
                .map(entity -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("---\n");
                    sb.append("name: ").append(entity.getName()).append('\n');
                    sb.append("description: ").append(entity.getDescription()).append('\n');
                    if (entity.getDisplayName() != null) {
                        sb.append("displayName: ").append(entity.getDisplayName()).append('\n');
                    }
                    if (entity.getTags() != null) {
                        sb.append("tags: ").append(entity.getTags()).append('\n');
                    }
                    if (entity.getMetadata() != null) {
                        sb.append("metadata: ").append(entity.getMetadata()).append('\n');
                    }
                    sb.append("---\n\n");
                    if (entity.getContent() != null) {
                        sb.append(entity.getContent());
                    }
                    return ResponseEntity.ok()
                            .header("Content-Disposition", "attachment; filename=\"SKILL.md\"")
                            .contentType(MediaType.TEXT_MARKDOWN)
                            .body(sb.toString());
                });
    }

    @GetMapping("/{id}/export/zip")
    public Mono<ResponseEntity<byte[]>> exportSkillZip(@PathVariable Long id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND)))
                .map(entity -> {
                    byte[] zipData = fileService.zipSkillDirectory(entity.getName());
                    return ResponseEntity.ok()
                            .header("Content-Disposition",
                                    "attachment; filename=\"" + entity.getName() + ".zip\"")
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .body(zipData);
                });
    }

    // ─── File Management ───

    @GetMapping("/{id}/files")
    public Mono<ApiResponse<SkillFileService.SkillFiles>> listFiles(@PathVariable Long id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND)))
                .map(entity -> ApiResponse.ok(fileService.listFiles(entity.getName())));
    }

    @PostMapping("/{id}/files/{type}")
    public Mono<ApiResponse<Map<String, Object>>> uploadFile(
            @PathVariable Long id,
            @PathVariable String type,
            @RequestPart("file") FilePart filePart) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND)))
                .flatMap(entity -> filePart.content()
                        .map(dataBuffer -> {
                            byte[] bytes = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(bytes);
                            return bytes;
                        })
                        .reduce(new byte[0], (a, b) -> {
                            byte[] merged = new byte[a.length + b.length];
                            System.arraycopy(a, 0, merged, 0, a.length);
                            System.arraycopy(b, 0, merged, a.length, b.length);
                            return merged;
                        })
                        .flatMap(bytes -> Mono.fromCallable(() -> {
                            String filename = filePart.filename();
                            fileService.saveFile(entity.getName(), type, filename, bytes);
                            return entity;
                        }).subscribeOn(Schedulers.boundedElastic()))
                        .flatMap(ent -> updateFileFlags(ent))
                        .map(saved -> ApiResponse.ok(Map.<String, Object>of(
                                "success", true,
                                "filename", filePart.filename(),
                                "type", type))));
    }

    @DeleteMapping("/{id}/files/{type}/{filename}")
    public Mono<ApiResponse<Map<String, Object>>> deleteFile(
            @PathVariable Long id,
            @PathVariable String type,
            @PathVariable String filename) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND)))
                .flatMap(entity -> Mono.fromCallable(() -> {
                    fileService.deleteFile(entity.getName(), type, filename);
                    return entity;
                }).subscribeOn(Schedulers.boundedElastic()))
                .flatMap(ent -> updateFileFlags(ent))
                .map(saved -> ApiResponse.ok(Map.<String, Object>of(
                        "success", true,
                        "deletedFile", filename,
                        "type", type)));
    }

    private Mono<SkillDefinitionEntity> updateFileFlags(SkillDefinitionEntity entity) {
        boolean hasScripts = fileService.hasSubdir(entity.getName(), "scripts");
        boolean hasRefs = fileService.hasSubdir(entity.getName(), "references");
        entity.setHasScripts(hasScripts ? 1 : 0);
        entity.setHasReferences(hasRefs ? 1 : 0);
        entity.setUpdatedAt(LocalDateTime.now());
        return repository.save(entity);
    }

    // ─── File Tree ───

    @GetMapping("/{id}/tree")
    public Mono<ApiResponse<SkillFileService.FileNode>> getFileTree(@PathVariable Long id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND)))
                .map(entity -> ApiResponse.ok(fileService.listAllFiles(entity.getName())));
    }

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

    // ─── Version Management ───

    @GetMapping("/{id}/versions")
    public Mono<ApiResponse<List<SkillVersionEntity>>> listVersions(@PathVariable Long id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND)))
                .flatMap(entity -> versionRepository.findAllBySkillIdOrderByVersionDesc(id).collectList())
                .map(ApiResponse::ok);
    }

    @GetMapping("/{id}/versions/{version}")
    public Mono<ApiResponse<SkillVersionEntity>> getVersion(@PathVariable Long id, @PathVariable Integer version) {
        return versionRepository.findBySkillIdAndVersion(id, version)
                .map(ApiResponse::ok)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND,
                        "Version " + version + " not found")));
    }

    @PostMapping("/{id}/rollback/{version}")
    public Mono<ApiResponse<SkillDTO>> rollback(@PathVariable Long id, @PathVariable Integer version) {
        return Mono.zip(
                repository.findById(id)
                        .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND))),
                versionRepository.findBySkillIdAndVersion(id, version)
                        .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND,
                                "Version " + version + " not found")))
        ).flatMap(tuple -> {
            var current = tuple.getT1();
            var target = tuple.getT2();

            return saveCurrentAsVersion(current, "rollback to v" + version)
                    .then(Mono.defer(() -> {
                        current.setContent(target.getContent());
                        current.setDescription(target.getDescription());
                        current.setUpdatedAt(LocalDateTime.now());
                        fileService.updateSkillContent(current.getName(), target.getContent());
                        return repository.save(current);
                    }));
        })
                .map(SkillDTO::fromEntity)
                .map(ApiResponse::ok);
    }

    // ─── Usage Statistics ───

    @GetMapping("/stats")
    public Mono<ApiResponse<List<Map<String, Object>>>> usageStats() {
        return usageLogRepository.findUsageStats()
                .map(row -> {
                    Map<String, Object> map = new java.util.HashMap<>();
                    map.put("skillName", row.getSkillName() != null ? row.getSkillName() : "");
                    map.put("totalActivations", row.getCnt());
                    map.put("lastActivatedAt", row.getLastAt() != null ? row.getLastAt().toString() : "");
                    return map;
                })
                .collectList()
                .map(ApiResponse::ok);
    }

    @GetMapping("/{id}/stats")
    public Mono<ApiResponse<Map<String, Object>>> skillStats(@PathVariable Long id) {
        return repository.findById(id)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SKILL_NOT_FOUND)))
                .flatMap(entity -> usageLogRepository.countBySkillName(entity.getName())
                        .map(count -> {
                            Map<String, Object> map = new java.util.HashMap<>();
                            map.put("skillName", entity.getName() != null ? entity.getName() : "");
                            map.put("totalActivations", count);
                            return ApiResponse.ok(map);
                        }));
    }

    // ─── Util ───

    private String toJsonString(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("Failed to serialize to JSON: {}", e.getMessage());
            return obj.toString();
        }
    }
}
