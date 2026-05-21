import { create } from "zustand";
import {
  fetchAgents,
  fetchAgentConfig,
  updateAgentContext,
  updateAgentApi,
  createAgentApi,
  deleteAgentApi,
  type AgentSummary,
  type AgentConfig,
} from "../lib/api";

type ContextField = "soulMd" | "agentsMd";

interface AgentState {
  agents: AgentSummary[];
  activeAgent: string | null;
  listLoading: boolean;

  config: AgentConfig | null;
  draft: Record<ContextField, string>;
  toolsEnabledDraft: string | null;
  mcpToolsEnabledDraft: string | null;
  skillsEnabledDraft: string | null;
  skillGroupsEnabledDraft: string | null;
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
  setToolsEnabled: (value: string | null) => void;
  setMcpToolsEnabled: (value: string | null) => void;
  setSkillsEnabled: (value: string | null) => void;
  setSkillGroupsEnabled: (value: string | null) => void;
  saveConfig: () => Promise<void>;
  saveToolsEnabled: () => Promise<void>;
  saveMcpToolsEnabled: () => Promise<void>;
  saveSkillsEnabled: () => Promise<void>;
  saveSkillGroupsEnabled: () => Promise<void>;
  saveModel: (modelId: string) => Promise<void>;
  resetConfig: () => void;
}

const EMPTY_DRAFT: Record<ContextField, string> = { soulMd: "", agentsMd: "" };
const STORAGE_KEY = "intellimate-active-agent";

export const useAgentStore = create<AgentState>((set, get) => ({
  agents: [],
  activeAgent: localStorage.getItem(STORAGE_KEY),
  listLoading: false,

  config: null,
  draft: { ...EMPTY_DRAFT },
  toolsEnabledDraft: null,
  mcpToolsEnabledDraft: null,
  skillsEnabledDraft: null,
  skillGroupsEnabledDraft: null,
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
          agentsMd: config.agentsMd ?? "",
        },
        toolsEnabledDraft: config.toolsEnabled,
        mcpToolsEnabledDraft: config.mcpToolsEnabled,
        skillsEnabledDraft: config.skillsEnabled,
        skillGroupsEnabledDraft: config.skillGroupsEnabled,
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

  setToolsEnabled: (value) => {
    set({ toolsEnabledDraft: value, dirty: true });
  },

  setMcpToolsEnabled: (value) => {
    set({ mcpToolsEnabledDraft: value, dirty: true });
  },

  setSkillsEnabled: (value) => {
    set({ skillsEnabledDraft: value, dirty: true });
  },

  setSkillGroupsEnabled: (value) => {
    set({ skillGroupsEnabledDraft: value, dirty: true });
  },

  saveConfig: async () => {
    const { config, draft } = get();
    if (!config) return;
    set({ saving: true, error: null });
    try {
      await updateAgentContext(config.name, {
        soulMd: draft.soulMd || null,
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

  saveToolsEnabled: async () => {
    const { config, toolsEnabledDraft } = get();
    if (!config) return;
    set({ saving: true, error: null });
    try {
      await updateAgentApi(config.name, { toolsEnabled: toolsEnabledDraft });
      set((s) => ({
        saving: false,
        dirty: false,
        config: s.config ? { ...s.config, toolsEnabled: toolsEnabledDraft } : null,
      }));
      get().fetchAgentList().catch(() => {});
    } catch (e) {
      set({ saving: false, error: e instanceof Error ? e.message : String(e) });
    }
  },

  saveMcpToolsEnabled: async () => {
    const { config, mcpToolsEnabledDraft } = get();
    if (!config) return;
    set({ saving: true, error: null });
    try {
      await updateAgentApi(config.name, { mcpToolsEnabled: mcpToolsEnabledDraft });
      // 先更新 config，确保状态变化被检测到
      set((s) => ({
        saving: false,
        dirty: false,
        config: s.config ? { ...s.config, mcpToolsEnabled: mcpToolsEnabledDraft } : null,
      }));
      // 异步更新列表，不影响当前状态
      get().fetchAgentList().catch(() => {});
    } catch (e) {
      set({ saving: false, error: e instanceof Error ? e.message : String(e) });
    }
  },

  saveSkillsEnabled: async () => {
    const { config, skillsEnabledDraft } = get();
    if (!config) return;
    set({ saving: true, error: null });
    try {
      await updateAgentApi(config.name, { skillsEnabled: skillsEnabledDraft });
      set((s) => ({
        saving: false,
        dirty: false,
        config: s.config ? { ...s.config, skillsEnabled: skillsEnabledDraft } : null,
      }));
      get().fetchAgentList().catch(() => {});
    } catch (e) {
      set({ saving: false, error: e instanceof Error ? e.message : String(e) });
    }
  },

  saveSkillGroupsEnabled: async () => {
    const { config, skillGroupsEnabledDraft } = get();
    if (!config) return;
    set({ saving: true, error: null });
    try {
      await updateAgentApi(config.name, { skillGroupsEnabled: skillGroupsEnabledDraft });
      set((s) => ({
        saving: false,
        dirty: false,
        config: s.config ? { ...s.config, skillGroupsEnabled: skillGroupsEnabledDraft } : null,
      }));
      get().fetchAgentList().catch(() => {});
    } catch (e) {
      set({ saving: false, error: e instanceof Error ? e.message : String(e) });
    }
  },

  saveModel: async (modelId: string) => {
    const { config } = get();
    if (!config) return;
    set({ saving: true, error: null });
    try {
      await updateAgentApi(config.name, { model: modelId });
      set((s) => ({
        saving: false,
        config: s.config ? { ...s.config, model: modelId } : null,
      }));
      await get().fetchAgentList();
    } catch (e) {
      set({ saving: false, error: e instanceof Error ? e.message : String(e) });
    }
  },

  resetConfig: () => {
    set({ config: null, draft: { ...EMPTY_DRAFT }, toolsEnabledDraft: null, mcpToolsEnabledDraft: null, skillsEnabledDraft: null, skillGroupsEnabledDraft: null, loading: false, saving: false, dirty: false, error: null });
  },
}));
