package com.atm.intellimate.gateway.scheduler.model;

import java.time.LocalDateTime;
import java.util.Map;

public record JobExecutionContext(
    Long executionId,
    String jobName,
    String jobGroup,
    Map<String, Object> params,
    int retryCount,
    LocalDateTime fireTime,
    LocalDateTime startTime
) {
    public boolean isRetry() { return retryCount > 0; }
}
