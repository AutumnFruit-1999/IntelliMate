package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.AuditLogEntity;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

public interface AuditLogRepository extends ReactiveCrudRepository<AuditLogEntity, Long> {

    Flux<AuditLogEntity> findBySessionIdOrderByCreatedAtDesc(Long sessionId);
}
