package com.atm.intellimate.gateway.http;

import com.atm.intellimate.core.exception.ErrorCode;
import com.atm.intellimate.core.exception.IntelliMateException;
import com.atm.intellimate.gateway.dto.ApiResponse;
import com.atm.intellimate.gateway.dto.ModelDTO;
import com.atm.intellimate.gateway.dto.ModelGroupDTO;
import com.atm.intellimate.gateway.entity.ModelDefinitionEntity;
import com.atm.intellimate.gateway.entity.ModelProviderEntity;
import com.atm.intellimate.gateway.repository.ModelDefinitionRepository;
import com.atm.intellimate.gateway.repository.ModelProviderRepository;
import com.atm.intellimate.gateway.service.ModelRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "Model", description = "模型定义管理 API")
@RestController
@RequestMapping("/api")
public class ModelDefinitionController {

    private final ModelProviderRepository providerRepo;
    private final ModelDefinitionRepository definitionRepo;
    private final ModelRegistryService registryService;

    public ModelDefinitionController(ModelProviderRepository providerRepo,
                                     ModelDefinitionRepository definitionRepo,
                                     ModelRegistryService registryService) {
        this.providerRepo = providerRepo;
        this.definitionRepo = definitionRepo;
        this.registryService = registryService;
    }

    @Operation(summary = "获取分组模型列表")
    @GetMapping("/models")
    public Mono<ApiResponse<List<ModelGroupDTO>>> listGrouped() {
        return providerRepo.findAllByEnabledOrderBySortOrder(1)
                .collectList()
                .flatMap(providers -> {
                    Map<Long, ModelProviderEntity> providerMap = new LinkedHashMap<>();
                    for (var p : providers) {
                        providerMap.put(p.getId(), p);
                    }
                    return definitionRepo.findAllByEnabledOrderBySortOrder(1)
                            .collectList()
                            .map(definitions -> ApiResponse.ok(buildGroups(providerMap, definitions)));
                });
    }

    @Operation(summary = "创建模型定义")
    @PostMapping("/model-definitions")
    public Mono<ApiResponse<ModelDTO>> create(@RequestBody CreateModelRequest body) {
        if (body.providerId == null) {
            return Mono.error(new IntelliMateException(ErrorCode.VALIDATION_FAILED, "providerId is required"));
        }
        if (body.modelId == null || body.modelId.isBlank()) {
            return Mono.error(new IntelliMateException(ErrorCode.VALIDATION_FAILED, "modelId is required"));
        }
        if (body.displayName == null || body.displayName.isBlank()) {
            return Mono.error(new IntelliMateException(ErrorCode.VALIDATION_FAILED, "displayName is required"));
        }

        return providerRepo.findById(body.providerId)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.MODEL_NOT_FOUND, "Provider not found")))
                .flatMap(provider -> {
                    ModelDefinitionEntity entity = new ModelDefinitionEntity();
                    entity.setProviderId(body.providerId);
                    entity.setModelId(body.modelId.trim());
                    entity.setDisplayName(body.displayName.trim());
                    entity.setDescription(body.description);
                    entity.setMaxTokens(body.maxTokens);
                    entity.setEnabled(1);
                    entity.setSortOrder(body.sortOrder != null ? body.sortOrder : 0);
                    return definitionRepo.save(entity);
                })
                .flatMap(saved -> registryService.reload().thenReturn(saved))
                .map(ModelDTO::fromEntity)
                .map(ApiResponse::ok);
    }

    @PutMapping("/model-definitions/{id}")
    public Mono<ApiResponse<ModelDTO>> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return definitionRepo.findById(id)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.MODEL_NOT_FOUND)))
                .flatMap(entity -> {
                    if (body.containsKey("modelId")) entity.setModelId((String) body.get("modelId"));
                    if (body.containsKey("displayName")) entity.setDisplayName((String) body.get("displayName"));
                    if (body.containsKey("description")) entity.setDescription((String) body.get("description"));
                    if (body.containsKey("maxTokens")) {
                        Object v = body.get("maxTokens");
                        entity.setMaxTokens(v != null ? ((Number) v).intValue() : null);
                    }
                    if (body.containsKey("enabled")) entity.setEnabled(((Number) body.get("enabled")).intValue());
                    if (body.containsKey("sortOrder")) entity.setSortOrder(((Number) body.get("sortOrder")).intValue());
                    return definitionRepo.save(entity);
                })
                .flatMap(saved -> registryService.reload().thenReturn(saved))
                .map(ModelDTO::fromEntity)
                .map(ApiResponse::ok);
    }

    @DeleteMapping("/model-definitions/{id}")
    public Mono<ApiResponse<Map<String, Object>>> delete(@PathVariable Long id) {
        return definitionRepo.findById(id)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.MODEL_NOT_FOUND)))
                .flatMap(entity -> definitionRepo.delete(entity)
                        .then(registryService.reload())
                        .thenReturn(ApiResponse.ok(Map.<String, Object>of(
                                "success", true, "deletedModelId", entity.getModelId()))));
    }

    private List<ModelGroupDTO> buildGroups(Map<Long, ModelProviderEntity> providerMap,
                                            List<ModelDefinitionEntity> definitions) {
        Map<Long, ModelGroupDTO> groupMap = new LinkedHashMap<>();
        for (var provider : providerMap.values()) {
            groupMap.put(provider.getId(), new ModelGroupDTO(
                    provider.getId(),
                    provider.getName(),
                    provider.getType(),
                    new ArrayList<>()
            ));
        }

        for (var def : definitions) {
            ModelGroupDTO group = groupMap.get(def.getProviderId());
            if (group == null) continue;
            group.models().add(ModelDTO.fromEntity(def));
        }

        return groupMap.values().stream()
                .filter(g -> !g.models().isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public record CreateModelRequest(Long providerId, String modelId, String displayName,
                                     String description, Integer maxTokens, Integer sortOrder) {}
}
