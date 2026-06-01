package com.atm.intellimate.gateway.config;

import com.atm.intellimate.gateway.service.QdrantVectorStoreImpl;
import com.atm.intellimate.memory.retrieval.VectorMemoryStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "spring.ai.vectorstore.qdrant.host")
public class QdrantVectorStoreConfig {

    @Bean
    @ConditionalOnBean(VectorStore.class)
    public VectorMemoryStore vectorMemoryStore(VectorStore vectorStore, EmbeddingModel embeddingModel) {
        return new QdrantVectorStoreImpl(vectorStore, embeddingModel);
    }
}
