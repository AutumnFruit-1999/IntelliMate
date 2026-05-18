package com.atm.javaclaw.gateway.http;

import com.atm.javaclaw.gateway.entity.HeartbeatConfigEntity;
import com.atm.javaclaw.gateway.heartbeat.LifecycleState;
import com.atm.javaclaw.gateway.repository.HeartbeatConfigRepository;
import com.atm.javaclaw.gateway.repository.HeartbeatLogRepository;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/heartbeat")
public class HeartbeatController {

    private final HeartbeatConfigRepository configRepo;
    private final HeartbeatLogRepository logRepo;

    public HeartbeatController(HeartbeatConfigRepository configRepo,
                               HeartbeatLogRepository logRepo) {
        this.configRepo = configRepo;
        this.logRepo = logRepo;
    }

    @GetMapping("/{agentId}")
    public Mono<Map<String, Object>> getConfig(@PathVariable Long agentId) {
        return configRepo.findByAgentId(agentId)
                .map(this::toDto)
                .defaultIfEmpty(defaultConfig(agentId));
    }

    @PutMapping("/{agentId}")
    public Mono<Map<String, Object>> updateConfig(@PathVariable Long agentId,
                                                   @RequestBody Map<String, Object> body) {
        return configRepo.findByAgentId(agentId)
                .defaultIfEmpty(newConfig(agentId))
                .flatMap(config -> {
                    if (body.containsKey("enabled"))
                        config.setEnabled(((Number) body.get("enabled")).intValue());
                    if (body.containsKey("timezone"))
                        config.setTimezone((String) body.get("timezone"));
                    if (body.containsKey("wakeTime"))
                        config.setWakeTime((String) body.get("wakeTime"));
                    if (body.containsKey("sleepTime"))
                        config.setSleepTime((String) body.get("sleepTime"));
                    if (body.containsKey("heartbeatIntervalMinutes"))
                        config.setHeartbeatIntervalMinutes(((Number) body.get("heartbeatIntervalMinutes")).intValue());
                    if (body.containsKey("personalityPrompt"))
                        config.setPersonalityPrompt((String) body.get("personalityPrompt"));
                    config.setUpdatedAt(LocalDateTime.now());
                    return configRepo.save(config);
                })
                .map(this::toDto);
    }

    @GetMapping("/{agentId}/state")
    public Mono<Map<String, Object>> getState(@PathVariable Long agentId) {
        return configRepo.findByAgentId(agentId)
                .map(config -> {
                    ZoneId zone = ZoneId.of(config.getTimezone());
                    LocalTime now = LocalTime.now(zone);
                    LocalTime wake = LocalTime.parse(config.getWakeTime());
                    LocalTime sleep = LocalTime.parse(config.getSleepTime());
                    LifecycleState state = LifecycleState.compute(now, wake, sleep);
                    Map<String, Object> dto = new LinkedHashMap<>();
                    dto.put("currentState", state.name());
                    dto.put("stateDescription", state.description());
                    dto.put("currentTime", now.toString());
                    return dto;
                })
                .defaultIfEmpty(Map.of("currentState", "UNCONFIGURED"));
    }

    @SuppressWarnings("unchecked")
    @GetMapping("/{agentId}/logs")
    public Mono<Object> getLogs(@PathVariable Long agentId,
                                @RequestParam(defaultValue = "20") int limit) {
        return logRepo.findRecentByAgentId(agentId, limit).collectList().map(l -> (Object) l);
    }

    private Map<String, Object> toDto(HeartbeatConfigEntity e) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", e.getId());
        dto.put("agentId", e.getAgentId());
        dto.put("enabled", e.getEnabled());
        dto.put("timezone", e.getTimezone());
        dto.put("wakeTime", e.getWakeTime());
        dto.put("sleepTime", e.getSleepTime());
        dto.put("heartbeatIntervalMinutes", e.getHeartbeatIntervalMinutes());
        dto.put("personalityPrompt", e.getPersonalityPrompt());
        return dto;
    }

    private Map<String, Object> defaultConfig(Long agentId) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("agentId", agentId);
        dto.put("enabled", 0);
        dto.put("timezone", "Asia/Shanghai");
        dto.put("wakeTime", "06:00");
        dto.put("sleepTime", "23:00");
        dto.put("heartbeatIntervalMinutes", 60);
        dto.put("personalityPrompt", "");
        return dto;
    }

    private HeartbeatConfigEntity newConfig(Long agentId) {
        HeartbeatConfigEntity e = new HeartbeatConfigEntity();
        e.setAgentId(agentId);
        e.setEnabled(0);
        e.setTimezone("Asia/Shanghai");
        e.setWakeTime("06:00");
        e.setSleepTime("23:00");
        e.setHeartbeatIntervalMinutes(60);
        e.setCreatedAt(LocalDateTime.now());
        e.setUpdatedAt(LocalDateTime.now());
        return e;
    }
}
