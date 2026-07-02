import { useEffect, useRef, useCallback } from "react";
import { WsClient, type ReconnectMeta } from "../lib/wsClient";
import {
  createRequest,
  createConversationCancel,
  type EventFrame,
  type RequestFrame,
  type ResponseFrame,
} from "../lib/protocol";
import { useChatStore } from "../stores/chatStore";
import { useAgentStore } from "../stores/agentStore";
import { usePlanStore, type PlanData, type PlanStepData } from "../stores/planStore";
import { useMemoryStore } from "../stores/memoryStore";
import { useSchedulerStore } from "../stores/schedulerStore";
import { useNotification } from "./useNotification";

const WS_URL =
  import.meta.env.VITE_WS_URL ?? `ws://${window.location.host}/ws`;
const REQUEST_TIMEOUT_MS = 300_000;
const PLAN_ACTION_WAIT_MS = 30_000;

function planPayload<T>(payload: Record<string, unknown>): T {
  return payload as T;
}

export function useWebSocket() {
  const { requestPermission, notify } = useNotification();
  const clientRef = useRef<WsClient | null>(null);
  const sendMessageRef = useRef<(text: string, forcePlan?: boolean, regenerate?: boolean) => void>(() => {});
  const timeoutTimers = useRef<Map<string, ReturnType<typeof setTimeout>>>(
    new Map(),
  );
  const planActionWaiters = useRef<
    Map<string, (response: ResponseFrame) => void>
  >(new Map());

  useEffect(() => {
    const token = localStorage.getItem("auth_token") ?? import.meta.env.VITE_AUTH_TOKEN ?? "";

    const client = new WsClient({
      url: WS_URL,
      token,
      onStateChange: (state, meta?: ReconnectMeta) => {
        const store = useChatStore.getState();
        store.setConnectionState(state);
        if (state === "reconnecting" && meta) {
          store.setReconnectMeta(meta.attempt, meta.nextRetryMs);
        } else if (state === "connected") {
          store.clearReconnectMeta();
        }
      },
      onEvent: (event: EventFrame) => {
        if (event.event.startsWith("plan.")) {
          console.log("[WS] received plan event:", event.event, event.payload);
        }
        const store = useChatStore.getState();
        switch (event.event) {
          case "session.welcome": {
            store.setWsSessionId(event.payload.wsSessionId as string);
            // 绑定当前 agent 以接收 proactive 消息
            const agentState = useAgentStore.getState();
            const currentAgentName = agentState.activeAgent;
            console.log("[WS] session.welcome received, activeAgent:", currentAgentName);
            if (currentAgentName && clientRef.current) {
              clientRef.current.send({
                type: "event",
                event: "agent.bind",
                payload: { agentName: currentAgentName },
                seq: 0,
              });
              console.log("[WS] agent.bind sent for:", currentAgentName);
            }
            // 加载消息历史
            const chatState = useChatStore.getState();
            if (currentAgentName && !chatState.historyLoaded) {
              chatState.loadHistoryFromServer(currentAgentName);
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
            requestPermission();

            if (document.hidden) {
              const msgs = useChatStore.getState().messages;
              let lastAssistantContent = "";
              for (let i = msgs.length - 1; i >= 0; i--) {
                if (msgs[i].role === "assistant") {
                  lastAssistantContent = msgs[i].content;
                  break;
                }
              }
              const agentName = useAgentStore.getState().activeAgent ?? "Agent";
              notify(
                `${agentName} 回复了你`,
                lastAssistantContent?.slice(0, 50) || "新回复",
              );
            }

            const queued = useChatStore.getState().queuedMessage;
            if (queued) {
              useChatStore.getState().setQueuedMessage(null);
              setTimeout(() => sendMessageRef.current(queued), 100);
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
          case "message.sync": {
            const content = event.payload.content as string;
            const role = event.payload.role as "user" | "assistant";
            const sourceChannel = (event.payload.sourceChannel as string) || "external";
            if (!content?.trim()) break;
            store.addSyncMessage(role, content, sourceChannel);
            break;
          }
          case "binding.success": {
            const channelId = event.payload.channelId as string;
            const externalName = event.payload.externalName as string;
            const boundAt = event.payload.boundAt as string;
            window.dispatchEvent(
              new CustomEvent("binding-success", {
                detail: { channelId, externalName, boundAt },
              }),
            );
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
            store.setActivityTool(tcName, tcDesc || null);
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
            store.clearActivityTool();
            break;
          }
          case "plan.created": {
            console.log("[WS] plan.created:", event.payload);
            const created = planPayload<{
              messageId: number;
              title: string;
              status: string;
              steps: PlanStepData[];
            }>(event.payload);
            usePlanStore.getState().handlePlanCreated(created);
            useChatStore.getState().addPlanMessage(created);
            break;
          }
          case "plan.step_updated": {
            console.log("[WS] plan.step_updated:", event.payload);
            const stepData = planPayload<{
              messageId: number;
              stepIndex: number;
              status: string;
              resultSummary?: string;
            }>(event.payload);
            usePlanStore.getState().handleStepUpdated(stepData);
            useChatStore.getState().updateMessageMetadata(stepData.messageId, (metadata) => {
              const plan = metadata.plan as PlanData | undefined;
              if (!plan) return metadata;
              return {
                ...metadata,
                plan: {
                  ...plan,
                  steps: plan.steps.map((s) =>
                    s.index === stepData.stepIndex
                      ? {
                          ...s,
                          status: stepData.status,
                          resultSummary: stepData.resultSummary ?? s.resultSummary,
                        }
                      : s,
                  ),
                },
              };
            });
            break;
          }
          case "plan.status_changed": {
            console.log("[WS] plan.status_changed:", event.payload);
            const statusData = planPayload<{ messageId: number; status: string }>(event.payload);
            usePlanStore.getState().handleStatusChanged(statusData);
            useChatStore.getState().updateMessageMetadata(statusData.messageId, (metadata) => {
              const plan = metadata.plan as PlanData | undefined;
              if (!plan) return metadata;
              return {
                ...metadata,
                plan: { ...plan, status: statusData.status },
              };
            });
            break;
          }
          case "plan.completed": {
            console.log("[WS] plan.completed:", event.payload);
            const completed = planPayload<{ messageId: number; summary?: string }>(event.payload);
            usePlanStore.getState().handlePlanCompleted(completed);
            useChatStore.getState().updateMessageMetadata(completed.messageId, (metadata) => {
              const plan = metadata.plan as PlanData | undefined;
              if (!plan) return metadata;
              return {
                ...metadata,
                plan: {
                  ...plan,
                  status: "completed",
                  completionSummary: completed.summary ?? plan.completionSummary,
                },
              };
            });
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

  const activeAgent = useAgentStore((s) => s.activeAgent);

  useEffect(() => {
    console.log("[WS] activeAgent changed:", activeAgent, "client:", !!clientRef.current);
    if (activeAgent && clientRef.current) {
      clientRef.current.send({
        type: "event",
        event: "agent.bind",
        payload: { agentName: activeAgent },
        seq: 0,
      });
      console.log("[WS] agent.bind sent via useEffect for:", activeAgent);

      const chatState = useChatStore.getState();
      if (!chatState.historyLoaded && !chatState.loadingHistory) {
        chatState.loadHistoryFromServer(activeAgent);
      }
    }
  }, [activeAgent]);

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

  const sendMessage = useCallback((text: string, forcePlan?: boolean, regenerate?: boolean) => {
    if (text.trim() === "/clear") {
      const agentName = useAgentStore.getState().activeAgent;
      if (!agentName) return;
      import("../lib/sessionApi").then(({ clearSession }) => {
        clearSession(agentName)
          .then(() => {
            const store = useChatStore.getState();
            store.clearMessages();
            store.setHistoryLoaded(true);
            import("../stores/memoryStore").then(({ useMemoryStore }) => {
              useMemoryStore.setState({ workingMemory: null, consolidationLog: [] });
            });
          })
          .catch((err) => {
            console.error("[/clear] Failed:", err);
          });
      });
      return;
    }

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
      ...(regenerate ? { regenerate: true } : {}),
    });

    store.addUserMessage(text, req.id, regenerate);
    client.send(req);

    const timer = setTimeout(() => {
      timeoutTimers.current.delete(req.id);
      useChatStore.getState().timeoutRequest(req.id);
    }, REQUEST_TIMEOUT_MS);
    timeoutTimers.current.set(req.id, timer);
  }, []);

  sendMessageRef.current = sendMessage;

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

  const cancelRequest = useCallback(() => {
    const client = clientRef.current;
    if (!client) return;

    const store = useChatStore.getState();
    const agentName = useAgentStore.getState().activeAgent ?? "";
    const msgs = store.messagesByAgent[agentName] ?? [];
    const waitingMsg = msgs.find((m) => m.streaming);
    if (!waitingMsg) return;

    const requestId =
      typeof waitingMsg.id === "string"
        ? waitingMsg.id.replace("assistant-", "")
        : String(waitingMsg.id);
    const req = createConversationCancel(requestId);
    client.send(req);

    store.setActivityPhase("cancelled");
    setTimeout(() => {
      store.resetActivity();
      store.finishStreaming(requestId, waitingMsg.content + "\n\n[已取消]", undefined);
    }, 500);
  }, []);

  return { sendMessage, sendPlanAction, sendPlanActionAndWait, cancelRequest };
}
