package com.atm.javaclaw.agent.model;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;

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
            case DEEPSEEK -> createDeepSeek(config);
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

    private ChatModel createDeepSeek(ProviderConfig config) {
        log.info("Creating DeepSeek ChatModel for provider '{}', thinkingMode={}",
                config.name(), config.thinkingMode());
        String thinkingMode = config.thinkingMode();
        DeepSeekApi.Builder apiBuilder = DeepSeekApi.builder()
                .apiKey(config.apiKey())
                .webClientBuilder(WebClient.builder().filter(deepSeekThinkingFilter(thinkingMode)));
        if (config.baseUrl() != null && !config.baseUrl().isBlank()) {
            apiBuilder.baseUrl(config.baseUrl());
        }
        DeepSeekApi api = apiBuilder.build();
        return DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .build();
    }

    /**
     * Spring AI 1.0.0 的 DeepSeekChatModel 不支持 thinking 参数，
     * 也不会在 tool-call 多轮对话时回传 reasoning_content，
     * 导致 deepseek-v4 系列模型 (默认 thinking=enabled) 在第二轮返回 400。
     * <p>
     * 当 thinkingMode 不为 "enabled" 时（包括 null / "disabled"），
     * 此 filter 在请求体中注入 {"thinking":{"type":"disabled"}} 来绕过此限制。
     * 当 thinkingMode 为 "enabled" 时，不注入 thinking 参数，使用 API 默认行为。
     */
    private ExchangeFilterFunction deepSeekThinkingFilter(String thinkingMode) {
        boolean shouldDisable = !"enabled".equalsIgnoreCase(thinkingMode);
        if (!shouldDisable) {
            return (request, next) -> next.exchange(request);
        }
        ObjectMapper mapper = new ObjectMapper();
        return (request, next) -> {
            ClientRequest wrapped = ClientRequest.from(request)
                    .body((outputMessage, context) -> request.body().insert(
                            new ClientHttpRequestDecorator(outputMessage) {
                                @Override
                                public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                                    return DataBufferUtils.join(Mono.from(body)).flatMap(buf -> {
                                        byte[] bytes = new byte[buf.readableByteCount()];
                                        buf.read(bytes);
                                        DataBufferUtils.release(buf);
                                        try {
                                            ObjectNode root = (ObjectNode) mapper.readTree(bytes);
                                            if (!root.has("thinking")) {
                                                ObjectNode thinking = mapper.createObjectNode();
                                                thinking.put("type", "disabled");
                                                root.set("thinking", thinking);
                                            }
                                            byte[] patched = mapper.writeValueAsBytes(root);
                                            DataBuffer newBuf = outputMessage.bufferFactory().wrap(patched);
                                            return super.writeWith(Mono.just(newBuf));
                                        } catch (Exception e) {
                                            log.warn("[DeepSeek] Failed to patch request body, sending as-is", e);
                                            DataBuffer newBuf = outputMessage.bufferFactory().wrap(bytes);
                                            return super.writeWith(Mono.just(newBuf));
                                        }
                                    });
                                }
                            }, context))
                    .build();
            return next.exchange(wrapped);
        };
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
