import { create } from "zustand";
import { fetchPlan } from "../lib/api";
import { useChatStore } from "./chatStore";

export type PlanStepStatus =
  | "pending"
  | "in_progress"
  | "completed"
  | "failed"
  | "skipped";
export type PlanStatus =
  | "draft"
  | "approved"
  | "executing"
  | "paused"
  | "completed"
  | "failed"
  | "cancelled";

export interface PlanStep {
  index: number;
  title: string;
  description: string;
  status: PlanStepStatus;
  resultSummary?: string;
}

export interface Plan {
  planId: number;
  title: string;
  status: PlanStatus;
  steps: PlanStep[];
}

export interface StepToolCall {
  toolCallId: string;
  name: string;
  description?: string;
  arguments: string;
  result?: string;
  success?: boolean;
  status: "calling" | "done" | "error";
}

interface PendingToolCall {
  toolCallId: string;
  name: string;
  description?: string;
  arguments: string;
  result?: string;
  success?: boolean;
  status?: StepToolCall["status"];
}

function matchesActivePlan(
  plan: Plan | null,
  payload: Record<string, unknown>,
): boolean {
  if (!plan) return false;
  const pid = payload.planId;
  return typeof pid === "number" && pid === plan.planId;
}

interface PlanState {
  plan: Plan | null;
  stepToolCalls: Record<number, StepToolCall[]>;
  currentStepIndex: number | null;
  pendingToolCalls: PendingToolCall[];
  planHistory: Plan[];
  currentPlanIndex: number;
  dismissed: boolean;

  handlePlanCreated(payload: Record<string, unknown>): void;
  handleStepStart(payload: Record<string, unknown>): void;
  handleStepDone(payload: Record<string, unknown>): void;
  handlePlanAdjusted(payload: Record<string, unknown>): void;
  handlePlanStatusChanged(payload: Record<string, unknown>): void;
  handlePlanCompleted(payload: Record<string, unknown>): void;
  setAwaitingApproval(planId: number): void;
  syncFromServer(planId: number): Promise<void>;
  clearPlan(): void;
  dismissPlan(): void;
  viewHistoryPlan(direction: "prev" | "next"): void;

  addStepToolCall(info: {
    toolCallId: string;
    name: string;
    description?: string;
    arguments: string;
  }): void;
  updateStepToolResult(
    toolCallId: string,
    result: string,
    success: boolean,
  ): void;
}

export const usePlanStore = create<PlanState>((set, get) => ({
  plan: null,
  stepToolCalls: {},
  currentStepIndex: null,
  pendingToolCalls: [],
  planHistory: [],
  currentPlanIndex: 0,
  dismissed: false,

  handlePlanCreated(payload) {
    useChatStore.getState().snapshotStepGroup(true);

    const steps = (
      payload.steps as Array<{
        index: number;
        title: string;
        description: string;
      }>
    ).map((s) => ({
      index: s.index,
      title: s.title,
      description: s.description || "",
      status: "pending" as PlanStepStatus,
    }));
    const newPlan: Plan = {
      planId: payload.planId as number,
      title: payload.title as string,
      status: "draft",
      steps,
    };
    set((state) => {
      const isTerminal =
        state.plan &&
        ["completed", "cancelled", "failed"].includes(state.plan.status);
      const history = isTerminal
        ? [state.plan!, ...state.planHistory].slice(0, 5)
        : state.planHistory;
      return {
        plan: newPlan,
        stepToolCalls: {},
        currentStepIndex: null,
        pendingToolCalls: [],
        planHistory: history,
        currentPlanIndex: 0,
        dismissed: false,
      };
    });
  },

  setAwaitingApproval(planId) {
    set((state) => {
      if (!state.plan || state.plan.planId !== planId) return {};
      const st = state.plan.status;
      if (
        st === "executing" ||
        st === "paused" ||
        st === "completed" ||
        st === "cancelled" ||
        st === "failed"
      ) {
        return {};
      }
      return { plan: { ...state.plan, status: "draft" } };
    });
  },

  handleStepStart(payload) {
    const stepIndex = payload.stepIndex as number;
    set((state) => {
      const pl = state.plan;
      if (!pl || !matchesActivePlan(pl, payload)) return {};
      const newStepToolCalls = { ...state.stepToolCalls };
      const existing = newStepToolCalls[stepIndex] ?? [];
      const flushed: StepToolCall[] = state.pendingToolCalls.map((p) => ({
        ...p,
        status: (p.status ?? "calling") as StepToolCall["status"],
      }));
      newStepToolCalls[stepIndex] = [...existing, ...flushed];
      return {
        currentStepIndex: stepIndex,
        pendingToolCalls: [],
        stepToolCalls: newStepToolCalls,
        plan: {
          ...pl,
          steps: pl.steps.map((s) =>
            s.index === stepIndex
              ? { ...s, status: "in_progress" as PlanStepStatus }
              : s,
          ),
        },
      };
    });
  },

  handleStepDone(payload) {
    const stepIndex = payload.stepIndex as number;
    const status = payload.status as PlanStepStatus;
    const resultSummary = payload.resultSummary as string | undefined;
    set((state) => {
      const pl = state.plan;
      if (!pl || !matchesActivePlan(pl, payload)) return {};

      const existingCalls = state.stepToolCalls[stepIndex] ?? [];
      let stepToolCalls = { ...state.stepToolCalls };
      let pendingToolCalls = state.pendingToolCalls;
      if (
        pendingToolCalls.length > 0 &&
        existingCalls.length === 0
      ) {
        const flushed: StepToolCall[] = pendingToolCalls.map((p) => ({
          ...p,
          status: (p.status ?? "calling") as StepToolCall["status"],
        }));
        stepToolCalls = {
          ...stepToolCalls,
          [stepIndex]: flushed,
        };
        pendingToolCalls = [];
      }

      return {
        currentStepIndex:
          state.currentStepIndex === stepIndex
            ? null
            : state.currentStepIndex,
        pendingToolCalls,
        stepToolCalls,
        plan: {
          ...pl,
          steps: pl.steps.map((s) =>
            s.index === stepIndex ? { ...s, status, resultSummary } : s,
          ),
        },
      };
    });
  },

  handlePlanAdjusted(payload) {
    const currentSteps = (
      payload.currentSteps as Array<{
        index: number;
        title: string;
        description: string;
      }>
    ).map((s) => ({
      index: s.index,
      title: s.title,
      description: s.description || "",
      status: "pending" as PlanStepStatus,
    }));
    set((state) => {
      const pl = state.plan;
      if (!pl || !matchesActivePlan(pl, payload)) return {};
      const existing = new Map(pl.steps.map((s) => [s.index, s]));
      const merged = currentSteps.map((ns) => existing.get(ns.index) ?? ns);
      return { plan: { ...pl, steps: merged } };
    });
  },

  handlePlanStatusChanged(payload) {
    const status = payload.status as PlanStatus;
    set((state) => {
      const pl = state.plan;
      if (!pl || !matchesActivePlan(pl, payload)) return {};
      const skipRemaining = status === "cancelled";
      const updatedPlan: Plan = {
        ...pl,
        status,
        steps: skipRemaining
          ? pl.steps.map((s) =>
              s.status === "pending" || s.status === "in_progress"
                ? { ...s, status: "skipped" as PlanStepStatus }
                : s,
            )
          : pl.steps,
      };
      if (skipRemaining) {
        return {
          plan: updatedPlan,
          currentStepIndex: null,
        };
      }
      return { plan: updatedPlan };
    });
  },

  handlePlanCompleted(payload) {
    const status = payload.status as PlanStatus;
    set((state) => {
      const pl = state.plan;
      if (!pl || !matchesActivePlan(pl, payload)) return {};
      const stepFinalStatus: PlanStepStatus = status === "completed" ? "completed" : "skipped";
      const updatedPlan: Plan = {
        ...pl,
        status,
        steps: pl.steps.map((s) =>
          s.status === "pending" || s.status === "in_progress"
            ? { ...s, status: stepFinalStatus }
            : s,
        ),
      };
      return {
        currentStepIndex: null,
        plan: updatedPlan,
      };
    });
  },

  async syncFromServer(planId: number) {
    const applyData = (data: { status: string; steps: Array<{ index: number; title: string; description: string; status: string; resultSummary?: string }> }) => {
      set((state) => {
        const pl = state.plan;
        if (!pl || pl.planId !== planId) return {};
        return {
          plan: {
            ...pl,
            status: data.status as PlanStatus,
            steps: data.steps.map((s) => {
              const local = pl.steps.find((ls) => ls.index === s.index);
              return {
                index: s.index,
                title: s.title,
                description: s.description,
                status: s.status as PlanStepStatus,
                resultSummary: s.resultSummary ?? local?.resultSummary,
              };
            }),
          },
          currentStepIndex: ["completed", "failed", "cancelled"].includes(data.status)
            ? null
            : state.currentStepIndex,
        };
      });
    };

    try {
      const data = await fetchPlan(planId);
      applyData(data);
    } catch {
      setTimeout(() => {
        fetchPlan(planId).then(applyData).catch(() => {});
      }, 3000);
    }
  },

  clearPlan() {
    set({ plan: null, stepToolCalls: {}, currentStepIndex: null, pendingToolCalls: [], dismissed: false });
  },

  dismissPlan() {
    set({ dismissed: true });
  },

  viewHistoryPlan(direction) {
    set((state) => {
      if (!state.plan) return {};
      const total = state.planHistory.length + 1;
      if (total <= 1) return {};
      let newIdx = state.currentPlanIndex;
      if (direction === "prev") newIdx = Math.max(0, newIdx - 1);
      else newIdx = Math.min(total - 1, newIdx + 1);
      if (newIdx === state.currentPlanIndex) return {};
      return { currentPlanIndex: newIdx };
    });
  },

  addStepToolCall(info) {
    const { currentStepIndex, plan } = get();

    if (currentStepIndex == null) {
      const planActive = plan && (
        plan.status === "executing" || plan.status === "approved"
      );
      if (planActive) {
        const nextStep = plan!.steps.find((s) => s.status === "pending");
        if (nextStep) {
          set((state) => {
            const idx = nextStep.index;
            const calls = [...(state.stepToolCalls[idx] ?? [])];
            calls.push({ ...info, status: "calling" });
            return {
              currentStepIndex: idx,
              stepToolCalls: { ...state.stepToolCalls, [idx]: calls },
              plan: {
                ...state.plan!,
                steps: state.plan!.steps.map((s) =>
                  s.index === idx
                    ? { ...s, status: "in_progress" as PlanStepStatus }
                    : s,
                ),
              },
            };
          });
          return;
        }
      }
      set((state) => ({
        pendingToolCalls: [...state.pendingToolCalls, info],
      }));
      return;
    }

    set((state) => {
      const calls = [...(state.stepToolCalls[currentStepIndex] ?? [])];
      calls.push({ ...info, status: "calling" });
      return {
        stepToolCalls: { ...state.stepToolCalls, [currentStepIndex]: calls },
      };
    });
  },

  updateStepToolResult(toolCallId, result, success) {
    set((state) => {
      const updated: Record<number, StepToolCall[]> = {};
      let found = false;
      for (const [idx, calls] of Object.entries(state.stepToolCalls)) {
        const mapped = calls.map((tc) => {
          if (tc.toolCallId === toolCallId) {
            found = true;
            return {
              ...tc,
              result,
              success,
              status: (success ? "done" : "error") as StepToolCall["status"],
            };
          }
          return tc;
        });
        updated[Number(idx)] = mapped;
      }
      if (found) return { stepToolCalls: updated };

      const pendingIdx = state.pendingToolCalls.findIndex(
        (p) => p.toolCallId === toolCallId,
      );
      if (pendingIdx !== -1) {
        const pending = [...state.pendingToolCalls];
        const matched = pending[pendingIdx];
        const stepIdx = state.currentStepIndex;
        if (stepIdx != null) {
          const [removed] = pending.splice(pendingIdx, 1);
          const calls = [...(state.stepToolCalls[stepIdx] ?? [])];
          calls.push({
            ...removed,
            result,
            success,
            status: (success ? "done" : "error") as StepToolCall["status"],
          });
          return {
            pendingToolCalls: pending,
            stepToolCalls: { ...state.stepToolCalls, [stepIdx]: calls },
          };
        }
        pending[pendingIdx] = {
          ...matched,
          result,
          success,
          status: (success ? "done" : "error") as StepToolCall["status"],
        };
        return { pendingToolCalls: pending };
      }
      return {};
    });
  },
}));
