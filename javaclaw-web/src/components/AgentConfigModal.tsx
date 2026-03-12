import { useEffect, useState, useCallback } from "react";
import { X, Loader2, Check } from "lucide-react";
import { useAgentStore } from "../stores/agentStore";
import AgentContextEditor from "./AgentContextEditor";
import ToolsTab from "./ToolsTab";
import McpToolsTab from "./McpToolsTab";
import ModelTab from "./ModelTab";

export type ContextTab = "soul" | "user" | "agents" | "tools" | "mcp" | "model";

interface AgentConfigModalProps {
  open: boolean;
  onClose: () => void;
  initialTab?: ContextTab;
  initialAgent?: string;
}

const CONTEXT_TABS: {
  key: Exclude<ContextTab, "tools" | "mcp" | "model">;
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
  { key: "model", label: "模型管理" },
];

export default function AgentConfigModal({
  open,
  onClose,
  initialTab = "soul",
  initialAgent,
}: AgentConfigModalProps) {
  const [activeTab, setActiveTab] = useState<ContextTab>(initialTab);
  const [saveSuccess, setSaveSuccess] = useState(false);

  const activeAgent = useAgentStore((s) => s.activeAgent);

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

  const agentName = initialAgent ?? activeAgent ?? null;

  useEffect(() => {
    if (open && agentName) {
      setActiveTab(initialTab);
      setSaveSuccess(false);
      fetchConfig(agentName);
    } else if (!open) {
      resetConfig();
    }
  }, [open, agentName]);

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
    } else if (activeTab === "model") {
      return;
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

  if (!open) return null;

  const isToolsTab = activeTab === "tools";
  const isMcpTab = activeTab === "mcp";
  const isModelTab = activeTab === "model";
  const contextTab = CONTEXT_TABS.find((t) => t.key === activeTab);

  const showSaveButton = activeTab !== "model";

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      onClick={handleBackdropClick}
    >
      <div className="flex flex-col w-full max-w-5xl h-[90vh] md:h-[85vh] bg-white dark:bg-slate-900 rounded-xl shadow-2xl overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-200 dark:border-slate-700">
          <div>
            <h2 className="text-lg font-semibold text-slate-800 dark:text-slate-100">
              Agent 配置
            </h2>
            {config && (
              <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
                {config.name}
                {config.model && (
                  <span className="ml-2 font-mono">{config.model}</span>
                )}
              </p>
            )}
          </div>
          <button
            onClick={handleClose}
            className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          >
            <X size={18} className="text-slate-500" />
          </button>
        </div>

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
          ) : isModelTab ? (
            <ModelTab currentModel={config?.model ?? ""} />
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
            {showSaveButton && (
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
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
