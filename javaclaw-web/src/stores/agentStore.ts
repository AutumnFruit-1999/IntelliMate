import { create } from "zustand";
import { fetchAgentConfig, updateAgentContext, type AgentConfig } from "../lib/api";

type ContextField = "soulMd" | "userMd" | "agentsMd";

interface AgentState {
  config: AgentConfig | null;
  draft: Record<ContextField, string>;
  loading: boolean;
  saving: boolean;
  dirty: boolean;
  error: string | null;

  fetchConfig: (name: string) => Promise<void>;
  updateField: (field: ContextField, value: string) => void;
  saveConfig: () => Promise<void>;
  reset: () => void;
}

const EMPTY_DRAFT: Record<ContextField, string> = { soulMd: "", userMd: "", agentsMd: "" };

export const useAgentStore = create<AgentState>((set, get) => ({
  config: null,
  draft: { ...EMPTY_DRAFT },
  loading: false,
  saving: false,
  dirty: false,
  error: null,

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
    } catch (e) {
      set({ saving: false, error: e instanceof Error ? e.message : String(e) });
    }
  },

  reset: () => {
    set({ config: null, draft: { ...EMPTY_DRAFT }, loading: false, saving: false, dirty: false, error: null });
  },
}));
