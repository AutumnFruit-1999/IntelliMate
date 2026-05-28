package com.atm.intellimate.agent.runtime;

import com.atm.intellimate.core.config.IntelliMateProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Component
public class DelegationExecutor {

    private static final Logger log = LoggerFactory.getLogger(DelegationExecutor.class);

    private static final int MAX_DELEGATION_RESULT_CHARS = 32_000;

    private final ObjectMapper objectMapper;
    private final DelegationResolver delegationResolver;

    public DelegationExecutor(ObjectMapper objectMapper,
                              @Autowired(required = false) DelegationResolver delegationResolver) {
        this.objectMapper = objectMapper;
        this.delegationResolver = delegationResolver;
    }

    public Flux<AgentEvent> executeDelegationToolCall(
            AssistantMessage.ToolCall tc, String parentAgentName, Long parentSessionId,
            DelegationContext parentCtx, Map<String, String> toolCallArgs, int turn,
            Function<AgentRunRequest, Flux<AgentEvent>> dispatchFn) {

        if (delegationResolver == null) {
            AgentEvent result = new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Error: delegation is not available (DelegationResolver not configured)", false, turn);
            return Flux.just(result);
        }

        if ("delegateAgent".equals(tc.name())) {
            return executeSingleDelegation(tc, parentAgentName, parentSessionId, parentCtx, turn, dispatchFn);
        } else if ("delegateAgentsParallel".equals(tc.name())) {
            return executeParallelDelegation(tc, parentAgentName, parentSessionId, parentCtx, turn, dispatchFn);
        }
        return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                "Unknown delegation tool: " + tc.name(), false, turn));
    }

    private Flux<AgentEvent> executeSingleDelegation(
            AssistantMessage.ToolCall tc, String parentAgentName, Long parentSessionId,
            DelegationContext parentCtx, int turn,
            Function<AgentRunRequest, Flux<AgentEvent>> dispatchFn) {

        String args = tc.arguments();
        String workerName, task, context;
        try {
            JsonNode node = objectMapper.readTree(args);
            workerName = node.has("agentName") ? node.get("agentName").asText() : null;
            task = node.has("task") ? node.get("task").asText() : null;
            context = node.has("context") ? node.get("context").asText("") : "";
        } catch (Exception e) {
            return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Failed to parse delegation arguments: " + e.getMessage(), false, turn));
        }
        if (workerName == null || task == null) {
            return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Missing required parameters: agentName and task", false, turn));
        }

        DelegationContext ctx = parentCtx != null ? parentCtx : DelegationContext.root(parentSessionId, parentAgentName);
        if (!ctx.canDelegate()) {
            return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Delegation limit reached (depth=" + ctx.nestingDepth() + ", count=" + ctx.delegationCount().get() + ")", false, turn));
        }
        ctx.incrementAndGetCount();

        String delegationId = java.util.UUID.randomUUID().toString().substring(0, 8);
        DelegationContext childCtx = ctx.incrementDepth();
        String finalWorkerName = workerName;
        String finalTask = task;
        String finalContext = context;

        return Flux.defer(() -> {
            AgentEvent start = new AgentEvent.DelegationStart(finalWorkerName, finalTask, delegationId);
            long startMs = System.currentTimeMillis();

            Mono<DelegationResolver.ResolvedWorkerConfig> workerConfigMono = delegationResolver.resolveWorker(finalWorkerName)
                    .switchIfEmpty(Mono.error(new IllegalArgumentException("Worker agent not found: " + finalWorkerName)));

            Mono<Long> workerSessionMono = delegationResolver.createWorkerSession(parentSessionId, finalWorkerName, delegationId);

            return Flux.just(start).concatWith(
                    Mono.zip(workerConfigMono, workerSessionMono)
                            .flatMapMany(tuple -> {
                                DelegationResolver.ResolvedWorkerConfig workerCfg = tuple.getT1();
                                Long workerSessionId = tuple.getT2();

                                String workerMessage = buildWorkerPrompt(finalTask, finalContext);
                                IntelliMateProperties.Agent workerAgent = workerCfg.agent();
                                if (!workerCfg.canDelegate()) {
                                    workerAgent.setCanDelegate(false);
                                }

                                AgentRunRequest workerRequest = new AgentRunRequest(
                                        workerSessionId, null, workerAgent, workerMessage,
                                        List.of(),
                                        workerCfg.toolsEnabled(), workerCfg.mcpToolsEnabled(),
                                        workerCfg.skillsEnabled(), workerCfg.skillGroupsEnabled(),
                                        null, false, null, null, childCtx,
                                        workerCfg.bridgeNode());

                                StringBuilder workerResult = new StringBuilder();
                                int[] workerTurns = {0};
                                boolean[] workerSuccess = {true};

                                return dispatchFn.apply(workerRequest)
                                        .concatMap(event -> {
                                            if (event instanceof AgentEvent.Done done) {
                                                workerResult.append(done.fullText());
                                                workerTurns[0] = done.totalTurns();
                                            } else if (event instanceof AgentEvent.Error err) {
                                                workerResult.append("Error: ").append(err.message());
                                                workerSuccess[0] = false;
                                            }
                                            AgentEvent progress = new AgentEvent.DelegationProgress(
                                                    finalWorkerName, delegationId, event);
                                            return Flux.just(progress);
                                        })
                                        .concatWith(Flux.defer(() -> {
                                            long durationMs = System.currentTimeMillis() - startMs;
                                            String resultText = workerResult.toString();
                                            if (resultText.length() > MAX_DELEGATION_RESULT_CHARS) {
                                                resultText = resultText.substring(0, MAX_DELEGATION_RESULT_CHARS) + "\n...[truncated]";
                                            }
                                            AgentEvent delegationResult = new AgentEvent.DelegationResult(
                                                    finalWorkerName, delegationId, resultText,
                                                    workerSuccess[0], workerTurns[0], durationMs);
                                            AgentEvent toolResult = new AgentEvent.ToolResult(
                                                    tc.id(), tc.name(), resultText, workerSuccess[0], turn);
                                            return Flux.just(delegationResult, toolResult);
                                        }));
                            })
                            .onErrorResume(e -> {
                                long durationMs = System.currentTimeMillis() - startMs;
                                String errorMsg = "Delegation failed: " + e.getMessage();
                                log.warn("Delegation to {} failed: {}", finalWorkerName, e.getMessage(), e);
                                AgentEvent delegationResult = new AgentEvent.DelegationResult(
                                        finalWorkerName, delegationId, errorMsg, false, 0, durationMs);
                                AgentEvent toolResult = new AgentEvent.ToolResult(
                                        tc.id(), tc.name(), errorMsg, false, turn);
                                return Flux.just(delegationResult, toolResult);
                            })
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<AgentEvent> executeParallelDelegation(
            AssistantMessage.ToolCall tc, String parentAgentName, Long parentSessionId,
            DelegationContext parentCtx, int turn,
            Function<AgentRunRequest, Flux<AgentEvent>> dispatchFn) {

        List<AgentEvent.ParallelTask> taskList;
        try {
            JsonNode node = objectMapper.readTree(tc.arguments());
            JsonNode tasksNode = node.has("tasks") ? node.get("tasks") : node;
            if (tasksNode.isTextual()) {
                tasksNode = objectMapper.readTree(tasksNode.asText());
            }
            taskList = new ArrayList<>();
            for (JsonNode t : tasksNode) {
                String agent = t.has("agent") ? t.get("agent").asText() : t.has("agentName") ? t.get("agentName").asText() : null;
                String task = t.has("task") ? t.get("task").asText() : null;
                if (agent != null && task != null) {
                    taskList.add(new AgentEvent.ParallelTask(agent, task));
                }
            }
        } catch (Exception e) {
            return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Failed to parse parallel tasks: " + e.getMessage(), false, turn));
        }
        if (taskList.isEmpty()) {
            return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "No valid tasks found in the input", false, turn));
        }

        DelegationContext ctx = parentCtx != null ? parentCtx : DelegationContext.root(parentSessionId, parentAgentName);
        String groupId = java.util.UUID.randomUUID().toString().substring(0, 8);
        int maxPar = ctx.maxParallel();

        AgentEvent parallelStart = new AgentEvent.ParallelStart(groupId, List.copyOf(taskList));

        Flux<AgentEvent> parallelExec = Flux.fromIterable(taskList)
                .flatMap(pt -> {
                    if (!ctx.canDelegate()) {
                        return Flux.just((AgentEvent) new AgentEvent.ParallelProgress(groupId, pt.agentName(),
                                new AgentEvent.Error("Delegation limit reached")));
                    }
                    ctx.incrementAndGetCount();
                    String delegationId = groupId + "-" + pt.agentName();
                    DelegationContext childCtx = ctx.incrementDepth();

                    return delegationResolver.resolveWorker(pt.agentName())
                            .switchIfEmpty(Mono.error(new IllegalArgumentException("Agent not found: " + pt.agentName())))
                            .flatMap(cfg -> delegationResolver.createWorkerSession(parentSessionId, pt.agentName(), delegationId)
                                    .map(sid -> Map.entry(cfg, sid)))
                            .flatMapMany(entry -> {
                                DelegationResolver.ResolvedWorkerConfig cfg = entry.getKey();
                                Long sid = entry.getValue();
                                IntelliMateProperties.Agent wa = cfg.agent();
                                if (!cfg.canDelegate()) wa.setCanDelegate(false);

                                AgentRunRequest wr = new AgentRunRequest(
                                        sid, null, wa, buildWorkerPrompt(pt.task(), ""),
                                        List.of(), cfg.toolsEnabled(), cfg.mcpToolsEnabled(),
                                        cfg.skillsEnabled(), cfg.skillGroupsEnabled(),
                                        null, false, null, null, childCtx,
                                        cfg.bridgeNode());

                                StringBuilder result = new StringBuilder();
                                boolean[] ok = {true};
                                return dispatchFn.apply(wr)
                                        .doOnNext(e -> {
                                            if (e instanceof AgentEvent.Done d) result.append(d.fullText());
                                            else if (e instanceof AgentEvent.Error er) { result.append(er.message()); ok[0] = false; }
                                        })
                                        .map(e -> (AgentEvent) new AgentEvent.ParallelProgress(groupId, pt.agentName(), e))
                                        .concatWith(Flux.defer(() -> Flux.empty()));
                            })
                            .onErrorResume(e -> Flux.just(new AgentEvent.ParallelProgress(groupId, pt.agentName(),
                                    new AgentEvent.Error("Worker failed: " + e.getMessage()))));
                }, maxPar)
                .subscribeOn(Schedulers.boundedElastic());

        Flux<AgentEvent> toolResultEvent = Flux.defer(() -> {
            AgentEvent toolResult = new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Parallel delegation completed for " + taskList.size() + " agents", true, turn);
            return Flux.just(toolResult);
        });

        return Flux.concat(Flux.just(parallelStart), parallelExec, toolResultEvent);
    }

    public Flux<AgentEvent> executeHandoffToolCall(AssistantMessage.ToolCall tc, String fromAgent, int turn) {
        String targetAgent, reason, contextSummary;
        try {
            JsonNode node = objectMapper.readTree(tc.arguments());
            targetAgent = node.has("agentName") ? node.get("agentName").asText() : null;
            reason = node.has("reason") ? node.get("reason").asText("") : "";
            contextSummary = node.has("contextSummary") ? node.get("contextSummary").asText("") : "";
        } catch (Exception e) {
            return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Failed to parse handoff arguments: " + e.getMessage(), false, turn));
        }
        if (targetAgent == null) {
            return Flux.just(new AgentEvent.ToolResult(tc.id(), tc.name(),
                    "Missing required parameter: agentName", false, turn));
        }

        AgentEvent handoffStart = new AgentEvent.HandoffStart(fromAgent, targetAgent, reason, contextSummary);
        AgentEvent toolResult = new AgentEvent.ToolResult(tc.id(), tc.name(),
                "Handoff to " + targetAgent + " initiated", true, turn);
        return Flux.just(handoffStart, toolResult);
    }

    private static String buildWorkerPrompt(String task, String context) {
        StringBuilder sb = new StringBuilder();
        sb.append("You have been assigned the following task:\n\n");
        sb.append(task);
        if (context != null && !context.isBlank()) {
            sb.append("\n\nAdditional context:\n").append(context);
        }
        sb.append("\n\nComplete this task thoroughly and return your results.");
        return sb.toString();
    }
}
