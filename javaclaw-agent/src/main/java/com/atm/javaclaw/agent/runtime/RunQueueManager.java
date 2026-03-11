package com.atm.javaclaw.agent.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(RunQueueManager.class);

    private final ConcurrentMap<Long, Mono<Void>> sessionChains = new ConcurrentHashMap<>();

    /**
     * Enqueue a streaming run for the given session. Returns a Flux that emits
     * tokens as they arrive from the LLM, while ensuring sequential execution
     * per session.
     */
    public synchronized Flux<String> enqueue(Long sessionId, Supplier<Flux<String>> runSupplier) {
        Mono<Void> previous = sessionChains.getOrDefault(sessionId, Mono.empty());

        Sinks.Many<String> replaySink = Sinks.many().replay().all();

        Mono<Void> run = previous
                .onErrorComplete()
                .then(Mono.defer(() -> {
                    log.debug("Starting agent run for session={}", sessionId);
                    return runSupplier.get()
                            .doOnNext(token -> replaySink.tryEmitNext(token))
                            .doOnComplete(() -> replaySink.tryEmitComplete())
                            .doOnError(e -> replaySink.tryEmitError(e))
                            .then();
                }));

        Mono<Void> cached = run.cache();
        sessionChains.put(sessionId, cached);

        cached.subscribe();

        return replaySink.asFlux();
    }
}
