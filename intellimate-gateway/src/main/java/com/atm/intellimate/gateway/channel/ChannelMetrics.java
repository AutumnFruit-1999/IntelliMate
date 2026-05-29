package com.atm.intellimate.gateway.channel;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Micrometer metrics for external channel message processing.
 */
@Component
public class ChannelMetrics {

    private final MeterRegistry registry;
    private final Map<String, AtomicInteger> channelStatusValues = new ConcurrentHashMap<>();

    public ChannelMetrics(@Autowired(required = false) MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordMessageReceived(String channelId, String contextType) {
        if (registry == null) {
            return;
        }
        registry.counter("channel_messages_received_total",
                        "channel", channelId,
                        "type", contextType != null ? contextType : "unknown")
                .increment();
    }

    public void recordMessageSent(String channelId, boolean success) {
        if (registry == null) {
            return;
        }
        registry.counter("channel_messages_sent_total",
                        "channel", channelId,
                        "status", success ? "success" : "failed")
                .increment();
    }

    public void recordError(String channelId, String errorType) {
        if (registry == null) {
            return;
        }
        registry.counter("channel_errors_total",
                        "channel", channelId,
                        "type", errorType != null ? errorType : "unknown")
                .increment();
    }

    public Timer.Sample startProcessingTimer() {
        if (registry == null) {
            return null;
        }
        return Timer.start(registry);
    }

    public void stopProcessingTimer(Timer.Sample sample, String channelId) {
        if (registry == null || sample == null) {
            return;
        }
        sample.stop(Timer.builder("channel_message_processing_seconds")
                .tag("channel", channelId)
                .register(registry));
    }

    public void recordChannelStatus(String channelId, boolean connected) {
        AtomicInteger value = channelStatusValues.computeIfAbsent(channelId, id -> {
            AtomicInteger gaugeValue = new AtomicInteger(connected ? 1 : 0);
            if (registry != null) {
                registry.gauge("channel_status",
                        io.micrometer.core.instrument.Tags.of("channel", id),
                        gaugeValue,
                        AtomicInteger::get);
            }
            return gaugeValue;
        });
        value.set(connected ? 1 : 0);
    }

}
