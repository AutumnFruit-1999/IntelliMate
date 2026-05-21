package com.atm.intellimate.gateway.tools;

import com.atm.intellimate.agent.tools.AgentContext;
import com.atm.intellimate.gateway.entity.AgentTaskEntity;
import com.atm.intellimate.gateway.repository.AgentTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

@Component
public class TaskManagementTool {

    private static final Logger log = LoggerFactory.getLogger(TaskManagementTool.class);
    private static final ObjectMapper om = new ObjectMapper();

    private final AgentTaskRepository taskRepo;

    public TaskManagementTool(AgentTaskRepository taskRepo) {
        this.taskRepo = taskRepo;
    }

    @Tool(description = "创建待办任务/提醒。用于用户需要记录待办事项或设置一次性提醒的场景。"
            + "示例：'明天下午3点提醒我开会'、'记下买牛奶'、'添加一个待办：写周报'")
    public String createTodoTask(
            @ToolParam(description = "任务标题，简明扼要描述要做的事") String title,
            @ToolParam(description = "任务详细描述", required = false) String description,
            @ToolParam(description = "截止时间，ISO 8601 格式如 2026-05-22T15:00:00", required = false) String dueAt,
            @ToolParam(description = "提醒时间，ISO 8601 格式，应早于或等于截止时间", required = false) String remindAt,
            @ToolParam(description = "优先级：0=普通 1=重要 2=紧急，默认 0", required = false) Integer priority
    ) {
        Long agentId = AgentContext.getAgentId();
        if (agentId == null) {
            return errorJson("任务管理功能在当前环境不可用（缺少 Agent 上下文）");
        }

        LocalDateTime dueAtParsed = null, remindAtParsed = null;
        try {
            if (dueAt != null && !dueAt.isBlank()) dueAtParsed = LocalDateTime.parse(dueAt);
            if (remindAt != null && !remindAt.isBlank()) remindAtParsed = LocalDateTime.parse(remindAt);
        } catch (DateTimeParseException e) {
            return errorJson("时间格式无效，请使用 ISO 8601 格式如 2026-05-22T15:00:00");
        }

        AgentTaskEntity task = new AgentTaskEntity();
        task.setAgentId(agentId);
        task.setTitle(title);
        task.setDescription(description);
        task.setDueAt(dueAtParsed);
        task.setRemindAt(remindAtParsed);
        task.setStatus("pending");
        task.setPriority(priority != null ? priority : 0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        AgentTaskEntity saved = taskRepo.save(task).block();
        log.info("Todo task created via chat: id={}, title='{}', agent={}", saved.getId(), title, agentId);

        return taskToJson("created", saved);
    }

    @Tool(description = "查询当前 Agent 的待办任务列表。可按状态筛选（pending/done/cancelled）。")
    public String listTodoTasks(
            @ToolParam(description = "按状态筛选：pending/done/cancelled/all，默认 pending", required = false) String status,
            @ToolParam(description = "返回数量上限，默认 20", required = false) Integer limit
    ) {
        Long agentId = AgentContext.getAgentId();
        if (agentId == null) return errorJson("任务管理功能在当前环境不可用");

        String effectiveStatus = (status != null && !status.isBlank()) ? status : "pending";
        int effectiveLimit = (limit != null && limit > 0) ? limit : 20;

        List<AgentTaskEntity> tasks;
        if ("all".equalsIgnoreCase(effectiveStatus)) {
            tasks = taskRepo.findByAgentId(agentId).take(effectiveLimit).collectList().block();
        } else {
            tasks = taskRepo.findByAgentIdAndStatus(agentId, effectiveStatus).take(effectiveLimit).collectList().block();
        }

        ObjectNode root = om.createObjectNode();
        root.put("success", true);
        root.put("action", "listed");
        root.put("agentId", agentId);
        ArrayNode arr = root.putArray("tasks");
        for (AgentTaskEntity t : tasks) {
            arr.add(taskNode(t));
        }
        root.put("total", tasks.size());
        return root.toString();
    }

    @Tool(description = "更新待办任务。可修改标题、描述、时间、优先级或标记完成/取消。")
    public String updateTodoTask(
            @ToolParam(description = "任务 ID") Long taskId,
            @ToolParam(description = "新标题", required = false) String title,
            @ToolParam(description = "新描述", required = false) String description,
            @ToolParam(description = "新截止时间，ISO 8601 格式", required = false) String dueAt,
            @ToolParam(description = "新提醒时间，ISO 8601 格式", required = false) String remindAt,
            @ToolParam(description = "新优先级：0/1/2", required = false) Integer priority,
            @ToolParam(description = "新状态：done 或 cancelled", required = false) String status
    ) {
        Long agentId = AgentContext.getAgentId();
        if (agentId == null) return errorJson("任务管理功能在当前环境不可用");

        AgentTaskEntity task = taskRepo.findById(taskId).block();
        if (task == null) return errorJson("任务不存在：ID=" + taskId);
        if (!task.getAgentId().equals(agentId)) return errorJson("无权操作此任务");

        if (title != null) task.setTitle(title);
        if (description != null) task.setDescription(description);
        if (priority != null) task.setPriority(priority);
        if (status != null) task.setStatus(status);
        try {
            if (dueAt != null) task.setDueAt(dueAt.isBlank() ? null : LocalDateTime.parse(dueAt));
            if (remindAt != null) task.setRemindAt(remindAt.isBlank() ? null : LocalDateTime.parse(remindAt));
        } catch (DateTimeParseException e) {
            return errorJson("时间格式无效");
        }
        task.setUpdatedAt(LocalDateTime.now());

        AgentTaskEntity updated = taskRepo.save(task).block();
        return taskToJson("updated", updated);
    }

    @Tool(description = "删除待办任务。")
    public String deleteTodoTask(
            @ToolParam(description = "要删除的任务 ID") Long taskId
    ) {
        Long agentId = AgentContext.getAgentId();
        if (agentId == null) return errorJson("任务管理功能在当前环境不可用");

        AgentTaskEntity task = taskRepo.findById(taskId).block();
        if (task == null) return errorJson("任务不存在：ID=" + taskId);
        if (!task.getAgentId().equals(agentId)) return errorJson("无权操作此任务");

        taskRepo.deleteById(taskId).block();
        ObjectNode root = om.createObjectNode();
        root.put("success", true);
        root.put("action", "deleted");
        root.put("deletedTaskId", taskId);
        return root.toString();
    }

    private String taskToJson(String action, AgentTaskEntity task) {
        ObjectNode root = om.createObjectNode();
        root.put("success", true);
        root.put("action", action);
        root.put("agentId", task.getAgentId());
        root.set("task", taskNode(task));
        return root.toString();
    }

    private ObjectNode taskNode(AgentTaskEntity t) {
        ObjectNode node = om.createObjectNode();
        node.put("id", t.getId());
        node.put("title", t.getTitle());
        node.put("description", t.getDescription());
        node.put("dueAt", t.getDueAt() != null ? t.getDueAt().toString() : null);
        node.put("remindAt", t.getRemindAt() != null ? t.getRemindAt().toString() : null);
        node.put("priority", t.getPriority());
        node.put("status", t.getStatus());
        return node;
    }

    private String errorJson(String message) {
        return "{\"success\":false,\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
    }
}
