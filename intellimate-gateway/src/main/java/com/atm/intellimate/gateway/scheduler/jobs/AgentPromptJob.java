package com.atm.intellimate.gateway.scheduler.jobs;

import com.atm.intellimate.agent.runtime.AgentEvent;
import com.atm.intellimate.agent.runtime.AgentRunRequest;
import com.atm.intellimate.agent.runtime.AgentRuntime;
import com.atm.intellimate.gateway.config.AgentConfigService;
import com.atm.intellimate.gateway.service.ChatInjectionService;
import com.atm.intellimate.gateway.scheduler.PromptTemplateRenderer;
import com.atm.intellimate.gateway.scheduler.ScheduledJob;
import com.atm.intellimate.gateway.scheduler.model.JobExecutionContext;
import com.atm.intellimate.gateway.scheduler.model.JobResult;
import com.atm.intellimate.memory.MemorySystem;
import com.atm.intellimate.memory.model.MemoryChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class AgentPromptJob implements ScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(AgentPromptJob.class);
    private static final int DEFAULT_MAX_RECALL_TOKENS = 500;

    private final AgentConfigService agentConfigService;
    private final AgentRuntime agentRuntime;
    private final PromptTemplateRenderer templateRenderer;
    private final MemorySystem memorySystem;
    private final ChatInjectionService chatInjectionService;

    public AgentPromptJob(AgentConfigService agentConfigService,
                          @Lazy AgentRuntime agentRuntime,
                          PromptTemplateRenderer templateRenderer,
                          MemorySystem memorySystem,
                          ChatInjectionService chatInjectionService) {
        this.agentConfigService = agentConfigService;
        this.agentRuntime = agentRuntime;
        this.templateRenderer = templateRenderer;
        this.memorySystem = memorySystem;
        this.chatInjectionService = chatInjectionService;
    }

    @Override
    public String getJobName() { return "agent-prompt"; }

    @Override
    public String getJobGroup() { return "agent"; }

    @Override
    public Duration getDefaultTimeout() { return Duration.ofMinutes(5); }

    @Override
    public boolean allowConcurrent() { return true; }

    @Override
    public Mono<JobResult> execute(JobExecutionContext context) {
        Map<String, Object> params = context.params();
        String agentName = (String) params.get("agentName");
        String userId = (String) params.getOrDefault("userId", "scheduler");

        String promptTemplate = (String) params.get("promptTemplate");
        String staticPrompt = (String) params.get("prompt");
        boolean enableMemoryRecall = params.containsKey("enableMemoryRecall")
                ? Boolean.parseBoolean(params.get("enableMemoryRecall").toString())
                : true;
        int maxRecallTokens = params.containsKey("maxRecallTokens")
                ? Integer.parseInt(params.get("maxRecallTokens").toString())
                : DEFAULT_MAX_RECALL_TOKENS;

        if (agentName == null || agentName.isBlank()) {
            return Mono.just(JobResult.fail("Missing 'agentName' parameter"));
        }

        String rawPrompt = promptTemplate != null ? promptTemplate : staticPrompt;
        if (rawPrompt == null || rawPrompt.isBlank()) {
            return Mono.just(JobResult.fail("Missing 'prompt' or 'promptTemplate' parameter"));
        }

        boolean isTemplate = promptTemplate != null;

        Mono<String> promptMono = isTemplate
                ? templateRenderer.render(promptTemplate, context.jobName())
                : Mono.just(rawPrompt);

        return promptMono.flatMap(renderedPrompt -> {
            Mono<List<Message>> historyMono;
            if (enableMemoryRecall && memorySystem != null) {
                historyMono = recallMemories(renderedPrompt, userId, agentName, maxRecallTokens);
            } else {
                historyMono = Mono.just(Collections.emptyList());
            }

            return historyMono.flatMap(history ->
                    agentConfigService.resolve(agentName).flatMap(resolved -> {
                        long sessionId = System.currentTimeMillis();

                        AgentRunRequest runRequest = new AgentRunRequest(
                                sessionId,
                                userId,
                                resolved.agent(),
                                renderedPrompt,
                                history,
                                resolved.toolsEnabled(),
                                resolved.mcpToolsEnabled(),
                                resolved.skillsEnabled(),
                                resolved.skillGroupsEnabled(),
                                null,
                                false, null, null,
                                resolved.bridgeNode()
                        );

                        AtomicReference<String> responseText = new AtomicReference<>("");
                        StringBuilder lastTurnText = new StringBuilder();
                        StringBuilder allText = new StringBuilder();

                        return agentRuntime.dispatch(runRequest)
                                .doOnNext(event -> {
                                    if (event instanceof AgentEvent.TurnStart) {
                                        lastTurnText.setLength(0);
                                    } else if (event instanceof AgentEvent.TextChunk chunk) {
                                        lastTurnText.append(chunk.text());
                                        allText.append(chunk.text());
                                    } else if (event instanceof AgentEvent.Done done) {
                                        String finalAnswer = lastTurnText.toString();
                                        if (finalAnswer.isBlank() && !allText.toString().isBlank()) {
                                            finalAnswer = done.fullText();
                                        }
                                        responseText.set(finalAnswer);
                                    }
                                })
                                .doOnError(e -> log.error("AgentPromptJob [{}]: dispatch flux error for agent '{}': {}",
                                        context.jobName(), agentName, e.getMessage()))
                                .then(Mono.fromSupplier(responseText::get))
                                .flatMap(response -> chatInjectionService
                                        .injectAgentMessage(agentName, response,
                                                ChatInjectionService.ProactiveSource.SCHEDULED_JOB)
                                        .onErrorResume(e -> {
                                            log.warn("AgentPromptJob [{}]: failed to inject chat message for agent '{}': {}",
                                                    context.jobName(), agentName, e.getMessage());
                                            return Mono.just(0);
                                        })
                                        .thenReturn(response))
                                .map(response -> {
                                    String summary = response.length() > 200
                                            ? response.substring(0, 200) + "..."
                                            : response;

                                    return JobResult.ok("Agent responded (" + response.length() + " chars)", Map.of(
                                            "agentName", agentName,
                                            "responseLength", response.length(),
                                            "responseSummary", summary,
                                            "templateMode", isTemplate,
                                            "memoriesInjected", history.size()
                                    ));
                                });
                    })
            );
        }).onErrorResume(e -> {
            log.error("AgentPromptJob [{}] failed for agent '{}': {}", context.jobName(), agentName, e.getMessage());
            return Mono.just(JobResult.fail("Agent execution failed: " + e.getMessage()));
        });
    }

    private Mono<List<Message>> recallMemories(String cue, String userId, String agentId, int maxTokens) {
        return memorySystem.getMemoryRetrieval()
                .retrieve(cue, userId, agentId, maxTokens, 0.01)
                .map(chunks -> {
                    if (chunks.isEmpty()) return Collections.<Message>emptyList();
                    List<Message> messages = new ArrayList<>();
                    StringBuilder sb = new StringBuilder("[历史记忆参考]\n");
                    for (MemoryChunk chunk : chunks) {
                        sb.append("- ").append(chunk.content()).append("\n");
                    }
                    messages.add(new AssistantMessage(sb.toString()));
                    return messages;
                })
                .onErrorResume(e -> {
                    log.warn("Memory recall failed, continuing without: {}", e.getMessage());
                    return Mono.just(Collections.emptyList());
                });
    }
}
