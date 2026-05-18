package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.SkillVersionEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SkillVersionRepository extends ReactiveCrudRepository<SkillVersionEntity, Long> {

    Flux<SkillVersionEntity> findAllBySkillIdOrderByVersionDesc(Long skillId);

    Mono<SkillVersionEntity> findBySkillIdAndVersion(Long skillId, Integer version);

    Mono<SkillVersionEntity> findTopBySkillIdOrderByVersionDesc(Long skillId);
}
