package com.atm.intellimate.agent.model;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class EmbeddingModelFactory {

    private static final String DASHSCOPE_COMPATIBLE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    public EmbeddingModel create(ProviderConfig config, String modelId, int dimensions) {
        return switch (config.type()) {
            case DASHSCOPE, OPENAI_COMPATIBLE -> createOpenAi(config, modelId, dimensions);
            case ANTHROPIC, DEEPSEEK ->
                    throw new UnsupportedOperationException(
                            "Provider type " + config.type() + " does not support embedding models");
        };
    }

    private EmbeddingModel createOpenAi(ProviderConfig config, String modelId, int dimensions) {
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder().apiKey(config.apiKey());
        String baseUrl = resolveBaseUrl(config);
        if (baseUrl != null && !baseUrl.isBlank()) {
            apiBuilder.baseUrl(baseUrl);
            if (hasVersionPath(baseUrl)) {
                apiBuilder.embeddingsPath("/embeddings");
            }
        }
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(modelId)
                .dimensions(dimensions)
                .build();
        return new OpenAiEmbeddingModel(apiBuilder.build(), MetadataMode.EMBED, options);
    }

    private String resolveBaseUrl(ProviderConfig config) {
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            return config.baseUrl();
        }
        if (config.type() == ProviderType.DASHSCOPE) {
            return DASHSCOPE_COMPATIBLE_BASE_URL;
        }
        return null;
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
