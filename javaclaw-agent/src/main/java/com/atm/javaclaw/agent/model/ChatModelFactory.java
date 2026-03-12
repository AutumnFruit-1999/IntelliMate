package com.atm.javaclaw.agent.model;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

import java.net.URI;

@Component
public class ChatModelFactory {

    private static final Logger log = LoggerFactory.getLogger(ChatModelFactory.class);

    public ChatModel create(ProviderConfig config) {
        log.info("Creating ChatModel: provider='{}', type={}, baseUrl={}, apiKey={}",
                config.name(), config.type(), config.baseUrl(), maskKey(config.apiKey()));
        return switch (config.type()) {
            case DASHSCOPE -> createDashScope(config);
            case OPENAI_COMPATIBLE -> createOpenAiCompatible(config);
            case ANTHROPIC -> createAnthropic(config);
        };
    }

    private String maskKey(String key) {
        if (key == null || key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private ChatModel createDashScope(ProviderConfig config) {
        log.info("Creating DashScope ChatModel for provider '{}'", config.name());
        DashScopeApi.Builder apiBuilder = DashScopeApi.builder()
                .apiKey(config.apiKey());
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            apiBuilder.baseUrl(config.baseUrl());
        }
        DashScopeApi api = apiBuilder.build();
        return DashScopeChatModel.builder()
                .dashScopeApi(api)
                .build();
    }

    private ChatModel createOpenAiCompatible(ProviderConfig config) {
        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .apiKey(config.apiKey());
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            apiBuilder.baseUrl(config.baseUrl());
            if (hasVersionPath(config.baseUrl())) {
                apiBuilder.completionsPath("/chat/completions");
                apiBuilder.embeddingsPath("/embeddings");
                log.info("BaseUrl '{}' contains version path, using /chat/completions instead of default /v1/chat/completions",
                        config.baseUrl());
            }
        }
        OpenAiApi api = apiBuilder.build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .build();
    }

    /**
     * Detect if the baseUrl already contains a version path segment (e.g. /v1, /v2, /v3).
     * In that case the default /v1/chat/completions would create a duplicated version path.
     */
    private boolean hasVersionPath(String baseUrl) {
        try {
            String path = URI.create(baseUrl).getPath();
            return path != null && path.matches(".*/(v\\d+)(/.*)?$");
        } catch (Exception e) {
            return false;
        }
    }

    private ChatModel createAnthropic(ProviderConfig config) {
        log.info("Creating Anthropic ChatModel for provider '{}'", config.name());
        AnthropicApi.Builder apiBuilder = AnthropicApi.builder()
                .apiKey(config.apiKey());
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            apiBuilder.baseUrl(config.baseUrl());
        }
        AnthropicApi api = apiBuilder.build();
        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .build();
    }
}
