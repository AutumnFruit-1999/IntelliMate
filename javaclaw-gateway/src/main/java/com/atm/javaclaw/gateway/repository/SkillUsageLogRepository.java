package com.atm.javaclaw.gateway.repository;

import com.atm.javaclaw.gateway.entity.SkillUsageLogEntity;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface SkillUsageLogRepository extends ReactiveCrudRepository<SkillUsageLogEntity, Long> {

    Mono<Long> countBySkillName(String skillName);

    Flux<SkillUsageLogEntity> findAllBySkillNameOrderByActivatedAtDesc(String skillName);

    @Query("SELECT skill_name, COUNT(*) as cnt, MAX(activated_at) as last_at " +
           "FROM skill_usage_log GROUP BY skill_name ORDER BY cnt DESC")
    Flux<SkillUsageStatsProjection> findUsageStats();

    interface SkillUsageStatsProjection {
        String getSkillName();
        Long getCnt();
        java.time.LocalDateTime getLastAt();
    }
}
