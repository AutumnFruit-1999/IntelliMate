package com.atm.intellimate.gateway.service;

import com.atm.intellimate.agent.model.ChatModelRegistry;
import com.atm.intellimate.agent.model.DelegatingEmbeddingModel;
import com.atm.intellimate.agent.model.EmbeddingModelFactory;
import com.atm.intellimate.agent.model.ModelConfig;
import com.atm.intellimate.agent.model.ProviderConfig;
import com.atm.intellimate.agent.model.ProviderType;
import com.atm.intellimate.gateway.entity.MemoryConfigEntity;
import com.atm.intellimate.gateway.entity.ModelDefinitionEntity;
import com.atm.intellimate.gateway.entity.ModelProviderEntity;
import com.atm.intellimate.gateway.repository.MemoryConfigRepository;
import com.atm.intellimate.gateway.repository.ModelDefinitionRepository;
import com.atm.intellimate.gateway.repository.ModelProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class ModelRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryService.class);
    private static final String PLACEHOLDER = "__PLACEHOLDER__";

    private final ModelProviderRepository providerRepo;
    private final ModelDefinitionRepository definitionRepo;
    private final MemoryConfigRepository memoryConfigRepo;
    private final CryptoService cryptoService;
    private final ChatModelRegistry registry;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final DelegatingEmbeddingModel delegatingEmbeddingModel;

    private volatile List<ProviderConfig> cachedProviderConfigs = List.of();

    @Value("${spring.ai.dashscope.api-key:}")
    private String dashScopeApiKeyFromYml;

    public ModelRegistryService(ModelProviderRepository providerRepo,
                                ModelDefinitionRepository definitionRepo,
                                MemoryConfigRepository memoryConfigRepo,
                                CryptoService cryptoService,
                                ChatModelRegistry registry,
                                EmbeddingModelFactory embeddingModelFactory,
                                @Autowired(required = false) DelegatingEmbeddingModel delegatingEmbeddingModel) {
        this.providerRepo = providerRepo;
        this.definitionRepo = definitionRepo;
        this.memoryConfigRepo = memoryConfigRepo;
        this.cryptoService = cryptoService;
        this.registry = registry;
        this.embeddingModelFactory = embeddingModelFactory;
        this.delegatingEmbeddingModel = delegatingEmbeddingModel;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        migrateApiKeyIfNeeded()
                .then(loadAll())
                .then(initEmbeddingModel())
                .subscribe(
                        unused -> {},
                        err -> log.error("ModelRegistryService initialization failed", err)
                );
    }

    /**
     * Auto-migrate DashScope API Key from application.yml to DB on first start.
     */
    private Mono<Void> migrateApiKeyIfNeeded() {
        if (dashScopeApiKeyFromYml == null || dashScopeApiKeyFromYml.isBlank()) {
            return Mono.empty();
        }
        return providerRepo.findByName("阿里 DashScope")
                .flatMap(provider -> {
                    if (PLACEHOLDER.equals(provider.getApiKeyEncrypted())) {
                        provider.setApiKeyEncrypted(cryptoService.encrypt(dashScopeApiKeyFromYml));
                        return providerRepo.save(provider).then();
                    }
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    log.warn("API Key migration failed (non-fatal): {}", e.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<Void> loadAll() {
        return providerRepo.findAllByEnabledOrderBySortOrder(1)
                .collectList()
                .zipWith(definitionRepo.findAllByEnabledOrderBySortOrder(1).collectList())
                .doOnNext(tuple -> {
                    List<ModelProviderEntity> providers = tuple.getT1();
                    List<ModelDefinitionEntity> definitions = tuple.getT2();

                    List<ProviderConfig> providerConfigs = providers.stream()
                            .map(this::toProviderConfig)
                            .toList();

                    List<ModelConfig> chatConfigs = definitions.stream()
                            .filter(d -> !"EMBEDDING".equals(d.getCategory()))
                            .map(this::toModelConfig)
                            .toList();

                    registry.refreshAll(providerConfigs, chatConfigs);

                    this.cachedProviderConfigs = providerConfigs;
                })
                .then();
    }

    /**
     * Reload the entire registry — called after CRUD operations.
     */
    public Mono<Void> reload() {
        return loadAll();
    }

    private Mono<Void> initEmbeddingModel() {
        if (delegatingEmbeddingModel == null) {
            return Mono.empty();
        }
        return memoryConfigRepo.findByAgentNameAndConfigKey("_global_", "embedding.definition_id")
                .flatMap(config -> {
                    String val = config.getConfigValue();
                    if (val == null || val.isBlank()) {
                        return Mono.empty();
                    }
                    try {
                        return refreshEmbeddingModel(Long.parseLong(val));
                    } catch (NumberFormatException e) {
                        log.warn("Invalid embedding.definition_id '{}', ignoring", val);
                        return Mono.empty();
                    }
                })
                .onErrorResume(e -> {
                    log.warn("Embedding model init failed (non-fatal, will use keyword retrieval): {}", e.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<Void> refreshEmbeddingModel(Long definitionId) {
        return definitionRepo.findById(definitionId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Embedding definition not found: " + definitionId)))
                .flatMap(def -> providerRepo.findById(def.getProviderId())
                        .switchIfEmpty(Mono.error(new IllegalStateException("Provider not found for definition: " + definitionId)))
                        .map(provider -> {
                            ProviderConfig pc = toProviderConfig(provider);
                            int dims = def.getDimensions() != null ? def.getDimensions() : 1024;
                            EmbeddingModel model = embeddingModelFactory.create(pc, def.getModelId(), dims);
                            delegatingEmbeddingModel.setDelegate(model);
                            return model;
                        }))
                .then();
    }

    private ProviderConfig toProviderConfig(ModelProviderEntity entity) {
        String apiKey = cryptoService.decrypt(entity.getApiKeyEncrypted());
        return new ProviderConfig(
                entity.getId(),
                entity.getName(),
                ProviderType.valueOf(entity.getType()),
                entity.getBaseUrl(),
                apiKey,
                entity.getThinkingMode()
        );
    }

    private ModelConfig toModelConfig(ModelDefinitionEntity entity) {
        return new ModelConfig(
                entity.getId(),
                entity.getProviderId(),
                entity.getModelId(),
                entity.getDisplayName(),
                entity.getCategory(),
                entity.getDimensions()
        );
    }
}
