package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.ChannelGroupEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ChannelGroupRepository extends ReactiveCrudRepository<ChannelGroupEntity, Long> {

    Flux<ChannelGroupEntity> findByChannelId(String channelId);

    Mono<ChannelGroupEntity> findByChannelIdAndGroupId(String channelId, String groupId);

    Flux<ChannelGroupEntity> findByAgentName(String agentName);
}
