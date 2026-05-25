import { apiFetch, apiFetchRaw } from "./httpClient";

export interface HeartbeatConfig {
  id?: number;
  agentId: number;
  enabled: number;
  timezone: string;
  wakeTime: string;
  sleepTime: string;
  heartbeatIntervalMinutes: number;
}

export interface HeartbeatState {
  currentState: string;
  stateDescription?: string;
  currentTime?: string;
}

export interface HeartbeatLog {
  id: number;
  agentId: number;
  state: string;
  triggeredAt: string;
  response: string;
  delivered: number;
}

export interface AgentTask {
  id?: number;
  agentId: number;
  title: string;
  description?: string;
  dueAt?: string | null;
  remindAt?: string | null;
  status: string;
  priority: number;
  createdAt?: string;
  updatedAt?: string;
}

export function fetchHeartbeatConfig(agentId: number): Promise<HeartbeatConfig> {
  return apiFetch<HeartbeatConfig>(`/api/heartbeat/${agentId}`);
}

export function updateHeartbeatConfig(agentId: number, config: Partial<HeartbeatConfig>): Promise<HeartbeatConfig> {
  return apiFetch<HeartbeatConfig>(`/api/heartbeat/${agentId}`, {
    method: "PUT",
    body: JSON.stringify(config),
  });
}

export function fetchHeartbeatState(agentId: number): Promise<HeartbeatState> {
  return apiFetch<HeartbeatState>(`/api/heartbeat/${agentId}/state`);
}

export function fetchHeartbeatLogs(agentId: number, limit = 20): Promise<HeartbeatLog[]> {
  return apiFetch<HeartbeatLog[]>(`/api/heartbeat/${agentId}/logs?limit=${limit}`);
}

export function fetchTasks(agentId: number, status?: string): Promise<AgentTask[]> {
  const url = status
    ? `/api/tasks/${agentId}?status=${status}`
    : `/api/tasks/${agentId}`;
  return apiFetch<AgentTask[]>(url);
}

export function createTask(agentId: number, task: Partial<AgentTask>): Promise<AgentTask> {
  return apiFetch<AgentTask>(`/api/tasks/${agentId}`, {
    method: "POST",
    body: JSON.stringify(task),
  });
}

export function updateTask(agentId: number, taskId: number, updates: Partial<AgentTask>): Promise<AgentTask> {
  return apiFetch<AgentTask>(`/api/tasks/${agentId}/${taskId}`, {
    method: "PUT",
    body: JSON.stringify(updates),
  });
}

export async function deleteTask(agentId: number, taskId: number): Promise<void> {
  const res = await apiFetchRaw(`/api/tasks/${agentId}/${taskId}`, {
    method: "DELETE",
  });
  if (!res.ok) throw new Error("Failed to delete task");
}
