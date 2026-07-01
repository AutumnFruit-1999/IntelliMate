package com.atm.intellimate.agent.runtime;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * Per-session FIFO queue ensuring only one agent run
 * executes at a time per session. Concurrent sessions
 * run in parallel.
 */
@Component
public class RunQueueManager {

    private final ConcurrentMap<Long, Mono<Void>> sessionChains = new ConcurrentHashMap<>();

    public synchronized Flux<AgentEvent> enqueue(Long sessionId, Supplier<Flux<AgentEvent>> runSupplier) {
        Mono<Void> previous = sessionChains.getOrDefault(sessionId, Mono.empty());

        Sinks.Many<AgentEvent> replaySink = Sinks.many().replay().all();

        Mono<Void> run = previous
                .onErrorComplete()
                .then(Mono.defer(() -> runSupplier.get()
                            .doOnNext(event -> replaySink.tryEmitNext(event))
                            .doOnComplete(() -> replaySink.tryEmitComplete())
                            .doOnError(e -> replaySink.tryEmitError(e))
                            .then()));

        Mono<Void> cached = run.cache();
        sessionChains.put(sessionId, cached);

        cached.subscribe();

        return replaySink.asFlux();
    }
}
