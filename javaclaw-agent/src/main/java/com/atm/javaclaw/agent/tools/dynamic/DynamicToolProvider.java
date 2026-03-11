package com.atm.javaclaw.agent.tools.dynamic;

import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * SPI for providing dynamically-defined tools (from DB).
 * The implementation lives in javaclaw-gateway where DB access is available.
 */
public interface DynamicToolProvider {

    List<ToolCallback> getDynamicCallbacks();

    Mono<Void> reload();
}
