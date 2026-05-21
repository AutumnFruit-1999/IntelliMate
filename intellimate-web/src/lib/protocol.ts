export interface EventFrame {
  type: "event";
  event: string;
  payload: Record<string, unknown>;
  seq: number | null;
}

export interface RequestFrame {
  type: "request";
  id: string;
  method: string;
  params: Record<string, unknown>;
}

export interface ResponseFrame {
  type: "response";
  id: string;
  ok: boolean;
  payload: Record<string, unknown> | null;
  error: unknown | null;
}

export type GatewayFrame = EventFrame | RequestFrame | ResponseFrame;

export function generateId(): string {
  if (typeof crypto !== "undefined" && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function createRequest(
  method: string,
  params: Record<string, unknown>,
): RequestFrame {
  return {
    type: "request",
    id: generateId(),
    method,
    params,
  };
}

export function createPong(): EventFrame {
  return {
    type: "event",
    event: "pong",
    payload: {},
    seq: null,
  };
}

export function isEventFrame(frame: GatewayFrame): frame is EventFrame {
  return frame.type === "event";
}

export function isResponseFrame(frame: GatewayFrame): frame is ResponseFrame {
  return frame.type === "response";
}

export function createPlanApprove(
  planId: number,
  approved: boolean,
  modifications?: Array<{
    type: "edit" | "add" | "remove";
    stepIndex?: number;
    title?: string;
    description?: string;
  }>,
): RequestFrame {
  return createRequest("plan.approve", { planId, approved, modifications });
}

export function createPlanPause(planId: number): RequestFrame {
  return createRequest("plan.pause", { planId });
}

export function createPlanResume(planId: number): RequestFrame {
  return createRequest("plan.resume", { planId });
}

export function createPlanCancel(planId: number): RequestFrame {
  return createRequest("plan.cancel", { planId });
}

export function createPlanSkipStep(
  planId: number,
  stepIndex: number,
): RequestFrame {
  return createRequest("plan.skip_step", { planId, stepIndex });
}

export function createPlanModifyStep(
  planId: number,
  stepIndex: number,
  title?: string,
  description?: string,
): RequestFrame {
  return createRequest("plan.modify_step", { planId, stepIndex, title, description });
}

export function createPlanAddStep(
  planId: number,
  afterIndex: number,
  title: string,
  description: string,
): RequestFrame {
  return createRequest("plan.add_step", { planId, afterIndex, title, description });
}

export function createPlanApproveAndExecute(planId: number): RequestFrame {
  return createRequest("plan.approve_and_execute", { planId });
}

export function createPlanReorderSteps(
  planId: number,
  newOrder: number[],
): RequestFrame {
  return createRequest("plan.reorder_steps", { planId, newOrder });
}

// Memory types
export interface MemoryChunkInfo {
  id: string;
  category: string;
  tokens: number;
  importance: number;
  preview: string;
  timestamp: number;
}

export interface MemorySnapshotFrame {
  type: "MEMORY_SNAPSHOT";
  tokenBudget: number;
  tokenUsed: number;
  /** Per-chunk heuristic sum when provided by the server. */
  tokenEstimated?: number;
  usageRatio: number;
  chunks: MemoryChunkInfo[];
}

export interface ConsolidationTriggeredFrame {
  type: "CONSOLIDATION_TRIGGERED";
  removedChunks: number;
  tokensFreed: number;
  factsExtracted: number;
}

export interface MemoryConfig {
  key: string;
  value: string;
  category: string;
  description: string;
}

export interface MemoryConfigGroup {
  [category: string]: MemoryConfig[];
}

export interface LongTermMemoryEntry {
  id: number;
  userId: string;
  memoryType: string;
  content: string;
  importance: number;
  accessCount: number;
  lastAccessTime: string;
  createdAt: string;
}
