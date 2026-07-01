import { create } from "zustand";

export type PlanStepStatus = "pending" | "in_progress" | "completed" | "failed" | "skipped";
export type PlanStatus = "draft" | "approved" | "executing" | "paused" | "completed" | "failed" | "cancelled";

export interface PlanStepData {
  index: number;
  title: string;
  description: string;
  verification: string;
  status: PlanStepStatus;
  resultSummary?: string | null;
}

export interface PlanData {
  status: PlanStatus;
  steps: PlanStepData[];
  completionSummary?: string | null;
}

interface PlanState {
  activePlanMessageId: number | null;
  activePlanStatus: PlanStatus | null;

  handlePlanCreated(data: { messageId: number; title: string; status: string; steps: PlanStepData[] }): void;
  handleStepUpdated(data: { messageId: number; stepIndex: number; status: string; resultSummary?: string }): void;
  handleStatusChanged(data: { messageId: number; status: string }): void;
  handlePlanCompleted(data: { messageId: number; summary?: string }): void;
  clearActivePlan(): void;
}

export const usePlanStore = create<PlanState>((set) => ({
  activePlanMessageId: null,
  activePlanStatus: null,

  handlePlanCreated(data) {
    set({
      activePlanMessageId: data.messageId,
      activePlanStatus: data.status as PlanStatus,
    });
  },

  handleStepUpdated(data) {
    set((state) => {
      if (state.activePlanMessageId !== data.messageId) return {};
      return {};
    });
  },

  handleStatusChanged(data) {
    set((state) => {
      if (state.activePlanMessageId !== data.messageId) return {};
      const status = data.status as PlanStatus;
      const isTerminal = ["completed", "cancelled", "failed"].includes(status);
      return {
        activePlanStatus: status,
        ...(isTerminal ? { activePlanMessageId: null, activePlanStatus: null } : {}),
      };
    });
  },

  handlePlanCompleted(data) {
    set((state) => {
      if (state.activePlanMessageId !== data.messageId) return {};
      return {
        activePlanMessageId: null,
        activePlanStatus: null,
      };
    });
  },

  clearActivePlan() {
    set({ activePlanMessageId: null, activePlanStatus: null });
  },
}));
