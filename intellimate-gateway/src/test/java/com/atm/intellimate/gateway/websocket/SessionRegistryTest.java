package com.atm.intellimate.gateway.websocket;

import com.atm.intellimate.core.protocol.GatewayFrame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SessionRegistryTest {

    private SessionRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SessionRegistry(null);
    }

    @Test
    void bindAgent_multipleSessionsSameAgent_allReceivePush() {
        Sinks.Many<GatewayFrame> sink1 = Sinks.many().unicast().onBackpressureBuffer();
        Sinks.Many<GatewayFrame> sink2 = Sinks.many().unicast().onBackpressureBuffer();

        registry.register("ws-1", sink1);
        registry.register("ws-2", sink2);
        registry.bindAgent("ws-1", "agent-a");
        registry.bindAgent("ws-2", "agent-a");

        int delivered = registry.pushToAllAgentSessions("agent-a", "test.event", Map.of("key", "value"));

        assertThat(delivered).isEqualTo(2);
    }

    @Test
    void pushToAllAgentSessions_noBindings_returnsZero() {
        int delivered = registry.pushToAllAgentSessions("unknown-agent", "test.event", Map.of());
        assertThat(delivered).isEqualTo(0);
    }

    @Test
    void unregister_removesFromAllAgentSets() {
        Sinks.Many<GatewayFrame> sink1 = Sinks.many().unicast().onBackpressureBuffer();
        registry.register("ws-1", sink1);
        registry.bindAgent("ws-1", "agent-a");

        registry.unregister("ws-1");

        assertThat(registry.isAgentOnline("agent-a")).isFalse();
        assertThat(registry.pushToAllAgentSessions("agent-a", "test.event", Map.of())).isEqualTo(0);
    }

    @Test
    void pushToAllAgentSessions_staleSidPruned() {
        Sinks.Many<GatewayFrame> sink1 = Sinks.many().unicast().onBackpressureBuffer();
        registry.register("ws-1", sink1);
        registry.bindAgent("ws-1", "agent-a");
        registry.bindAgent("ws-stale", "agent-a");

        int delivered = registry.pushToAllAgentSessions("agent-a", "test.event", Map.of());

        assertThat(delivered).isEqualTo(1);
    }

    @Test
    void pushToAgent_returnsTrue_whenAnyDelivered() {
        Sinks.Many<GatewayFrame> sink1 = Sinks.many().unicast().onBackpressureBuffer();
        registry.register("ws-1", sink1);
        registry.bindAgent("ws-1", "agent-a");

        boolean result = registry.pushToAgent("agent-a", "test.event", Map.of());
        assertThat(result).isTrue();
    }

    @Test
    void isAgentOnline_withValidSink_returnsTrue() {
        Sinks.Many<GatewayFrame> sink1 = Sinks.many().unicast().onBackpressureBuffer();
        registry.register("ws-1", sink1);
        registry.bindAgent("ws-1", "agent-a");

        assertThat(registry.isAgentOnline("agent-a")).isTrue();
    }

    @Test
    void bindAgent_sameSessionTwice_idempotent() {
        Sinks.Many<GatewayFrame> sink1 = Sinks.many().unicast().onBackpressureBuffer();
        registry.register("ws-1", sink1);
        registry.bindAgent("ws-1", "agent-a");
        registry.bindAgent("ws-1", "agent-a");

        int delivered = registry.pushToAllAgentSessions("agent-a", "test.event", Map.of());
        assertThat(delivered).isEqualTo(1);
    }
}
