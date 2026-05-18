package com.atm.intellimate.gateway.audit;

import com.atm.intellimate.gateway.entity.AuditLogEntity;
import com.atm.intellimate.gateway.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;

    public AuditService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public Mono<Void> log(String action, String actor, Long sessionId, String detail) {
        AuditLogEntity entry = new AuditLogEntity();
        entry.setAction(action);
        entry.setActor(actor);
        entry.setSessionId(sessionId);
        entry.setDetail(detail);
        entry.setCreatedAt(LocalDateTime.now());

        return auditLogRepository.save(entry)
                .then()
                .doOnError(e -> log.error("Failed to write audit log: action={}, actor={}", action, actor, e))
                .onErrorComplete();
    }
}
