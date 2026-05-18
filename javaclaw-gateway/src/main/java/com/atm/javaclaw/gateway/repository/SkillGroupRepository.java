package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.SkillGroupEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SkillGroupRepository extends ReactiveCrudRepository<SkillGroupEntity, Long> {

    Flux<SkillGroupEntity> findAllByEnabledOrderBySortOrderAsc(Integer enabled);

    Flux<SkillGroupEntity> findAllByOrderBySortOrderAsc();

    Mono<SkillGroupEntity> findByName(String name);

    Flux<SkillGroupEntity> findAllByNameIn(java.util.Collection<String> names);

    @Query("SELECT g.* FROM skill_group g WHERE g.enabled = 1 AND g.name IN (:names) ORDER BY g.sort_order ASC")
    Flux<SkillGroupEntity> findEnabledByNames(java.util.Collection<String> names);
}
