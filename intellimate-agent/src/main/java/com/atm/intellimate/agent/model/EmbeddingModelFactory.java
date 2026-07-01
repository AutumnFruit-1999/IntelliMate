package com.atm.intellimate.agent.model;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class EmbeddingModelFactory {

    public EmbeddingModel create(ProviderConfig config, String modelId, int dimensions) {
        return switch (config.type()) {
            case DASHSCOPE -> createDashScope(config, modelId, dimensions);
            case OPENAI_COMPATIBLE -> createOpenAi(config, modelId, dimensions);
            case ANTHROPIC, DEEPSEEK ->
                    throw new UnsupportedOperationException(
                            "Provider type " + config.type() + " does not support embedding models");
        };
    }

    private EmbeddingModel createDashScope(ProviderConfig config, String modelId, int dimensions) {
        DashScopeApi.Builder apiBuilder = DashScopeApi.builder().apiKey(config.apiKey());
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            apiBuilder.baseUrl(config.baseUrl());
        }
        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
                .withModel(modelId)
                .withDimensions(dimensions)
                .build();
        return new DashScopeEmbeddingModel(apiBuilder.build(), MetadataMode.EMBED, options);
    }

    private EmbeddingModel createOpenAi(ProviderConfig config, String modelId, int dimensions) {
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(config.apiKey());
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            apiBuilder.baseUrl(config.baseUrl());
            if (hasVersionPath(config.baseUrl())) {
                apiBuilder.embeddingsPath("/embeddings");
            }
        }
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(modelId)
                .dimensions(dimensions)
                .build();
        return new OpenAiEmbeddingModel(apiBuilder.build(), MetadataMode.EMBED, options);
    }

    private boolean hasVersionPath(String baseUrl) {
        try {
            String path = URI.create(baseUrl).getPath();
            return path != null && path.matches(".*/(v\\d+)(/.*)?$");
        } catch (Exception e) {
            return false;
        }
    }
}
