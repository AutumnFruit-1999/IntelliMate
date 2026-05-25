package com.atm.intellimate.gateway.http;

import com.atm.intellimate.core.exception.ErrorCode;
import com.atm.intellimate.core.exception.IntelliMateException;
import com.atm.intellimate.gateway.dto.ApiResponse;
import com.atm.intellimate.gateway.dto.SkillGroupDTO;
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
    public Mono<ApiResponse<List<SkillGroupDTO>>> list() {
        return service.listAll()
                .flatMap(group -> service.countMembers(group.getId())
                        .map(count -> SkillGroupDTO.fromEntity(group, count)))
                .collectList()
                .map(ApiResponse::ok);
    }

    @PostMapping
    public Mono<ApiResponse<SkillGroupDTO>> create(@RequestBody Map<String, String> body) {
        String name = body.get("name");
        String displayName = body.get("displayName");
        String description = body.get("description");
        if (name == null || name.isBlank()) {
            return Mono.error(new IntelliMateException(ErrorCode.VALIDATION_FAILED, "name is required"));
        }
        return service.create(name.trim(), displayName, description)
                .map(SkillGroupDTO::fromEntity)
                .map(ApiResponse::ok);
    }

    @PutMapping("/{id}")
    public Mono<ApiResponse<SkillGroupDTO>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        String displayName = (String) body.get("displayName");
        String description = (String) body.get("description");
        Integer enabled = body.containsKey("enabled") ? ((Number) body.get("enabled")).intValue() : null;
        return service.update(id, name, displayName, description, enabled)
                .map(SkillGroupDTO::fromEntity)
                .map(ApiResponse::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<ApiResponse<Void>> delete(@PathVariable Long id) {
        return service.delete(id).thenReturn(ApiResponse.ok());
    }

    @SuppressWarnings("unchecked")
    @PutMapping("/reorder")
    public Mono<ApiResponse<Void>> reorder(@RequestBody Map<String, Object> body) {
        List<Number> ids = (List<Number>) body.get("ids");
        if (ids == null || ids.isEmpty()) {
            return Mono.just(ApiResponse.ok());
        }
        List<Long> orderedIds = ids.stream().map(Number::longValue).toList();
        return service.reorder(orderedIds).thenReturn(ApiResponse.ok());
    }

    @GetMapping("/{id}/skills")
    public Mono<ApiResponse<List<Long>>> getSkills(@PathVariable Long id) {
        return service.getMembers(id)
                .map(SkillGroupMemberEntity::getSkillId)
                .collectList()
                .map(ApiResponse::ok);
    }

    @SuppressWarnings("unchecked")
    @PutMapping("/{id}/skills")
    public Mono<ApiResponse<Void>> setSkills(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        List<Number> skillIds = (List<Number>) body.get("skillIds");
        if (skillIds == null) {
            return Mono.just(ApiResponse.ok());
        }
        List<Long> ids = skillIds.stream().map(Number::longValue).toList();
        return service.setMembers(id, ids).thenReturn(ApiResponse.ok());
    }
}
