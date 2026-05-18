package com.atm.intellimate.gateway.heartbeat;

import com.atm.intellimate.gateway.entity.AgentTaskEntity;
import com.atm.intellimate.gateway.entity.HeartbeatConfigEntity;
import com.atm.intellimate.gateway.entity.HeartbeatLogEntity;
import com.atm.intellimate.gateway.entity.OfflineMessageEntity;
import com.atm.intellimate.gateway.repository.AgentTaskRepository;
import com.atm.intellimate.gateway.repository.HeartbeatLogRepository;
import com.atm.intellimate.gateway.repository.OfflineMessageRepository;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
public class HeartbeatEngine {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatEngine.class);
    private static final String SILENT_MARKER = "[SILENT]";

    private final HeartbeatLogRepository logRepo;
    private final AgentTaskRepository taskRepo;
    private final OfflineMessageRepository offlineMsgRepo;
    private final SessionRegistry sessionRegistry;
    private final HeartbeatContextBuilder contextBuilder;

    public HeartbeatEngine(HeartbeatLogRepository logRepo,
                           AgentTaskRepository taskRepo,
                           OfflineMessageRepository offlineMsgRepo,
                           SessionRegistry sessionRegistry,
                           HeartbeatContextBuilder contextBuilder) {
        this.logRepo = logRepo;
        this.taskRepo = taskRepo;
        this.offlineMsgRepo = offlineMsgRepo;
        this.sessionRegistry = sessionRegistry;
        this.contextBuilder = contextBuilder;
    }

    public Mono<Void> processHeartbeat(HeartbeatConfigEntity config) {
        ZoneId zone = ZoneId.of(config.getTimezone());
        LocalDateTime now = LocalDateTime.now(zone);
        LocalTime nowTime = now.toLocalTime();
        LocalTime wakeTime = LocalTime.parse(config.getWakeTime());
        LocalTime sleepTime = LocalTime.parse(config.getSleepTime());

        LifecycleState state = LifecycleState.compute(nowTime, wakeTime, sleepTime);

        if (state == LifecycleState.SLEEPING) {
            return Mono.empty();
        }

        return shouldTrigger(config, state)
                .flatMap(shouldFire -> {
                    if (!shouldFire) return Mono.empty();
                    return executeBeat(config, state, now);
                });
    }

    private Mono<Boolean> shouldTrigger(HeartbeatConfigEntity config, LifecycleState state) {
        if (state == LifecycleState.WAKING || state == LifecycleState.WINDING_DOWN) {
            return logRepo.findTodayByAgentIdAndState(config.getAgentId(), state.name())
                    .map(existing -> false)
                    .defaultIfEmpty(true);
        }

        return logRepo.findLatestByAgentId(config.getAgentId())
                .map(latest -> {
                    Duration elapsed = Duration.between(latest.getTriggeredAt(), LocalDateTime.now());
                    return elapsed.toMinutes() >= config.getHeartbeatIntervalMinutes();
                })
                .defaultIfEmpty(true)
                .flatMap(intervalOk -> {
                    if (!intervalOk) return Mono.just(false);
                    return taskRepo.findDueReminders(config.getAgentId(), LocalDateTime.now())
                            .hasElements();
                });
    }

    private Mono<Void> executeBeat(HeartbeatConfigEntity config, LifecycleState state, LocalDateTime now) {
        Long agentId = config.getAgentId();

        return taskRepo.findUpcomingTasks(agentId, now.plusHours(2))
                .collectList()
                .flatMap(tasks -> {
                    String prompt = contextBuilder.buildPrompt(
                            "Agent#" + agentId, state,
                            config.getPersonalityPrompt(), tasks, now);

                    String response = generatePlaceholderResponse(state, tasks);

                    if (SILENT_MARKER.equals(response.trim())) {
                        log.debug("Heartbeat for agent {} decided to stay silent", agentId);
                        return saveLog(config, state, prompt, response).then();
                    }

                    return saveLog(config, state, prompt, response)
                            .then(deliver(config, response));
                });
    }

    private String generatePlaceholderResponse(LifecycleState state, List<AgentTaskEntity> tasks) {
        return switch (state) {
            case WAKING -> "早上好！新的一天开始了。" +
                    (tasks.isEmpty() ? "" : "今天有 " + tasks.size() + " 件待办事项。");
            case WINDING_DOWN -> "晚上好！今天辛苦了，早点休息。";
            case ACTIVE -> tasks.isEmpty() ? SILENT_MARKER :
                    "提醒：你有 " + tasks.size() + " 个任务即将到期。";
            default -> SILENT_MARKER;
        };
    }

    private Mono<HeartbeatLogEntity> saveLog(HeartbeatConfigEntity config,
                                              LifecycleState state, String prompt, String response) {
        HeartbeatLogEntity logEntry = new HeartbeatLogEntity();
        logEntry.setAgentId(config.getAgentId());
        logEntry.setState(state.name());
        logEntry.setTriggeredAt(LocalDateTime.now());
        logEntry.setPromptUsed(prompt);
        logEntry.setResponse(response);
        logEntry.setDelivered(0);
        logEntry.setCreatedAt(LocalDateTime.now());
        return logRepo.save(logEntry);
    }

    private Mono<Void> deliver(HeartbeatConfigEntity config, String response) {
        String agentName = "Agent#" + config.getAgentId();

        boolean delivered = sessionRegistry.pushToAgent(agentName, "heartbeat.message",
                Map.of("content", response, "agentId", config.getAgentId()));

        if (delivered) {
            log.info("Heartbeat message delivered to agent {} via WebSocket", config.getAgentId());
            return Mono.empty();
        }

        log.info("Agent {} offline, caching heartbeat message", config.getAgentId());
        OfflineMessageEntity msg = new OfflineMessageEntity();
        msg.setAgentId(config.getAgentId());
        msg.setContent(response);
        msg.setMessageType("heartbeat");
        msg.setCreatedAt(LocalDateTime.now());
        msg.setDelivered(0);
        return offlineMsgRepo.save(msg).then();
    }
}
