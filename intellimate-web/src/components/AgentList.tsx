import { Bot, Plus, Trash2 } from "lucide-react";
import type { AgentSummary } from "../lib/api";

interface AgentListProps {
  agents: AgentSummary[];
  activeAgent: string | null;
  onSelect: (name: string) => void;
  onCreateClick: () => void;
  onDelete: (name: string) => void;
}

export default function AgentList({
  agents,
  activeAgent,
  onSelect,
  onCreateClick,
  onDelete,
}: AgentListProps) {
  return (
    <div>
      <p className="text-[11px] font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider px-3 mb-2">
        智能体
      </p>
      <div className="space-y-1">
        {agents.map((agent) => {
          const isActive = agent.name === activeAgent;
          return (
            <div
              key={agent.name}
              className={`group flex items-center gap-2.5 px-3 py-2.5 rounded-lg cursor-pointer transition-all duration-150 ${
                isActive
                  ? "bg-blue-50 dark:bg-blue-900/20 ring-1 ring-blue-200/60 dark:ring-blue-800/40"
                  : "hover:bg-slate-100 dark:hover:bg-slate-700/60"
              }`}
              onClick={() => onSelect(agent.name)}
            >
              <div className={`p-1.5 rounded-md ${
                isActive
                  ? "bg-blue-100 dark:bg-blue-800/30"
                  : "bg-slate-200/70 dark:bg-slate-700"
              }`}>
                <Bot size={14} className={isActive ? "text-blue-500" : "text-slate-400"} />
              </div>
              <div className="flex-1 min-w-0">
                <p className={`text-[13px] font-medium truncate ${
                  isActive ? "text-blue-700 dark:text-blue-300" : "text-slate-700 dark:text-slate-200"
                }`}>
                  {agent.name}
                </p>
                <p className="text-[10px] text-slate-400 dark:text-slate-500 truncate mt-0.5">
                  {agent.modelDisplayName || agent.model}
                </p>
              </div>
              {isActive && (
                <span className="w-2 h-2 rounded-full bg-blue-500 shrink-0 animate-pulse" />
              )}
              {!agent.isDefault && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    if (window.confirm(`确定删除智能体「${agent.name}」吗？`)) {
                      onDelete(agent.name);
                    }
                  }}
                  className="hidden group-hover:flex items-center justify-center w-6 h-6 rounded-md hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors outline-none"
                >
                  <Trash2 size={12} className="text-red-400" />
                </button>
              )}
            </div>
          );
        })}
      </div>
      <button
        onClick={onCreateClick}
        className="w-full flex items-center gap-2.5 px-3 py-2 mt-1.5 text-[13px] text-slate-400 dark:text-slate-500 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700/60 hover:text-slate-600 dark:hover:text-slate-300 transition-colors outline-none"
      >
        <div className="p-1.5 rounded-md bg-slate-200/50 dark:bg-slate-700/50">
          <Plus size={12} className="text-slate-400" />
        </div>
        新建智能体
      </button>
    </div>
  );
}
