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
      <p className="text-xs font-medium text-slate-500 dark:text-slate-400 uppercase tracking-wider mb-2">
        智能体
      </p>
      <div className="space-y-0.5">
        {agents.map((agent) => {
          const isActive = agent.name === activeAgent;
          return (
            <div
              key={agent.name}
              className={`group flex items-center gap-2 px-3 py-2 rounded-lg cursor-pointer transition-colors ${
                isActive
                  ? "bg-blue-50 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300"
                  : "text-slate-600 dark:text-slate-300 hover:bg-slate-200/60 dark:hover:bg-slate-700"
              }`}
              onClick={() => onSelect(agent.name)}
            >
              <Bot size={16} className={isActive ? "text-blue-500" : "text-slate-400"} />
              <div className="flex-1 min-w-0">
                <p className="text-sm font-medium truncate">{agent.name}</p>
                <p className="text-[10px] text-slate-400 dark:text-slate-500 truncate">
                  {agent.model}
                </p>
              </div>
              {isActive && (
                <span className="w-1.5 h-1.5 rounded-full bg-blue-500 shrink-0" />
              )}
              {!agent.isDefault && (
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    if (window.confirm(`确定删除智能体「${agent.name}」吗？`)) {
                      onDelete(agent.name);
                    }
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
      <button
        onClick={onCreateClick}
        className="w-full flex items-center gap-2 px-3 py-2 mt-1 text-sm text-slate-500 dark:text-slate-400 rounded-lg hover:bg-slate-200/60 dark:hover:bg-slate-700 transition-colors"
      >
        <Plus size={14} />
        新建智能体
      </button>
    </div>
  );
}
