package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.entity.SkillGroupEntity;
import com.atm.intellimate.gateway.entity.SkillGroupMemberEntity;
import com.atm.intellimate.gateway.service.SkillGroupService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/skill-groups")
public class SkillGroupController {

    private final SkillGroupService service;

    public SkillGroupController(SkillGroupService service) {
        this.service = service;
    }

    @GetMapping
    public Mono<List<Map<String, Object>>> list() {
        return service.listAll()
                .flatMap(group -> service.countMembers(group.getId())
                        .map(count -> toMap(group, count)))
                .collectList();
    }

    @PostMapping
    public Mono<SkillGroupEntity> create(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String displayName = body.get("displayName");
        String description = body.get("description");
        if (name == null || name.isBlank()) {
            return Mono.error(new IllegalArgumentException("name is required"));
        }
        return service.create(name.trim(), displayName, description);
    }

    @PutMapping("/{id}")
    public Mono<SkillGroupEntity> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String displayName = (String) body.get("displayName");
        String description = (String) body.get("description");
        Integer enabled = body.containsKey("enabled") ? ((Number) body.get("enabled")).intValue() : null;
        return service.update(id, name, displayName, description, enabled);
    }

    @DeleteMapping("/{id}")
    public Mono<Void> delete(@PathVariable Long id) {
        return service.delete(id);
    }

    @SuppressWarnings("unchecked")
    @PutMapping("/reorder")
    public Mono<Void> reorder(@RequestBody Map<String, Object> body) {
        List<Number> ids = (List<Number>) body.get("ids");
        if (ids == null || ids.isEmpty()) {
            return Mono.empty();
        }
        List<Long> orderedIds = ids.stream().map(Number::longValue).toList();
        return service.reorder(orderedIds);
    }

    @GetMapping("/{id}/skills")
    public Mono<List<Long>> getSkills(@PathVariable Long id) {
        return service.getMembers(id)
                .map(SkillGroupMemberEntity::getSkillId)
                .collectList();
    }

    @SuppressWarnings("unchecked")
    @PutMapping("/{id}/skills")
    public Mono<Void> setSkills(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        List<Number> skillIds = (List<Number>) body.get("skillIds");
        if (skillIds == null) {
            return Mono.empty();
        }
        List<Long> ids = skillIds.stream().map(Number::longValue).toList();
        return service.setMembers(id, ids);
    }

    private Map<String, Object> toMap(SkillGroupEntity group, Long skillCount) {
        return Map.of(
                "id", group.getId(),
                "name", group.getName(),
                "displayName", group.getDisplayName() != null ? group.getDisplayName() : "",
                "description", group.getDescription() != null ? group.getDescription() : "",
                "sortOrder", group.getSortOrder() != null ? group.getSortOrder() : 0,
                "enabled", group.getEnabled() != null ? group.getEnabled() : 1,
                "skillCount", skillCount
        );
    }
}
