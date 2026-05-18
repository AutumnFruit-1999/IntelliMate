package com.atm.intellimate.gateway.service;

import com.atm.intellimate.gateway.entity.SkillGroupEntity;
import com.atm.intellimate.gateway.entity.SkillGroupMemberEntity;
import com.atm.intellimate.gateway.repository.SkillGroupMemberRepository;
import com.atm.intellimate.gateway.repository.SkillGroupRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class SkillGroupService {

    private final SkillGroupRepository groupRepository;
    private final SkillGroupMemberRepository memberRepository;

    public SkillGroupService(SkillGroupRepository groupRepository,
                             SkillGroupMemberRepository memberRepository) {
        this.groupRepository = groupRepository;
        this.memberRepository = memberRepository;
    }

    public Flux<SkillGroupEntity> listAll() {
        return groupRepository.findAllByOrderBySortOrderAsc();
    }

    public Flux<SkillGroupEntity> listEnabled() {
        return groupRepository.findAllByEnabledOrderBySortOrderAsc(1);
    }

    public Mono<SkillGroupEntity> getById(Long id) {
        return groupRepository.findById(id);
    }

    public Mono<SkillGroupEntity> create(String name, String displayName, String description) {
        SkillGroupEntity entity = new SkillGroupEntity();
        entity.setName(name);
        entity.setDisplayName(displayName);
        entity.setDescription(description);
        entity.setSortOrder(0);
        entity.setEnabled(1);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        return groupRepository.save(entity);
    }

    public Mono<SkillGroupEntity> update(Long id, String name, String displayName, String description, Integer enabled) {
        return groupRepository.findById(id)
                .flatMap(entity -> {
                    if (name != null) entity.setName(name);
                    if (displayName != null) entity.setDisplayName(displayName);
                    if (description != null) entity.setDescription(description);
                    if (enabled != null) entity.setEnabled(enabled);
                    entity.setUpdatedAt(LocalDateTime.now());
                    return groupRepository.save(entity);
                });
    }

    public Mono<Void> delete(Long id) {
        return memberRepository.deleteByGroupId(id)
                .then(groupRepository.deleteById(id));
    }

    public Mono<Void> reorder(List<Long> orderedIds) {
        AtomicInteger idx = new AtomicInteger(0);
        return Flux.fromIterable(orderedIds)
                .concatMap(id -> groupRepository.findById(id)
                        .flatMap(entity -> {
                            entity.setSortOrder(idx.getAndIncrement());
                            entity.setUpdatedAt(LocalDateTime.now());
                            return groupRepository.save(entity);
                        }))
                .then();
    }

    public Flux<SkillGroupMemberEntity> getMembers(Long groupId) {
        return memberRepository.findByGroupIdOrderBySortOrderAsc(groupId);
    }

    public Mono<Void> setMembers(Long groupId, List<Long> skillIds) {
        return memberRepository.deleteByGroupId(groupId)
                .thenMany(Flux.fromIterable(skillIds)
                        .map(skillId -> {
                            SkillGroupMemberEntity m = new SkillGroupMemberEntity();
                            m.setGroupId(groupId);
                            m.setSkillId(skillId);
                            m.setSortOrder(skillIds.indexOf(skillId));
                            return m;
                        })
                        .flatMap(memberRepository::save))
                .then();
    }

    public Mono<Long> countMembers(Long groupId) {
        return memberRepository.findByGroupIdOrderBySortOrderAsc(groupId)
                .count();
    }

    public Flux<SkillGroupMemberEntity> getMembersByGroupIds(Collection<Long> groupIds) {
        return memberRepository.findByGroupIdIn(groupIds);
    }
}
