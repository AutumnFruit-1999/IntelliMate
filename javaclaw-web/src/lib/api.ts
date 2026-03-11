const BASE_URL = import.meta.env.VITE_API_URL ?? `http://${window.location.hostname}:3007`;

export interface AgentSummary {
  name: string;
  model: string;
  hasSoul: boolean;
  hasUser: boolean;
  hasAgents: boolean;
  isDefault?: boolean;
}

export interface AgentConfig {
  name: string;
  model: string;
  soulMd: string | null;
  userMd: string | null;
  agentsMd: string | null;
}

export interface AgentContextUpdate {
  soulMd: string | null;
  userMd: string | null;
  agentsMd: string | null;
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText);
    throw new Error(`API ${res.status}: ${text}`);
  }
  return res.json();
}

export function fetchAgents(): Promise<AgentSummary[]> {
  return request<AgentSummary[]>("/api/agents");
}

export function fetchAgentConfig(name: string): Promise<AgentConfig> {
  return request<AgentConfig>(`/api/agent/${encodeURIComponent(name)}`);
}

export function createAgentApi(data: { name: string; model: string }): Promise<AgentSummary> {
  return request("/api/agent", { method: "POST", body: JSON.stringify(data) });
}

export function updateAgentApi(name: string, data: Record<string, unknown>): Promise<{ success: boolean }> {
  return request(`/api/agent/${encodeURIComponent(name)}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export function updateAgentContext(name: string, data: AgentContextUpdate): Promise<{ success: boolean }> {
  return request(`/api/agent/${encodeURIComponent(name)}/context`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export function deleteAgentApi(name: string): Promise<{ success: boolean }> {
  return request(`/api/agent/${encodeURIComponent(name)}`, { method: "DELETE" });
}
