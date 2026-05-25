package com.atm.intellimate.gateway.http;

import com.atm.intellimate.core.exception.ErrorCode;
import com.atm.intellimate.core.exception.IntelliMateException;
import com.atm.intellimate.gateway.dto.ApiResponse;
import com.atm.intellimate.gateway.entity.AgentTaskEntity;
import com.atm.intellimate.gateway.repository.AgentTaskRepository;
import org.springframework.web.bind.annotation.*;
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
    public Mono<ApiResponse<List<AgentTaskEntity>>> listTasks(@PathVariable Long agentId,
                                                  @RequestParam(required = false) String status) {
        Mono<List<AgentTaskEntity>> tasks = status != null
                ? taskRepo.findByAgentIdAndStatus(agentId, status).collectList()
                : taskRepo.findByAgentId(agentId).collectList();
        return tasks.map(ApiResponse::ok);
    }

    @PostMapping("/{agentId}")
    public Mono<ApiResponse<AgentTaskEntity>> createTask(@PathVariable Long agentId,
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
        return taskRepo.save(task).map(ApiResponse::ok);
    }

    @PutMapping("/{agentId}/{taskId}")
    public Mono<ApiResponse<AgentTaskEntity>> updateTask(@PathVariable Long agentId,
                                             @PathVariable Long taskId,
                                             @RequestBody Map<String, Object> body) {
        return taskRepo.findById(taskId)
                .switchIfEmpty(Mono.error(new IntelliMateException(ErrorCode.SESSION_NOT_FOUND, "Task not found")))
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
                })
                .map(ApiResponse::ok);
    }

    @DeleteMapping("/{agentId}/{taskId}")
    public Mono<ApiResponse<Map<String, Object>>> deleteTask(@PathVariable Long agentId,
                                                 @PathVariable Long taskId) {
        return taskRepo.deleteById(taskId)
                .thenReturn(ApiResponse.ok(Map.<String, Object>of("success", true)));
    }
}
