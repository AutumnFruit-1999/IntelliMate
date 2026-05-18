package com.atm.intellimate.gateway.service;

import com.atm.intellimate.agent.model.ChatModelRegistry;
import com.atm.intellimate.agent.model.ModelConfig;
import com.atm.intellimate.agent.model.ProviderConfig;
import com.atm.intellimate.agent.model.ProviderType;
import com.atm.intellimate.gateway.entity.ModelDefinitionEntity;
import com.atm.intellimate.gateway.entity.ModelProviderEntity;
import com.atm.intellimate.gateway.repository.ModelDefinitionRepository;
import com.atm.intellimate.gateway.repository.ModelProviderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class ModelRegistryService {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistryService.class);
    private static final String PLACEHOLDER = "__PLACEHOLDER__";

    private final ModelProviderRepository providerRepo;
    private final ModelDefinitionRepository definitionRepo;
    private final CryptoService cryptoService;
    private final ChatModelRegistry registry;

    @Value("${spring.ai.dashscope.api-key:}")
    private String dashScopeApiKeyFromYml;

    public ModelRegistryService(ModelProviderRepository providerRepo,
                                ModelDefinitionRepository definitionRepo,
                                CryptoService cryptoService,
                                ChatModelRegistry registry) {
        this.providerRepo = providerRepo;
        this.definitionRepo = definitionRepo;
        this.cryptoService = cryptoService;
        this.registry = registry;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("ModelRegistryService starting up...");
        migrateApiKeyIfNeeded()
                .then(loadAll())
                .subscribe(
                        unused -> log.info("ModelRegistryService initialized: {} providers, {} definitions",
                                registry.providerCount(), registry.definitionCount()),
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
                        log.info("Migrating DashScope API Key from application.yml to DB");
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

                    List<ModelConfig> modelConfigs = definitions.stream()
                            .map(this::toModelConfig)
                            .toList();

                    registry.refreshAll(providerConfigs, modelConfigs);
                })
                .then();
    }

    /**
     * Reload the entire registry — called after CRUD operations.
     */
    public Mono<Void> reload() {
        return loadAll()
                .doOnSuccess(v -> log.info("Registry reloaded: {} providers, {} definitions",
                        registry.providerCount(), registry.definitionCount()));
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
                entity.getDisplayName()
        );
    }
}
