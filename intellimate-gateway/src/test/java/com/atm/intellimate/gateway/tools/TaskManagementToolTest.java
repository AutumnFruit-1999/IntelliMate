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
        AgentTaskEntity task = new AgentTaskEntity();
        task.setId(10L);
        task.setAgentId(1L);
        when(taskRepo.findById(10L)).thenReturn(Mono.just(task));
        when(taskRepo.deleteById(10L)).thenReturn(Mono.empty());

        String result = tool.deleteTodoTask(10L);
        JsonNode json = om.readTree(result);
        assertThat(json.get("success").asBoolean()).isTrue();
    }
}
