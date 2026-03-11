import { create } from "zustand";
import type { ConnectionState } from "../lib/wsClient";
import type { ResponseFrame } from "../lib/protocol";

export interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "system";
  content: string;
  streaming: boolean;
  timestamp: number;
  requestId?: string;
}

interface ChatState {
  messages: ChatMessage[];
  connectionState: ConnectionState;
  wsSessionId: string | null;
  isWaiting: boolean;

  setConnectionState: (state: ConnectionState) => void;
  setWsSessionId: (id: string) => void;
  addUserMessage: (text: string, requestId: string) => void;
  appendChunk: (requestId: string, chunk: string) => void;
  finishStreaming: (requestId: string, fullText: string) => void;
  addResponse: (response: ResponseFrame) => void;
  addSystemMessage: (text: string) => void;
  clearMessages: () => void;
  timeoutRequest: (requestId: string) => void;
}

export const useChatStore = create<ChatState>((set, get) => ({
  messages: [],
  connectionState: "disconnected",
  wsSessionId: null,
  isWaiting: false,

  setConnectionState: (connectionState) => set({ connectionState }),

  setWsSessionId: (wsSessionId) => set({ wsSessionId }),

  addUserMessage: (text, requestId) => {
    set((state) => ({
      isWaiting: true,
      messages: [
        ...state.messages,
        {
          id: crypto.randomUUID(),
          role: "user",
          content: text,
          streaming: false,
          timestamp: Date.now(),
          requestId,
        },
        {
          id: `assistant-${requestId}`,
          role: "assistant",
          content: "",
          streaming: true,
          timestamp: Date.now(),
          requestId,
        },
      ],
    }));
  },

  appendChunk: (requestId, chunk) => {
    set((state) => ({
      messages: state.messages.map((msg) =>
        msg.id === `assistant-${requestId}` && msg.streaming
          ? { ...msg, content: msg.content + chunk }
          : msg,
      ),
    }));
  },

  finishStreaming: (requestId, fullText) => {
    set((state) => ({
      isWaiting: false,
      messages: state.messages.map((msg) =>
        msg.id === `assistant-${requestId}`
          ? { ...msg, content: fullText, streaming: false }
          : msg,
      ),
    }));
  },

  addResponse: (response) => {
    const bubbleId = `assistant-${response.id}`;
    const existing = get().messages.find((m) => m.id === bubbleId);

    if (response.ok && response.payload) {
      const text = (response.payload as Record<string, unknown>).text as string | undefined;

      if (existing && existing.streaming && !existing.content && text) {
        set((state) => ({
          isWaiting: false,
          messages: state.messages.map((msg) =>
            msg.id === bubbleId
              ? { ...msg, content: text, streaming: false }
              : msg,
          ),
        }));
        return;
      }

      if (existing && existing.streaming) {
        set((state) => ({
          isWaiting: false,
          messages: state.messages.map((msg) =>
            msg.id === bubbleId ? { ...msg, streaming: false } : msg,
          ),
        }));
        return;
      }

      set({ isWaiting: false });
      return;
    }

    if (!response.ok) {
      const errorText =
        typeof response.error === "string"
          ? response.error
          : JSON.stringify(response.error);

      if (existing && existing.streaming) {
        set((state) => ({
          isWaiting: false,
          messages: state.messages.map((msg) =>
            msg.id === bubbleId
              ? { ...msg, content: `Error: ${errorText}`, streaming: false }
              : msg,
          ),
        }));
      } else {
        set({ isWaiting: false });
      }
    }
  },

  addSystemMessage: (text) => {
    set((state) => ({
      messages: [
        ...state.messages,
        {
          id: crypto.randomUUID(),
          role: "system",
          content: text,
          streaming: false,
          timestamp: Date.now(),
        },
      ],
    }));
  },

  clearMessages: () => set({ messages: [] }),

  timeoutRequest: (requestId) => {
    const bubbleId = `assistant-${requestId}`;
    const existing = get().messages.find((m) => m.id === bubbleId);
    if (existing && existing.streaming) {
      set((state) => ({
        isWaiting: false,
        messages: state.messages.map((msg) =>
          msg.id === bubbleId
            ? { ...msg, content: "请求超时，请重试。", streaming: false }
            : msg,
        ),
      }));
    }
  },
}));
