package com.atm.javaclaw.gateway;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeImageAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeRerankAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioSpeechAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioTranscriptionAutoConfiguration;
import com.atm.javaclaw.core.config.JavaClawProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;

@SpringBootApplication(exclude = {
        DashScopeAgentAutoConfiguration.class,
        DashScopeEmbeddingAutoConfiguration.class,
        DashScopeImageAutoConfiguration.class,
        DashScopeRerankAutoConfiguration.class,
        DashScopeAudioSpeechAutoConfiguration.class,
        DashScopeAudioTranscriptionAutoConfiguration.class
})
@ComponentScan(basePackages = "com.atm.javaclaw")
@EnableR2dbcRepositories(basePackages = "com.atm.javaclaw.gateway.repository")
@EnableConfigurationProperties(JavaClawProperties.class)
public class JavaClawApplication {

    public static void main(String[] args) {
        SpringApplication.run(JavaClawApplication.class, args);
    }
}
