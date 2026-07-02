package com.atm.intellimate.gateway.service;

import com.atm.intellimate.gateway.entity.ChannelGroupEntity;
import com.atm.intellimate.gateway.repository.ChannelGroupRepository;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class ChannelGroupService {

    private final ChannelGroupRepository groupRepository;

    public ChannelGroupService(ChannelGroupRepository groupRepository) {
        this.groupRepository = groupRepository;
    }

    public Mono<ChannelGroupEntity> recordGroup(String channelId, String groupId, String groupName) {
        return groupRepository.findByChannelIdAndGroupId(channelId, groupId)
                .flatMap(existing -> {
                    if (groupName != null && !groupName.equals(existing.getGroupName())) {
                        existing.setGroupName(groupName);
                        existing.setUpdatedAt(LocalDateTime.now());
                        return groupRepository.save(existing);
                    }
                    return Mono.just(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    ChannelGroupEntity entity = new ChannelGroupEntity();
                    entity.setChannelId(channelId);
                    entity.setGroupId(groupId);
                    entity.setGroupName(groupName);
                    entity.setCreatedAt(LocalDateTime.now());
                    entity.setUpdatedAt(LocalDateTime.now());
                    return groupRepository.save(entity);
                }));
    }

    public Flux<ChannelGroupEntity> listByChannel(String channelId) {
        return groupRepository.findByChannelId(channelId);
    }

    public Flux<ChannelGroupEntity> listByAgent(String agentName) {
        return groupRepository.findByAgentName(agentName);
    }

    public Mono<ChannelGroupEntity> bindAgent(String channelId, String groupId, String agentName) {
        return groupRepository.findByChannelIdAndGroupId(channelId, groupId)
                .flatMap(entity -> {
                    entity.setAgentName(agentName);
                    entity.setUpdatedAt(LocalDateTime.now());
                    return groupRepository.save(entity);
                })
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Group not found")));
    }

    public Mono<ChannelGroupEntity> unbindAgent(String channelId, String groupId) {
        return groupRepository.findByChannelIdAndGroupId(channelId, groupId)
                .flatMap(entity -> {
                    entity.setAgentName(null);
                    entity.setUpdatedAt(LocalDateTime.now());
                    return groupRepository.save(entity);
                })
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Group not found")));
    }
}
