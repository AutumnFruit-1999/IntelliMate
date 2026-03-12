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
  toolsEnabled: string | null;
  mcpToolsEnabled: string | null;
}

export interface ToolInfo {
  name: string;
  description: string;
  group: string;
  groupDisplayName: string;
  source?: string;
}

export interface ToolProfileInfo {
  name: string;
  tools: string[];
}

export interface ToolGroupInfo {
  name: string;
  displayName: string;
  tools: string[];
}

export interface ToolsMetadata {
  tools: ToolInfo[];
  profiles: ToolProfileInfo[];
  groups: ToolGroupInfo[];
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

export function fetchToolsMetadata(): Promise<ToolsMetadata> {
  return request<ToolsMetadata>("/api/tools");
}

// ─── Custom Tool Definitions ───

export interface ToolDefinition {
  id: number;
  name: string;
  type: "HTTP_API" | "SHELL_COMMAND" | "BUILTIN_OVERRIDE";
  description: string | null;
  parametersSchema: string | null;
  executionConfig: string | null;
  timeoutSeconds: number;
  groupName: string | null;
  agentName: string | null;
  enabled: number;
  createdAt: string;
  updatedAt: string;
}

export interface ToolDefinitionCreate {
  name: string;
  type: "HTTP_API";
  description?: string;
  parametersSchema?: object;
  executionConfig: object;
  timeoutSeconds?: number;
  groupName?: string;
}

export interface ToolTestRequest {
  arguments: Record<string, unknown>;
}

export interface ToolTestResult {
  success: boolean;
  result?: string;
  error?: string;
  durationMs: number;
}

export function fetchToolDefinitions(): Promise<ToolDefinition[]> {
  return request<ToolDefinition[]>("/api/tool-definitions");
}

export function createToolDefinition(data: ToolDefinitionCreate): Promise<ToolDefinition> {
  return request<ToolDefinition>("/api/tool-definitions", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export function updateToolDefinition(id: number, data: Partial<ToolDefinitionCreate>): Promise<ToolDefinition> {
  return request<ToolDefinition>(`/api/tool-definitions/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export function deleteToolDefinition(id: number): Promise<{ success: boolean; deletedName: string }> {
  return request(`/api/tool-definitions/${id}`, { method: "DELETE" });
}

export function testToolDefinition(id: number, data: ToolTestRequest): Promise<ToolTestResult> {
  return request<ToolTestResult>(`/api/tool-definitions/${id}/test`, {
    method: "POST",
    body: JSON.stringify(data),
  });
}

// ─── MCP Servers ───

export interface McpServer {
  id: number;
  name: string;
  serverUrl: string;
  transportType: "SSE" | "STDIO" | "STREAMABLE_HTTP";
  authConfig: string | null;
  agentName: string | null;
  enabled: number;
  lastConnectedAt: string | null;
  toolsDiscovered: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface McpServerCreate {
  name: string;
  serverUrl: string;
  transportType: "SSE" | "STDIO" | "STREAMABLE_HTTP";
  authConfig?: object | null;
  agentName?: string | null;
}

export interface McpDiscoveredTool {
  name: string;
  description: string;
}

export interface McpTestResult {
  success: boolean;
  serverName: string;
  toolsDiscovered: McpDiscoveredTool[];
  error?: string;
}

export function fetchMcpServers(): Promise<McpServer[]> {
  return request<McpServer[]>("/api/mcp-servers");
}

export function createMcpServer(data: McpServerCreate): Promise<McpServer> {
  return request<McpServer>("/api/mcp-servers", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export function updateMcpServer(id: number, data: Partial<McpServerCreate> & { enabled?: number }): Promise<McpServer> {
  return request<McpServer>(`/api/mcp-servers/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export function deleteMcpServer(id: number): Promise<{ success: boolean; deletedName: string }> {
  return request(`/api/mcp-servers/${id}`, { method: "DELETE" });
}

export function testMcpServer(id: number): Promise<McpTestResult> {
  return request<McpTestResult>(`/api/mcp-servers/${id}/test`, { method: "POST" });
}

export function reconnectMcpServers(): Promise<{ success: boolean; connected: number; failed: number; totalTools: number }> {
  return request("/api/mcp-servers/reconnect", { method: "POST" });
}

export function testMcpServerConfig(data: McpServerCreate): Promise<McpTestResult> {
  return request<McpTestResult>("/api/mcp-servers/test-config", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

// ─── Model Management ───

export interface ModelItem {
  id: number;
  modelId: string;
  displayName: string;
  description: string | null;
}

export interface ModelGroup {
  providerId: number;
  providerName: string;
  providerType: string;
  models: ModelItem[];
}

export function fetchModelGroups(): Promise<ModelGroup[]> {
  return request<ModelGroup[]>("/api/models");
}

export interface ModelProviderDto {
  id: number;
  name: string;
  type: string;
  baseUrl: string | null;
  apiKeyMasked: string;
  enabled: number;
  sortOrder: number;
}

export interface ModelProviderCreate {
  name: string;
  type: string;
  baseUrl?: string | null;
  apiKey: string;
}

export interface ModelDefinitionDto {
  id: number;
  providerId: number;
  modelId: string;
  displayName: string;
  description: string | null;
  maxTokens: number | null;
  enabled: number;
  sortOrder: number;
}

export interface ModelDefinitionCreate {
  providerId: number;
  modelId: string;
  displayName: string;
  description?: string | null;
  maxTokens?: number | null;
}

export function fetchModelProviders(): Promise<ModelProviderDto[]> {
  return request<ModelProviderDto[]>("/api/model-providers");
}

export function createModelProvider(data: ModelProviderCreate): Promise<ModelProviderDto> {
  return request<ModelProviderDto>("/api/model-providers", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export function updateModelProvider(id: number, data: Partial<ModelProviderCreate> & { enabled?: number }): Promise<ModelProviderDto> {
  return request<ModelProviderDto>(`/api/model-providers/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export function deleteModelProvider(id: number): Promise<{ success: boolean }> {
  return request(`/api/model-providers/${id}`, { method: "DELETE" });
}

export function testModelProvider(id: number): Promise<{ success: boolean; error?: string }> {
  return request(`/api/model-providers/${id}/test`, { method: "POST" });
}

export function fetchModelDefinitions(providerId: number): Promise<ModelDefinitionDto[]> {
  return request<ModelDefinitionDto[]>(`/api/model-providers/${providerId}/models`);
}

export function createModelDefinition(data: ModelDefinitionCreate): Promise<ModelDefinitionDto> {
  return request<ModelDefinitionDto>("/api/model-definitions", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export function updateModelDefinition(id: number, data: Partial<ModelDefinitionCreate> & { enabled?: number }): Promise<ModelDefinitionDto> {
  return request<ModelDefinitionDto>(`/api/model-definitions/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export function deleteModelDefinition(id: number): Promise<{ success: boolean }> {
  return request(`/api/model-definitions/${id}`, { method: "DELETE" });
}
