import { apiFetch, apiFetchRaw } from "./httpClient";

export interface AgentSummary {
  id?: number;
  name: string;
  model: string;
  modelDisplayName?: string;
  hasSoul: boolean;
  hasUser: boolean;
  hasAgents: boolean;
  canDelegate?: boolean;
  goal?: string;
  isDefault?: boolean;
}

export interface AgentConfig {
  name: string;
  model: string;
  soulMd: string | null;
  agentsMd: string | null;
  toolsEnabled: string | null;
  mcpToolsEnabled: string | null;
  skillsEnabled: string | null;
  skillGroupsEnabled: string | null;
  canDelegate?: boolean;
  delegateAgents?: string | null;
  goal?: string | null;
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
  agentsMd: string | null;
}


export function fetchAgents(): Promise<AgentSummary[]> {
  return apiFetch<AgentSummary[]>("/api/agents");
}

export function fetchAgentConfig(name: string): Promise<AgentConfig> {
  return apiFetch<AgentConfig>(`/api/agent/${encodeURIComponent(name)}`);
}

export function createAgentApi(data: { name: string; model: string }): Promise<AgentSummary> {
  return apiFetch("/api/agent", { method: "POST", body: JSON.stringify(data) });
}

export function updateAgentApi(name: string, data: Record<string, unknown>): Promise<{ success: boolean }> {
  return apiFetch(`/api/agent/${encodeURIComponent(name)}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export function updateAgentContext(name: string, data: AgentContextUpdate): Promise<{ success: boolean }> {
  return apiFetch(`/api/agent/${encodeURIComponent(name)}/context`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export function deleteAgentApi(name: string): Promise<{ success: boolean }> {
  return apiFetch(`/api/agent/${encodeURIComponent(name)}`, { method: "DELETE" });
}

export function fetchToolsMetadata(): Promise<ToolsMetadata> {
  return apiFetch<ToolsMetadata>("/api/tools");
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
  return apiFetch<ToolDefinition[]>("/api/tool-definitions");
}

export function createToolDefinition(data: ToolDefinitionCreate): Promise<ToolDefinition> {
  return apiFetch<ToolDefinition>("/api/tool-definitions", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export function updateToolDefinition(id: number, data: Partial<ToolDefinitionCreate>): Promise<ToolDefinition> {
  return apiFetch<ToolDefinition>(`/api/tool-definitions/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export function deleteToolDefinition(id: number): Promise<{ success: boolean; deletedName: string }> {
  return apiFetch(`/api/tool-definitions/${id}`, { method: "DELETE" });
}

export function testToolDefinition(id: number, data: ToolTestRequest): Promise<ToolTestResult> {
  return apiFetch<ToolTestResult>(`/api/tool-definitions/${id}/test`, {
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
  requestTimeoutSeconds: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface McpServerCreate {
  name: string;
  serverUrl: string;
  transportType: "SSE" | "STDIO" | "STREAMABLE_HTTP";
  authConfig?: object | null;
  agentName?: string | null;
  requestTimeoutSeconds?: number | null;
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
  return apiFetch<McpServer[]>("/api/mcp-servers");
}

export function createMcpServer(data: McpServerCreate): Promise<McpServer> {
  return apiFetch<McpServer>("/api/mcp-servers", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export function updateMcpServer(id: number, data: Partial<McpServerCreate> & { enabled?: number }): Promise<McpServer> {
  return apiFetch<McpServer>(`/api/mcp-servers/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export function deleteMcpServer(id: number): Promise<{ success: boolean; deletedName: string }> {
  return apiFetch(`/api/mcp-servers/${id}`, { method: "DELETE" });
}

export function testMcpServer(id: number): Promise<McpTestResult> {
  return apiFetch<McpTestResult>(`/api/mcp-servers/${id}/test`, { method: "POST" });
}

export function reconnectMcpServers(): Promise<{ success: boolean; connected: number; failed: number; totalTools: number }> {
  return apiFetch("/api/mcp-servers/reconnect", { method: "POST" });
}

export function testMcpServerConfig(data: McpServerCreate): Promise<McpTestResult> {
  return apiFetch<McpTestResult>("/api/mcp-servers/test-config", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

// ─── Plan ───

export interface PlanDetail {
  planId: number;
  title: string;
  status: string;
  steps: Array<{
    index: number;
    title: string;
    description: string;
    status: string;
    resultSummary?: string;
  }>;
}

export function fetchPlan(planId: number): Promise<PlanDetail> {
  return apiFetch<PlanDetail>(`/api/plans/${planId}`);
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
  return apiFetch<ModelGroup[]>("/api/models");
}

export interface ModelProviderDto {
  id: number;
  name: string;
  type: string;
  baseUrl: string | null;
  apiKeyMasked: string;
  enabled: number;
  sortOrder: number;
  thinkingMode: string | null;
}

export interface ModelProviderCreate {
  name: string;
  type: string;
  baseUrl?: string | null;
  apiKey: string;
  thinkingMode?: string | null;
}

export interface ModelDefinitionDto {
  id: number;
  providerId: number;
  modelId: string;
  displayName: string;
  description: string | null;
  category: string;
  dimensions: number | null;
  maxTokens: number | null;
  enabled: number;
  sortOrder: number;
}

export interface ModelDefinitionCreate {
  providerId: number;
  modelId: string;
  displayName: string;
  description?: string | null;
  category?: string;
  dimensions?: number | null;
  maxTokens?: number | null;
}

export function fetchModelProviders(): Promise<ModelProviderDto[]> {
  return apiFetch<ModelProviderDto[]>("/api/model-providers");
}

export function createModelProvider(data: ModelProviderCreate): Promise<ModelProviderDto> {
  return apiFetch<ModelProviderDto>("/api/model-providers", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export function updateModelProvider(id: number, data: Partial<ModelProviderCreate> & { enabled?: number }): Promise<ModelProviderDto> {
  return apiFetch<ModelProviderDto>(`/api/model-providers/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export function deleteModelProvider(id: number): Promise<{ success: boolean }> {
  return apiFetch(`/api/model-providers/${id}`, { method: "DELETE" });
}

export function testModelProvider(id: number): Promise<{ success: boolean; error?: string }> {
  return apiFetch(`/api/model-providers/${id}/test`, { method: "POST" });
}

export function fetchModelDefinitions(providerId: number): Promise<ModelDefinitionDto[]> {
  return apiFetch<ModelDefinitionDto[]>(`/api/model-providers/${providerId}/models`);
}

export function createModelDefinition(data: ModelDefinitionCreate): Promise<ModelDefinitionDto> {
  return apiFetch<ModelDefinitionDto>("/api/model-definitions", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export function updateModelDefinition(id: number, data: Partial<ModelDefinitionCreate> & { enabled?: number }): Promise<ModelDefinitionDto> {
  return apiFetch<ModelDefinitionDto>(`/api/model-definitions/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export function deleteModelDefinition(id: number): Promise<{ success: boolean }> {
  return apiFetch(`/api/model-definitions/${id}`, { method: "DELETE" });
}

export interface EmbeddingModelGroup {
  providerId: number;
  providerName: string;
  providerType: string;
  models: ModelDefinitionDto[];
}

export function fetchEmbeddingModels(): Promise<EmbeddingModelGroup[]> {
  return apiFetch<EmbeddingModelGroup[]>("/api/embedding-models");
}

export function getActiveEmbeddingModel(): Promise<{ definitionId: number | null; active: boolean }> {
  return apiFetch("/api/embedding-models/active");
}

export function activateEmbeddingModel(definitionId: number): Promise<{ success: boolean; definitionId: number; modelId: string; dimensions: number }> {
  return apiFetch("/api/embedding-models/activate", {
    method: "POST",
    body: JSON.stringify({ definitionId }),
  });
}

export function deactivateEmbeddingModel(): Promise<{ success: boolean }> {
  return apiFetch("/api/embedding-models/deactivate", { method: "POST" });
}

// ─── Skill Definitions ───

export interface SkillDefinition {
  id: number;
  name: string;
  displayName: string | null;
  description: string;
  content: string | null;
  tags: string | null;
  metadata: string | null;
  hasScripts: number;
  hasReferences: number;
  enabled: number;
  gitUrl: string | null;
  gitSubPath: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface SkillDefinitionCreate {
  name: string;
  displayName?: string;
  description: string;
  content?: string;
  tags?: string;
  metadata?: object;
}

export function fetchSkillDefinitions(): Promise<SkillDefinition[]> {
  return apiFetch<SkillDefinition[]>("/api/skills");
}

export function fetchSkillDefinition(id: number): Promise<SkillDefinition> {
  return apiFetch<SkillDefinition>(`/api/skills/${id}`);
}

export function createSkillDefinition(data: SkillDefinitionCreate): Promise<SkillDefinition> {
  return apiFetch<SkillDefinition>("/api/skills", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export function updateSkillDefinition(
  id: number,
  data: Partial<SkillDefinitionCreate> & { enabled?: number },
): Promise<SkillDefinition> {
  return apiFetch<SkillDefinition>(`/api/skills/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export function deleteSkillDefinition(id: number): Promise<{ success: boolean; deletedName: string }> {
  return apiFetch(`/api/skills/${id}`, { method: "DELETE" });
}

export async function exportSkillMdApi(id: number): Promise<string> {
  const res = await apiFetchRaw(`/api/skills/${id}/export`);
  if (!res.ok) throw new Error(`API ${res.status}: ${await res.text().catch(() => res.statusText)}`);
  return res.text();
}

export async function exportSkillZip(id: number, skillName: string): Promise<void> {
  const res = await apiFetchRaw(`/api/skills/${id}/export/zip`);
  if (!res.ok) throw new Error(`API ${res.status}: ${await res.text().catch(() => res.statusText)}`);
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `${skillName}.zip`;
  a.click();
  URL.revokeObjectURL(url);
}

export interface SkillFiles {
  scripts: string[];
  references: string[];
  assets: string[];
}

export function fetchSkillFiles(id: number): Promise<SkillFiles> {
  return apiFetch<SkillFiles>(`/api/skills/${id}/files`);
}

export async function uploadSkillFile(
  id: number,
  type: "scripts" | "references" | "assets",
  file: File,
): Promise<{ success: boolean; filename: string; type: string }> {
  const form = new FormData();
  form.append("file", file);
  const res = await apiFetchRaw(`/api/skills/${id}/files/${type}`, {
    method: "POST",
    body: form,
  });
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText);
    throw new Error(`API ${res.status}: ${text}`);
  }
  return res.json();
}

// ─── Skill Versions ───

export interface SkillVersion {
  id: number;
  skillId: number;
  version: number;
  content: string | null;
  description: string | null;
  changeNote: string | null;
  createdAt: string;
}

export function fetchSkillVersions(id: number): Promise<SkillVersion[]> {
  return apiFetch<SkillVersion[]>(`/api/skills/${id}/versions`);
}

export function fetchSkillVersion(id: number, version: number): Promise<SkillVersion> {
  return apiFetch<SkillVersion>(`/api/skills/${id}/versions/${version}`);
}

export function rollbackSkillVersion(id: number, version: number): Promise<SkillDefinition> {
  return apiFetch<SkillDefinition>(`/api/skills/${id}/rollback/${version}`, { method: "POST" });
}

// ─── Skill Stats ───

export interface SkillUsageStats {
  skillName: string;
  totalActivations: number;
  lastActivatedAt: string;
}

export function fetchSkillStats(): Promise<SkillUsageStats[]> {
  return apiFetch<SkillUsageStats[]>("/api/skills/stats");
}

export function fetchSingleSkillStats(id: number): Promise<{ skillName: string; totalActivations: number }> {
  return apiFetch(`/api/skills/${id}/stats`);
}

export function deleteSkillFile(
  id: number,
  type: string,
  filename: string,
): Promise<{ success: boolean; deletedFile: string; type: string }> {
  return apiFetch(`/api/skills/${id}/files/${type}/${encodeURIComponent(filename)}`, {
    method: "DELETE",
  });
}

// ─── Skill Git Import ───

export interface GitImportParams {
  gitUrl: string;
  branch?: string;
  subPath?: string;
  name?: string;
  description?: string;
}

export function importSkillFromGit(params: GitImportParams): Promise<SkillDefinition> {
  return apiFetch<SkillDefinition>("/api/skills/import/git", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(params),
  });
}

export function syncSkillFromGit(id: number): Promise<SkillDefinition> {
  return apiFetch<SkillDefinition>(`/api/skills/${id}/git/sync`, { method: "POST" });
}

// ─── Skill File Tree ───

export interface FileNode {
  name: string;
  path: string;
  isDirectory: boolean;
  size: number;
  children: FileNode[];
}

export function fetchSkillTree(id: number): Promise<FileNode> {
  return apiFetch<FileNode>(`/api/skills/${id}/tree`);
}

export async function readSkillFile(id: number, path: string): Promise<string> {
  const res = await apiFetchRaw(`/api/skills/${id}/files/read?path=${encodeURIComponent(path)}`);
  if (!res.ok) throw new Error(`API ${res.status}`);
  return res.text();
}

// ─── Skill Groups ───

export interface SkillGroup {
  id: number;
  name: string;
  displayName: string;
  description: string;
  sortOrder: number;
  enabled: number;
  skillCount: number;
}

export function fetchSkillGroups(): Promise<SkillGroup[]> {
  return apiFetch<SkillGroup[]>("/api/skill-groups");
}

export function createSkillGroup(data: { name: string; displayName?: string; description?: string }): Promise<SkillGroup> {
  return apiFetch<SkillGroup>("/api/skill-groups", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export function updateSkillGroup(id: number, data: Partial<{ name: string; displayName: string; description: string; enabled: number }>): Promise<SkillGroup> {
  return apiFetch<SkillGroup>(`/api/skill-groups/${id}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export function deleteSkillGroup(id: number): Promise<void> {
  return apiFetch(`/api/skill-groups/${id}`, { method: "DELETE" });
}

export function reorderSkillGroups(ids: number[]): Promise<void> {
  return apiFetch("/api/skill-groups/reorder", {
    method: "PUT",
    body: JSON.stringify({ ids }),
  });
}

export function fetchSkillGroupMembers(id: number): Promise<number[]> {
  return apiFetch<number[]>(`/api/skill-groups/${id}/skills`);
}

export function setSkillGroupMembers(id: number, skillIds: number[]): Promise<void> {
  return apiFetch(`/api/skill-groups/${id}/skills`, {
    method: "PUT",
    body: JSON.stringify({ skillIds }),
  });
}

// ─── Memory System ───

export interface MemoryConfigItem {
  value: string;
  description: string;
  type: string;
}

export interface MemoryConfigResponse {
  working: Record<string, MemoryConfigItem>;
  consolidation: Record<string, MemoryConfigItem>;
  longTerm: Record<string, MemoryConfigItem>;
  vector?: Record<string, MemoryConfigItem>;
  embedding?: Record<string, MemoryConfigItem>;
  retrieval?: Record<string, MemoryConfigItem>;
  scoring?: Record<string, MemoryConfigItem>;
}

export interface MemoryStatsResponse {
  episodicCount: number;
  semanticCount: number;
  proceduralCount: number;
  totalCount: number;
}

export interface LongTermMemoryItem {
  id: number;
  userId: string;
  agentId: string;
  memoryType: string;
  content: string;
  importance: number;
  accessCount: number;
  lastAccessedAt: string | null;
  createdAt: string;
  keywords?: string;
  topic?: string;
  memoryLevel?: "detail" | "consolidated";
  sourceMemoryIds?: number[];
  enrichedContent?: string;
}

/** Row from `agent_memory_archive` (cold-archived long-term memories). */
export interface ArchivedMemoryItem extends LongTermMemoryItem {
  archivedAt: string;
}

export function fetchMemoryConfig(agentName?: string): Promise<MemoryConfigResponse> {
  const params = agentName ? `?agentName=${encodeURIComponent(agentName)}` : "";
  return apiFetch<MemoryConfigResponse>(`/api/memory/config${params}`);
}

export function updateMemoryConfig(updates: Record<string, string>, agentName?: string): Promise<{ success: string }> {
  const params = agentName ? `?agentName=${encodeURIComponent(agentName)}` : "";
  return apiFetch(`/api/memory/config${params}`, {
    method: "PUT",
    body: JSON.stringify(updates),
  });
}

export function deleteMemoryConfig(agentName: string): Promise<{ success: string }> {
  return apiFetch(`/api/memory/config?agentName=${encodeURIComponent(agentName)}`, { method: "DELETE" });
}

export function fetchMemoryStats(userId = "default", agentId = "default"): Promise<MemoryStatsResponse> {
  const params = new URLSearchParams({ userId, agentId });
  return apiFetch<MemoryStatsResponse>(`/api/memory/stats?${params}`);
}

export function fetchWorkingMemoryByAgent(agentName: string): Promise<Record<string, unknown>> {
  return apiFetch(`/api/memory/working/by-agent/${encodeURIComponent(agentName)}`);
}

export function fetchLongTermMemories(
  userId?: string,
  type?: string,
  agentId = "default",
  level?: "detail" | "consolidated"
): Promise<LongTermMemoryItem[]> {
  const params = new URLSearchParams({ agentId });
  if (userId) params.set("userId", userId);
  if (type) params.set("type", type);
  if (level) params.set("level", level);
  return apiFetch<LongTermMemoryItem[]>(`/api/memory/long-term?${params}`);
}

export function fetchArchivedMemories(userId = "default", agentId = "default"): Promise<ArchivedMemoryItem[]> {
  const params = new URLSearchParams({ userId, agentId });
  return apiFetch<ArchivedMemoryItem[]>(`/api/memory/archive?${params}`);
}

export function deleteLongTermMemory(id: number): Promise<{ success: string }> {
  return apiFetch(`/api/memory/long-term/${id}`, { method: "DELETE" });
}
