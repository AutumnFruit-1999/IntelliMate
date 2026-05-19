package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.BridgeNodeEntity;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface BridgeNodeRepository extends ReactiveCrudRepository<BridgeNodeEntity, Long> {

    Mono<BridgeNodeEntity> findByName(String name);

    @Modifying
    @Query("UPDATE bridge_node SET status = :status, last_connected_at = NOW() WHERE name = :name")
    Mono<Integer> updateStatus(String name, String status);

    @Modifying
    @Query("UPDATE bridge_node SET last_heartbeat_at = NOW() WHERE name = :name")
    Mono<Integer> updateHeartbeat(String name);

    @Modifying
    @Query("UPDATE bridge_node SET registered_tools = :tools WHERE name = :name")
    Mono<Integer> updateRegisteredTools(String name, String tools);
}
