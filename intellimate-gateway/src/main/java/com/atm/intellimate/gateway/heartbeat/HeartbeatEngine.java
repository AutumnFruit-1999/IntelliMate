package com.atm.intellimate.gateway.heartbeat;

import com.atm.intellimate.agent.runtime.AgentEvent;
import com.atm.intellimate.agent.runtime.AgentRunRequest;
import com.atm.intellimate.agent.runtime.AgentRuntime;
import com.atm.intellimate.gateway.config.AgentConfigService;
import com.atm.intellimate.gateway.entity.AgentTaskEntity;
import com.atm.intellimate.gateway.entity.HeartbeatConfigEntity;
import com.atm.intellimate.gateway.entity.HeartbeatLogEntity;
import com.atm.intellimate.gateway.repository.AgentRepository;
import com.atm.intellimate.gateway.repository.AgentTaskRepository;
import com.atm.intellimate.gateway.repository.HeartbeatLogRepository;
import com.atm.intellimate.gateway.service.ChatInjectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class HeartbeatEngine {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatEngine.class);
    private static final String SILENT_MARKER = "[SILENT]";
    private static final Duration LLM_TIMEOUT = Duration.ofSeconds(30);

    private final HeartbeatLogRepository logRepo;
    private final AgentTaskRepository taskRepo;
    private final HeartbeatContextBuilder contextBuilder;
    private final AgentRuntime agentRuntime;
    private final AgentConfigService agentConfigService;
    private final AgentRepository agentRepository;
    private final ChatInjectionService chatInjectionService;

    public HeartbeatEngine(HeartbeatLogRepository logRepo,
                           AgentTaskRepository taskRepo,
                           HeartbeatContextBuilder contextBuilder,
                           AgentRuntime agentRuntime,
                           AgentConfigService agentConfigService,
                           AgentRepository agentRepository,
                           ChatInjectionService chatInjectionService) {
        this.logRepo = logRepo;
        this.taskRepo = taskRepo;
        this.contextBuilder = contextBuilder;
        this.agentRuntime = agentRuntime;
        this.agentConfigService = agentConfigService;
        this.agentRepository = agentRepository;
        this.chatInjectionService = chatInjectionService;
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

    public Mono<Void> forceHeartbeat(HeartbeatConfigEntity config) {
        ZoneId zone = ZoneId.of(config.getTimezone());
        LocalDateTime now = LocalDateTime.now(zone);
        LocalTime nowTime = now.toLocalTime();
        LocalTime wakeTime = LocalTime.parse(config.getWakeTime());
        LocalTime sleepTime = LocalTime.parse(config.getSleepTime());
        LifecycleState state = LifecycleState.compute(nowTime, wakeTime, sleepTime);
        return executeBeat(config, state, now);
    }

    private Mono<Boolean> shouldTrigger(HeartbeatConfigEntity config, LifecycleState state) {
        // Due task reminders always bypass any rate-limiting — fire immediately in any state
        return taskRepo.findDueReminders(config.getAgentId(), LocalDateTime.now())
                .hasElements()
                .flatMap(hasDueReminders -> {
                    if (hasDueReminders) return Mono.just(true);

                    if (state == LifecycleState.WAKING || state == LifecycleState.WINDING_DOWN) {
                        ZoneId zone = ZoneId.of(config.getTimezone());
                        LocalDateTime startOfDay = java.time.LocalDate.now(zone).atStartOfDay();
                        return logRepo.findTodayByAgentIdAndState(config.getAgentId(), state.name(), startOfDay)
                                .map(existing -> false)
                                .defaultIfEmpty(true);
                    }

                    // ACTIVE state: fall back to interval-based heartbeat
                    return logRepo.findLatestByAgentId(config.getAgentId())
                            .map(latest -> {
                                Duration elapsed = Duration.between(latest.getTriggeredAt(), LocalDateTime.now());
                                return elapsed.toMinutes() >= config.getHeartbeatIntervalMinutes();
                            })
                            .defaultIfEmpty(true);
                });
    }

    private Mono<Void> executeBeat(HeartbeatConfigEntity config, LifecycleState state, LocalDateTime now) {
        Long agentId = config.getAgentId();

        Mono<List<AgentTaskEntity>> upcomingMono = taskRepo.findUpcomingTasks(agentId, now.plusHours(2)).collectList();
        Mono<List<AgentTaskEntity>> dueMono = taskRepo.findDueReminders(agentId, LocalDateTime.now()).collectList();

        return Mono.zip(upcomingMono, dueMono)
                .flatMap(tuple -> {
                    List<AgentTaskEntity> dueReminders = tuple.getT2();
                    java.util.Set<Long> seen = new java.util.HashSet<>();
                    List<AgentTaskEntity> merged = new java.util.ArrayList<>();
                    for (AgentTaskEntity t : dueReminders) { seen.add(t.getId()); merged.add(t); }
                    for (AgentTaskEntity t : tuple.getT1()) { if (!seen.contains(t.getId())) merged.add(t); }

                    return agentRepository.findById(agentId)
                            .map(entity -> entity.getName())
                            .defaultIfEmpty("Agent#" + agentId)
                            .flatMap(agentName -> {
                                String prompt = contextBuilder.buildPrompt(
                                        agentName, state, merged, now);

                                return generateLlmResponse(config, agentName, prompt, merged, state)
                                        .flatMap(response -> {
                                            Mono<Void> clearReminders = dueReminders.isEmpty() ? Mono.empty()
                                                    : Flux.fromIterable(dueReminders)
                                                            .flatMap(task -> taskRepo.clearRemindAt(task.getId(), LocalDateTime.now()))
                                                            .then();
                                            if (SILENT_MARKER.equals(response.trim())) {
                                                log.debug("Heartbeat for agent {} decided to stay silent", agentId);
                                                return saveLog(config, state, prompt, response)
                                                        .then(clearReminders);
                                            }
                                            return saveLog(config, state, prompt, response)
                                                    .then(injectToChat(agentName, response))
                                                    .then(clearReminders);
                                        });
                            });
                });
    }

    private Mono<String> generateLlmResponse(HeartbeatConfigEntity config,
                                              String agentName,
                                              String prompt,
                                              List<AgentTaskEntity> tasks,
                                              LifecycleState state) {
        Long agentId = config.getAgentId();

        return agentConfigService.resolve(agentName)
                .flatMap(resolved -> {
                    AgentRunRequest request = new AgentRunRequest(
                            System.currentTimeMillis(),
                            "heartbeat",
                            resolved.agent(),
                            prompt,
                            Collections.emptyList(),
                            null, null, null, null, null,
                            false, null, null, null,
                            resolved.bridgeNode()
                    );

                    AtomicReference<String> responseText = new AtomicReference<>("");

                    return agentRuntime.dispatch(request)
                            .doOnNext(event -> {
                                if (event instanceof AgentEvent.TextChunk chunk) {
                                    responseText.updateAndGet(s -> s + chunk.text());
                                } else if (event instanceof AgentEvent.Done done) {
                                    responseText.set(done.fullText());
                                }
                            })
                            .then(Mono.fromSupplier(() -> {
                                String text = responseText.get();
                                return text.isBlank() ? SILENT_MARKER : text;
                            }));
                })
                .timeout(LLM_TIMEOUT)
                .switchIfEmpty(Mono.fromSupplier(() -> generatePlaceholderResponse(state, tasks)))
                .onErrorResume(e -> {
                    log.warn("LLM heartbeat failed for agent {}, using placeholder: {}",
                             agentId, e.getMessage());
                    return Mono.just(generatePlaceholderResponse(state, tasks));
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

    private Mono<Void> injectToChat(String agentName, String response) {
        return chatInjectionService
                .injectAgentMessage(agentName, response, ChatInjectionService.ProactiveSource.HEARTBEAT)
                .onErrorResume(e -> {
                    log.warn("Failed to inject heartbeat chat message for agent {}: {}", agentName, e.getMessage());
                    return Mono.just(0);
                })
                .then();
    }

}
