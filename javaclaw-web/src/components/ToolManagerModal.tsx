import { useState, useCallback } from "react";
import { X } from "lucide-react";
import CustomToolsPanel from "./CustomToolsPanel";
import McpServerPanel from "./McpServerPanel";

type TabKey = "custom" | "mcp";

interface ToolManagerModalProps {
  open: boolean;
  onClose: () => void;
}

const TABS: { key: TabKey; label: string }[] = [
  { key: "custom", label: "自定义工具" },
  { key: "mcp", label: "MCP 服务" },
];

export default function ToolManagerModal({ open, onClose }: ToolManagerModalProps) {
  const [tab, setTab] = useState<TabKey>("custom");

  const handleBackdropClick = useCallback(
    (e: React.MouseEvent) => {
      if (e.target === e.currentTarget) onClose();
    },
    [onClose],
  );

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      onClick={handleBackdropClick}
    >
      <div className="flex flex-col w-full max-w-5xl max-h-[90vh] md:max-h-[85vh] bg-white dark:bg-slate-900 rounded-xl shadow-2xl overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-200 dark:border-slate-700">
          <div>
            <h2 className="text-lg font-semibold text-slate-800 dark:text-slate-100">
              工具管理
            </h2>
            <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
              管理全局可用的自定义工具和 MCP 服务
            </p>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          >
            <X size={18} className="text-slate-500" />
          </button>
        </div>

        {/* Tabs */}
        <div className="flex border-b border-slate-200 dark:border-slate-700 px-6">
          {TABS.map((t) => (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className={`px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
                tab === t.key
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
          {tab === "custom" && <CustomToolsPanel />}
          {tab === "mcp" && <McpServerPanel />}
        </div>
      </div>
    </div>
  );
}
