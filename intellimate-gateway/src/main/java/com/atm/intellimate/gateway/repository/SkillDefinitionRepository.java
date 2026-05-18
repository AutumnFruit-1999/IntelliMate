package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.SkillDefinitionEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;

public interface SkillDefinitionRepository extends ReactiveCrudRepository<SkillDefinitionEntity, Long> {

    Flux<SkillDefinitionEntity> findAllByEnabled(Integer enabled);

    Mono<SkillDefinitionEntity> findByName(String name);

    Flux<SkillDefinitionEntity> findAllByNameIn(Collection<String> names);
}
