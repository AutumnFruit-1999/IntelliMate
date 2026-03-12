package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.ModelProviderEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ModelProviderRepository extends ReactiveCrudRepository<ModelProviderEntity, Long> {

    Flux<ModelProviderEntity> findAllByEnabledOrderBySortOrder(Integer enabled);

    Mono<ModelProviderEntity> findByName(String name);

    Flux<ModelProviderEntity> findAllByOrderBySortOrder();
}
