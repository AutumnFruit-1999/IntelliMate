package com.atm.intellimate.gateway.pipeline;

import com.atm.intellimate.agent.runtime.AgentEvent;
import com.atm.intellimate.agent.runtime.AgentRunRequest;
import com.atm.intellimate.agent.runtime.AgentRuntime;
import com.atm.intellimate.core.config.IntelliMateProperties;
import com.atm.intellimate.core.protocol.EventFrame;
import com.atm.intellimate.core.protocol.GatewayFrame;
import com.atm.intellimate.core.protocol.ResponseFrame;
import com.atm.intellimate.gateway.config.AgentConfigService;
import com.atm.intellimate.gateway.entity.SessionEntity;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class AgentEventMapper {

    private final AgentConfigService agentConfigService;
    private final AgentRuntime agentRuntime;
    private final IntelliMateProperties properties;
    private final AtomicLong seqGenerator = new AtomicLong(0);

    public AgentEventMapper(AgentConfigService agentConfigService,
                            AgentRuntime agentRuntime,
                            IntelliMateProperties properties) {
        this.agentConfigService = agentConfigService;
        this.agentRuntime = agentRuntime;
        this.properties = properties;
    }

    public Flux<GatewayFrame> mapAgentEvent(
            AgentEvent event,
            String requestId,
            StringBuilder fullResponse,
            SessionEntity session) {

        return switch (event) {
            case AgentEvent.TurnStart ts -> Flux.just(new EventFrame(
                    "agent.turn_start",
                    Map.of("turn", ts.turn(),
                           "maxTurns", ts.maxTurns(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.TextChunk tc -> {
                fullResponse.append(tc.text());
                yield Flux.just(new EventFrame(
                        "agent.chunk",
                        Map.of("text", tc.text(), "requestId", requestId),
                        seqGenerator.incrementAndGet()
                ));
            }

            case AgentEvent.ToolCall tc -> Flux.just(new EventFrame(
                    "agent.tool_call",
                    Map.of("toolCallId", tc.toolCallId(),
                           "name", tc.name(),
                           "description", tc.description() != null ? tc.description() : "",
                           "arguments", tc.arguments(),
                           "turn", tc.turn(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.ToolResult tr -> Flux.just(new EventFrame(
                    "agent.tool_result",
                    Map.of("toolCallId", tr.toolCallId(),
                           "name", tr.name(),
                           "result", tr.result(),
                           "success", tr.success(),
                           "turn", tr.turn(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.Done done -> {
                fullResponse.delete(0, fullResponse.length());
                fullResponse.append(done.fullText());
                yield Flux.just(new EventFrame(
                        "agent.done",
                        Map.of("text", done.fullText(),
                               "totalTurns", done.totalTurns(),
                               "requestId", requestId),
                        seqGenerator.incrementAndGet()
                ));
            }

            case AgentEvent.Error err -> Flux.just(
                    ResponseFrame.failure(requestId, err.message())
            );

            case AgentEvent.ApprovalRequired ar -> Flux.just(new EventFrame(
                    "agent.approval_required",
                    Map.of("toolCallId", ar.toolCallId(),
                           "name", ar.toolName(),
                           "arguments", ar.arguments(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.ApprovalResponse ignored -> Flux.empty();

            case AgentEvent.PlanCreated pc -> Flux.just(new EventFrame(
                    "plan.created",
                    Map.of("messageId", pc.messageId(),
                           "title", pc.title(),
                           "status", "draft",
                           "steps", pc.steps().stream()
                                   .map(s -> Map.of("index", s.index(), "title", s.title(),
                                           "description", s.description() != null ? s.description() : "",
                                           "verification", s.verification() != null ? s.verification() : ""))
                                   .toList(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.PlanStepUpdated psu -> Flux.just(new EventFrame(
                    "plan.step_updated",
                    Map.of("messageId", psu.messageId(),
                           "stepIndex", psu.stepIndex(),
                           "status", psu.status(),
                           "resultSummary", psu.resultSummary() != null ? psu.resultSummary() : "",
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.PlanStatusChanged psc -> Flux.just(new EventFrame(
                    "plan.status_changed",
                    Map.of("messageId", psc.messageId(), "status", psc.status(), "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.PlanCompleted pcomp -> Flux.just(new EventFrame(
                    "plan.completed",
                    Map.of("messageId", pcomp.messageId(),
                           "summary", pcomp.summary() != null ? pcomp.summary() : "",
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.DelegationStart ds -> Flux.just(new EventFrame(
                    "workflow.delegation_start",
                    Map.of("workerAgent", ds.workerAgentName(),
                           "task", ds.task(),
                           "delegationId", ds.delegationId(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.DelegationProgress dp -> {
                if (dp.nestedEvent() instanceof AgentEvent.TextChunk tc) {
                    yield Flux.just(new EventFrame(
                            "workflow.delegation_progress",
                            Map.of("workerAgent", dp.workerAgentName(),
                                   "delegationId", dp.delegationId(),
                                   "eventType", "chunk",
                                   "text", tc.text(),
                                   "requestId", requestId),
                            seqGenerator.incrementAndGet()));
                } else if (dp.nestedEvent() instanceof AgentEvent.ToolCall ntc) {
                    yield Flux.just(new EventFrame(
                            "workflow.delegation_progress",
                            Map.of("workerAgent", dp.workerAgentName(),
                                   "delegationId", dp.delegationId(),
                                   "eventType", "tool_call",
                                   "name", ntc.name(),
                                   "arguments", ntc.arguments(),
                                   "requestId", requestId),
                            seqGenerator.incrementAndGet()));
                } else if (dp.nestedEvent() instanceof AgentEvent.ToolResult ntr) {
                    yield Flux.just(new EventFrame(
                            "workflow.delegation_progress",
                            Map.of("workerAgent", dp.workerAgentName(),
                                   "delegationId", dp.delegationId(),
                                   "eventType", "tool_result",
                                   "name", ntr.name(),
                                   "success", ntr.success(),
                                   "requestId", requestId),
                            seqGenerator.incrementAndGet()));
                } else if (dp.nestedEvent() instanceof AgentEvent.TurnStart ts) {
                    yield Flux.just(new EventFrame(
                            "workflow.delegation_progress",
                            Map.of("workerAgent", dp.workerAgentName(),
                                   "delegationId", dp.delegationId(),
                                   "eventType", "turn_start",
                                   "turn", ts.turn(),
                                   "requestId", requestId),
                            seqGenerator.incrementAndGet()));
                } else {
                    yield Flux.empty();
                }
            }

            case AgentEvent.DelegationResult dr -> Flux.just(new EventFrame(
                    "workflow.delegation_result",
                    Map.of("workerAgent", dr.workerAgentName(),
                           "delegationId", dr.delegationId(),
                           "result", dr.result() != null ? dr.result() : "",
                           "success", dr.success(),
                           "turnsUsed", dr.turnsUsed(),
                           "durationMs", dr.durationMs(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.HandoffStart hs ->
                    handleHandoff(hs, requestId, fullResponse, session);

            case AgentEvent.ParallelStart ps -> Flux.just(new EventFrame(
                    "workflow.parallel_start",
                    Map.of("parallelGroupId", ps.parallelGroupId(),
                           "tasks", ps.tasks().stream()
                                   .map(t -> Map.of("agentName", t.agentName(), "task", t.task()))
                                   .toList(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.ParallelProgress pp -> {
                if (pp.nestedEvent() instanceof AgentEvent.TextChunk tc) {
                    yield Flux.just(new EventFrame(
                            "workflow.parallel_progress",
                            Map.of("parallelGroupId", pp.parallelGroupId(),
                                   "agentName", pp.agentName(),
                                   "eventType", "chunk",
                                   "text", tc.text(),
                                   "requestId", requestId),
                            seqGenerator.incrementAndGet()));
                } else if (pp.nestedEvent() instanceof AgentEvent.Done done) {
                    yield Flux.just(new EventFrame(
                            "workflow.parallel_progress",
                            Map.of("parallelGroupId", pp.parallelGroupId(),
                                   "agentName", pp.agentName(),
                                   "eventType", "done",
                                   "text", done.fullText(),
                                   "requestId", requestId),
                            seqGenerator.incrementAndGet()));
                } else {
                    yield Flux.empty();
                }
            }

            case AgentEvent.ParallelResult pr -> Flux.just(new EventFrame(
                    "workflow.parallel_result",
                    Map.of("parallelGroupId", pr.parallelGroupId(),
                           "results", pr.results().stream()
                                   .map(r -> Map.of("agentName", r.agentName(),
                                           "result", r.result() != null ? r.result() : "",
                                           "success", r.success(),
                                           "durationMs", r.durationMs()))
                                   .toList(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.MemorySnapshot ms -> Flux.just(new EventFrame(
                    "memory.snapshot",
                    Map.of("tokenBudget", ms.tokenBudget(),
                           "tokenUsed", ms.tokenUsed(),
                           "tokenEstimated", ms.tokenEstimated(),
                           "usageRatio", ms.usageRatio(),
                           "chunkCount", ms.chunkCount(),
                           "chunks", ms.chunks().stream()
                                   .map(c -> Map.of("id", c.id(), "type", c.type(),
                                           "category", c.category(), "importance", c.importance(),
                                           "tokens", c.tokens(), "contentPreview", c.contentPreview(),
                                           "createdAt", c.createdAt()))
                                   .toList(),
                           "requestId", requestId),
                    seqGenerator.incrementAndGet()
            ));

            case AgentEvent.ConsolidationTriggered ct -> {
                var payload = new java.util.LinkedHashMap<String, Object>();
                payload.put("chunksSelected", ct.chunksSelected());
                payload.put("tokensBefore", ct.tokensBefore());
                payload.put("tokensAfter", ct.tokensAfter());
                payload.put("extractedFacts", ct.extractedFacts());
                payload.put("candidates", ct.candidates().stream()
                        .map(c -> Map.of("type", c.type(), "tokens", c.tokens(),
                                "importance", c.importance(), "preview", c.preview()))
                        .toList());
                payload.put("factsStoredToLongTerm", ct.factsStoredToLongTerm());
                payload.put("requestId", requestId);
                yield Flux.just(new EventFrame("memory.consolidation", payload, seqGenerator.incrementAndGet()));
            }
        };
    }

    private Flux<GatewayFrame> handleHandoff(
            AgentEvent.HandoffStart hs,
            String requestId,
            StringBuilder fullResponse,
            SessionEntity session) {

        EventFrame handoffEvent = new EventFrame(
                "workflow.handoff",
                Map.of("fromAgent", hs.fromAgent(),
                       "toAgent", hs.toAgent(),
                       "reason", hs.reason(),
                       "contextSummary", hs.contextSummary(),
                       "requestId", requestId),
                seqGenerator.incrementAndGet());

        String handoffMessage = "You are taking over this conversation from agent '" + hs.fromAgent() + "'.\n"
                + "Reason: " + hs.reason() + "\n"
                + "Context summary: " + hs.contextSummary() + "\n"
                + "Continue helping the user with their request.";

        Flux<GatewayFrame> handoffExecution = agentConfigService.resolve(hs.toAgent())
                .flatMapMany(resolved -> {
                    String effectiveUserId = session.getContextId() != null && !session.getContextId().isBlank()
                            ? session.getContextId() : "default";
                    AgentRunRequest handoffRequest = new AgentRunRequest(
                            session.getId(),
                            effectiveUserId,
                            resolved.agent(),
                            handoffMessage,
                            List.of(),
                            resolved.toolsEnabled(),
                            resolved.mcpToolsEnabled(),
                            resolved.skillsEnabled(),
                            resolved.skillGroupsEnabled(),
                            null, false, null, null,
                            resolved.bridgeNode());
                    return agentRuntime.dispatch(handoffRequest)
                            .concatMap(event -> mapAgentEvent(event, requestId, fullResponse, session));
                });

        return Flux.concat(Flux.just(handoffEvent), handoffExecution);
    }
}
