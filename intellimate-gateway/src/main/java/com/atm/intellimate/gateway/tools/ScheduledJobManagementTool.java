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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class ScheduledJobManagementTool {

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
        engine.emitConfigChange(new ConfigChangeEvent(jobName, ConfigChangeEvent.ChangeType.UPDATED));

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
