package com.atm.intellimate.gateway.config;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.atm.intellimate.gateway.service.QdrantVectorStoreImpl;
import com.atm.intellimate.memory.retrieval.VectorMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "spring.ai.vectorstore.qdrant.host")
public class QdrantVectorStoreConfig {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStoreConfig.class);

    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel dashScopeEmbeddingModel(
            @Value("${spring.ai.dashscope.api-key}") String apiKey,
            @Value("${spring.ai.dashscope.embedding.options.model:text-embedding-v3}") String model,
            @Value("${spring.ai.dashscope.embedding.options.dimensions:1024}") int dimensions) {
        log.info("Creating DashScope EmbeddingModel: model={}, dimensions={}", model, dimensions);
        DashScopeApi dashScopeApi = DashScopeApi.builder().apiKey(apiKey).build();
        DashScopeEmbeddingOptions options = DashScopeEmbeddingOptions.builder()
                .withModel(model)
                .withDimensions(dimensions)
                .build();
        return new DashScopeEmbeddingModel(dashScopeApi, MetadataMode.EMBED, options);
    }

    @Bean
    @ConditionalOnBean(VectorStore.class)
    public VectorMemoryStore vectorMemoryStore(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        return new QdrantVectorStoreImpl(vectorStore, embeddingModel);
    }
}
