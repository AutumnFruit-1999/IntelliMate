package com.atm.javaclaw.gateway.entity;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("scheduled_job_config")
public class ScheduledJobConfigEntity {

    @Id
    private Long id;
    private String jobName;
    private String jobGroup;
    private String displayName;
    private String description;
    private String jobClass;
    private String triggerType;
    private String triggerValue;
    private String timezone;
    private Integer enabled;
    private Integer maxRetryCount;
    private Long retryBackoffMs;
    private Long timeoutMs;
    private String paramsJson;
    private Integer concurrentAllowed;
    private LocalDateTime nextFireTime;
    private LocalDateTime lastFireTime;
    private String lastStatus;
    private Integer consecutiveFailures;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }
    public String getJobGroup() { return jobGroup; }
    public void setJobGroup(String jobGroup) { this.jobGroup = jobGroup; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getJobClass() { return jobClass; }
    public void setJobClass(String jobClass) { this.jobClass = jobClass; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getTriggerValue() { return triggerValue; }
    public void setTriggerValue(String triggerValue) { this.triggerValue = triggerValue; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public Integer getEnabled() { return enabled; }
    public void setEnabled(Integer enabled) { this.enabled = enabled; }
    public Integer getMaxRetryCount() { return maxRetryCount; }
    public void setMaxRetryCount(Integer maxRetryCount) { this.maxRetryCount = maxRetryCount; }
    public Long getRetryBackoffMs() { return retryBackoffMs; }
    public void setRetryBackoffMs(Long retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }
    public Long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(Long timeoutMs) { this.timeoutMs = timeoutMs; }
    public String getParamsJson() { return paramsJson; }
    public void setParamsJson(String paramsJson) { this.paramsJson = paramsJson; }
    public Integer getConcurrentAllowed() { return concurrentAllowed; }
    public void setConcurrentAllowed(Integer concurrentAllowed) { this.concurrentAllowed = concurrentAllowed; }
    public LocalDateTime getNextFireTime() { return nextFireTime; }
    public void setNextFireTime(LocalDateTime nextFireTime) { this.nextFireTime = nextFireTime; }
    public LocalDateTime getLastFireTime() { return lastFireTime; }
    public void setLastFireTime(LocalDateTime lastFireTime) { this.lastFireTime = lastFireTime; }
    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }
    public Integer getConsecutiveFailures() { return consecutiveFailures; }
    public void setConsecutiveFailures(Integer consecutiveFailures) { this.consecutiveFailures = consecutiveFailures; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
