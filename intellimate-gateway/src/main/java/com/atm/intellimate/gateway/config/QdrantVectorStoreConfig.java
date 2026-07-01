package com.atm.intellimate.gateway.config;

import com.atm.intellimate.agent.model.DelegatingEmbeddingModel;
import com.atm.intellimate.gateway.service.QdrantVectorStoreImpl;
import com.atm.intellimate.memory.retrieval.VectorMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
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
    public DelegatingEmbeddingModel embeddingModel() {
        return new DelegatingEmbeddingModel();
    }

    /**
     * ObjectProvider defers VectorStore lookup to bean instantiation phase,
     * after Spring AI auto-configuration has created the VectorStore bean.
     * Direct @ConditionalOnBean(VectorStore.class) fails because user @Configuration
     * classes are processed before auto-configurations.
     */
    @Bean
    public VectorMemoryStore vectorMemoryStore(ObjectProvider<VectorStore> vectorStoreProvider) {
        VectorStore vectorStore = vectorStoreProvider.getIfAvailable();
        if (vectorStore != null) {
            return new QdrantVectorStoreImpl(vectorStore);
        }
        log.warn("VectorStore bean not available, vector memory features disabled");
        return null;
    }
}
