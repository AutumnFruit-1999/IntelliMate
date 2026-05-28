import { apiFetch } from "./httpClient";

export interface HistoryMessage {
  id: number;
  role: "user" | "assistant" | "tool";
  content: string;
  createdAt: string;
  toolName?: string;
  metadata?: string;
}

export interface MessagesResponse {
  messages: HistoryMessage[];
  hasMore: boolean;
}

export interface ArchivedSession {
  id: number;
  title: string;
  agentName: string;
  lastActiveAt: string;
  createdAt: string;
}

export interface ArchivedSessionsResponse {
  sessions: ArchivedSession[];
  total: number;
  hasMore: boolean;
}

export function fetchActiveMessages(
  agentName: string,
  limit = 50,
  before?: number,
): Promise<MessagesResponse> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (before != null) params.set("before", String(before));
  return apiFetch(`/api/sessions/${encodeURIComponent(agentName)}/messages?${params}`);
}

export function clearSession(agentName: string): Promise<{ success: boolean; newSessionId: number }> {
  return apiFetch(`/api/sessions/${encodeURIComponent(agentName)}/clear`, { method: "POST" });
}

export function fetchArchivedSessions(
  agentName: string,
  limit = 20,
  offset = 0,
): Promise<ArchivedSessionsResponse> {
  const params = new URLSearchParams({ limit: String(limit), offset: String(offset) });
  return apiFetch(`/api/sessions/${encodeURIComponent(agentName)}/archived?${params}`);
}

export function fetchSessionMessages(
  sessionId: number,
  limit = 100,
): Promise<MessagesResponse> {
  return apiFetch(`/api/sessions/by-id/${sessionId}/messages?limit=${limit}`);
}

export interface SearchResult {
  id: number;
  role: string;
  content: string;
  createdAt: string;
  toolName?: string;
}

export function searchMessages(
  agentName: string,
  query: string,
  limit = 20,
): Promise<{ results: SearchResult[] }> {
  const params = new URLSearchParams({ q: query, limit: String(limit) });
  return apiFetch(`/api/sessions/${encodeURIComponent(agentName)}/search?${params}`);
}
