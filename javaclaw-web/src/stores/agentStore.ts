import { create } from "zustand";
import {
  fetchAgents,
  fetchAgentConfig,
  updateAgentContext,
  createAgentApi,
  deleteAgentApi,
  type AgentSummary,
  type AgentConfig,
} from "../lib/api";

type ContextField = "soulMd" | "userMd" | "agentsMd";

interface AgentState {
  agents: AgentSummary[];
  activeAgent: string | null;
  listLoading: boolean;

  config: AgentConfig | null;
  draft: Record<ContextField, string>;
  loading: boolean;
  saving: boolean;
  dirty: boolean;
  error: string | null;

  fetchAgentList: () => Promise<void>;
  setActiveAgent: (name: string) => void;
  createAgent: (name: string, model: string) => Promise<void>;
  removeAgent: (name: string) => Promise<void>;

  fetchConfig: (name: string) => Promise<void>;
  updateField: (field: ContextField, value: string) => void;
  saveConfig: () => Promise<void>;
  resetConfig: () => void;
}

const EMPTY_DRAFT: Record<ContextField, string> = { soulMd: "", userMd: "", agentsMd: "" };
const STORAGE_KEY = "javaclaw-active-agent";

export const useAgentStore = create<AgentState>((set, get) => ({
  agents: [],
  activeAgent: localStorage.getItem(STORAGE_KEY),
  listLoading: false,

  config: null,
  draft: { ...EMPTY_DRAFT },
  loading: false,
  saving: false,
  dirty: false,
  error: null,

  fetchAgentList: async () => {
    set({ listLoading: true });
    try {
      const agents = await fetchAgents();
      const { activeAgent } = get();
      const hasActive = activeAgent && agents.some((a) => a.name === activeAgent);
      const resolvedActive = hasActive ? activeAgent : (agents[0]?.name ?? null);

      set({ agents, listLoading: false, activeAgent: resolvedActive });
      if (resolvedActive) localStorage.setItem(STORAGE_KEY, resolvedActive);
    } catch {
      set({ listLoading: false });
    }
  },

  setActiveAgent: (name: string) => {
    set({ activeAgent: name });
    localStorage.setItem(STORAGE_KEY, name);
  },

  createAgent: async (name: string, model: string) => {
    await createAgentApi({ name, model });
    await get().fetchAgentList();
    set({ activeAgent: name });
    localStorage.setItem(STORAGE_KEY, name);
  },

  removeAgent: async (name: string) => {
    await deleteAgentApi(name);
    const { activeAgent } = get();
    await get().fetchAgentList();
    if (activeAgent === name) {
      const agents = get().agents;
      const next = agents[0]?.name ?? null;
      set({ activeAgent: next });
      if (next) localStorage.setItem(STORAGE_KEY, next);
    }
  },

  fetchConfig: async (name: string) => {
    set({ loading: true, error: null });
    try {
      const config = await fetchAgentConfig(name);
      set({
        config,
        draft: {
          soulMd: config.soulMd ?? "",
          userMd: config.userMd ?? "",
          agentsMd: config.agentsMd ?? "",
        },
        loading: false,
        dirty: false,
      });
    } catch (e) {
      set({ loading: false, error: e instanceof Error ? e.message : String(e) });
    }
  },

  updateField: (field, value) => {
    set((s) => ({
      draft: { ...s.draft, [field]: value },
      dirty: true,
    }));
  },

  saveConfig: async () => {
    const { config, draft } = get();
    if (!config) return;
    set({ saving: true, error: null });
    try {
      await updateAgentContext(config.name, {
        soulMd: draft.soulMd || null,
        userMd: draft.userMd || null,
        agentsMd: draft.agentsMd || null,
      });
      set((s) => ({
        saving: false,
        dirty: false,
        config: s.config ? { ...s.config, ...draft } : null,
      }));
      await get().fetchAgentList();
    } catch (e) {
      set({ saving: false, error: e instanceof Error ? e.message : String(e) });
    }
  },

  resetConfig: () => {
    set({ config: null, draft: { ...EMPTY_DRAFT }, loading: false, saving: false, dirty: false, error: null });
  },
}));
