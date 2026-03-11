import { useEffect, useRef, useCallback } from "react";
import { WsClient } from "../lib/wsClient";
import { createRequest, type EventFrame, type ResponseFrame } from "../lib/protocol";
import { useChatStore } from "../stores/chatStore";
import { useAgentStore } from "../stores/agentStore";

const WS_URL =
  import.meta.env.VITE_WS_URL ?? `ws://${window.location.host}/ws`;
const REQUEST_TIMEOUT_MS = 60_000;

export function useWebSocket() {
  const clientRef = useRef<WsClient | null>(null);
  const timeoutTimers = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  useEffect(() => {
    const token = import.meta.env.VITE_AUTH_TOKEN ?? "";

    const client = new WsClient({
      url: WS_URL,
      token,
      onStateChange: (state) => {
        useChatStore.getState().setConnectionState(state);
      },
      onEvent: (event: EventFrame) => {
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
            store.finishStreaming(requestId, event.payload.text as string);
            clearRequestTimeout(requestId);
            break;
          }
        }
      },
      onResponse: (response: ResponseFrame) => {
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
    };
  }, []);

  function clearRequestTimeout(requestId: string) {
    const timer = timeoutTimers.current.get(requestId);
    if (timer) {
      clearTimeout(timer);
      timeoutTimers.current.delete(requestId);
    }
  }

  const sendMessage = useCallback((text: string) => {
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
    });

    store.addUserMessage(text, req.id);
    client.send(req);

    const timer = setTimeout(() => {
      timeoutTimers.current.delete(req.id);
      useChatStore.getState().timeoutRequest(req.id);
    }, REQUEST_TIMEOUT_MS);
    timeoutTimers.current.set(req.id, timer);
  }, []);

  return { sendMessage };
}
