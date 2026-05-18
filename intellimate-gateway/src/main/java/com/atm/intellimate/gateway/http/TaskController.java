package com.atm.intellimate.gateway.http;

import com.atm.intellimate.gateway.entity.AgentTaskEntity;
import com.atm.intellimate.gateway.repository.AgentTaskRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final AgentTaskRepository taskRepo;

    public TaskController(AgentTaskRepository taskRepo) {
        this.taskRepo = taskRepo;
    }

    @GetMapping("/{agentId}")
    public Mono<List<AgentTaskEntity>> listTasks(@PathVariable Long agentId,
                                                  @RequestParam(required = false) String status) {
        if (status != null) {
            return taskRepo.findByAgentIdAndStatus(agentId, status).collectList();
        }
        return taskRepo.findByAgentId(agentId).collectList();
    }

    @PostMapping("/{agentId}")
    public Mono<AgentTaskEntity> createTask(@PathVariable Long agentId,
                                             @RequestBody Map<String, Object> body) {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setAgentId(agentId);
        task.setTitle((String) body.get("title"));
        task.setDescription((String) body.get("description"));
        if (body.get("dueAt") != null) task.setDueAt(LocalDateTime.parse((String) body.get("dueAt")));
        if (body.get("remindAt") != null) task.setRemindAt(LocalDateTime.parse((String) body.get("remindAt")));
        task.setStatus("pending");
        task.setPriority(body.get("priority") != null ? ((Number) body.get("priority")).intValue() : 0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return taskRepo.save(task);
    }

    @PutMapping("/{agentId}/{taskId}")
    public Mono<AgentTaskEntity> updateTask(@PathVariable Long agentId,
                                             @PathVariable Long taskId,
                                             @RequestBody Map<String, Object> body) {
        return taskRepo.findById(taskId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND)))
                .flatMap(task -> {
                    if (body.containsKey("title")) task.setTitle((String) body.get("title"));
                    if (body.containsKey("description")) task.setDescription((String) body.get("description"));
                    if (body.containsKey("status")) task.setStatus((String) body.get("status"));
                    if (body.containsKey("priority")) task.setPriority(((Number) body.get("priority")).intValue());
                    if (body.containsKey("dueAt"))
                        task.setDueAt(body.get("dueAt") != null ? LocalDateTime.parse((String) body.get("dueAt")) : null);
                    if (body.containsKey("remindAt"))
                        task.setRemindAt(body.get("remindAt") != null ? LocalDateTime.parse((String) body.get("remindAt")) : null);
                    task.setUpdatedAt(LocalDateTime.now());
                    return taskRepo.save(task);
                });
    }

    @DeleteMapping("/{agentId}/{taskId}")
    public Mono<Map<String, Object>> deleteTask(@PathVariable Long agentId,
                                                 @PathVariable Long taskId) {
        return taskRepo.deleteById(taskId)
                .thenReturn(Map.<String, Object>of("success", true));
    }
}
