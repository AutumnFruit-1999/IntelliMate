import { create } from "zustand";
import {
  fetchMemoryConfig,
  updateMemoryConfig,
  deleteMemoryConfig,
  fetchMemoryStats,
  fetchLongTermMemories as fetchLongTermMemoriesApi,
  deleteLongTermMemory,
  type MemoryConfigResponse,
  type MemoryStatsResponse,
  type LongTermMemoryItem,
} from "../lib/api";

export interface MemorySnapshotData {
  tokenBudget: number;
  tokenUsed: number;
  /** Sum of per-chunk heuristic estimates (for comparison with API-based tokenUsed). */
  tokenEstimated?: number;
  usageRatio: number;
  chunkCount: number;
  chunks: Array<{
    id: string;
    type: string;
    category: string;
    importance: number;
    tokens: number;
    contentPreview: string;
    createdAt: string;
  }>;
}

export interface ChunkPreview {
  type: string;
  tokens: number;
  importance: number;
  preview: string;
}

export interface ConsolidationLogEntry {
  timestamp: string;
  chunksSelected: number;
  tokensBefore: number;
  tokensAfter: number;
  extractedFacts: string[];
  candidates?: ChunkPreview[];
  factsStoredToLongTerm?: boolean;
}

interface MemoryState {
  workingMemory: MemorySnapshotData | null;
  consolidationLog: ConsolidationLogEntry[];
  longTermMemories: LongTermMemoryItem[];
  memoryStats: MemoryStatsResponse | null;
  memoryConfig: MemoryConfigResponse | null;
  configLoading: boolean;
  configError: string | null;
  selectedAgentId: string;

  handleMemorySnapshot: (data: MemorySnapshotData) => void;
  handleConsolidation: (data: ConsolidationLogEntry) => void;

  setSelectedAgentId: (agentId: string) => void;

  fetchConfig: () => Promise<void>;
  saveConfig: (updates: Record<string, string>) => Promise<void>;
  deleteConfig: () => Promise<void>;
  fetchStats: (userId?: string) => Promise<void>;
  fetchLongTermMemories: (userId?: string) => Promise<void>;
  deleteLongTermMemory: (id: number) => Promise<void>;
}

export const useMemoryStore = create<MemoryState>((set, get) => ({
  workingMemory: null,
  consolidationLog: [],
  longTermMemories: [],
  memoryStats: null,
  memoryConfig: null,
  configLoading: false,
  configError: null,
  selectedAgentId: "default",

  setSelectedAgentId: (agentId) => set({ selectedAgentId: agentId }),

  handleMemorySnapshot: (data) => {
    set({ workingMemory: data });
  },

  handleConsolidation: (data) => {
    set((state) => ({
      consolidationLog: [
        { ...data, timestamp: new Date().toISOString() },
        ...state.consolidationLog,
      ].slice(0, 100),
    }));
  },

  fetchConfig: async () => {
    set({ configLoading: true, configError: null });
    try {
      const agentId = get().selectedAgentId;
      const config = await fetchMemoryConfig(agentId);
      set({ memoryConfig: config, configLoading: false, configError: null });
    } catch (e) {
      console.error("Failed to fetch memory config", e);
      set({ configLoading: false, configError: "加载配置失败，请检查后端服务是否正常运行" });
    }
  },

  saveConfig: async (updates) => {
    try {
      const agentId = get().selectedAgentId;
      await updateMemoryConfig(updates, agentId);
      await get().fetchConfig();
    } catch (e) {
      console.error("Failed to save memory config", e);
      throw e;
    }
  },

  deleteConfig: async () => {
    try {
      const agentId = get().selectedAgentId;
      await deleteMemoryConfig(agentId);
      await get().fetchConfig();
    } catch (e) {
      console.error("Failed to delete memory config", e);
      throw e;
    }
  },

  fetchStats: async (userId = "default") => {
    try {
      const agentId = get().selectedAgentId;
      const stats = await fetchMemoryStats(userId, agentId);
      set({ memoryStats: stats });
    } catch (e) {
      console.error("Failed to fetch memory stats", e);
    }
  },

  fetchLongTermMemories: async (userId) => {
    try {
      const agentId = get().selectedAgentId;
      const memories = await fetchLongTermMemoriesApi(userId, undefined, agentId);
      set({ longTermMemories: memories });
    } catch (e) {
      console.error("Failed to fetch long-term memories", e);
    }
  },

  deleteLongTermMemory: async (id) => {
    try {
      await deleteLongTermMemory(id);
      set((state) => ({
        longTermMemories: state.longTermMemories.filter((m) => m.id !== id),
      }));
    } catch (e) {
      console.error("Failed to delete memory", e);
      throw e;
    }
  },
}));
