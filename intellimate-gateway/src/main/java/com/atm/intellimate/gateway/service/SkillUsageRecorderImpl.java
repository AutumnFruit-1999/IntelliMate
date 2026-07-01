package com.atm.intellimate.gateway.service;

import com.atm.intellimate.agent.skills.SkillUsageRecorder;
import com.atm.intellimate.gateway.entity.SkillUsageLogEntity;
import com.atm.intellimate.gateway.repository.SkillUsageLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class SkillUsageRecorderImpl implements SkillUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(SkillUsageRecorderImpl.class);

    private final SkillUsageLogRepository repository;

    public SkillUsageRecorderImpl(SkillUsageLogRepository repository) {
        this.repository = repository;
    }

    @Override
    public void recordActivation(String skillName, String agentName, Long sessionId, String activationType) {
        SkillUsageLogEntity entity = new SkillUsageLogEntity();
        entity.setSkillName(skillName);
        entity.setAgentName(agentName);
        entity.setSessionId(sessionId);
        entity.setActivatedAt(LocalDateTime.now());
        entity.setActivationType(activationType);

        repository.save(entity)
                .doOnError(e -> log.warn("Failed to record skill activation: {}", e.getMessage()))
                .subscribe();
    }
}
