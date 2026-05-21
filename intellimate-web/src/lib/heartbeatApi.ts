const BASE_URL = import.meta.env.VITE_API_URL ?? `http://${window.location.hostname}:3007`;

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

export async function fetchHeartbeatConfig(agentId: number): Promise<HeartbeatConfig> {
  const res = await fetch(`${BASE_URL}/api/heartbeat/${agentId}`);
  if (!res.ok) throw new Error("Failed to fetch heartbeat config");
  return res.json();
}

export async function updateHeartbeatConfig(agentId: number, config: Partial<HeartbeatConfig>): Promise<HeartbeatConfig> {
  const res = await fetch(`${BASE_URL}/api/heartbeat/${agentId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(config),
  });
  if (!res.ok) throw new Error("Failed to update heartbeat config");
  return res.json();
}

export async function fetchHeartbeatState(agentId: number): Promise<HeartbeatState> {
  const res = await fetch(`${BASE_URL}/api/heartbeat/${agentId}/state`);
  if (!res.ok) throw new Error("Failed to fetch heartbeat state");
  return res.json();
}

export async function fetchHeartbeatLogs(agentId: number, limit = 20): Promise<HeartbeatLog[]> {
  const res = await fetch(`${BASE_URL}/api/heartbeat/${agentId}/logs?limit=${limit}`);
  if (!res.ok) throw new Error("Failed to fetch heartbeat logs");
  return res.json();
}

export async function fetchTasks(agentId: number, status?: string): Promise<AgentTask[]> {
  const url = status
    ? `${BASE_URL}/api/tasks/${agentId}?status=${status}`
    : `${BASE_URL}/api/tasks/${agentId}`;
  const res = await fetch(url);
  if (!res.ok) throw new Error("Failed to fetch tasks");
  return res.json();
}

export async function createTask(agentId: number, task: Partial<AgentTask>): Promise<AgentTask> {
  const res = await fetch(`${BASE_URL}/api/tasks/${agentId}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(task),
  });
  if (!res.ok) throw new Error("Failed to create task");
  return res.json();
}

export async function updateTask(agentId: number, taskId: number, updates: Partial<AgentTask>): Promise<AgentTask> {
  const res = await fetch(`${BASE_URL}/api/tasks/${agentId}/${taskId}`, {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(updates),
  });
  if (!res.ok) throw new Error("Failed to update task");
  return res.json();
}

export async function deleteTask(agentId: number, taskId: number): Promise<void> {
  const res = await fetch(`${BASE_URL}/api/tasks/${agentId}/${taskId}`, {
    method: "DELETE",
  });
  if (!res.ok) throw new Error("Failed to delete task");
}
