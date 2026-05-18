package com.atm.intellimate.gateway.scheduler;

import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Component
public class CronCalculator {

    public LocalDateTime nextFireTime(String cronExpr, String timezone, LocalDateTime after) {
        CronExpression cron = CronExpression.parse(cronExpr);
        LocalDateTime base = after.atZone(ZoneId.of(timezone)).toLocalDateTime();
        return cron.next(base);
    }

    public LocalDateTime nextFireTime(String triggerType, String triggerValue, String timezone, LocalDateTime after) {
        return switch (triggerType) {
            case "CRON" -> nextFireTime(triggerValue, timezone, after);
            case "FIXED_RATE", "FIXED_DELAY" -> {
                long intervalMs = Long.parseLong(triggerValue);
                yield after.plusNanos(intervalMs * 1_000_000L);
            }
            default -> throw new IllegalArgumentException("Unknown trigger type: " + triggerType);
        };
    }
}
