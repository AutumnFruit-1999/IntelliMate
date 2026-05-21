import { useEffect, useRef, useCallback } from "react";
import { WsClient } from "../lib/wsClient";
import {
  createRequest,
  type EventFrame,
  type RequestFrame,
  type ResponseFrame,
} from "../lib/protocol";
import { useChatStore } from "../stores/chatStore";
import { useAgentStore } from "../stores/agentStore";
import { usePlanStore } from "../stores/planStore";
import { useMemoryStore } from "../stores/memoryStore";
import { useSchedulerStore } from "../stores/schedulerStore";

const WS_URL =
  import.meta.env.VITE_WS_URL ?? `ws://${window.location.host}/ws`;
const REQUEST_TIMEOUT_MS = 300_000;
const PLAN_ACTION_WAIT_MS = 30_000;
const PLAN_POLL_INTERVAL_MS = 10_000;

export function useWebSocket() {
  const clientRef = useRef<WsClient | null>(null);
  const timeoutTimers = useRef<Map<string, ReturnType<typeof setTimeout>>>(
    new Map(),
  );
  const planActionWaiters = useRef<
    Map<string, (response: ResponseFrame) => void>
  >(new Map());

  useEffect(() => {
    const token = import.meta.env.VITE_AUTH_TOKEN ?? "";

    const client = new WsClient({
      url: WS_URL,
      token,
      onStateChange: (state) => {
        useChatStore.getState().setConnectionState(state);
      },
      onEvent: (event: EventFrame) => {
        if (event.event.startsWith("plan.")) {
          console.log("[WS] received plan event:", event.event, event.payload);
        }
        const store = useChatStore.getState();
        switch (event.event) {
          case "session.welcome": {
            store.setWsSessionId(event.payload.wsSessionId as string);
            const planState = usePlanStore.getState();
            if (
              planState.plan &&
              !["completed", "cancelled", "failed"].includes(planState.plan.status)
            ) {
              planState.syncFromServer(planState.plan.planId);
            }
            // 绑定当前 agent 以接收 proactive 消息
            const agentState = useAgentStore.getState();
            const currentAgentName = agentState.activeAgent;
            if (currentAgentName && clientRef.current) {
              clientRef.current.send({
                type: "event",
                event: "agent.bind",
                payload: { agentName: currentAgentName },
                seq: 0,
              });
            }
            break;
          }
          case "agent.chunk": {
            const chunkReqId = event.payload.requestId as string;
            resetRequestTimeout(chunkReqId);
            store.appendChunk(chunkReqId, event.payload.text as string);
            break;
          }
          case "agent.done": {
            const requestId = event.payload.requestId as string;
            store.finishStreaming(
              requestId,
              event.payload.text as string,
              event.payload.totalTurns as number | undefined,
            );
            clearRequestTimeout(requestId);

            const planState = usePlanStore.getState();
            if (planState.plan && planState.plan.status === "executing") {
              const nonTerminal = planState.plan.steps.filter(
                (s) => s.status === "pending" || s.status === "in_progress",
              );
              for (const step of nonTerminal) {
                usePlanStore.getState().handleStepDone({
                  planId: planState.plan.planId,
                  stepIndex: step.index,
                  status: "completed",
                  resultSummary: "",
                });
              }
              const updated = usePlanStore.getState();
              if (updated.plan && updated.plan.status === "executing") {
                updated.handlePlanCompleted({
                  planId: updated.plan.planId,
                  status: "completed",
                });
              }
            }
            if (planState.plan &&
                !["completed", "cancelled", "failed"].includes(planState.plan.status)) {
              store.snapshotStepGroup();
            }
            break;
          }
          case "agent.proactive": {
            const agentName = event.payload.agentName as string;
            const text = event.payload.text as string;
            const requestId = event.payload.requestId as string;
            const source = (event.payload.source as string) || "unknown";
            if (!text?.trim() || !agentName?.trim()) break;

            if (store.isWaiting) {
              store.bufferProactiveMessage(agentName, text, requestId, source);
            } else {
              store.addProactiveMessage(agentName, text, requestId, source);
            }
            break;
          }
          case "agent.turn_start": {
            const turnReqId = event.payload.requestId as string;
            resetRequestTimeout(turnReqId);
            store.setTurnStart(
              turnReqId,
              event.payload.turn as number,
              event.payload.maxTurns as number,
            );
            break;
          }
          case "agent.tool_call": {
            const tcReqId = event.payload.requestId as string;
            resetRequestTimeout(tcReqId);
            const tcName = event.payload.name as string;
            const tcDesc = (event.payload.description as string) || undefined;
            store.addToolCall(tcReqId, {
              toolCallId: event.payload.toolCallId as string,
              name: tcName,
              description: tcDesc,
              arguments: event.payload.arguments as string,
              turn: event.payload.turn as number | undefined,
            });
            if (tcName !== "writePlan" && tcName !== "updatePlan") {
              const ps = usePlanStore.getState();
              if (ps.plan && (ps.plan.status === "executing" || ps.plan.status === "approved")) {
                ps.addStepToolCall({
                  toolCallId: event.payload.toolCallId as string,
                  name: tcName,
                  description: tcDesc,
                  arguments: event.payload.arguments as string,
                });
              }
            }
            break;
          }
          case "agent.tool_result": {
            const trReqId = event.payload.requestId as string;
            resetRequestTimeout(trReqId);
            const trId = event.payload.toolCallId as string;
            const trResult = event.payload.result as string;
            const trSuccess = event.payload.success as boolean;
            store.updateToolResult(
              trReqId,
              trId,
              trResult,
              trSuccess,
            );
            usePlanStore.getState().updateStepToolResult(trId, trResult, trSuccess);
            break;
          }
          case "plan.created": {
            console.log("[WS] plan.created:", event.payload);
            usePlanStore.getState().handlePlanCreated(event.payload);
            break;
          }
          case "plan.awaiting_approval": {
            console.log("[WS] plan.awaiting_approval:", event.payload);
            usePlanStore.getState().setAwaitingApproval(event.payload.planId as number);
            break;
          }
          case "plan.status_changed": {
            console.log("[WS] plan.status_changed:", event.payload);
            usePlanStore.getState().handlePlanStatusChanged(event.payload);
            const changedStatus = event.payload.status as string;
            if (changedStatus === "cancelled" || changedStatus === "failed") {
              useChatStore.getState().snapshotStepGroup();
            }
            break;
          }
          case "plan.step_start": {
            console.log("[WS] plan.step_start:", event.payload);
            usePlanStore.getState().handleStepStart(event.payload);
            break;
          }
          case "plan.step_done": {
            console.log("[WS] plan.step_done:", event.payload);
            usePlanStore.getState().handleStepDone(event.payload);
            break;
          }
          case "plan.adjusted": {
            console.log("[WS] plan.adjusted:", event.payload);
            usePlanStore.getState().handlePlanAdjusted(event.payload);
            break;
          }
          case "plan.completed": {
            console.log("[WS] plan.completed:", event.payload);
            usePlanStore.getState().handlePlanCompleted(event.payload);
            useChatStore.getState().snapshotStepGroup();
            break;
          }
          // ─── Delegation / Handoff / Parallel events ───
          case "workflow.delegation_start": {
            const reqId = event.payload.requestId as string;
            resetRequestTimeout(reqId);
            store.addDelegationStart(
              reqId,
              event.payload.delegationId as string,
              event.payload.workerAgent as string,
              event.payload.task as string,
            );
            break;
          }
          case "workflow.delegation_progress": {
            const reqId = event.payload.requestId as string;
            resetRequestTimeout(reqId);
            store.updateDelegationProgress(
              reqId,
              event.payload.delegationId as string,
              event.payload.eventType as string,
              event.payload as Record<string, unknown>,
            );
            break;
          }
          case "workflow.delegation_result": {
            const reqId = event.payload.requestId as string;
            resetRequestTimeout(reqId);
            store.completeDelegation(
              reqId,
              event.payload.delegationId as string,
              event.payload.result as string,
              event.payload.success as boolean,
              event.payload.turnsUsed as number,
              event.payload.durationMs as number,
            );
            break;
          }
          case "workflow.handoff": {
            const reqId = event.payload.requestId as string;
            resetRequestTimeout(reqId);
            store.addHandoff(reqId, {
              fromAgent: event.payload.fromAgent as string,
              toAgent: event.payload.toAgent as string,
              reason: event.payload.reason as string,
            });
            break;
          }
          case "workflow.parallel_start": {
            const reqId = event.payload.requestId as string;
            resetRequestTimeout(reqId);
            store.addParallelStart(
              reqId,
              event.payload.parallelGroupId as string,
              event.payload.tasks as Array<{ agentName: string; task: string }>,
            );
            break;
          }
          case "workflow.parallel_progress": {
            const reqId = event.payload.requestId as string;
            resetRequestTimeout(reqId);
            store.updateParallelProgress(
              reqId,
              event.payload.parallelGroupId as string,
              event.payload.agentName as string,
              event.payload.eventType as string,
              event.payload as Record<string, unknown>,
            );
            break;
          }
          case "workflow.parallel_result": {
            const reqId = event.payload.requestId as string;
            resetRequestTimeout(reqId);
            break;
          }
          case "memory.snapshot": {
            useMemoryStore.getState().handleMemorySnapshot(event.payload as any);
            break;
          }
          case "memory.consolidation": {
            useMemoryStore.getState().handleConsolidation(event.payload as any);
            break;
          }
          case "scheduler.job.started":
          case "scheduler.job.completed": {
            useSchedulerStore.getState().handleSchedulerEvent({ type: event.event, payload: event.payload as Record<string, unknown> });
            break;
          }
        }
      },
      onResponse: (response: ResponseFrame) => {
        const waiter = planActionWaiters.current.get(response.id);
        if (waiter) {
          planActionWaiters.current.delete(response.id);
          waiter(response);
        }
        useChatStore.getState().addResponse(response);
        clearRequestTimeout(response.id);
      },
    });

    client.connect();
    clientRef.current = client;

    return () => {
      client.disconnect();
      clientRef.current = null;
      timeoutTimers.current.forEach((t) => clearTimeout(t));
      timeoutTimers.current.clear();
      planActionWaiters.current.clear();
    };
  }, []);

  const pollTimerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    const stopPolling = () => {
      if (pollTimerRef.current) {
        clearInterval(pollTimerRef.current);
        pollTimerRef.current = null;
      }
    };

    const unsub = usePlanStore.subscribe((state) => {
      const plan = state.plan;
      const isExecuting = plan && plan.status === "executing";

      if (isExecuting && !pollTimerRef.current) {
        const planId = plan.planId;
        pollTimerRef.current = setInterval(() => {
          usePlanStore.getState().syncFromServer(planId);
        }, PLAN_POLL_INTERVAL_MS);
      } else if (!isExecuting) {
        stopPolling();
      }
    });

    return () => {
      unsub();
      stopPolling();
    };
  }, []);

  function clearRequestTimeout(requestId: string) {
    const timer = timeoutTimers.current.get(requestId);
    if (timer) {
      clearTimeout(timer);
      timeoutTimers.current.delete(requestId);
    }
  }

  function resetRequestTimeout(requestId: string) {
    if (!timeoutTimers.current.has(requestId)) return;
    clearRequestTimeout(requestId);
    const timer = setTimeout(() => {
      timeoutTimers.current.delete(requestId);
      useChatStore.getState().timeoutRequest(requestId);
    }, REQUEST_TIMEOUT_MS);
    timeoutTimers.current.set(requestId, timer);
  }

  const sendMessage = useCallback((text: string, forcePlan?: boolean) => {
    const client = clientRef.current;
    if (!client) return;

    const store = useChatStore.getState();
    if (store.isWaiting) return;

    const agentName = useAgentStore.getState().activeAgent ?? "";

    const req = createRequest("conversation.message", {
      text,
      channelId: "webchat",
      contextType: "dm",
      agentName,
      ...(forcePlan ? { forcePlan: true } : {}),
    });

    store.addUserMessage(text, req.id);
    client.send(req);

    const timer = setTimeout(() => {
      timeoutTimers.current.delete(req.id);
      useChatStore.getState().timeoutRequest(req.id);
    }, REQUEST_TIMEOUT_MS);
    timeoutTimers.current.set(req.id, timer);
  }, []);

  const sendPlanAction = useCallback((request: RequestFrame) => {
    const client = clientRef.current;
    if (!client) return;
    client.send(request);
  }, []);

  const sendPlanActionAndWait = useCallback((request: RequestFrame) => {
    const client = clientRef.current;
    if (!client) {
      return Promise.reject(new Error("WebSocket 未连接"));
    }
    return new Promise<ResponseFrame>((resolve, reject) => {
      const timer = setTimeout(() => {
        if (planActionWaiters.current.delete(request.id)) {
          reject(new Error("计划操作超时"));
        }
      }, PLAN_ACTION_WAIT_MS);
      planActionWaiters.current.set(request.id, (r) => {
        clearTimeout(timer);
        planActionWaiters.current.delete(request.id);
        resolve(r);
      });
      client.send(request);
    });
  }, []);

  return { sendMessage, sendPlanAction, sendPlanActionAndWait };
}
