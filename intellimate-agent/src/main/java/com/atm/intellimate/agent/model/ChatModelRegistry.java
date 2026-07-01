package com.atm.intellimate.agent.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory registry caching ChatModel instances per provider,
 * and resolving model_definition.id → ResolvedModel.
 * Uses volatile snapshot swap for atomic refresh.
 */
@Component
public class ChatModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ChatModelRegistry.class);

    private final ChatModelFactory factory;

    private volatile RegistrySnapshot snapshot = RegistrySnapshot.EMPTY;

    public ChatModelRegistry(ChatModelFactory factory) {
        this.factory = factory;
    }

    /**
     * Resolve by model_definition.id.
     */
    public ResolvedModel resolve(Long definitionId) {
        RegistrySnapshot s = snapshot;
        ModelConfig mc = s.definitionIndex.get(definitionId);
        if (mc == null) {
            throw new IllegalArgumentException("Unknown model definition id: " + definitionId);
        }
        ChatModel chatModel = s.providerModels.get(mc.providerId());
        if (chatModel == null) {
            throw new IllegalStateException("No ChatModel registered for provider id: " + mc.providerId());
        }
        ProviderType type = s.providerTypes.getOrDefault(mc.providerId(), ProviderType.OPENAI_COMPATIBLE);
        return new ResolvedModel(chatModel, mc.modelId(), type);
    }

    /**
     * Resolve by legacy model name (e.g. "qwen-plus").
     * Falls back to the first available provider if not found.
     */
    public ResolvedModel resolveByModelName(String modelName) {
        RegistrySnapshot s = snapshot;
        ModelConfig mc = s.legacyNameIndex.get(modelName);
        if (mc != null) {
            ChatModel chatModel = s.providerModels.get(mc.providerId());
            if (chatModel != null) {
                ProviderType type = s.providerTypes.getOrDefault(mc.providerId(), ProviderType.OPENAI_COMPATIBLE);
                return new ResolvedModel(chatModel, mc.modelId(), type);
            }
            throw new IllegalStateException(
                    "Provider id=" + mc.providerId() + " for model '" + modelName + "' has no ChatModel registered");
        }
        throw new IllegalStateException("Model '" + modelName + "' not found in registry. "
                + "Available models: " + s.legacyNameIndex.keySet());
    }

    /**
     * Atomically rebuild the entire registry and swap in one step.
     * Concurrent readers see either the old or the new state, never a partial state.
     */
    public void refreshAll(List<ProviderConfig> providers, List<ModelConfig> definitions) {
        Map<Long, ChatModel> newProviderModels = new HashMap<>();
        Map<Long, ProviderType> newProviderTypes = new HashMap<>();
        Map<Long, ModelConfig> newDefinitionIndex = new HashMap<>();
        Map<String, ModelConfig> newLegacyNameIndex = new HashMap<>();

        for (ProviderConfig pc : providers) {
            try {
                ChatModel model = factory.create(pc);
                newProviderModels.put(pc.id(), model);
                newProviderTypes.put(pc.id(), pc.type());
            } catch (Exception e) {
                log.error("Failed to register provider '{}': {}", pc.name(), e.getMessage(), e);
            }
        }

        for (ModelConfig def : definitions) {
            newDefinitionIndex.put(def.definitionId(), def);
            ModelConfig existing = newLegacyNameIndex.put(def.modelId(), def);
            if (existing != null && !existing.providerId().equals(def.providerId())) {
                log.warn("Model name '{}' exists in multiple providers (def {} and {}), "
                        + "legacy name resolution may be ambiguous",
                        def.modelId(), existing.definitionId(), def.definitionId());
            }
        }

        snapshot = new RegistrySnapshot(
                Map.copyOf(newProviderModels),
                Map.copyOf(newProviderTypes),
                Map.copyOf(newDefinitionIndex),
                Map.copyOf(newLegacyNameIndex)
        );
    }

    public boolean hasProvider(Long providerId) {
        return snapshot.providerModels.containsKey(providerId);
    }

    public int providerCount() {
        return snapshot.providerModels.size();
    }

    public int definitionCount() {
        return snapshot.definitionIndex.size();
    }

    public ModelConfig getDefinition(Long definitionId) {
        return snapshot.definitionIndex.get(definitionId);
    }

    private record RegistrySnapshot(
            Map<Long, ChatModel> providerModels,
            Map<Long, ProviderType> providerTypes,
            Map<Long, ModelConfig> definitionIndex,
            Map<String, ModelConfig> legacyNameIndex
    ) {
        static final RegistrySnapshot EMPTY = new RegistrySnapshot(
                Map.of(), Map.of(), Map.of(), Map.of()
        );
    }
}
