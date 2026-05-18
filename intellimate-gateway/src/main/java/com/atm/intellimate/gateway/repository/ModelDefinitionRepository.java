package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.ModelDefinitionEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ModelDefinitionRepository extends ReactiveCrudRepository<ModelDefinitionEntity, Long> {

    Flux<ModelDefinitionEntity> findByProviderIdAndEnabledOrderBySortOrder(Long providerId, Integer enabled);

    Flux<ModelDefinitionEntity> findAllByEnabledOrderBySortOrder(Integer enabled);

    Flux<ModelDefinitionEntity> findByProviderIdOrderBySortOrder(Long providerId);

    Mono<ModelDefinitionEntity> findByProviderIdAndModelId(Long providerId, String modelId);

    Mono<Void> deleteByProviderId(Long providerId);
}
