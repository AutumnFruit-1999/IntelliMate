import { create } from "zustand";
import {
  fetchToolDefinitions,
  createToolDefinition,
  updateToolDefinition,
  deleteToolDefinition,
  testToolDefinition,
  fetchMcpServers,
  createMcpServer,
  updateMcpServer,
  deleteMcpServer,
  testMcpServer,
  testMcpServerConfig,
  type ToolDefinition,
  type ToolDefinitionCreate,
  type ToolTestResult,
  type McpServer,
  type McpServerCreate,
  type McpTestResult,
} from "../lib/api";

interface ToolStoreState {
  definitions: ToolDefinition[];
  loading: boolean;
  error: string | null;

  mcpServers: McpServer[];
  mcpLoading: boolean;
  mcpError: string | null;

  fetchDefinitions: () => Promise<void>;
  createDefinition: (data: ToolDefinitionCreate) => Promise<ToolDefinition>;
  updateDefinition: (id: number, data: Partial<ToolDefinitionCreate>) => Promise<ToolDefinition>;
  deleteDefinition: (id: number) => Promise<void>;
  testDefinition: (id: number, args: Record<string, unknown>) => Promise<ToolTestResult>;

  fetchMcpServers: () => Promise<void>;
  createMcpServer: (data: McpServerCreate) => Promise<McpServer>;
  updateMcpServer: (id: number, data: Partial<McpServerCreate> & { enabled?: number }) => Promise<McpServer>;
  deleteMcpServer: (id: number) => Promise<void>;
  testMcpServer: (id: number) => Promise<McpTestResult>;
  testMcpServerConfig: (data: McpServerCreate) => Promise<McpTestResult>;
}

export const useToolStore = create<ToolStoreState>((set) => ({
  definitions: [],
  loading: false,
  error: null,

  mcpServers: [],
  mcpLoading: false,
  mcpError: null,

  fetchDefinitions: async () => {
    set({ loading: true, error: null });
    try {
      const definitions = await fetchToolDefinitions();
      set({ definitions, loading: false });
    } catch (e) {
      set({ error: e instanceof Error ? e.message : String(e), loading: false });
    }
  },

  createDefinition: async (data: ToolDefinitionCreate) => {
    const created = await createToolDefinition(data);
    set((s) => ({ definitions: [...s.definitions, created] }));
    return created;
  },

  updateDefinition: async (id: number, data: Partial<ToolDefinitionCreate>) => {
    const updated = await updateToolDefinition(id, data);
    set((s) => ({
      definitions: s.definitions.map((d) => (d.id === id ? updated : d)),
    }));
    return updated;
  },

  deleteDefinition: async (id: number) => {
    await deleteToolDefinition(id);
    set((s) => ({
      definitions: s.definitions.filter((d) => d.id !== id),
    }));
  },

  testDefinition: async (id: number, args: Record<string, unknown>) => {
    return testToolDefinition(id, { arguments: args });
  },

  fetchMcpServers: async () => {
    set({ mcpLoading: true, mcpError: null });
    try {
      const mcpServers = await fetchMcpServers();
      set({ mcpServers, mcpLoading: false });
    } catch (e) {
      set({ mcpError: e instanceof Error ? e.message : String(e), mcpLoading: false });
    }
  },

  createMcpServer: async (data: McpServerCreate) => {
    const created = await createMcpServer(data);
    set((s) => ({ mcpServers: [...s.mcpServers, created] }));
    return created;
  },

  updateMcpServer: async (id: number, data: Partial<McpServerCreate> & { enabled?: number }) => {
    const updated = await updateMcpServer(id, data);
    set((s) => ({
      mcpServers: s.mcpServers.map((m) => (m.id === id ? updated : m)),
    }));
    return updated;
  },

  deleteMcpServer: async (id: number) => {
    await deleteMcpServer(id);
    set((s) => ({
      mcpServers: s.mcpServers.filter((m) => m.id !== id),
    }));
  },

  testMcpServer: async (id: number) => {
    return testMcpServer(id);
  },

  testMcpServerConfig: async (data: McpServerCreate) => {
    return testMcpServerConfig(data);
  },
}));
