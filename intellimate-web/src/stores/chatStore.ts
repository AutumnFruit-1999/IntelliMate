import { create } from "zustand";
import type { ConnectionState } from "../lib/wsClient";
import type { ResponseFrame } from "../lib/protocol";
import { generateId } from "../lib/protocol";
import { usePlanStore } from "./planStore";
import type { DelegationState } from "../components/workflow/DelegationCard";
import type { WorkflowEntry, HandoffInfo, ParallelGroupInfo } from "../components/workflow/WorkflowTimeline";

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

export interface StepGroupSnapshot {
  steps: Array<{ index: number; title: string; status: string; resultSummary?: string }>;
  stepToolCalls: Record<number, import("./planStore").StepToolCall[]>;
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
  stepGroupSnapshot?: StepGroupSnapshot;
  workflowEntries?: WorkflowEntry[];
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
  snapshotStepGroup: (skipStreaming?: boolean) => void;
  addDelegationStart: (requestId: string, delegationId: string, workerAgent: string, task: string) => void;
  updateDelegationProgress: (requestId: string, delegationId: string, eventType: string, payload: Record<string, unknown>) => void;
  completeDelegation: (requestId: string, delegationId: string, result: string, success: boolean, turnsUsed: number, durationMs: number) => void;
  addHandoff: (requestId: string, info: HandoffInfo) => void;
  addParallelStart: (requestId: string, groupId: string, tasks: Array<{ agentName: string; task: string }>) => void;
  updateParallelProgress: (requestId: string, groupId: string, agentName: string, eventType: string, payload: Record<string, unknown>) => void;
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
        usePlanStore.getState().clearPlan();
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

  addDelegationStart: (requestId, delegationId, workerAgent, task) => {
    set((state) => updateAgentMessages(state, (msgs) =>
      msgs.map((msg) => {
        if (msg.id !== `assistant-${requestId}`) return msg;
        const entry: WorkflowEntry = {
          type: "delegation",
          data: { delegationId, workerAgent, task, status: "running", nestedToolCalls: [], textChunks: [] },
        };
        return { ...msg, workflowEntries: [...(msg.workflowEntries ?? []), entry] };
      })
    ));
  },

  updateDelegationProgress: (requestId, delegationId, eventType, payload) => {
    set((state) => updateAgentMessages(state, (msgs) =>
      msgs.map((msg) => {
        if (msg.id !== `assistant-${requestId}` || !msg.workflowEntries) return msg;
        return {
          ...msg,
          workflowEntries: msg.workflowEntries.map((entry) => {
            if (entry.type !== "delegation" || entry.data.delegationId !== delegationId) return entry;
            const d = entry.data as DelegationState;
            if (eventType === "tool_call") {
              return {
                ...entry,
                data: {
                  ...d,
                  nestedToolCalls: [
                    ...d.nestedToolCalls,
                    { name: payload.name as string, arguments: payload.arguments as string ?? "" },
                  ],
                },
              };
            }
            if (eventType === "tool_result") {
              const toolCalls = [...d.nestedToolCalls];
              const last = toolCalls[toolCalls.length - 1];
              if (last) {
                toolCalls[toolCalls.length - 1] = { ...last, success: payload.success as boolean };
              }
              return { ...entry, data: { ...d, nestedToolCalls: toolCalls } };
            }
            return entry;
          }),
        };
      })
    ));
  },

  completeDelegation: (requestId, delegationId, result, success, turnsUsed, durationMs) => {
    set((state) => updateAgentMessages(state, (msgs) =>
      msgs.map((msg) => {
        if (msg.id !== `assistant-${requestId}` || !msg.workflowEntries) return msg;
        return {
          ...msg,
          workflowEntries: msg.workflowEntries.map((entry) => {
            if (entry.type !== "delegation" || (entry.data as DelegationState).delegationId !== delegationId) return entry;
            return {
              ...entry,
              data: {
                ...(entry.data as DelegationState),
                status: success ? "completed" : "failed",
                result,
                turnsUsed,
                durationMs,
              } as DelegationState,
            };
          }),
        };
      })
    ));
  },

  addHandoff: (requestId, info) => {
    set((state) => updateAgentMessages(state, (msgs) =>
      msgs.map((msg) => {
        if (msg.id !== `assistant-${requestId}`) return msg;
        const entry: WorkflowEntry = { type: "handoff", data: info };
        return { ...msg, workflowEntries: [...(msg.workflowEntries ?? []), entry] };
      })
    ));
  },

  addParallelStart: (requestId, groupId, tasks) => {
    set((state) => updateAgentMessages(state, (msgs) =>
      msgs.map((msg) => {
        if (msg.id !== `assistant-${requestId}`) return msg;
        const agentStates: Record<string, DelegationState> = {};
        for (const t of tasks) {
          agentStates[t.agentName] = {
            delegationId: `${groupId}-${t.agentName}`,
            workerAgent: t.agentName,
            task: t.task,
            status: "running",
            nestedToolCalls: [],
            textChunks: [],
          };
        }
        const entry: WorkflowEntry = {
          type: "parallel",
          data: { groupId, tasks, agentStates } as ParallelGroupInfo,
        };
        return { ...msg, workflowEntries: [...(msg.workflowEntries ?? []), entry] };
      })
    ));
  },

  updateParallelProgress: (requestId, groupId, agentName, eventType, payload) => {
    set((state) => updateAgentMessages(state, (msgs) =>
      msgs.map((msg) => {
        if (msg.id !== `assistant-${requestId}` || !msg.workflowEntries) return msg;
        return {
          ...msg,
          workflowEntries: msg.workflowEntries.map((entry) => {
            if (entry.type !== "parallel" || (entry.data as ParallelGroupInfo).groupId !== groupId) return entry;
            const pInfo = entry.data as ParallelGroupInfo;
            const agentState = pInfo.agentStates[agentName];
            if (!agentState) return entry;

            let updatedState = { ...agentState };
            if (eventType === "done") {
              updatedState = { ...updatedState, status: "completed", result: payload.text as string };
            } else if (eventType === "chunk") {
              updatedState = { ...updatedState, textChunks: [...updatedState.textChunks, payload.text as string] };
            }
            return {
              ...entry,
              data: {
                ...pInfo,
                agentStates: { ...pInfo.agentStates, [agentName]: updatedState },
              },
            };
          }),
        };
      })
    ));
  },

  snapshotStepGroup: (skipStreaming?: boolean) => {
    const planState = usePlanStore.getState();
    if (!planState.plan || planState.plan.steps.length === 0) return;
    const hasAnyTools = Object.values(planState.stepToolCalls).some(
      (calls) => calls.length > 0,
    );
    if (!hasAnyTools) return;

    const snapshot: StepGroupSnapshot = {
      steps: planState.plan.steps.map((s) => ({
        index: s.index,
        title: s.title,
        status: s.status,
        resultSummary: s.resultSummary,
      })),
      stepToolCalls: { ...planState.stepToolCalls },
    };

    set((state) => {
      const msgs = getAgentMessages(state);
      let targetId: string | null = null;
      for (let i = msgs.length - 1; i >= 0; i--) {
        const m = msgs[i];
        if (skipStreaming && m.streaming) continue;
        if (m.role === "assistant" && m.toolCalls && m.toolCalls.length > 0) {
          targetId = m.id;
          break;
        }
      }
      if (!targetId) return {};
      return updateAgentMessages(state, (ms) =>
        ms.map((m) =>
          m.id === targetId ? { ...m, stepGroupSnapshot: snapshot } : m,
        ),
      );
    });
  },
}));
