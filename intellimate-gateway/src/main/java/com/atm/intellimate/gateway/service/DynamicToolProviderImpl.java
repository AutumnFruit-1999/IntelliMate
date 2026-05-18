package com.atm.intellimate.gateway.service;

import com.atm.intellimate.agent.tools.ToolsEngine;
import com.atm.intellimate.agent.tools.dynamic.DynamicToolDefinition;
import com.atm.intellimate.agent.tools.dynamic.DynamicToolProvider;
import com.atm.intellimate.agent.tools.dynamic.HttpToolCallback;
import com.atm.intellimate.gateway.entity.ToolDefinitionEntity;
import com.atm.intellimate.gateway.repository.ToolDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

@Component
public class DynamicToolProviderImpl implements DynamicToolProvider {

    private static final Logger log = LoggerFactory.getLogger(DynamicToolProviderImpl.class);

    private final ToolDefinitionRepository repository;
    private final ToolsEngine toolsEngine;
    private final WebClient webClient;
    private volatile List<ToolCallback> dynamicCallbacks = List.of();

    public DynamicToolProviderImpl(ToolDefinitionRepository repository, @Lazy ToolsEngine toolsEngine) {
        this.repository = repository;
        this.toolsEngine = toolsEngine;
        this.webClient = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024 * 1024))
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        try {
            reload().block();
            toolsEngine.refresh();
            log.info("Dynamic tools loaded and ToolsEngine refreshed on startup");
        } catch (Exception e) {
            log.warn("Failed to load dynamic tools on startup (table may not exist yet): {}", e.getMessage());
        }
    }

    @Override
    public List<ToolCallback> getDynamicCallbacks() {
        return dynamicCallbacks;
    }

    @Override
    public Mono<Void> reload() {
        return repository.findAllByEnabled(1)
                .collectList()
                .doOnNext(entities -> {
                    List<ToolCallback> callbacks = new ArrayList<>();
                    for (ToolDefinitionEntity entity : entities) {
                        try {
                            if ("HTTP_API".equals(entity.getType())) {
                                DynamicToolDefinition def = toDefinition(entity);
                                callbacks.add(new HttpToolCallback(def, webClient));
                            }
                        } catch (Exception e) {
                            log.warn("Failed to load dynamic tool '{}': {}",
                                    entity.getName(), e.getMessage(), e);
                        }
                    }
                    this.dynamicCallbacks = List.copyOf(callbacks);
                    log.info("DynamicToolProvider reloaded: {} tool(s)", callbacks.size());
                })
                .then();
    }

    private DynamicToolDefinition toDefinition(ToolDefinitionEntity entity) {
        return new DynamicToolDefinition(
                entity.getId(),
                entity.getName(),
                entity.getType(),
                entity.getDescription(),
                entity.getParametersSchema(),
                entity.getExecutionConfig(),
                entity.getTimeoutSeconds() != null ? entity.getTimeoutSeconds() : 30,
                entity.getGroupName(),
                entity.getAgentName()
        );
    }
}
