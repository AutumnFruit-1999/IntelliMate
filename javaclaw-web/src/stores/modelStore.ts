import { create } from "zustand";
import {
  fetchModelProviders,
  fetchModelDefinitions,
  createModelProvider,
  updateModelProvider,
  deleteModelProvider,
  testModelProvider,
  createModelDefinition,
  updateModelDefinition,
  deleteModelDefinition,
  type ModelProviderDto,
  type ModelDefinitionDto,
} from "../lib/api";

interface ModelState {
  providers: ModelProviderDto[];
  selectedProviderId: number | null;
  models: ModelDefinitionDto[];
  loading: boolean;
  modelsLoading: boolean;
  error: string | null;

  fetchProviders: () => Promise<void>;
  selectProvider: (id: number | null) => void;
  fetchModels: (providerId: number) => Promise<void>;

  addProvider: (data: { name: string; type: string; baseUrl?: string | null; apiKey: string }) => Promise<void>;
  editProvider: (id: number, data: Record<string, unknown>) => Promise<void>;
  removeProvider: (id: number) => Promise<void>;
  testProvider: (id: number) => Promise<{ success: boolean; error?: string }>;

  addModel: (data: { providerId: number; modelId: string; displayName: string; description?: string | null }) => Promise<void>;
  editModel: (id: number, data: Record<string, unknown>) => Promise<void>;
  removeModel: (id: number) => Promise<void>;
}

export const useModelStore = create<ModelState>((set, get) => ({
  providers: [],
  selectedProviderId: null,
  models: [],
  loading: false,
  modelsLoading: false,
  error: null,

  fetchProviders: async () => {
    set({ loading: true, error: null });
    try {
      const providers = await fetchModelProviders();
      set({ providers, loading: false });
    } catch (e) {
      set({ loading: false, error: e instanceof Error ? e.message : String(e) });
    }
  },

  selectProvider: (id) => {
    set({ selectedProviderId: id, models: [] });
    if (id != null) get().fetchModels(id);
  },

  fetchModels: async (providerId) => {
    set({ modelsLoading: true });
    try {
      const models = await fetchModelDefinitions(providerId);
      set({ models, modelsLoading: false });
    } catch (e) {
      set({ modelsLoading: false, error: e instanceof Error ? e.message : String(e) });
    }
  },

  addProvider: async (data) => {
    await createModelProvider(data);
    await get().fetchProviders();
  },

  editProvider: async (id, data) => {
    await updateModelProvider(id, data);
    await get().fetchProviders();
  },

  removeProvider: async (id) => {
    await deleteModelProvider(id);
    const { selectedProviderId } = get();
    await get().fetchProviders();
    if (selectedProviderId === id) {
      set({ selectedProviderId: null, models: [] });
    }
  },

  testProvider: async (id) => {
    return await testModelProvider(id);
  },

  addModel: async (data) => {
    await createModelDefinition(data);
    await get().fetchModels(data.providerId);
  },

  editModel: async (id, data) => {
    await updateModelDefinition(id, data);
    const { selectedProviderId } = get();
    if (selectedProviderId != null) await get().fetchModels(selectedProviderId);
  },

  removeModel: async (id) => {
    await deleteModelDefinition(id);
    const { selectedProviderId } = get();
    if (selectedProviderId != null) await get().fetchModels(selectedProviderId);
  },
}));
