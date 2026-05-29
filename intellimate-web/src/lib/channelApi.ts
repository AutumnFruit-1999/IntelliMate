import { apiFetch } from "./httpClient";

export interface ChannelInfo {
  channelId: string;
  status: string;
  enabled: boolean;
  config: Record<string, unknown>;
  configSchema: Record<string, unknown> | null;
}

export interface CreateChannelRequest {
  channelId: string;
  enabled: boolean;
  config: Record<string, unknown>;
}

export interface UpdateChannelRequest {
  enabled: boolean;
  config: Record<string, unknown>;
}

export function fetchChannels(): Promise<ChannelInfo[]> {
  return apiFetch<ChannelInfo[]>("/api/channels");
}

export function fetchChannel(channelId: string): Promise<ChannelInfo> {
  return apiFetch<ChannelInfo>(`/api/channels/${encodeURIComponent(channelId)}`);
}

export function createChannel(data: CreateChannelRequest): Promise<{ channelId: string; id?: number }> {
  return apiFetch("/api/channels", {
    method: "POST",
    body: JSON.stringify(data),
  });
}

export function updateChannel(
  channelId: string,
  data: UpdateChannelRequest,
): Promise<{ channelId: string; status: string }> {
  return apiFetch(`/api/channels/${encodeURIComponent(channelId)}`, {
    method: "PUT",
    body: JSON.stringify(data),
  });
}

export function deleteChannel(channelId: string): Promise<void> {
  return apiFetch(`/api/channels/${encodeURIComponent(channelId)}`, { method: "DELETE" });
}

export function connectChannel(channelId: string): Promise<{ status: string }> {
  return apiFetch(`/api/channels/${encodeURIComponent(channelId)}/connect`, { method: "POST" });
}

export function disconnectChannel(channelId: string): Promise<{ status: string }> {
  return apiFetch(`/api/channels/${encodeURIComponent(channelId)}/disconnect`, { method: "POST" });
}
