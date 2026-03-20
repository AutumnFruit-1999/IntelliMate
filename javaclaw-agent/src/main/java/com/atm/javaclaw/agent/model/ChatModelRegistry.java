package com.atm.javaclaw.agent.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry caching ChatModel instances per provider,
 * and resolving model_definition.id → ResolvedModel.
 */
@Component
public class ChatModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatModelRegistry.class);

    private final ChatModelFactory factory;

    private final Map<Long, ChatModel> providerModels = new ConcurrentHashMap<>();
    private final Map<Long, ProviderType> providerTypes = new ConcurrentHashMap<>();
    private final Map<Long, ModelConfig> definitionIndex = new ConcurrentHashMap<>();
    private final Map<String, ModelConfig> legacyNameIndex = new ConcurrentHashMap<>();

    public ChatModelRegistry(ChatModelFactory factory) {
        this.factory = factory;
    }

    public void registerProvider(ProviderConfig config) {
        try {
            ChatModel model = factory.create(config);
            providerModels.put(config.id(), model);
            providerTypes.put(config.id(), config.type());
            log.info("Registered provider '{}' (id={}, type={})", config.name(), config.id(), config.type());
        } catch (Exception e) {
            log.error("Failed to register provider '{}': {}", config.name(), e.getMessage(), e);
        }
    }

    public void registerDefinitions(List<ModelConfig> definitions) {
        for (ModelConfig def : definitions) {
            definitionIndex.put(def.definitionId(), def);
            legacyNameIndex.put(def.modelId(), def);
        }
        log.info("Registered {} model definitions", definitions.size());
    }

    /**
     * Resolve by model_definition.id.
     */
    public ResolvedModel resolve(Long definitionId) {
        ModelConfig mc = definitionIndex.get(definitionId);
        if (mc == null) {
            throw new IllegalArgumentException("Unknown model definition id: " + definitionId);
        }
        ChatModel chatModel = providerModels.get(mc.providerId());
        if (chatModel == null) {
            throw new IllegalStateException("No ChatModel registered for provider id: " + mc.providerId());
        }
        ProviderType type = providerTypes.getOrDefault(mc.providerId(), ProviderType.OPENAI_COMPATIBLE);
        return new ResolvedModel(chatModel, mc.modelId(), type);
    }

    /**
     * Resolve by legacy model name (e.g. "qwen-plus").
     * Falls back to the first available provider if not found.
     */
    public ResolvedModel resolveByModelName(String modelName) {
        ModelConfig mc = legacyNameIndex.get(modelName);
        if (mc != null) {
            ChatModel chatModel = providerModels.get(mc.providerId());
            if (chatModel != null) {
                ProviderType type = providerTypes.getOrDefault(mc.providerId(), ProviderType.OPENAI_COMPATIBLE);
                return new ResolvedModel(chatModel, mc.modelId(), type);
            }
        }
        if (!providerModels.isEmpty()) {
            var entry = providerModels.entrySet().iterator().next();
            log.warn("Model '{}' not found in registry, falling back to provider id={}", modelName, entry.getKey());
            ProviderType type = providerTypes.getOrDefault(entry.getKey(), ProviderType.OPENAI_COMPATIBLE);
            return new ResolvedModel(entry.getValue(), modelName, type);
        }
        throw new IllegalStateException("No model providers registered. Cannot resolve model: " + modelName);
    }

    public void refreshAll(List<ProviderConfig> providers, List<ModelConfig> definitions) {
        providerModels.clear();
        providerTypes.clear();
        definitionIndex.clear();
        legacyNameIndex.clear();

        for (ProviderConfig pc : providers) {
            registerProvider(pc);
        }
        registerDefinitions(definitions);
        log.info("Registry refreshed: {} providers, {} definitions", providers.size(), definitions.size());
    }

    public boolean hasProvider(Long providerId) {
        return providerModels.containsKey(providerId);
    }

    public int providerCount() {
        return providerModels.size();
    }

    public int definitionCount() {
        return definitionIndex.size();
    }
}
