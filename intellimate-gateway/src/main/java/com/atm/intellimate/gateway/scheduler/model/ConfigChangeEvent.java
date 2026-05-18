package com.atm.intellimate.gateway.scheduler.model;

public record ConfigChangeEvent(String jobName, ChangeType type) {

    public enum ChangeType {
        UPDATED, PAUSED, RESUMED, TRIGGERED
    }
}
