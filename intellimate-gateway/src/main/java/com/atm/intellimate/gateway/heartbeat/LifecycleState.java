package com.atm.intellimate.gateway.heartbeat;

import java.time.LocalTime;

public enum LifecycleState {
    SLEEPING,
    WAKING,
    ACTIVE,
    WINDING_DOWN;

    public static LifecycleState compute(LocalTime now, LocalTime wakeTime, LocalTime sleepTime) {
        LocalTime wakingEnd = wakeTime.plusHours(1);
        LocalTime windingStart = sleepTime.minusHours(2);

        if (isBetween(now, sleepTime, wakeTime)) return SLEEPING;
        if (isBetween(now, wakeTime, wakingEnd)) return WAKING;
        if (isBetween(now, windingStart, sleepTime)) return WINDING_DOWN;
        return ACTIVE;
    }

    private static boolean isBetween(LocalTime time, LocalTime start, LocalTime end) {
        if (start.isBefore(end)) {
            return !time.isBefore(start) && time.isBefore(end);
        }
        return !time.isBefore(start) || time.isBefore(end);
    }

    public String description() {
        return switch (this) {
            case SLEEPING -> "休眠中";
            case WAKING -> "刚醒来";
            case ACTIVE -> "活跃中";
            case WINDING_DOWN -> "准备休息";
        };
    }
}
