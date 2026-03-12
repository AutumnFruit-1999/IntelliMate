import { useEffect, useState, useCallback } from "react";
import { X, Loader2, Check, Bot, Plus, Trash2 } from "lucide-react";
import { useAgentStore } from "../stores/agentStore";
import AgentContextEditor from "./AgentContextEditor";
import ToolsTab from "./ToolsTab";
import McpToolsTab from "./McpToolsTab";
import ModelSelector from "./ModelSelector";

export type ContextTab = "soul" | "user" | "agents" | "tools" | "mcp";

interface AgentConfigModalProps {
  open: boolean;
  onClose: () => void;
  initialTab?: ContextTab;
  initialAgent?: string;
}

const CONTEXT_TABS: {
  key: Exclude<ContextTab, "tools">;
  label: string;
  field: "soulMd" | "userMd" | "agentsMd";
  title: string;
  desc: string;
  placeholder: string;
}[] = [
  {
    key: "soul",
    label: "SOUL",
    field: "soulMd",
    title: "性格与行为",
    desc: "定义助手的性格、语气风格和回答边界",
    placeholder:
      "例如：\n你是一个友好且专业的技术助手。\n你擅长 Java / Spring 技术栈。\n你回复时语气简洁、逻辑清晰，避免冗余。",
  },
  {
    key: "user",
    label: "USER",
    field: "userMd",
    title: "用户信息",
    desc: "描述你是谁，帮助助手更好地理解你",
    placeholder:
      "例如：\n我是一名后端开发工程师，主要使用 Java 21 + Spring Boot 3。\n时区：UTC+8（北京时间）\n偏好：中文回复，代码注释使用英文。",
  },
  {
    key: "agents",
    label: "AGENTS",
    field: "agentsMd",
    title: "工作规范",
    desc: "设定助手的工作习惯、回复格式和行为准则",
    placeholder:
      "例如：\n- 回复使用中文，代码注释使用英文\n- 优先使用项目现有的工具类，避免重复造轮子\n- 异常处理必须精准捕获，禁止吞异常",
  },
];

const ALL_TABS: { key: ContextTab; label: string }[] = [
  { key: "soul", label: "SOUL" },
  { key: "user", label: "USER" },
  { key: "agents", label: "AGENTS" },
  { key: "tools", label: "工具选择" },
  { key: "mcp", label: "MCP 工具" },
];

export default function AgentConfigModal({
  open,
  onClose,
  initialTab = "soul",
  initialAgent,
}: AgentConfigModalProps) {
  const [activeTab, setActiveTab] = useState<ContextTab>(initialTab);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [selectedAgent, setSelectedAgent] = useState<string | null>(null);

  const [showCreateForm, setShowCreateForm] = useState(false);
  const [newName, setNewName] = useState("");
  const [newModel, setNewModel] = useState("qwen-plus");
  const [createError, setCreateError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  const agents = useAgentStore((s) => s.agents);
  const activeAgent = useAgentStore((s) => s.activeAgent);
  const fetchAgentList = useAgentStore((s) => s.fetchAgentList);
  const createAgent = useAgentStore((s) => s.createAgent);
  const removeAgent = useAgentStore((s) => s.removeAgent);

  const {
    config,
    draft,
    toolsEnabledDraft,
    mcpToolsEnabledDraft,
    loading,
    saving,
    dirty,
    error,
    fetchConfig,
    updateField,
    setToolsEnabled,
    setMcpToolsEnabled,
    saveConfig,
    saveToolsEnabled,
    saveMcpToolsEnabled,
    resetConfig,
  } = useAgentStore();

  useEffect(() => {
    if (open) {
      setActiveTab(initialTab);
      setSaveSuccess(false);
      setShowCreateForm(false);
      fetchAgentList().then(() => {
        const agentsList = useAgentStore.getState().agents;
        const target = initialAgent ?? activeAgent ?? agentsList[0]?.name ?? null;
        setSelectedAgent(target);
        if (target) fetchConfig(target);
      });
    } else {
      resetConfig();
    }
  }, [open, initialAgent]);

  const switchAgent = useCallback(
    (name: string) => {
      if (name === selectedAgent) return;
      if (dirty && !window.confirm("有未保存的修改，切换将丢弃更改，是否继续？")) {
        return;
      }
      setSelectedAgent(name);
      setActiveTab("soul");
      setSaveSuccess(false);
      fetchConfig(name);
    },
    [selectedAgent, dirty, fetchConfig],
  );

  const handleClose = useCallback(() => {
    if (dirty && !window.confirm("有未保存的修改，确定关闭吗？")) {
      return;
    }
    onClose();
  }, [dirty, onClose]);

  const handleSave = useCallback(async () => {
    if (activeTab === "tools") {
      await saveToolsEnabled();
    } else if (activeTab === "mcp") {
      await saveMcpToolsEnabled();
    } else {
      await saveConfig();
    }
    setSaveSuccess(true);
    setTimeout(() => setSaveSuccess(false), 2000);
  }, [activeTab, saveConfig, saveToolsEnabled, saveMcpToolsEnabled]);

  const handleBackdropClick = useCallback(
    (e: React.MouseEvent) => {
      if (e.target === e.currentTarget) handleClose();
    },
    [handleClose],
  );

  const handleCreate = useCallback(async () => {
    const trimmed = newName.trim();
    if (!trimmed) {
      setCreateError("名称不能为空");
      return;
    }
    if (!/^[a-zA-Z0-9_-]+$/.test(trimmed)) {
      setCreateError("名称只能包含字母、数字、下划线和连字符");
      return;
    }
    setCreating(true);
    setCreateError(null);
    try {
      await createAgent(trimmed, newModel);
      setNewName("");
      setNewModel("qwen-plus");
      setShowCreateForm(false);
      setSelectedAgent(trimmed);
      fetchConfig(trimmed);
    } catch (e) {
      setCreateError(e instanceof Error ? e.message : String(e));
    } finally {
      setCreating(false);
    }
  }, [newName, newModel, createAgent, fetchConfig]);

  const handleDelete = useCallback(
    async (name: string) => {
      if (!window.confirm(`确定删除智能体「${name}」吗？`)) return;
      await removeAgent(name);
      if (selectedAgent === name) {
        const remaining = useAgentStore.getState().agents;
        const next = remaining[0]?.name ?? null;
        setSelectedAgent(next);
        if (next) fetchConfig(next);
        else resetConfig();
      }
    },
    [selectedAgent, removeAgent, fetchConfig, resetConfig],
  );

  if (!open) return null;

  const isToolsTab = activeTab === "tools";
  const isMcpTab = activeTab === "mcp";
  const contextTab = CONTEXT_TABS.find((t) => t.key === activeTab);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      onClick={handleBackdropClick}
    >
      <div className="flex w-full max-w-5xl max-h-[90vh] md:max-h-[85vh] bg-white dark:bg-slate-900 rounded-xl shadow-2xl overflow-hidden">
        {/* Left: Agent List */}
        <div className="w-52 shrink-0 border-r border-slate-200 dark:border-slate-700 flex flex-col bg-slate-50 dark:bg-slate-800/50">
          <div className="px-4 py-3 border-b border-slate-200 dark:border-slate-700">
            <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-200">
              Agent 管理
            </h2>
          </div>

          <div className="flex-1 overflow-y-auto p-2 space-y-0.5">
            {agents.map((agent) => {
              const isSelected = agent.name === selectedAgent;
              return (
                <div
                  key={agent.name}
                  className={`group flex items-center gap-2 px-3 py-2 rounded-lg cursor-pointer transition-colors ${
                    isSelected
                      ? "bg-blue-50 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300"
                      : "text-slate-600 dark:text-slate-300 hover:bg-slate-200/60 dark:hover:bg-slate-700"
                  }`}
                  onClick={() => switchAgent(agent.name)}
                >
                  <Bot
                    size={16}
                    className={isSelected ? "text-blue-500" : "text-slate-400"}
                  />
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium truncate">{agent.name}</p>
                    <p className="text-[10px] text-slate-400 dark:text-slate-500 truncate">
                      {agent.model}
                    </p>
                  </div>
                  {isSelected && (
                    <span className="w-1.5 h-1.5 rounded-full bg-blue-500 shrink-0" />
                  )}
                  {!agent.isDefault && (
                    <button
                      onClick={(e) => {
                        e.stopPropagation();
                        handleDelete(agent.name);
                      }}
                      className="hidden group-hover:block p-0.5 rounded hover:bg-red-100 dark:hover:bg-red-900/30 transition-colors"
                    >
                      <Trash2 size={12} className="text-red-400" />
                    </button>
                  )}
                </div>
              );
            })}
          </div>

          {/* Create Agent */}
          <div className="border-t border-slate-200 dark:border-slate-700 p-2">
            {showCreateForm ? (
              <div className="space-y-2 p-2">
                <input
                  type="text"
                  value={newName}
                  onChange={(e) => setNewName(e.target.value)}
                  placeholder="名称"
                  className="w-full px-2 py-1.5 rounded border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-xs text-slate-800 dark:text-slate-200 placeholder:text-slate-400 focus:outline-none focus:ring-1 focus:ring-blue-500/40"
                />
                <ModelSelector
                  value={newModel}
                  onChange={(m) => setNewModel(m)}
                />
                {createError && (
                  <p className="text-[11px] text-red-500">{createError}</p>
                )}
                <div className="flex gap-1.5">
                  <button
                    onClick={() => {
                      setShowCreateForm(false);
                      setCreateError(null);
                    }}
                    className="flex-1 px-2 py-1 text-xs text-slate-500 rounded hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
                  >
                    取消
                  </button>
                  <button
                    onClick={handleCreate}
                    disabled={creating}
                    className="flex-1 flex items-center justify-center gap-1 px-2 py-1 text-xs font-medium text-white bg-blue-600 rounded hover:bg-blue-700 disabled:opacity-50 transition-colors"
                  >
                    {creating && <Loader2 size={10} className="animate-spin" />}
                    创建
                  </button>
                </div>
              </div>
            ) : (
              <button
                onClick={() => setShowCreateForm(true)}
                className="w-full flex items-center gap-2 px-3 py-2 text-sm text-slate-500 dark:text-slate-400 rounded-lg hover:bg-slate-200/60 dark:hover:bg-slate-700 transition-colors"
              >
                <Plus size={14} />
                新建智能体
              </button>
            )}
          </div>
        </div>

        {/* Right: Config Panel */}
        <div className="flex-1 flex flex-col min-w-0">
          {/* Header */}
          <div className="flex items-center justify-between px-6 py-4 border-b border-slate-200 dark:border-slate-700">
            <div className="flex items-center gap-3">
              <div>
                <h2 className="text-lg font-semibold text-slate-800 dark:text-slate-100">
                  Agent 配置
                </h2>
                {config && (
                  <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
                    {config.name}
                  </p>
                )}
              </div>
              {config && (
                <ModelSelector
                  value={config.model}
                  onChange={async (modelId) => {
                    if (modelId !== config.model) {
                      const { saveModel } = useAgentStore.getState();
                      await saveModel(modelId);
                    }
                  }}
                />
              )}
            </div>
            <button
              onClick={handleClose}
              className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
            >
              <X size={18} className="text-slate-500" />
            </button>
          </div>

          {selectedAgent ? (
            <>
              {/* Tabs */}
              <div className="flex border-b border-slate-200 dark:border-slate-700 px-6">
                {ALL_TABS.map((t) => (
                  <button
                    key={t.key}
                    onClick={() => setActiveTab(t.key)}
                    className={`px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
                      activeTab === t.key
                        ? "border-blue-600 text-blue-600 dark:text-blue-400 dark:border-blue-400"
                        : "border-transparent text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300"
                    }`}
                  >
                    {t.label}
                  </button>
                ))}
              </div>

              {/* Body */}
              <div className="flex-1 min-h-0 overflow-y-auto px-6 py-4">
                {loading ? (
                  <div className="flex items-center justify-center py-16">
                    <Loader2 size={24} className="animate-spin text-slate-400" />
                  </div>
                ) : error && !config ? (
                  <div className="flex items-center justify-center text-red-500 text-sm py-16">
                    {error}
                  </div>
                ) : isToolsTab ? (
                  <ToolsTab
                    toolsEnabled={toolsEnabledDraft}
                    onChange={setToolsEnabled}
                  />
                ) : isMcpTab ? (
                  <McpToolsTab
                    mcpToolsEnabled={mcpToolsEnabledDraft}
                    onChange={setMcpToolsEnabled}
                  />
                ) : contextTab ? (
                  <div className="flex-1 flex flex-col min-h-0">
                    <div className="mb-3">
                      <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">
                        {contextTab.title}
                      </h3>
                      <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
                        {contextTab.desc}
                      </p>
                    </div>
                    <AgentContextEditor
                      value={draft[contextTab.field]}
                      onChange={(val) => updateField(contextTab.field, val)}
                      placeholder={contextTab.placeholder}
                    />
                  </div>
                ) : null}
              </div>

              {/* Footer */}
              <div className="flex items-center justify-between px-6 py-3 border-t border-slate-200 dark:border-slate-700">
                <div className="text-xs text-slate-400 dark:text-slate-500">
                  {error && config && (
                    <span className="text-red-500">{error}</span>
                  )}
                </div>
                <div className="flex gap-2">
                  <button
                    onClick={handleClose}
                    className="px-4 py-2 text-sm text-slate-600 dark:text-slate-300 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                  >
                    取消
                  </button>
                  <button
                    onClick={handleSave}
                    disabled={saving || !dirty}
                    className="flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                  >
                    {saving ? (
                      <Loader2 size={14} className="animate-spin" />
                    ) : saveSuccess ? (
                      <Check size={14} />
                    ) : null}
                    {saveSuccess ? "已保存" : "保存"}
                  </button>
                </div>
              </div>
            </>
          ) : (
            <div className="flex-1 flex items-center justify-center text-slate-400 dark:text-slate-500 text-sm">
              请在左侧选择或创建一个 Agent
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
