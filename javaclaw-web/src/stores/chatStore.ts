import { create } from "zustand";
import type { ConnectionState } from "../lib/wsClient";
import type { ResponseFrame } from "../lib/protocol";
import { generateId } from "../lib/protocol";

export interface ToolCallInfo {
  toolCallId: string;
  name: string;
  description?: string;
  arguments: string;
  result?: string;
  success?: boolean;
  status: "calling" | "done" | "error";
  turn?: number;
}

export interface ChatMessage {
  id: string;
  role: "user" | "assistant" | "system";
  content: string;
  streaming: boolean;
  timestamp: number;
  requestId?: string;
  toolCalls?: ToolCallInfo[];
  currentTurn?: number;
  maxTurns?: number;
  totalTurns?: number;
}

interface ChatState {
  messagesByAgent: Record<string, ChatMessage[]>;
  currentAgent: string;
  connectionState: ConnectionState;
  wsSessionId: string | null;
  isWaiting: boolean;
  pendingForcePlan: { text: string } | null;

  messages: ChatMessage[];

  setCurrentAgent: (agent: string) => void;
  setConnectionState: (state: ConnectionState) => void;
  setWsSessionId: (id: string) => void;
  addUserMessage: (text: string, requestId: string) => void;
  appendChunk: (requestId: string, chunk: string) => void;
  finishStreaming: (requestId: string, fullText: string, totalTurns?: number) => void;
  addResponse: (response: ResponseFrame) => void;
  addSystemMessage: (text: string) => void;
  clearMessages: () => void;
  clearPendingForcePlan: () => void;
  timeoutRequest: (requestId: string) => void;
  setTurnStart: (requestId: string, turn: number, maxTurns: number) => void;
  addToolCall: (
    requestId: string,
    info: { toolCallId: string; name: string; description?: string; arguments: string; turn?: number },
  ) => void;
  updateToolResult: (
    requestId: string,
    toolCallId: string,
    result: string,
    success: boolean,
  ) => void;
}

function getAgentMessages(state: ChatState): ChatMessage[] {
  return state.messagesByAgent[state.currentAgent] ?? [];
}

function updateAgentMessages(
  state: ChatState,
  updater: (msgs: ChatMessage[]) => ChatMessage[],
): Partial<ChatState> {
  const agent = state.currentAgent;
  const current = state.messagesByAgent[agent] ?? [];
  const updated = updater(current);
  const newByAgent = { ...state.messagesByAgent, [agent]: updated };
  return { messagesByAgent: newByAgent, messages: updated };
}

export const useChatStore = create<ChatState>((set, get) => ({
  messagesByAgent: {},
  currentAgent: "",
  connectionState: "disconnected",
  wsSessionId: null,
  isWaiting: false,
  pendingForcePlan: null,
  messages: [],

  setCurrentAgent: (agent) => {
    const state = get();
    if (agent === state.currentAgent) return;
    set({
      currentAgent: agent,
      messages: state.messagesByAgent[agent] ?? [],
      isWaiting: false,
    });
  },

  setConnectionState: (connectionState) => set({ connectionState }),

  setWsSessionId: (wsSessionId) => set({ wsSessionId }),

  addUserMessage: (text, requestId) => {
    set((state) => ({
      isWaiting: true,
      ...updateAgentMessages(state, (msgs) => [
        ...msgs,
        {
          id: generateId(),
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
      ]),
    }));
  },

  appendChunk: (requestId, chunk) => {
    set((state) => updateAgentMessages(state, (msgs) =>
      msgs.map((msg) =>
        msg.id === `assistant-${requestId}` && msg.streaming
          ? { ...msg, content: msg.content + chunk }
          : msg,
      ),
    ));
  },

  finishStreaming: (requestId, fullText, totalTurns) => {
    set((state) => ({
      isWaiting: false,
      ...updateAgentMessages(state, (msgs) =>
        msgs.map((msg) =>
          msg.id === `assistant-${requestId}`
            ? {
                ...msg,
                content: fullText,
                streaming: false,
                ...(totalTurns != null ? { totalTurns } : {}),
              }
            : msg,
        ),
      ),
    }));
  },

  addResponse: (response) => {
    const bubbleId = `assistant-${response.id}`;
    const msgs = getAgentMessages(get());
    const existing = msgs.find((m) => m.id === bubbleId);

    if (response.ok && response.payload) {
      const payload = response.payload as Record<string, unknown>;
      const text = payload.text as string | undefined;
      const command = payload.command as string | undefined;

      if (command === "clear" || command === "reset") {
        set((state) => ({
          isWaiting: false,
          ...updateAgentMessages(state, () => []),
        }));
        return;
      }

      if (command === "plan" && payload.forcePlan) {
        const planText = (text ?? "") as string;
        set((state) => ({
          isWaiting: false,
          pendingForcePlan: { text: planText },
          ...updateAgentMessages(state, (m) =>
            m.filter((msg) => msg.id !== bubbleId),
          ),
        }));
        return;
      }

      if (existing && existing.streaming && !existing.content && text) {
        set((state) => ({
          isWaiting: false,
          ...updateAgentMessages(state, (m) =>
            m.map((msg) =>
              msg.id === bubbleId
                ? { ...msg, content: text, streaming: false }
                : msg,
            ),
          ),
        }));
        return;
      }

      if (existing && existing.streaming) {
        set((state) => ({
          isWaiting: false,
          ...updateAgentMessages(state, (m) =>
            m.map((msg) =>
              msg.id === bubbleId ? { ...msg, streaming: false } : msg,
            ),
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
          ...updateAgentMessages(state, (m) =>
            m.map((msg) =>
              msg.id === bubbleId
                ? { ...msg, content: `Error: ${errorText}`, streaming: false }
                : msg,
            ),
          ),
        }));
      } else {
        set({ isWaiting: false });
      }
    }
  },

  addSystemMessage: (text) => {
    set((state) => updateAgentMessages(state, (msgs) => [
      ...msgs,
      {
        id: generateId(),
        role: "system",
        content: text,
        streaming: false,
        timestamp: Date.now(),
      },
    ]));
  },

  clearMessages: () => set((state) => updateAgentMessages(state, () => [])),

  clearPendingForcePlan: () => set({ pendingForcePlan: null }),

  timeoutRequest: (requestId) => {
    const bubbleId = `assistant-${requestId}`;
    const msgs = getAgentMessages(get());
    const existing = msgs.find((m) => m.id === bubbleId);
    if (existing && existing.streaming) {
      set((state) => ({
        isWaiting: false,
        ...updateAgentMessages(state, (m) =>
          m.map((msg) =>
            msg.id === bubbleId
              ? { ...msg, content: "请求超时，请重试。", streaming: false }
              : msg,
          ),
        ),
      }));
    }
  },

  setTurnStart: (requestId, turn, maxTurns) => {
    set((state) => updateAgentMessages(state, (msgs) =>
      msgs.map((msg) =>
        msg.id === `assistant-${requestId}`
          ? { ...msg, currentTurn: turn, maxTurns }
          : msg,
      ),
    ));
  },

  addToolCall: (requestId, info) => {
    set((state) => updateAgentMessages(state, (msgs) =>
      msgs.map((msg) =>
        msg.id === `assistant-${requestId}`
          ? {
              ...msg,
              toolCalls: [
                ...(msg.toolCalls ?? []),
                { ...info, status: "calling" as const },
              ],
            }
          : msg,
      ),
    ));
  },

  updateToolResult: (requestId, toolCallId, result, success) => {
    set((state) => updateAgentMessages(state, (msgs) =>
      msgs.map((msg) =>
        msg.id === `assistant-${requestId}`
          ? {
              ...msg,
              toolCalls: msg.toolCalls?.map((tc) =>
                tc.toolCallId === toolCallId
                  ? {
                      ...tc,
                      result,
                      success,
                      status: (success ? "done" : "error") as ToolCallInfo["status"],
                    }
                  : tc,
              ),
            }
          : msg,
      ),
    ));
  },
}));
