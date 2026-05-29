import { create } from "zustand";
import type { ConnectionState } from "../lib/wsClient";
import type { ResponseFrame } from "../lib/protocol";
import { generateId } from "../lib/protocol";
import { usePlanStore } from "./planStore";
import { useAgentStore } from "./agentStore";
import { useMemoryStore } from "./memoryStore";
import { toFriendlyError } from "../lib/errorMessages";
import type { DelegationState } from "../components/workflow/DelegationCard";
import type { WorkflowEntry, HandoffInfo, ParallelGroupInfo } from "../components/workflow/WorkflowTimeline";

export type ActivityPhase = "idle" | "waiting" | "thinking" | "streaming" | "tool_calling" | "cancelled";

export interface ActivityState {
  phase: ActivityPhase;
  modelName: string | null;
  currentTool: string | null;
  currentToolDescription: string | null;
  turn: number;
  maxTurns: number;
  startedAt: number | null;
}

export interface ToolCallInfo {
  toolCallId: string;
  name: string;
  description?: string;
  arguments: string;
  result?: string;
  success?: boolean;
  status: "calling" | "done" | "error";
  turn?: number;
  startTime?: number;
  duration?: number;
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
  error?: import("../lib/errorMessages").FriendlyError;
  sourceChannel?: string;
}

interface ChatState {
  messagesByAgent: Record<string, ChatMessage[]>;
  currentAgent: string;
  connectionState: ConnectionState;
  wsSessionId: string | null;
  isWaiting: boolean;
  queuedMessage: string | null;
  pendingForcePlan: { text: string } | null;

  messages: ChatMessage[];

  historyLoaded: boolean;
  historyHasMore: boolean;
  loadingHistory: boolean;

  setCurrentAgent: (agent: string) => void;
  reconnectAttempt: number;
  reconnectCountdown: number;
  setReconnectMeta: (attempt: number, nextRetryMs: number) => void;
  tickReconnectCountdown: () => void;
  clearReconnectMeta: () => void;

  setConnectionState: (state: ConnectionState) => void;
  setWsSessionId: (id: string) => void;
  setQueuedMessage: (msg: string | null) => void;
  addUserMessage: (text: string, requestId: string, regenerate?: boolean) => void;
  removeLastAssistantMessage: () => void;
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
  proactiveBuffer: Array<{ agentName: string; text: string; requestId: string; source: string }>;
  addProactiveMessage: (agentName: string, text: string, requestId: string, source: string) => void;
  bufferProactiveMessage: (agentName: string, text: string, requestId: string, source: string) => void;
  flushProactiveBuffer: () => void;
  addSyncMessage: (role: "user" | "assistant", content: string, sourceChannel: string) => void;

  activity: ActivityState;
  setActivityPhase: (phase: ActivityPhase) => void;
  setActivityTool: (name: string, description: string | null) => void;
  clearActivityTool: () => void;
  resetActivity: () => void;

  loadHistoryFromServer: (agentName: string) => Promise<void>;
  prependHistory: (messages: ChatMessage[]) => void;
  loadMoreHistory: (agentName: string) => Promise<void>;
  setHistoryLoaded: (loaded: boolean) => void;
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
  queuedMessage: null,
  reconnectAttempt: 0,
  reconnectCountdown: 0,
  pendingForcePlan: null,
  messages: [],
  historyLoaded: false,
  historyHasMore: false,
  loadingHistory: false,
  proactiveBuffer: [],
  activity: {
    phase: "idle",
    modelName: null,
    currentTool: null,
    currentToolDescription: null,
    turn: 0,
    maxTurns: 0,
    startedAt: null,
  },

  setCurrentAgent: (agent) => {
    const state = get();
    if (agent === state.currentAgent) return;
    set({
      currentAgent: agent,
      messages: state.messagesByAgent[agent] ?? [],
      isWaiting: false,
      historyLoaded: false,
    });
  },

  setReconnectMeta: (attempt, nextRetryMs) => {
    set({ reconnectAttempt: attempt, reconnectCountdown: Math.ceil(nextRetryMs / 1000) });
  },

  tickReconnectCountdown: () => {
    set((state) => ({
      reconnectCountdown: Math.max(0, state.reconnectCountdown - 1),
    }));
  },

  clearReconnectMeta: () => {
    set({ reconnectAttempt: 0, reconnectCountdown: 0 });
  },

  setConnectionState: (connectionState) => set({ connectionState }),

  setWsSessionId: (wsSessionId) => set({ wsSessionId }),

  setQueuedMessage: (msg) => set({ queuedMessage: msg }),

  addUserMessage: (text, requestId, regenerate) => {
    const agentState = useAgentStore.getState();
    const activeAgent = agentState.activeAgent;
    const agentInfo = agentState.agents.find((a) => a.name === activeAgent);
    const modelName = agentInfo?.modelDisplayName || agentInfo?.model || null;

    set((state) => ({
      isWaiting: true,
      activity: {
        phase: "waiting",
        modelName,
        currentTool: null,
        currentToolDescription: null,
        turn: 0,
        maxTurns: 0,
        startedAt: Date.now(),
      },
      ...updateAgentMessages(state, (msgs) => {
        const next: ChatMessage[] = [...msgs];
        if (!regenerate) {
          next.push({
            id: generateId(),
            role: "user",
            content: text,
            streaming: false,
            timestamp: Date.now(),
            requestId,
          });
        }
        next.push({
          id: `assistant-${requestId}`,
          role: "assistant",
          content: "",
          streaming: true,
          timestamp: Date.now(),
          requestId,
        });
        return next;
      }),
    }));
  },

  removeLastAssistantMessage: () => {
    const agent = get().currentAgent;
    const msgs = [...(get().messagesByAgent[agent] ?? [])];
    let lastIdx = -1;
    for (let i = msgs.length - 1; i >= 0; i--) {
      if (msgs[i].role === "assistant") {
        lastIdx = i;
        break;
      }
    }
    if (lastIdx >= 0) msgs.splice(lastIdx, 1);
    set({
      messagesByAgent: { ...get().messagesByAgent, [agent]: msgs },
      messages: msgs,
      isWaiting: false,
    });
  },

  appendChunk: (requestId, chunk) => {
    set((state) => {
      const newActivity = state.activity.phase === "thinking" || state.activity.phase === "waiting" || state.activity.phase === "tool_calling"
        ? { ...state.activity, phase: "streaming" as ActivityPhase, currentTool: null, currentToolDescription: null }
        : state.activity;
      return {
        activity: newActivity,
        ...updateAgentMessages(state, (msgs) =>
          msgs.map((msg) =>
            msg.id === `assistant-${requestId}` && msg.streaming
              ? { ...msg, content: msg.content + chunk }
              : msg,
          ),
        ),
      };
    });
  },

  finishStreaming: (requestId, fullText, totalTurns) => {
    set((state) => ({
      isWaiting: false,
      activity: {
        phase: "idle",
        modelName: null,
        currentTool: null,
        currentToolDescription: null,
        turn: 0,
        maxTurns: 0,
        startedAt: null,
      },
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
    setTimeout(() => get().flushProactiveBuffer(), 0);
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
        useMemoryStore.setState({ workingMemory: null, consolidationLog: [] });
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
        const friendlyError = toFriendlyError(errorText);
        set((state) => ({
          isWaiting: false,
          ...updateAgentMessages(state, (m) =>
            m.map((msg) =>
              msg.id === bubbleId
                ? { ...msg, content: "", error: friendlyError, streaming: false }
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
    set((state) => ({
      activity: {
        ...state.activity,
        phase: "thinking",
        turn,
        maxTurns,
      },
      ...updateAgentMessages(state, (msgs) =>
        msgs.map((msg) =>
          msg.id === `assistant-${requestId}`
            ? { ...msg, currentTurn: turn, maxTurns }
            : msg,
        ),
      ),
    }));
  },

  addToolCall: (requestId, info) => {
    set((state) => updateAgentMessages(state, (msgs) =>
      msgs.map((msg) =>
        msg.id === `assistant-${requestId}`
          ? {
              ...msg,
              toolCalls: [
                ...(msg.toolCalls ?? []),
                { ...info, status: "calling" as const, startTime: Date.now() },
              ],
            }
          : msg,
      ),
    ));
  },

  updateToolResult: (requestId, toolCallId, result, success) => {
    const now = Date.now();
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
                      duration: tc.startTime ? now - tc.startTime : undefined,
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

  addProactiveMessage: (agentName, text, requestId, source) => {
    const agent = agentName || get().currentAgent;
    const current = get().messagesByAgent[agent] ?? [];
    const newMsg: ChatMessage = {
      id: `assistant-${requestId}`,
      role: "assistant",
      content: text,
      streaming: false,
      timestamp: Date.now(),
      requestId,
    };
    const newByAgent = { ...get().messagesByAgent, [agent]: [...current, newMsg] };

    if (agent === get().currentAgent) {
      set({ messagesByAgent: newByAgent, messages: [...current, newMsg] });
    } else {
      set({ messagesByAgent: newByAgent });
    }
  },

  bufferProactiveMessage: (agentName, text, requestId, source) => {
    set((state) => ({
      proactiveBuffer: [...state.proactiveBuffer, { agentName, text, requestId, source }],
    }));
  },

  flushProactiveBuffer: () => {
    const buffer = get().proactiveBuffer;
    if (buffer.length === 0) return;
    for (const msg of buffer) {
      get().addProactiveMessage(msg.agentName, msg.text, msg.requestId, msg.source);
    }
    set({ proactiveBuffer: [] });
  },

  addSyncMessage: (role, content, sourceChannel) => {
    const agent = get().currentAgent;
    const current = get().messagesByAgent[agent] ?? [];
    const newMsg: ChatMessage = {
      id: `sync-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      role,
      content,
      streaming: false,
      timestamp: Date.now(),
      sourceChannel,
    };
    const updated = [...current, newMsg];
    const newByAgent = { ...get().messagesByAgent, [agent]: updated };
    if (agent === get().currentAgent) {
      set({ messagesByAgent: newByAgent, messages: updated });
    } else {
      set({ messagesByAgent: newByAgent });
    }
  },

  setActivityPhase: (phase) => {
    set((state) => ({
      activity: {
        ...state.activity,
        phase,
        ...(phase === "waiting" ? { startedAt: Date.now(), turn: 0, maxTurns: 0, currentTool: null, currentToolDescription: null } : {}),
      },
    }));
  },

  setActivityTool: (name, description) => {
    set((state) => ({
      activity: {
        ...state.activity,
        phase: "tool_calling",
        currentTool: name,
        currentToolDescription: description,
      },
    }));
  },

  clearActivityTool: () => {
    set((state) => ({
      activity: {
        ...state.activity,
        phase: state.activity.phase === "tool_calling" ? "thinking" : state.activity.phase,
        currentTool: null,
        currentToolDescription: null,
      },
    }));
  },

  resetActivity: () => {
    set({
      activity: {
        phase: "idle",
        modelName: null,
        currentTool: null,
        currentToolDescription: null,
        turn: 0,
        maxTurns: 0,
        startedAt: null,
      },
    });
  },

  loadHistoryFromServer: async (agentName: string) => {
    const { fetchActiveMessages } = await import("../lib/sessionApi");
    set({ loadingHistory: true });
    try {
      const resp = await fetchActiveMessages(agentName, 50);
      const messages: ChatMessage[] = resp.messages
        .filter((m) => m.role === "user" || m.role === "assistant")
        .map((m) => ({
          id: `hist-${m.id}`,
          role: m.role as "user" | "assistant",
          content: m.content ?? "",
          streaming: false,
          timestamp: new Date(m.createdAt).getTime(),
          sourceChannel: m.sourceChannel,
        }));
      const current = get().currentAgent;
      if (current === agentName) {
        const existing = get().messagesByAgent[agentName] ?? [];
        if (existing.length === 0) {
          set({
            messagesByAgent: { ...get().messagesByAgent, [agentName]: messages },
            messages: messages,
            historyLoaded: true,
            historyHasMore: resp.hasMore,
            loadingHistory: false,
          });
        } else {
          set({ historyLoaded: true, historyHasMore: resp.hasMore, loadingHistory: false });
        }
      }
    } catch (e) {
      console.error("[chatStore] Failed to load history:", e);
      set({ loadingHistory: false });
    }
  },

  prependHistory: (messages: ChatMessage[]) => {
    const agent = get().currentAgent;
    const existing = get().messagesByAgent[agent] ?? [];
    const merged = [...messages, ...existing];
    set({
      messagesByAgent: { ...get().messagesByAgent, [agent]: merged },
      messages: merged,
    });
  },

  loadMoreHistory: async (agentName: string) => {
    const { fetchActiveMessages } = await import("../lib/sessionApi");
    const currentMessages = get().messagesByAgent[agentName] ?? [];
    if (currentMessages.length === 0) return;
    const firstMsg = currentMessages[0];
    const beforeId = firstMsg.id.startsWith("hist-")
      ? parseInt(firstMsg.id.replace("hist-", ""), 10)
      : undefined;
    if (!beforeId) return;
    set({ loadingHistory: true });
    try {
      const resp = await fetchActiveMessages(agentName, 50, beforeId);
      const messages: ChatMessage[] = resp.messages
        .filter((m) => m.role === "user" || m.role === "assistant")
        .map((m) => ({
          id: `hist-${m.id}`,
          role: m.role as "user" | "assistant",
          content: m.content ?? "",
          streaming: false,
          timestamp: new Date(m.createdAt).getTime(),
          sourceChannel: m.sourceChannel,
        }));
      get().prependHistory(messages);
      set({ historyHasMore: resp.hasMore, loadingHistory: false });
    } catch (e) {
      console.error("[chatStore] Failed to load more history:", e);
      set({ loadingHistory: false });
    }
  },

  setHistoryLoaded: (loaded: boolean) => set({ historyLoaded: loaded }),
}));
