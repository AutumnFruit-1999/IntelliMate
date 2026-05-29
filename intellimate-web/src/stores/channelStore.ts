import { create } from "zustand";
import {
  fetchChannels,
  createChannel,
  updateChannel,
  deleteChannel,
  connectChannel,
  disconnectChannel,
  type ChannelInfo,
  type CreateChannelRequest,
  type UpdateChannelRequest,
} from "../lib/channelApi";

interface ChannelState {
  channels: ChannelInfo[];
  loading: boolean;
  error: string | null;

  fetchChannels: () => Promise<void>;
  createChannel: (data: CreateChannelRequest) => Promise<void>;
  updateChannel: (channelId: string, data: UpdateChannelRequest) => Promise<void>;
  deleteChannel: (channelId: string) => Promise<void>;
  connectChannel: (channelId: string) => Promise<void>;
  disconnectChannel: (channelId: string) => Promise<void>;
  updateChannelStatus: (channelId: string, status: string) => void;
}

export const useChannelStore = create<ChannelState>((set, get) => ({
  channels: [],
  loading: false,
  error: null,

  fetchChannels: async () => {
    set({ loading: true, error: null });
    try {
      const channels = await fetchChannels();
      set({ channels, loading: false });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : String(e), loading: false });
    }
  },

  createChannel: async (data) => {
    await createChannel(data);
    await get().fetchChannels();
  },

  updateChannel: async (channelId, data) => {
    await updateChannel(channelId, data);
    await get().fetchChannels();
  },

  deleteChannel: async (channelId) => {
    await deleteChannel(channelId);
    set({ channels: get().channels.filter((c) => c.channelId !== channelId) });
  },

  connectChannel: async (channelId) => {
    await connectChannel(channelId);
    get().updateChannelStatus(channelId, "CONNECTED");
  },

  disconnectChannel: async (channelId) => {
    await disconnectChannel(channelId);
    get().updateChannelStatus(channelId, "DISCONNECTED");
  },

  updateChannelStatus: (channelId, status) => {
    set({
      channels: get().channels.map((c) =>
        c.channelId === channelId ? { ...c, status } : c,
      ),
    });
  },
}));
