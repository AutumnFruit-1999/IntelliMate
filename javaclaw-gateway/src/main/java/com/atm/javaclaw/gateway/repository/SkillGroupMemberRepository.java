package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.SkillGroupMemberEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SkillGroupMemberRepository extends ReactiveCrudRepository<SkillGroupMemberEntity, Long> {

    Flux<SkillGroupMemberEntity> findByGroupIdOrderBySortOrderAsc(Long groupId);

    Flux<SkillGroupMemberEntity> findBySkillId(Long skillId);

    Flux<SkillGroupMemberEntity> findByGroupIdIn(java.util.Collection<Long> groupIds);

    @Modifying
    @Query("DELETE FROM skill_group_member WHERE group_id = :groupId")
    Mono<Void> deleteByGroupId(Long groupId);

    @Modifying
    @Query("DELETE FROM skill_group_member WHERE group_id = :groupId AND skill_id IN (:skillIds)")
    Mono<Void> deleteByGroupIdAndSkillIdIn(Long groupId, java.util.Collection<Long> skillIds);
}
