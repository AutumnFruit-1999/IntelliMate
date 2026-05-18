import { useState } from "react";
import { ArrowLeft, Settings } from "lucide-react";
import CustomToolsPanel from "./CustomToolsPanel";
import McpServerPanel from "./McpServerPanel";

type TabKey = "custom" | "mcp";

interface ToolManagerPageProps {
  onBack: () => void;
}

const TABS: { key: TabKey; label: string }[] = [
  { key: "custom", label: "自定义工具" },
  { key: "mcp", label: "MCP 服务" },
];

export default function ToolManagerPage({ onBack }: ToolManagerPageProps) {
  const [tab, setTab] = useState<TabKey>("custom");

  return (
    <div className="flex flex-col flex-1 min-w-0 min-h-0">
      {/* Header */}
      <div className="px-6 py-4 border-b border-slate-200 dark:border-slate-700 flex items-center gap-3">
        <button
          onClick={onBack}
          className="p-1.5 rounded hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
        >
          <ArrowLeft size={18} className="text-slate-500" />
        </button>
        <Settings size={20} className="text-blue-500" />
        <h1 className="text-lg font-semibold">工具管理</h1>

        <div className="ml-auto flex items-center gap-2">
          {TABS.map((t) => (
            <button
              key={t.key}
              onClick={() => setTab(t.key)}
              className={`px-3 py-1.5 text-sm font-medium rounded-lg transition-colors ${
                tab === t.key
                  ? "bg-blue-50 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400"
                  : "text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {/* Body */}
      <div className="flex-1 min-h-0 overflow-y-auto px-6 py-4">
        {tab === "custom" && <CustomToolsPanel />}
        {tab === "mcp" && <McpServerPanel />}
      </div>
    </div>
  );
}
