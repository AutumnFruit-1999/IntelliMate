package com.atm.javaclaw.gateway.heartbeat;

import com.atm.javaclaw.gateway.repository.HeartbeatConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
public class HeartbeatScheduler {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatScheduler.class);

    private final HeartbeatEngine engine;
    private final HeartbeatConfigRepository configRepo;

    public HeartbeatScheduler(HeartbeatEngine engine, HeartbeatConfigRepository configRepo) {
        this.engine = engine;
        this.configRepo = configRepo;
    }

    /**
     * @deprecated Managed by ReactiveScheduleEngine via HeartbeatJob.
     * Retained for backward compatibility if @EnableScheduling is still active.
     */
    public void tick() {
        configRepo.findAllByEnabled(1)
                .flatMap(config -> engine.processHeartbeat(config)
                        .onErrorResume(e -> {
                            log.error("Heartbeat failed for agent {}: {}",
                                    config.getAgentId(), e.getMessage());
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }
}
