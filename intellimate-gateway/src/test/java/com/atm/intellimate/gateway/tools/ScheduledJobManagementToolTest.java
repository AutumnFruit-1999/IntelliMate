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
