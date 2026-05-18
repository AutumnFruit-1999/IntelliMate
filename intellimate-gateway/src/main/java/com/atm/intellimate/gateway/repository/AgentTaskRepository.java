package com.atm.intellimate.gateway.repository;

import com.atm.intellimate.gateway.entity.AgentTaskEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.time.LocalDateTime;

public interface AgentTaskRepository extends ReactiveCrudRepository<AgentTaskEntity, Long> {

    Flux<AgentTaskEntity> findByAgentIdAndStatus(Long agentId, String status);

    Flux<AgentTaskEntity> findByAgentId(Long agentId);

    @Query("SELECT * FROM agent_task WHERE agent_id = :agentId AND status = 'pending' AND remind_at IS NOT NULL AND remind_at <= :now")
    Flux<AgentTaskEntity> findDueReminders(Long agentId, LocalDateTime now);

    @Query("SELECT * FROM agent_task WHERE agent_id = :agentId AND status = 'pending' AND due_at IS NOT NULL AND due_at <= :deadline ORDER BY due_at")
    Flux<AgentTaskEntity> findUpcomingTasks(Long agentId, LocalDateTime deadline);
}
