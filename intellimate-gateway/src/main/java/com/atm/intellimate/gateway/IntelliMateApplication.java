package com.atm.intellimate.gateway;

import com.atm.intellimate.core.config.IntelliMateProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
        org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration.class,
        org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration.class,
        org.springframework.ai.model.anthropic.autoconfigure.AnthropicChatAutoConfiguration.class,
        org.springframework.ai.model.deepseek.autoconfigure.DeepSeekChatAutoConfiguration.class
})
@ComponentScan(basePackages = "com.atm.intellimate")
@EnableR2dbcRepositories(basePackages = "com.atm.intellimate.gateway.repository")
@EnableConfigurationProperties(IntelliMateProperties.class)
@EnableScheduling
public class IntelliMateApplication {

    public static void main(String[] args) {
        SpringApplication.run(IntelliMateApplication.class, args);
    }
}
