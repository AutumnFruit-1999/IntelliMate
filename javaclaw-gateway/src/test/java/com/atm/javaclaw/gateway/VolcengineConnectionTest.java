package com.atm.javaclaw.gateway;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;

import java.net.URI;

/**
 * Standalone test — run main() directly from IDE, no Spring context needed.
 * Fill in BASE_URL / API_KEY / MODEL_ID before running.
 */
public class VolcengineConnectionTest {

    private static final String BASE_URL = "https://ark.cn-beijing.volces.com/api/coding/v3";
    private static final String API_KEY  = "e3434d07-0aa1-401f-85cb-2332a73e5be6";
    private static final String MODEL_ID = "ark-code-latest";

    public static void main(String[] args) {
        System.out.println("=== Volcengine Connection Test ===");
        System.out.println("BaseURL : " + BASE_URL);
        System.out.println("API Key : " + mask(API_KEY));
        System.out.println("Model   : " + MODEL_ID);
        System.out.println();

        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(BASE_URL)
                .apiKey(API_KEY);

        if (hasVersionPath(BASE_URL)) {
            apiBuilder.completionsPath("/chat/completions");
            apiBuilder.embeddingsPath("/embeddings");
            System.out.println("Detected version path in baseUrl, using /chat/completions");
        }

        OpenAiApi api = apiBuilder.build();

        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiApi(api)
                .build();

        try {
            ChatResponse response = chatModel.call(
                    new Prompt("hi",
                            ToolCallingChatOptions.builder().model(MODEL_ID).build()));

            String text = response.getResult().getOutput().getText();
            System.out.println("[SUCCESS] Response: " + text);
        } catch (Exception e) {
            System.err.println("[FAILED] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            Throwable cause = e;
            while (cause.getCause() != null && cause.getCause() != cause) {
                cause = cause.getCause();
                System.err.println("  Caused by: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
        }
    }

    private static boolean hasVersionPath(String baseUrl) {
        try {
            String path = URI.create(baseUrl).getPath();
            return path != null && path.matches(".*/(v\\d+)(/.*)?$");
        } catch (Exception e) {
            return false;
        }
    }

    private static String mask(String key) {
        if (key == null || key.length() <= 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
