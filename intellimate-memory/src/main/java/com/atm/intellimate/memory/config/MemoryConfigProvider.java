package com.atm.intellimate.memory.config;

import reactor.core.publisher.Mono;

/**
 * Abstraction for resolving memory configuration without coupling the agent module
 * to a specific persistence implementation (e.g. gateway services).
 */
public interface MemoryConfigProvider {

    Mono<ResolvedMemoryConfig> resolve();

    default Mono<ResolvedMemoryConfig> resolveForAgent(String agentName) {
        return resolve();
    }
}
