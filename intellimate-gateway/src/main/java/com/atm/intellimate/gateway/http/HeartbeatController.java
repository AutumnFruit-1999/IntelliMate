package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.dto.ApiResponse;
import com.atm.intellimate.gateway.entity.HeartbeatConfigEntity;
import com.atm.intellimate.gateway.heartbeat.HeartbeatEngine;
import com.atm.intellimate.gateway.heartbeat.LifecycleState;
import com.atm.intellimate.gateway.repository.HeartbeatConfigRepository;
import com.atm.intellimate.gateway.repository.HeartbeatLogRepository;
import com.atm.intellimate.gateway.websocket.SessionRegistry;
import org.springframework.context.annotation.Lazy;
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
    private final SessionRegistry sessionRegistry;
    private final HeartbeatEngine heartbeatEngine;

    public HeartbeatController(HeartbeatConfigRepository configRepo,
                               HeartbeatLogRepository logRepo,
                               SessionRegistry sessionRegistry,
                               @Lazy HeartbeatEngine heartbeatEngine) {
        this.configRepo = configRepo;
        this.logRepo = logRepo;
        this.sessionRegistry = sessionRegistry;
        this.heartbeatEngine = heartbeatEngine;
    }

    @GetMapping("/debug/online/{agentName}")
    public ApiResponse<Map<String, Object>> checkAgentOnline(@PathVariable String agentName) {
        boolean online = sessionRegistry.isAgentOnline(agentName);
        int pushed = sessionRegistry.pushToAllAgentSessions(agentName, "agent.proactive", Map.of(
                "agentName", agentName,
                "requestId", "debug-" + System.currentTimeMillis(),
                "text", "[DEBUG] 心跳测试推送",
                "source", "debug",
                "timestamp", System.currentTimeMillis()
        ));
        return ApiResponse.ok(Map.of("agentName", agentName, "online", online, "pushedToSessions", pushed));
    }

    @PostMapping("/{agentId}/trigger")
    public Mono<ApiResponse<Map<String, Object>>> triggerHeartbeat(@PathVariable Long agentId) {
        return configRepo.findByAgentId(agentId)
                .flatMap(config -> heartbeatEngine.forceHeartbeat(config)
                        .thenReturn(ApiResponse.ok(Map.<String, Object>of("status", "triggered", "agentId", agentId))))
                .defaultIfEmpty(ApiResponse.ok(Map.of("status", "no_config", "agentId", agentId)));
    }

    @GetMapping("/{agentId}")
    public Mono<ApiResponse<Map<String, Object>>> getConfig(@PathVariable Long agentId) {
        return configRepo.findByAgentId(agentId)
                .map(this::toDto)
                .map(ApiResponse::ok)
                .defaultIfEmpty(ApiResponse.ok(defaultConfig(agentId)));
    }

    @PutMapping("/{agentId}")
    public Mono<ApiResponse<Map<String, Object>>> updateConfig(@PathVariable Long agentId,
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
                    config.setUpdatedAt(LocalDateTime.now());
                    return configRepo.save(config);
                })
                .map(this::toDto)
                .map(ApiResponse::ok);
    }

    @GetMapping("/{agentId}/state")
    public Mono<ApiResponse<Map<String, Object>>> getState(@PathVariable Long agentId) {
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
                    return ApiResponse.ok(dto);
                })
                .defaultIfEmpty(ApiResponse.ok(Map.of("currentState", "UNCONFIGURED")));
    }

    @SuppressWarnings("unchecked")
    @GetMapping("/{agentId}/logs")
    public Mono<ApiResponse<Object>> getLogs(@PathVariable Long agentId,
                                @RequestParam(defaultValue = "20") int limit) {
        return logRepo.findRecentByAgentId(agentId, limit).collectList()
                .map(list -> ApiResponse.ok((Object) list));
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
