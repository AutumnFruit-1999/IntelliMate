package com.atm.javaclaw.gateway.http;

import com.atm.javaclaw.gateway.entity.ModelDefinitionEntity;
import com.atm.javaclaw.gateway.entity.ModelProviderEntity;
import com.atm.javaclaw.gateway.repository.ModelDefinitionRepository;
import com.atm.javaclaw.gateway.repository.ModelProviderRepository;
import com.atm.javaclaw.gateway.service.ModelRegistryService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @GetMapping("/models")
    public Mono<List<ModelGroupDto>> listGrouped() {
        return providerRepo.findAllByEnabledOrderBySortOrder(1)
                .collectList()
                .flatMap(providers -> {
                    Map<Long, ModelProviderEntity> providerMap = new LinkedHashMap<>();
                    for (var p : providers) {
                        providerMap.put(p.getId(), p);
                    }
                    return definitionRepo.findAllByEnabledOrderBySortOrder(1)
                            .collectList()
                            .map(definitions -> buildGroups(providerMap, definitions));
                });
    }

    @PostMapping("/model-definitions")
    public Mono<ModelItemDto> create(@RequestBody CreateModelRequest body) {
        if (body.providerId == null) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "providerId is required"));
        }
        if (body.modelId == null || body.modelId.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "modelId is required"));
        }
        if (body.displayName == null || body.displayName.isBlank()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "displayName is required"));
        }

        return providerRepo.findById(body.providerId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Provider not found")))
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
                .map(this::toItemDto);
    }

    @PutMapping("/model-definitions/{id}")
    public Mono<ModelItemDto> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return definitionRepo.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found")))
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
                .map(this::toItemDto);
    }

    @DeleteMapping("/model-definitions/{id}")
    public Mono<Map<String, Object>> delete(@PathVariable Long id) {
        return definitionRepo.findById(id)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Model not found")))
                .flatMap(entity -> definitionRepo.delete(entity)
                        .then(registryService.reload())
                        .thenReturn(Map.<String, Object>of("success", true, "deletedModelId", entity.getModelId()))
                );
    }

    private List<ModelGroupDto> buildGroups(Map<Long, ModelProviderEntity> providerMap,
                                            List<ModelDefinitionEntity> definitions) {
        Map<Long, ModelGroupDto> groupMap = new LinkedHashMap<>();
        for (var provider : providerMap.values()) {
            groupMap.put(provider.getId(), new ModelGroupDto(
                    provider.getId(),
                    provider.getName(),
                    provider.getType(),
                    new ArrayList<>()
            ));
        }

        for (var def : definitions) {
            ModelGroupDto group = groupMap.get(def.getProviderId());
            if (group == null) continue;
            group.models().add(toItemDto(def));
        }

        return new ArrayList<>(groupMap.values());
    }

    private ModelItemDto toItemDto(ModelDefinitionEntity e) {
        return new ModelItemDto(e.getId(), e.getModelId(), e.getDisplayName(), e.getDescription());
    }

    public record ModelGroupDto(
            Long providerId,
            String providerName,
            String providerType,
            List<ModelItemDto> models
    ) {}

    public record ModelItemDto(
            Long id,
            String modelId,
            String displayName,
            String description
    ) {}

    public record CreateModelRequest(Long providerId, String modelId, String displayName,
                                     String description, Integer maxTokens, Integer sortOrder) {}
}
