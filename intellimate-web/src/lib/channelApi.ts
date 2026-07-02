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

export interface ChannelIdentity {
  id: number;
  userId: string;
  channelId: string;
  externalId: string;
  externalName: string | null;
  boundAt: string;
}

export function generateBindingCode(userId: string): Promise<{ code: string; expiresIn: number }> {
  return apiFetch("/api/channel-binding/generate-code", {
    method: "POST",
    body: JSON.stringify({ userId }),
  });
}

export function listBoundIdentities(userId: string): Promise<ChannelIdentity[]> {
  return apiFetch(`/api/channel-binding/identities/${encodeURIComponent(userId)}`);
}

export function unbindIdentity(identityId: number): Promise<void> {
  return apiFetch(`/api/channel-binding/identities/${identityId}`, { method: "DELETE" });
}

export interface ChannelGroup {
  id: number;
  channelId: string;
  groupId: string;
  groupName: string | null;
  agentName: string | null;
  createdAt: string;
  updatedAt: string;
}

export function listChannelGroups(channelId: string): Promise<ChannelGroup[]> {
  return apiFetch(`/api/channels/${encodeURIComponent(channelId)}/groups`);
}

export function bindGroupAgent(
  channelId: string,
  groupId: string,
  agentName: string,
): Promise<ChannelGroup> {
  return apiFetch(
    `/api/channels/${encodeURIComponent(channelId)}/groups/${encodeURIComponent(groupId)}/agent`,
    {
      method: "PUT",
      body: JSON.stringify({ agentName }),
    },
  );
}

export function unbindGroupAgent(
  channelId: string,
  groupId: string,
): Promise<ChannelGroup> {
  return apiFetch(
    `/api/channels/${encodeURIComponent(channelId)}/groups/${encodeURIComponent(groupId)}/agent`,
    { method: "DELETE" },
  );
}
