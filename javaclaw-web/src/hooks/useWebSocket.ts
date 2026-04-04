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

const WS_URL =
  import.meta.env.VITE_WS_URL ?? `ws://${window.location.host}/ws`;
const REQUEST_TIMEOUT_MS = 300_000;
const PLAN_ACTION_WAIT_MS = 30_000;

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
            break;
          }
          case "agent.chunk": {
            store.appendChunk(
              event.payload.requestId as string,
              event.payload.text as string,
            );
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
            break;
          }
          case "agent.turn_start": {
            store.setTurnStart(
              event.payload.requestId as string,
              event.payload.turn as number,
              event.payload.maxTurns as number,
            );
            break;
          }
          case "agent.tool_call": {
            const tcName = event.payload.name as string;
            store.addToolCall(event.payload.requestId as string, {
              toolCallId: event.payload.toolCallId as string,
              name: tcName,
              arguments: event.payload.arguments as string,
              turn: event.payload.turn as number | undefined,
            });
            if (tcName !== "writePlan" && tcName !== "updatePlan") {
              usePlanStore.getState().addStepToolCall({
                toolCallId: event.payload.toolCallId as string,
                name: tcName,
                arguments: event.payload.arguments as string,
              });
            }
            break;
          }
          case "agent.tool_result": {
            const trId = event.payload.toolCallId as string;
            const trResult = event.payload.result as string;
            const trSuccess = event.payload.success as boolean;
            store.updateToolResult(
              event.payload.requestId as string,
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

  function clearRequestTimeout(requestId: string) {
    const timer = timeoutTimers.current.get(requestId);
    if (timer) {
      clearTimeout(timer);
      timeoutTimers.current.delete(requestId);
    }
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
