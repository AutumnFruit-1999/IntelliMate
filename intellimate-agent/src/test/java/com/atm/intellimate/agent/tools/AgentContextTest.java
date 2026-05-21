package com.atm.intellimate.agent.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentContextTest {

    @AfterEach
    void cleanup() {
        AgentContext.clear();
    }

    @Test
    void setAndGet_roundTrip() {
        AgentContext.set(42L, "test-agent");
        assertThat(AgentContext.getAgentId()).isEqualTo(42L);
        assertThat(AgentContext.getAgentName()).isEqualTo("test-agent");
    }

    @Test
    void clear_removesValues() {
        AgentContext.set(42L, "test-agent");
        AgentContext.clear();
        assertThat(AgentContext.getAgentId()).isNull();
        assertThat(AgentContext.getAgentName()).isNull();
    }

    @Test
    void get_withoutSet_returnsNull() {
        assertThat(AgentContext.getAgentId()).isNull();
        assertThat(AgentContext.getAgentName()).isNull();
    }
}
