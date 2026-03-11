const BASE_URL = import.meta.env.VITE_API_URL ?? `http://${window.location.hostname}:3007`;

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

export function fetchAgentConfig(name: string): Promise<AgentConfig> {
  return request<AgentConfig>(`/api/agent/${encodeURIComponent(name)}`);
}

export function updateAgentContext(name: string, data: AgentContextUpdate): Promise<{ success: boolean }> {
  return request(`/api/agent/${encodeURIComponent(name)}/context`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}
