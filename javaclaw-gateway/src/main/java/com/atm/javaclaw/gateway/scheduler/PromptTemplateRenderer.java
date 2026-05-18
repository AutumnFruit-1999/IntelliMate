package com.atm.javaclaw.gateway.scheduler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.atm.javaclaw.gateway.entity.ScheduledJobLogEntity;
import com.atm.javaclaw.gateway.repository.ScheduledJobLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders prompt templates by replacing {{variable}} placeholders with actual context values.
 * Falls back gracefully: unknown or unresolvable variables are replaced with empty string.
 */
@Component
public class PromptTemplateRenderer {

    private static final Logger log = LoggerFactory.getLogger(PromptTemplateRenderer.class);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Map<DayOfWeek, String> DAY_OF_WEEK_CN = Map.of(
            DayOfWeek.MONDAY, "星期一",
            DayOfWeek.TUESDAY, "星期二",
            DayOfWeek.WEDNESDAY, "星期三",
            DayOfWeek.THURSDAY, "星期四",
            DayOfWeek.FRIDAY, "星期五",
            DayOfWeek.SATURDAY, "星期六",
            DayOfWeek.SUNDAY, "星期日"
    );

    private final ScheduledJobLogRepository logRepo;
    private final ObjectMapper objectMapper;

    public PromptTemplateRenderer(ScheduledJobLogRepository logRepo, ObjectMapper objectMapper) {
        this.logRepo = logRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * Gather context and render template. Returns the final prompt string.
     */
    public Mono<String> render(String template, String jobName) {
        if (template == null || !template.contains("{{")) {
            return Mono.just(template != null ? template : "");
        }

        return gatherLastExecution(jobName)
                .map(lastCtx -> {
                    LocalDateTime now = LocalDateTime.now();
                    Map<String, String> vars = buildContextVars(now, lastCtx);
                    return replaceAll(template, vars);
                });
    }

    private Map<String, String> buildContextVars(LocalDateTime now, LastExecutionContext lastCtx) {
        Map<String, String> vars = new HashMap<>();
        vars.put("time", now.format(TIME_FMT));
        vars.put("date", now.format(DATE_FMT));
        vars.put("datetime", now.format(DATETIME_FMT));
        vars.put("dayOfWeek", DAY_OF_WEEK_CN.getOrDefault(now.getDayOfWeek(), ""));
        vars.put("timeOfDay", resolveTimeOfDay(now.getHour()));

        vars.put("lastResponse", lastCtx.responseSummary() != null ? lastCtx.responseSummary() : "");
        vars.put("executionCount", String.valueOf(lastCtx.executionCount()));
        if (lastCtx.lastFireTime() != null) {
            long days = ChronoUnit.DAYS.between(lastCtx.lastFireTime(), now);
            vars.put("daysSinceLastRun", String.valueOf(days));
        } else {
            vars.put("daysSinceLastRun", "0");
        }
        return vars;
    }

    private String resolveTimeOfDay(int hour) {
        if (hour < 5) return "凌晨";
        if (hour < 8) return "早上";
        if (hour < 11) return "上午";
        if (hour < 13) return "中午";
        if (hour < 17) return "下午";
        if (hour < 21) return "晚上";
        return "深夜";
    }

    private String replaceAll(String template, Map<String, String> vars) {
        String result = template;
        for (Map.Entry<String, String> entry : vars.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    private Mono<LastExecutionContext> gatherLastExecution(String jobName) {
        Mono<ScheduledJobLogEntity> lastLog = logRepo.findByJobNamePaged(jobName, 1, 0).next();
        Mono<Long> count = logRepo.countByJobName(jobName);

        return Mono.zip(lastLog.defaultIfEmpty(new ScheduledJobLogEntity()), count.defaultIfEmpty(0L))
                .map(tuple -> {
                    ScheduledJobLogEntity logEntry = tuple.getT1();
                    long total = tuple.getT2();
                    if (logEntry.getId() == null) {
                        return new LastExecutionContext(null, 0, null);
                    }
                    return extractContext(logEntry, total);
                });
    }

    private LastExecutionContext extractContext(ScheduledJobLogEntity lastLog, long totalCount) {
        String responseSummary = null;
        try {
            if (lastLog.getMetricsJson() != null && !lastLog.getMetricsJson().isBlank()) {
                Map<String, Object> metrics = objectMapper.readValue(
                        lastLog.getMetricsJson(), new TypeReference<>() {});
                Object summary = metrics.get("responseSummary");
                if (summary != null) responseSummary = summary.toString();
            }
        } catch (Exception e) {
            log.warn("Failed to parse metricsJson for job log {}: {}", lastLog.getId(), e.getMessage());
        }
        return new LastExecutionContext(responseSummary, totalCount, lastLog.getFireTime());
    }

    record LastExecutionContext(String responseSummary, long executionCount, LocalDateTime lastFireTime) {}
}
