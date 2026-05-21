# 对话创建任务 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 通过 Agent Tool（Function Calling）让 LLM 在对话中创建和管理待办任务及定时任务，前端以结构化卡片展示。

**架构：** 在 `intellimate-gateway` 中新建两个 `@Tool` 工具类（`TaskManagementTool`、`ScheduledJobManagementTool`），通过独立的 `TaskToolAutoConfiguration` 注册为 `ToolCallbackProvider`。`AgentRuntime` 通过新增的 `AgentContext` ThreadLocal 向工具传递 agentId 和 agentName。前端新建 `TaskCard` 和 `ScheduledJobCard` 组件，在聊天消息中检测工具调用结果并渲染结构化卡片。

**技术栈：** Java 21 / Spring WebFlux / R2DBC / Spring AI Tool Annotations / React 19 / TypeScript / Zustand

---

## 文件结构

| 文件 | 操作 | 职责 |
|------|------|------|
| `intellimate-agent/.../tools/AgentContext.java` | 新建 | Agent 上下文 ThreadLocal（agentId + agentName） |
| `intellimate-agent/.../runtime/AgentRuntime.java` | 修改 | doExecuteTool 中设置/清理 AgentContext |
| `intellimate-gateway/.../tools/TaskManagementTool.java` | 新建 | 待办任务 CRUD 工具（4 个 @Tool 方法） |
| `intellimate-gateway/.../tools/ScheduledJobManagementTool.java` | 新建 | 定时任务 CRUD 工具（4 个 @Tool 方法） |
| `intellimate-gateway/.../tools/TaskToolAutoConfiguration.java` | 新建 | 注册工具到 ToolCallbackProvider |
| `intellimate-agent/.../tools/ToolGroup.java` | 修改 | 添加 TASK 分组 |
| `intellimate-agent/.../tools/ToolProfile.java` | 修改 | FULL profile 添加任务工具 |
| `intellimate-agent/src/main/resources/prompts/tool-usage-guidelines.md` | 修改 | 添加任务工具使用指南 |
| `intellimate-web/src/components/chat/TaskCard.tsx` | 新建 | 待办任务结构化卡片 |
| `intellimate-web/src/components/chat/ScheduledJobCard.tsx` | 新建 | 定时任务结构化卡片 |
| `intellimate-web/src/components/chat/ToolCallRenderer.tsx`（或现有渲染组件） | 修改 | 集成卡片渲染逻辑 |
| `intellimate-gateway/.../tools/TaskManagementToolTest.java` | 新建 | 待办工具单元测试 |
| `intellimate-gateway/.../tools/ScheduledJobManagementToolTest.java` | 新建 | 定时工具单元测试 |

---

### 任务 1：AgentContext 上下文工具类

**文件：**
- 创建：`intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/AgentContext.java`
- 测试：`intellimate-agent/src/test/java/com/atm/intellimate/agent/tools/AgentContextTest.java`

- [ ] **步骤 1：编写测试**

```java
package com.atm.intellimate.agent.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContextTest {

    @AfterEach
    void cleanup() {
        AgentContext.clear();
    }

    @Test
    void setAndGet_roundTrip() {
        AgentContext.set(42L, "test-agent");
        assertThat(AgentContext.getAgentId()).isEqualTo(42L);
        assertThat(AgentContext.getAgentName()).isEqualTo("test-agent");
    }

    @Test
    void clear_removesValues() {
        AgentContext.set(42L, "test-agent");
        AgentContext.clear();
        assertThat(AgentContext.getAgentId()).isNull();
        assertThat(AgentContext.getAgentName()).isNull();
    }

    @Test
    void get_withoutSet_returnsNull() {
        assertThat(AgentContext.getAgentId()).isNull();
        assertThat(AgentContext.getAgentName()).isNull();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd intellimate-agent && mvn test -pl . -Dtest=AgentContextTest -Dsurefire.useFile=false`
预期：编译失败（`AgentContext` 类不存在）

- [ ] **步骤 3：实现 AgentContext**

```java
package com.atm.intellimate.agent.tools;

public final class AgentContext {

    private static final ThreadLocal<Long> AGENT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> AGENT_NAME = new ThreadLocal<>();

    private AgentContext() {}

    public static void set(Long agentId, String agentName) {
        AGENT_ID.set(agentId);
        AGENT_NAME.set(agentName);
    }

    public static Long getAgentId() {
        return AGENT_ID.get();
    }

    public static String getAgentName() {
        return AGENT_NAME.get();
    }

    public static void clear() {
        AGENT_ID.remove();
        AGENT_NAME.remove();
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd intellimate-agent && mvn test -pl . -Dtest=AgentContextTest -Dsurefire.useFile=false`
预期：全部 PASS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/AgentContext.java
git add intellimate-agent/src/test/java/com/atm/intellimate/agent/tools/AgentContextTest.java
git commit -m "feat(tools): add AgentContext ThreadLocal for tool-level agent identity"
```

---

### 任务 2：AgentRuntime 集成 AgentContext

**文件：**
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java`

需要在 `doExecuteTool` 的 `Mono.fromCallable` 中设置 `AgentContext`，在 `finally` 中清理。`doExecuteTool` 已有 `agentName` 参数，但需要 `agentId`。agentId 来自 `AgentRunRequest` 中的 agent 配置。

- [ ] **步骤 1：查找 agentId 的来源**

在 `AgentRuntime` 中，`executeAgentLoop` 方法接收 `AgentRunRequest`。找到 `agentId` 的获取方式。`IntelliMateProperties.Agent` 或 `AgentRunRequest` 应包含 agentId 字段。搜索 `agentId` 在 `AgentRunRequest` 或 `processToolCalls` 调用链中的传递。

如果 `AgentRunRequest` 不包含 `agentId`，需从 `AgentConfigService` 获取。检查 `MessagePipeline` 中构建 `AgentRunRequest` 的逻辑。

- [ ] **步骤 2：在 doExecuteTool 中设置 AgentContext**

在 `doExecuteTool` 方法的 `Mono.fromCallable` 内，紧接 `agentSessionContext.set(sessionId);` 之后添加 AgentContext 设置。

将 `doExecuteTool` 的参数签名扩展，新增 `Long agentId` 参数。同时更新所有调用处（`executeSingleTool`、`processToolCalls` 中的 approval 流程）。

修改位置——在 `Mono.fromCallable(() -> {` 块内：

```java
agentSessionContext.set(sessionId);
AgentContext.set(agentId, agentName);  // 新增
```

在 `finally` 块中添加清理：

```java
} finally {
    SkillGroupContext.clear();
    agentSessionContext.clear();
    AgentContext.clear();  // 新增
}
```

- [ ] **步骤 3：追溯 agentId 传递链**

从 `executeAgentLoop` → `processToolCalls` → `executeSingleTool` → `doExecuteTool` 完整传递 agentId。

如果 `AgentRunRequest` 中没有 agentId 字段，在 `MessagePipeline.processRequest()` 构建 request 时从 agent config entity 中获取并填入。

- [ ] **步骤 4：编译验证**

运行：`cd intellimate-agent && mvn compile -pl .`
预期：BUILD SUCCESS

- [ ] **步骤 5：运行已有测试确保不破坏**

运行：`cd intellimate-agent && mvn test -pl . -Dsurefire.useFile=false`
预期：全部 PASS

- [ ] **步骤 6：Commit**

```bash
git add intellimate-agent/src/main/java/com/atm/intellimate/agent/runtime/AgentRuntime.java
git commit -m "feat(runtime): propagate AgentContext to tool execution context"
```

---

### 任务 3：TaskManagementTool（待办任务 CRUD）

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/tools/TaskManagementTool.java`
- 测试：`intellimate-gateway/src/test/java/com/atm/intellimate/gateway/tools/TaskManagementToolTest.java`

注意：工具类放在 `intellimate-gateway` 的 `tools` 包中，因为需要直接访问 `AgentTaskRepository`。工具方法返回 `String`（JSON），内部使用 `.block()` 调用 reactive repository（安全，因为 `doExecuteTool` 运行在 `Schedulers.boundedElastic()` 上）。

- [ ] **步骤 1：编写测试**

```java
package com.atm.intellimate.gateway.tools;

import com.atm.intellimate.agent.tools.AgentContext;
import com.atm.intellimate.gateway.entity.AgentTaskEntity;
import com.atm.intellimate.gateway.repository.AgentTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskManagementToolTest {

    @Mock private AgentTaskRepository taskRepo;
    private TaskManagementTool tool;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tool = new TaskManagementTool(taskRepo);
        AgentContext.set(1L, "test-agent");
    }

    @AfterEach
    void cleanup() { AgentContext.clear(); }

    @Test
    void createTodoTask_success() throws Exception {
        AgentTaskEntity saved = new AgentTaskEntity();
        saved.setId(42L);
        saved.setTitle("给客户打电话");
        saved.setStatus("pending");
        saved.setPriority(1);
        when(taskRepo.save(any())).thenReturn(Mono.just(saved));

        String result = tool.createTodoTask("给客户打电话", null, "2026-05-22T15:00:00", "2026-05-22T14:50:00", 1);
        JsonNode json = om.readTree(result);

        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("task").get("id").asLong()).isEqualTo(42L);
        assertThat(json.get("task").get("title").asText()).isEqualTo("给客户打电话");
        verify(taskRepo).save(argThat(t -> t.getAgentId().equals(1L)));
    }

    @Test
    void createTodoTask_noAgentContext_returnsError() throws Exception {
        AgentContext.clear();
        String result = tool.createTodoTask("test", null, null, null, null);
        JsonNode json = om.readTree(result);
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("error").asText()).contains("不可用");
    }

    @Test
    void createTodoTask_invalidTimeFormat_returnsError() throws Exception {
        String result = tool.createTodoTask("test", null, "not-a-date", null, null);
        JsonNode json = om.readTree(result);
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("error").asText()).contains("时间格式");
    }

    @Test
    void listTodoTasks_byStatus() throws Exception {
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(1L);
        task.setTitle("Task 1");
        task.setStatus("pending");
        when(taskRepo.findByAgentIdAndStatus(1L, "pending")).thenReturn(Flux.just(task));

        String result = tool.listTodoTasks("pending", null);
        JsonNode json = om.readTree(result);
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("tasks")).hasSize(1);
    }

    @Test
    void updateTodoTask_markDone() throws Exception {
        AgentTaskEntity existing = new AgentTaskEntity();
        existing.setId(10L);
        existing.setAgentId(1L);
        existing.setTitle("Old title");
        existing.setStatus("pending");
        when(taskRepo.findById(10L)).thenReturn(Mono.just(existing));
        when(taskRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        String result = tool.updateTodoTask(10L, null, null, null, null, null, "done");
        JsonNode json = om.readTree(result);
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("task").get("status").asText()).isEqualTo("done");
    }

    @Test
    void updateTodoTask_notFound() throws Exception {
        when(taskRepo.findById(99L)).thenReturn(Mono.empty());
        String result = tool.updateTodoTask(99L, null, null, null, null, null, "done");
        JsonNode json = om.readTree(result);
        assertThat(json.get("success").asBoolean()).isFalse();
    }

    @Test
    void updateTodoTask_wrongAgent_rejected() throws Exception {
        AgentTaskEntity foreign = new AgentTaskEntity();
        foreign.setId(20L);
        foreign.setAgentId(999L);
        when(taskRepo.findById(20L)).thenReturn(Mono.just(foreign));

        String result = tool.updateTodoTask(20L, null, null, null, null, null, "done");
        JsonNode json = om.readTree(result);
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("error").asText()).contains("无权");
    }

    @Test
    void listTodoTasks_allStatus() throws Exception {
        AgentTaskEntity t1 = new AgentTaskEntity(); t1.setId(1L); t1.setTitle("A"); t1.setStatus("pending");
        AgentTaskEntity t2 = new AgentTaskEntity(); t2.setId(2L); t2.setTitle("B"); t2.setStatus("done");
        when(taskRepo.findByAgentId(1L)).thenReturn(Flux.just(t1, t2));

        String result = tool.listTodoTasks("all", null);
        JsonNode json = om.readTree(result);
        assertThat(json.get("tasks")).hasSize(2);
    }

    @Test
    void deleteTodoTask_success() throws Exception {
        when(taskRepo.findById(10L)).thenReturn(Mono.just(new AgentTaskEntity()));
        when(taskRepo.deleteById(10L)).thenReturn(Mono.empty());

        String result = tool.deleteTodoTask(10L);
        JsonNode json = om.readTree(result);
        assertThat(json.get("success").asBoolean()).isTrue();
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd intellimate-gateway && mvn test -pl . -Dtest=TaskManagementToolTest -Dsurefire.useFile=false`
预期：编译失败（`TaskManagementTool` 类不存在）

- [ ] **步骤 3：实现 TaskManagementTool**

```java
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
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd intellimate-gateway && mvn test -pl . -Dtest=TaskManagementToolTest -Dsurefire.useFile=false`
预期：全部 PASS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/tools/TaskManagementTool.java
git add intellimate-gateway/src/test/java/com/atm/intellimate/gateway/tools/TaskManagementToolTest.java
git commit -m "feat(tools): add TaskManagementTool for chat-based todo CRUD"
```

---

### 任务 4：ScheduledJobManagementTool（定时任务 CRUD）

**文件：**
- 修改：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/repository/ScheduledJobConfigRepository.java`（新增查询方法）
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/tools/ScheduledJobManagementTool.java`
- 测试：`intellimate-gateway/src/test/java/com/atm/intellimate/gateway/tools/ScheduledJobManagementToolTest.java`

- [ ] **步骤 0：扩展 ScheduledJobConfigRepository**

在 `ScheduledJobConfigRepository.java` 中新增两个查询方法供 `listScheduledJobs` 做数据库端过滤：

```java
Flux<ScheduledJobConfigEntity> findByJobGroup(String jobGroup);

Flux<ScheduledJobConfigEntity> findByEnabledAndJobGroup(Integer enabled, String jobGroup);
```

- [ ] **步骤 1：编写测试**

```java
package com.atm.intellimate.gateway.tools;

import com.atm.intellimate.agent.tools.AgentContext;
import com.atm.intellimate.gateway.entity.ScheduledJobConfigEntity;
import com.atm.intellimate.gateway.repository.ScheduledJobConfigRepository;
import com.atm.intellimate.gateway.scheduler.CronCalculator;
import com.atm.intellimate.gateway.scheduler.ReactiveScheduleEngine;
import com.atm.intellimate.gateway.scheduler.TaskRegistry;
import com.atm.intellimate.gateway.scheduler.model.ConfigChangeEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduledJobManagementToolTest {

    @Mock private ScheduledJobConfigRepository jobRepo;
    @Mock private ReactiveScheduleEngine engine;
    @Mock private TaskRegistry registry;
    @Mock private CronCalculator cronCalculator;
    private ScheduledJobManagementTool tool;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        tool = new ScheduledJobManagementTool(jobRepo, engine, registry, cronCalculator);
        AgentContext.set(1L, "test-agent");
    }

    @AfterEach
    void cleanup() { AgentContext.clear(); }

    @Test
    void createScheduledJob_agentPrompt_success() throws Exception {
        when(cronCalculator.nextFireTime(eq("CRON"), eq("0 0 9 * * ?"), anyString(), any()))
                .thenReturn(LocalDateTime.of(2026, 5, 22, 9, 0));
        when(jobRepo.findByJobName(anyString())).thenReturn(Mono.empty());
        when(jobRepo.save(any())).thenAnswer(inv -> {
            ScheduledJobConfigEntity e = inv.getArgument(0);
            e.setId(1L);
            return Mono.just(e);
        });
        when(registry.getJobBean("agent-prompt")).thenReturn(mock(com.atm.intellimate.gateway.scheduler.ScheduledJob.class));

        String result = tool.createScheduledJob("每日新闻摘要", "CRON", "0 0 9 * * ?", "agent-prompt",
                "{\"prompt\":\"给我今天的新闻摘要\"}");
        JsonNode json = om.readTree(result);

        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("job").get("displayName").asText()).isEqualTo("每日新闻摘要");
        assertThat(json.get("job").get("jobType").asText()).isEqualTo("agent-prompt");
        verify(jobRepo).save(argThat(j -> "user-chat".equals(j.getJobGroup())));
    }

    @Test
    void createScheduledJob_unsupportedJobType_returnsError() throws Exception {
        when(registry.getJobBean("invalid-type")).thenReturn(null);

        String result = tool.createScheduledJob("test", "CRON", "0 0 9 * * ?", "invalid-type", "{}");
        JsonNode json = om.readTree(result);
        assertThat(json.get("success").asBoolean()).isFalse();
    }

    @Test
    void deleteScheduledJob_systemJob_rejected() throws Exception {
        String result = tool.deleteScheduledJob("heartbeat-tick");
        JsonNode json = om.readTree(result);
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("error").asText()).contains("系统任务");
    }

    @Test
    void createScheduledJob_httpCallback_ssrfBlocked() throws Exception {
        when(registry.getJobBean("http-callback")).thenReturn(mock(com.atm.intellimate.gateway.scheduler.ScheduledJob.class));
        String result = tool.createScheduledJob("test", "FIXED_RATE", "3600", "http-callback",
                "{\"url\":\"http://192.168.1.1/admin\",\"method\":\"GET\"}");
        JsonNode json = om.readTree(result);
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("error").asText()).contains("内网地址");
    }

    @Test
    void updateScheduledJob_systemJob_rejected() throws Exception {
        String result = tool.updateScheduledJob("heartbeat-tick", "new name", null, null, null, null);
        JsonNode json = om.readTree(result);
        assertThat(json.get("success").asBoolean()).isFalse();
        assertThat(json.get("error").asText()).contains("系统任务");
    }

    @Test
    void listScheduledJobs_returnsAll() throws Exception {
        ScheduledJobConfigEntity job = new ScheduledJobConfigEntity();
        job.setJobName("test-job");
        job.setDisplayName("Test Job");
        job.setEnabled(1);
        job.setTriggerType("CRON");
        job.setTriggerValue("0 0 9 * * ?");
        job.setJobClass("agent-prompt");
        when(jobRepo.findAll()).thenReturn(Flux.just(job));

        String result = tool.listScheduledJobs(null, null);
        JsonNode json = om.readTree(result);
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("jobs")).hasSize(1);
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`cd intellimate-gateway && mvn test -pl . -Dtest=ScheduledJobManagementToolTest -Dsurefire.useFile=false`
预期：编译失败

- [ ] **步骤 3：实现 ScheduledJobManagementTool**

```java
package com.atm.intellimate.gateway.tools;

import com.atm.intellimate.agent.tools.AgentContext;
import com.atm.intellimate.gateway.entity.ScheduledJobConfigEntity;
import com.atm.intellimate.gateway.repository.ScheduledJobConfigRepository;
import com.atm.intellimate.gateway.scheduler.CronCalculator;
import com.atm.intellimate.gateway.scheduler.ReactiveScheduleEngine;
import com.atm.intellimate.gateway.scheduler.TaskRegistry;
import com.atm.intellimate.gateway.scheduler.model.ConfigChangeEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class ScheduledJobManagementTool {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobManagementTool.class);
    private static final ObjectMapper om = new ObjectMapper();
    private static final Set<String> PROTECTED_JOBS = Set.of(
            "heartbeat-tick", "memory-nightly-maintenance", "data-cleanup");
    private static final Set<String> ALLOWED_JOB_TYPES = Set.of("agent-prompt", "http-callback");

    private final ScheduledJobConfigRepository jobRepo;
    private final ReactiveScheduleEngine engine;
    private final TaskRegistry registry;
    private final CronCalculator cronCalculator;

    public ScheduledJobManagementTool(ScheduledJobConfigRepository jobRepo,
                                       ReactiveScheduleEngine engine,
                                       TaskRegistry registry,
                                       CronCalculator cronCalculator) {
        this.jobRepo = jobRepo;
        this.engine = engine;
        this.registry = registry;
        this.cronCalculator = cronCalculator;
    }

    @Tool(description = "创建定时任务，支持两种类型：1) agent-prompt：让 Agent 按计划定时执行提示词"
            + "（如'每天早上发新闻摘要'）2) http-callback：定时调用外部 HTTP 接口")
    public String createScheduledJob(
            @ToolParam(description = "任务显示名称") String displayName,
            @ToolParam(description = "触发类型：CRON / FIXED_RATE / FIXED_DELAY") String triggerType,
            @ToolParam(description = "触发值：CRON 填 cron 表达式如 '0 0 9 * * ?'；FIXED_RATE/FIXED_DELAY 填秒数如 '3600'") String triggerValue,
            @ToolParam(description = "任务类型：agent-prompt 或 http-callback") String jobType,
            @ToolParam(description = "参数 JSON：agent-prompt 需 {\"prompt\":\"...\"}；http-callback 需 {\"url\":\"...\",\"method\":\"GET/POST\"}") String paramsJson
    ) {
        if (!ALLOWED_JOB_TYPES.contains(jobType)) {
            return errorJson("不支持的任务类型：" + jobType + "。仅支持 agent-prompt 和 http-callback");
        }
        if (registry.getJobBean(jobType) == null) {
            return errorJson("任务类型的执行器未注册：" + jobType);
        }

        if ("http-callback".equals(jobType) && paramsJson != null) {
            String ssrfError = validateHttpCallbackUrl(paramsJson);
            if (ssrfError != null) return errorJson(ssrfError);
        }

        String agentName = AgentContext.getAgentName();
        String jobName = "chat-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);

        ScheduledJobConfigEntity existing = jobRepo.findByJobName(jobName).block();
        if (existing != null) return errorJson("任务名冲突，请重试");

        ScheduledJobConfigEntity config = new ScheduledJobConfigEntity();
        config.setJobName(jobName);
        config.setJobClass(jobType);
        config.setJobGroup("user-chat");
        config.setDisplayName(displayName);
        config.setTriggerType(triggerType.toUpperCase());
        config.setTriggerValue(triggerValue);
        config.setTimezone("Asia/Shanghai");
        config.setTimeoutMs(120000L);
        config.setMaxRetryCount(0);
        config.setRetryBackoffMs(5000L);
        config.setConcurrentAllowed(0);
        config.setEnabled(1);
        config.setConsecutiveFailures(0);

        if ("agent-prompt".equals(jobType) && agentName != null) {
            try {
                ObjectNode params = (ObjectNode) om.readTree(paramsJson != null ? paramsJson : "{}");
                if (!params.has("agentName")) params.put("agentName", agentName);
                config.setParamsJson(params.toString());
            } catch (Exception e) {
                config.setParamsJson(paramsJson);
            }
        } else {
            config.setParamsJson(paramsJson);
        }

        LocalDateTime next = cronCalculator.nextFireTime(
                config.getTriggerType(), config.getTriggerValue(),
                config.getTimezone(), LocalDateTime.now());
        config.setNextFireTime(next);
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());

        ScheduledJobConfigEntity saved = jobRepo.save(config).block();
        registry.registerJobBean(saved.getJobName(), registry.getJobBean(jobType));
        engine.emitConfigChange(new ConfigChangeEvent(jobName, ConfigChangeEvent.ChangeType.UPDATED));

        log.info("Scheduled job created via chat: name={}, type={}, agent={}", jobName, jobType, agentName);
        return jobToJson("created", saved);
    }

    @Tool(description = "查询定时任务列表。")
    public String listScheduledJobs(
            @ToolParam(description = "按启用状态：true/false/all，默认 all", required = false) String enabled,
            @ToolParam(description = "按任务组：user-chat/custom/system/all，默认 all", required = false) String jobGroup
    ) {
        List<ScheduledJobConfigEntity> jobs;
        boolean hasEnabledFilter = enabled != null && !"all".equalsIgnoreCase(enabled);
        boolean hasGroupFilter = jobGroup != null && !"all".equalsIgnoreCase(jobGroup);
        Integer enabledVal = hasEnabledFilter ? ("true".equalsIgnoreCase(enabled) ? 1 : 0) : null;

        if (hasEnabledFilter && hasGroupFilter) {
            jobs = jobRepo.findByEnabledAndJobGroup(enabledVal, jobGroup).collectList().block();
        } else if (hasEnabledFilter) {
            jobs = jobRepo.findByEnabled(enabledVal).collectList().block();
        } else if (hasGroupFilter) {
            jobs = jobRepo.findByJobGroup(jobGroup).collectList().block();
        } else {
            jobs = jobRepo.findAll().collectList().block();
        }

        ObjectNode root = om.createObjectNode();
        root.put("success", true);
        root.put("action", "listed");
        ArrayNode arr = root.putArray("jobs");
        for (ScheduledJobConfigEntity j : jobs) {
            arr.add(jobNode(j));
        }
        root.put("total", jobs.size());
        return root.toString();
    }

    @Tool(description = "更新定时任务配置。系统内置任务不可修改。")
    public String updateScheduledJob(
            @ToolParam(description = "任务名称") String jobName,
            @ToolParam(description = "新显示名称", required = false) String displayName,
            @ToolParam(description = "新触发类型", required = false) String triggerType,
            @ToolParam(description = "新触发值", required = false) String triggerValue,
            @ToolParam(description = "启用/禁用", required = false) Boolean enabled,
            @ToolParam(description = "新参数 JSON", required = false) String paramsJson
    ) {
        if (PROTECTED_JOBS.contains(jobName)) return errorJson("系统任务不可修改：" + jobName);

        ScheduledJobConfigEntity job = jobRepo.findByJobName(jobName).block();
        if (job == null) return errorJson("定时任务不存在：" + jobName);

        if (displayName != null) job.setDisplayName(displayName);
        if (triggerType != null) job.setTriggerType(triggerType.toUpperCase());
        if (triggerValue != null) job.setTriggerValue(triggerValue);
        if (enabled != null) job.setEnabled(enabled ? 1 : 0);
        if (paramsJson != null) job.setParamsJson(paramsJson);
        job.setUpdatedAt(LocalDateTime.now());

        if (triggerType != null || triggerValue != null) {
            LocalDateTime next = cronCalculator.nextFireTime(
                    job.getTriggerType(), job.getTriggerValue(),
                    job.getTimezone(), LocalDateTime.now());
            job.setNextFireTime(next);
        }

        ScheduledJobConfigEntity updated = jobRepo.save(job).block();
        engine.emitConfigChange(new ConfigChangeEvent(jobName, ConfigChangeEvent.ChangeType.UPDATED));
        return jobToJson("updated", updated);
    }

    @Tool(description = "删除定时任务。系统内置任务不可删除。")
    public String deleteScheduledJob(
            @ToolParam(description = "任务名称") String jobName
    ) {
        if (PROTECTED_JOBS.contains(jobName)) return errorJson("系统任务不可删除：" + jobName);

        ScheduledJobConfigEntity job = jobRepo.findByJobName(jobName).block();
        if (job == null) return errorJson("定时任务不存在：" + jobName);

        jobRepo.deleteById(job.getId()).block();
        engine.emitConfigChange(new ConfigChangeEvent(jobName, ConfigChangeEvent.ChangeType.DELETED));
        log.info("Scheduled job deleted via chat: {}", jobName);

        ObjectNode root = om.createObjectNode();
        root.put("success", true);
        root.put("action", "deleted");
        root.put("deletedJobName", jobName);
        return root.toString();
    }

    private String jobToJson(String action, ScheduledJobConfigEntity job) {
        ObjectNode root = om.createObjectNode();
        root.put("success", true);
        root.put("action", action);
        root.set("job", jobNode(job));
        return root.toString();
    }

    private ObjectNode jobNode(ScheduledJobConfigEntity j) {
        ObjectNode node = om.createObjectNode();
        node.put("jobName", j.getJobName());
        node.put("displayName", j.getDisplayName());
        node.put("triggerType", j.getTriggerType());
        node.put("triggerValue", j.getTriggerValue());
        node.put("jobType", j.getJobClass());
        node.put("jobGroup", j.getJobGroup());
        node.put("enabled", j.getEnabled() != null && j.getEnabled() == 1);
        node.put("nextFireTime", j.getNextFireTime() != null ? j.getNextFireTime().toString() : null);
        return node;
    }

    private String validateHttpCallbackUrl(String paramsJson) {
        try {
            JsonNode params = om.readTree(paramsJson);
            String url = params.has("url") ? params.get("url").asText() : null;
            if (url == null || url.isBlank()) return "http-callback 类型必须提供 url 参数";
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null) return "无效的 URL：" + url;
            if ("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host)
                    || host.startsWith("10.") || host.startsWith("192.168.")
                    || host.matches("^172\\.(1[6-9]|2[0-9]|3[01])\\..*")) {
                return "安全限制：不允许调用内网地址（" + host + "）";
            }
        } catch (Exception e) {
            return "参数 JSON 解析失败：" + e.getMessage();
        }
        return null;
    }

    private String errorJson(String message) {
        return "{\"success\":false,\"error\":\"" + message.replace("\"", "\\\"") + "\"}";
    }
}
```

- [ ] **步骤 4：运行测试验证通过**

运行：`cd intellimate-gateway && mvn test -pl . -Dtest=ScheduledJobManagementToolTest -Dsurefire.useFile=false`
预期：全部 PASS

- [ ] **步骤 5：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/tools/ScheduledJobManagementTool.java
git add intellimate-gateway/src/test/java/com/atm/intellimate/gateway/tools/ScheduledJobManagementToolTest.java
git commit -m "feat(tools): add ScheduledJobManagementTool for chat-based job CRUD"
```

---

### 任务 5：TaskToolAutoConfiguration 注册

**文件：**
- 创建：`intellimate-gateway/src/main/java/com/atm/intellimate/gateway/tools/TaskToolAutoConfiguration.java`

工具类在 `intellimate-gateway` 中，需通过独立的 `ToolCallbackProvider` bean 注册到 `ToolsEngine`。

- [ ] **步骤 1：实现 TaskToolAutoConfiguration**

```java
package com.atm.intellimate.gateway.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TaskToolAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TaskToolAutoConfiguration.class);

    @Bean
    public ToolCallbackProvider taskToolCallbackProvider(
            TaskManagementTool taskManagementTool,
            ScheduledJobManagementTool scheduledJobManagementTool
    ) {
        log.info("TaskToolAutoConfiguration: registering task management tools (todo CRUD + scheduled job CRUD)");
        return MethodToolCallbackProvider.builder()
                .toolObjects(taskManagementTool, scheduledJobManagementTool)
                .build();
    }
}
```

- [ ] **步骤 2：编译验证**

运行：`cd intellimate-gateway && mvn compile -pl .`
预期：BUILD SUCCESS

- [ ] **步骤 3：Commit**

```bash
git add intellimate-gateway/src/main/java/com/atm/intellimate/gateway/tools/TaskToolAutoConfiguration.java
git commit -m "feat(tools): register task tools via gateway ToolCallbackProvider"
```

---

### 任务 6：ToolGroup 和 ToolProfile 扩展

**文件：**
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/ToolGroup.java`
- 修改：`intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/ToolProfile.java`

- [ ] **步骤 1：在 ToolGroup 添加 TASK 枚举值**

在 `DELEGATION` 之后添加：

```java
TASK("任务管理", Set.of("createTodoTask", "listTodoTasks", "updateTodoTask", "deleteTodoTask",
        "createScheduledJob", "listScheduledJobs", "updateScheduledJob", "deleteScheduledJob"));
```

- [ ] **步骤 2：在 ToolProfile.FULL 中添加任务工具**

```java
FULL(Set.of("exec", "readFile", "writeFile", "editFile", "webSearch", "webFetch",
        "createTodoTask", "listTodoTasks", "updateTodoTask", "deleteTodoTask",
        "createScheduledJob", "listScheduledJobs", "updateScheduledJob", "deleteScheduledJob")),
```

- [ ] **步骤 3：编译验证**

运行：`cd intellimate-agent && mvn compile -pl .`
预期：BUILD SUCCESS

- [ ] **步骤 4：Commit**

```bash
git add intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/ToolGroup.java
git add intellimate-agent/src/main/java/com/atm/intellimate/agent/tools/ToolProfile.java
git commit -m "feat(tools): add TASK group and include task tools in FULL profile"
```

---

### 任务 7：工具使用指南更新

**文件：**
- 修改：`intellimate-agent/src/main/resources/prompts/tool-usage-guidelines.md`

- [ ] **步骤 1：在文件末尾（`%s` 标记之前）添加任务工具段落**

在 `%s` 行之前插入：

```markdown
### 任务管理工具

你有两组工具用于管理用户的任务：

**待办任务（一次性提醒/待办）：**
- `createTodoTask` — 创建待办事项或一次性提醒
- `listTodoTasks` — 查询待办列表（可按 pending/done/cancelled 筛选）
- `updateTodoTask` — 修改任务或标记完成/取消
- `deleteTodoTask` — 删除任务

**定时任务（周期性自动执行）：**
- `createScheduledJob` — 创建定时执行的任务（agent-prompt 或 http-callback）
- `listScheduledJobs` — 查询定时任务列表
- `updateScheduledJob` — 修改配置或暂停/恢复
- `deleteScheduledJob` — 删除定时任务

**选择指南：**
- 一次性提醒/备忘 → `createTodoTask`（如"明天3点提醒我开会"）
- 周期性重复执行 → `createScheduledJob`（如"每天早上9点给我发新闻"）
- 不确定时先询问用户

**创建确认规则：**
- 用户明确要求时（"帮我设个提醒"），直接调用工具
- 主动识别到意图时（用户随口提到"明天得开会"），先确认后再创建

**时间格式：** ISO 8601（如 2026-05-22T15:00:00）。定时任务 cron 参考：每天9点 = `0 0 9 * * ?`，每小时 = `0 0 * * * ?`

```

- [ ] **步骤 2：Commit**

```bash
git add intellimate-agent/src/main/resources/prompts/tool-usage-guidelines.md
git commit -m "docs(prompts): add task management tool usage guidelines"
```

---

### 任务 8：前端 TaskCard 组件

**文件：**
- 创建：`intellimate-web/src/components/chat/TaskCard.tsx`

- [ ] **步骤 1：查找现有 tool call 渲染位置**

搜索 `intellimate-web/src/components/chat/` 目录中渲染 `toolCalls` 的组件。找到 `ToolCallInfo` 被渲染的位置（可能在 `MessageBubble.tsx`、`ChatMessage.tsx` 或类似组件中）。

- [ ] **步骤 2：创建 TaskCard 组件**

```tsx
import React, { useState } from "react";
import { heartbeatApi } from "../../lib/heartbeatApi";

interface TaskData {
  id: number;
  title: string;
  description?: string | null;
  dueAt?: string | null;
  remindAt?: string | null;
  priority: number;
  status: string;
}

interface TaskCardProps {
  action: string;
  task?: TaskData;
  tasks?: TaskData[];
  total?: number;
  agentId?: number;
}

const priorityLabels: Record<number, { label: string; color: string }> = {
  0: { label: "普通", color: "text-gray-500" },
  1: { label: "重要", color: "text-orange-500" },
  2: { label: "紧急", color: "text-red-500" },
};

function formatTime(iso: string | null | undefined): string {
  if (!iso) return "";
  try {
    return new Date(iso).toLocaleString("zh-CN", {
      month: "numeric", day: "numeric",
      hour: "2-digit", minute: "2-digit",
    });
  } catch { return iso; }
}

export const TaskCard: React.FC<TaskCardProps> = ({ action, task, tasks, total, agentId }) => {
  const [localStatus, setLocalStatus] = useState(task?.status);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleStatusChange = async (newStatus: string) => {
    if (!task?.id || !agentId || loading) return;
    setLoading(true);
    setError(null);
    try {
      await heartbeatApi.updateTask(agentId, task.id, { status: newStatus });
      setLocalStatus(newStatus);
    } catch (e) {
      setError("操作失败，请重试");
    } finally {
      setLoading(false);
    }
  };

  if (action === "listed" && tasks) {
    return (
      <div className="rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 p-3 my-2 max-w-md">
        <div className="text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
          📋 待办任务（{total} 项）
        </div>
        <div className="space-y-1.5">
          {tasks.map((t) => {
            const p = priorityLabels[t.priority] ?? priorityLabels[0];
            return (
              <div key={t.id} className="flex items-center gap-2 text-sm">
                <span className={p.color}>●</span>
                <span className="flex-1 truncate">{t.title}</span>
                {t.dueAt && <span className="text-xs text-gray-400">{formatTime(t.dueAt)}</span>}
              </div>
            );
          })}
        </div>
      </div>
    );
  }

  if (!task) return null;
  const p = priorityLabels[task.priority] ?? priorityLabels[0];
  const effectiveStatus = localStatus ?? task.status;
  const isDone = effectiveStatus === "done" || effectiveStatus === "cancelled";
  const actionLabel = action === "created" ? "已创建" : action === "updated" ? "已更新" : action === "deleted" ? "已删除" : "";

  return (
    <div className="rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 p-3 my-2 max-w-md">
      <div className="text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
        ✅ 待办任务{actionLabel}
      </div>
      <div className="space-y-1">
        <div className="font-medium text-gray-900 dark:text-gray-100">{task.title}</div>
        {task.description && <div className="text-sm text-gray-500">{task.description}</div>}
        {task.dueAt && <div className="text-xs text-gray-400">⏰ 截止：{formatTime(task.dueAt)}</div>}
        {task.remindAt && <div className="text-xs text-gray-400">🔔 提醒：{formatTime(task.remindAt)}</div>}
        <div className="text-xs"><span className={p.color}>● {p.label}</span></div>
      </div>
      {error && <div className="text-xs text-red-500 mt-1">{error}</div>}
      {!isDone && action !== "deleted" && (
        <div className="flex gap-2 mt-2 pt-2 border-t border-gray-100 dark:border-gray-700">
          <button onClick={() => handleStatusChange("done")} disabled={loading}
                  className="text-xs px-2 py-1 rounded bg-green-50 text-green-600 hover:bg-green-100 disabled:opacity-50">
            {loading ? "处理中..." : "标记完成"}
          </button>
          <button onClick={() => handleStatusChange("cancelled")} disabled={loading}
                  className="text-xs px-2 py-1 rounded bg-gray-50 text-gray-500 hover:bg-gray-100 disabled:opacity-50">
            取消
          </button>
        </div>
      )}
      {isDone && <div className="text-xs text-gray-400 mt-1">状态：{effectiveStatus === "done" ? "已完成" : "已取消"}</div>}
    </div>
  );
};
```

- [ ] **步骤 3：Commit**

```bash
git add intellimate-web/src/components/chat/TaskCard.tsx
git commit -m "feat(ui): add TaskCard component for chat-embedded todo display"
```

---

### 任务 9：前端 ScheduledJobCard 组件

**文件：**
- 创建：`intellimate-web/src/components/chat/ScheduledJobCard.tsx`

- [ ] **步骤 1：创建 ScheduledJobCard 组件**

```tsx
import React, { useState } from "react";
import { schedulerApi } from "../../lib/schedulerApi";

interface JobData {
  jobName: string;
  displayName: string;
  triggerType: string;
  triggerValue: string;
  jobType: string;
  jobGroup: string;
  enabled: boolean;
  nextFireTime?: string | null;
}

interface ScheduledJobCardProps {
  action: string;
  job?: JobData;
  jobs?: JobData[];
  total?: number;
}

function describeTrigger(type: string, value: string): string {
  if (type === "CRON") {
    if (value === "0 0 9 * * ?") return "每天 09:00";
    if (value === "0 0 * * * ?") return "每小时";
    return `Cron: ${value}`;
  }
  const secs = parseInt(value);
  if (!isNaN(secs)) {
    if (secs >= 3600) return `每 ${secs / 3600} 小时`;
    if (secs >= 60) return `每 ${secs / 60} 分钟`;
    return `每 ${secs} 秒`;
  }
  return value;
}

export const ScheduledJobCard: React.FC<ScheduledJobCardProps> = ({ action, job, jobs, total }) => {
  const [localEnabled, setLocalEnabled] = useState(job?.enabled);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleToggle = async () => {
    if (!job || loading) return;
    setLoading(true);
    setError(null);
    try {
      if (localEnabled) {
        await schedulerApi.pauseJob(job.jobName);
        setLocalEnabled(false);
      } else {
        await schedulerApi.resumeJob(job.jobName);
        setLocalEnabled(true);
      }
    } catch (e) {
      setError("操作失败，请重试");
    } finally {
      setLoading(false);
    }
  };

  if (action === "listed" && jobs) {
    return (
      <div className="rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 p-3 my-2 max-w-md">
        <div className="text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
          ⏱️ 定时任务（{total} 项）
        </div>
        <div className="space-y-1.5">
          {jobs.map((j) => (
            <div key={j.jobName} className="flex items-center gap-2 text-sm">
              <span className={j.enabled ? "text-green-500" : "text-gray-400"}>●</span>
              <span className="flex-1 truncate">{j.displayName}</span>
              <span className="text-xs text-gray-400">{describeTrigger(j.triggerType, j.triggerValue)}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (!job) return null;
  const effectiveEnabled = localEnabled ?? job.enabled;
  const actionLabel = action === "created" ? "已创建" : action === "updated" ? "已更新" : action === "deleted" ? "已删除" : "";
  const jobTypeLabel = job.jobType === "agent-prompt" ? "Agent 提示词" : "HTTP 回调";

  return (
    <div className="rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 p-3 my-2 max-w-md">
      <div className="text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
        ⏱️ 定时任务{actionLabel}
      </div>
      <div className="space-y-1">
        <div className="font-medium text-gray-900 dark:text-gray-100">{job.displayName}</div>
        <div className="text-xs text-gray-400">🔄 {describeTrigger(job.triggerType, job.triggerValue)}</div>
        <div className="text-xs text-gray-400">🤖 类型：{jobTypeLabel}</div>
        <div className="text-xs">
          {effectiveEnabled
            ? <span className="text-green-500">✅ 已启用</span>
            : <span className="text-gray-400">⏸ 已暂停</span>}
        </div>
      </div>
      {error && <div className="text-xs text-red-500 mt-1">{error}</div>}
      {action !== "deleted" && (
        <div className="flex gap-2 mt-2 pt-2 border-t border-gray-100 dark:border-gray-700">
          <button onClick={handleToggle} disabled={loading}
                  className={`text-xs px-2 py-1 rounded disabled:opacity-50 ${effectiveEnabled
                    ? "bg-yellow-50 text-yellow-600 hover:bg-yellow-100"
                    : "bg-green-50 text-green-600 hover:bg-green-100"}`}>
            {loading ? "处理中..." : effectiveEnabled ? "暂停" : "恢复"}
          </button>
        </div>
      )}
    </div>
  );
};
```

- [ ] **步骤 2：Commit**

```bash
git add intellimate-web/src/components/chat/ScheduledJobCard.tsx
git commit -m "feat(ui): add ScheduledJobCard component for chat-embedded job display"
```

---

### 任务 10：聊天消息中集成卡片渲染

**文件：**
- 修改：聊天消息渲染组件（需先确定具体文件，搜索渲染 `toolCalls` 的位置）

- [ ] **步骤 1：定位工具调用渲染组件**

在 `intellimate-web/src/components/chat/` 中搜索渲染 `toolCalls` 或 `ToolCallInfo` 的组件。找到 `tc.name` 和 `tc.result` 被使用的位置。

- [ ] **步骤 2：创建工具结果卡片路由函数**

在工具调用渲染位置附近（或新建 `TaskToolCardRenderer.tsx`），添加识别和路由逻辑：

```tsx
import { TaskCard } from "./TaskCard";
import { ScheduledJobCard } from "./ScheduledJobCard";

const TASK_TOOL_NAMES = new Set([
  "createTodoTask", "listTodoTasks", "updateTodoTask", "deleteTodoTask",
  "createScheduledJob", "listScheduledJobs", "updateScheduledJob", "deleteScheduledJob",
]);

export function renderTaskToolResult(toolName: string, resultJson: string, agentId?: number) {
  if (!TASK_TOOL_NAMES.has(toolName)) return null;

  try {
    const data = JSON.parse(resultJson);
    if (!data.success) return null;

    if (toolName.includes("Todo") || toolName.includes("Task")) {
      return <TaskCard action={data.action} task={data.task} tasks={data.tasks} total={data.total} agentId={agentId} />;
    } else {
      return <ScheduledJobCard action={data.action} job={data.job} jobs={data.jobs} total={data.total} />;
    }
  } catch {
    return null;
  }
}
```

- [ ] **步骤 3：在消息渲染中调用路由函数**

在工具调用渲染位置，当 `tc.status === "done"` 且 `tc.result` 存在时，先尝试 `renderTaskToolResult(tc.name, tc.result)`。如果返回非 null，渲染卡片替代默认展示；否则保持原有渲染。

- [ ] **步骤 4：前端编译验证**

运行：`cd intellimate-web && npm run build`
预期：BUILD SUCCESS，无 TypeScript 错误

- [ ] **步骤 5：Commit**

```bash
git add intellimate-web/src/components/chat/
git commit -m "feat(ui): integrate task cards into chat message rendering"
```

---

### 任务 11：端到端验证

- [ ] **步骤 1：启动后端**

运行：`cd intellimate-gateway && mvn spring-boot:run`
预期：日志出现 `TaskToolAutoConfiguration: registering task management tools`

- [ ] **步骤 2：验证工具注册**

访问：`curl http://localhost:3007/api/tools`
预期：返回的工具列表中包含 `createTodoTask`、`listTodoTasks` 等 8 个新工具

- [ ] **步骤 3：启动前端**

运行：`cd intellimate-web && npm run dev`

- [ ] **步骤 4：对话测试——创建待办任务**

在聊天中发送："帮我创建一个待办任务，明天下午3点提醒我给客户打电话"
预期：
1. Agent 调用 `createTodoTask` 工具
2. 聊天中出现待办任务结构化卡片
3. Agent 用自然语言确认创建成功
4. 数据库 `agent_task` 表有新记录

- [ ] **步骤 5：对话测试——创建定时任务**

在聊天中发送："每天早上9点给我发一份新闻摘要"
预期：
1. Agent 调用 `createScheduledJob` 工具
2. 聊天中出现定时任务结构化卡片
3. `scheduled_job_config` 表有新记录，`job_group = 'user-chat'`

- [ ] **步骤 6：对话测试——查询任务**

发送："我有哪些待办任务？"
预期：Agent 调用 `listTodoTasks`，聊天中出现列表卡片

- [ ] **步骤 7：卡片交互测试**

点击待办任务卡片上的"标记完成"按钮。
预期：卡片状态更新为"已完成"，数据库 status 变为 `done`

- [ ] **步骤 8：Commit 最终修复（如有）**

```bash
git add -A && git commit -m "fix: adjustments from e2e verification"
```

---

## 实现顺序依赖图

```
任务 1 (AgentContext) ──→ 任务 2 (AgentRuntime) ──┐
                                                   │
                                                   ├──→ 任务 3 (TaskManagementTool) ──┐
                                                   │                                   │
                                                   ├──→ 任务 4 (ScheduledJobTool) ─────┤
                                                   │                                   │
                                                   └──→ 任务 5 (Registration) ─────────┤
                                                                                       │
任务 6 (ToolGroup/Profile) ─── 独立 ──────────────────────────────────────────────────┤
任务 7 (Prompt Guidelines) ─── 独立 ──────────────────────────────────────────────────┤
                                                                                       │
任务 8 (TaskCard) ─── 独立 ─────────────────────────────────────────────┐              │
任务 9 (ScheduledJobCard) ─── 独立 ─────────────────────────────────────┤              │
                                                                        │              │
                                                               任务 10 (集成) ─────────┤
                                                                                       │
全部完成 ──────────────────────────────────────────────────────── 任务 11 (E2E) ───────┘
```

**并行组：**
- 任务 1-2 必须顺序
- 任务 3、4、5 依赖 1-2，可互相并行
- 任务 6、7 独立，可与任何任务并行
- 任务 8、9 独立，可与后端任务并行
- 任务 10 依赖 8+9
- 任务 11 依赖全部
