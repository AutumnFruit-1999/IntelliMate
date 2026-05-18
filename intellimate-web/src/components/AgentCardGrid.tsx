import { useEffect } from "react";
import { Plus, ArrowLeft } from "lucide-react";
import { useAgentStore } from "../stores/agentStore";

interface AgentCardGridProps {
  onSelectAgent: (name: string) => void;
  onCreateAgent: () => void;
  onBack: () => void;
}

export default function AgentCardGrid({
  onSelectAgent,
  onCreateAgent,
  onBack,
}: AgentCardGridProps) {
  const agents = useAgentStore((s) => s.agents);
  const fetchAgentList = useAgentStore((s) => s.fetchAgentList);

  useEffect(() => {
    fetchAgentList();
  }, [fetchAgentList]);

  return (
    <div className="flex flex-col flex-1 min-w-0 min-h-0 overflow-y-auto">
      <div className="px-6 py-5 border-b border-slate-200 dark:border-slate-700 flex items-center gap-3">
        <button
          onClick={onBack}
          className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
        >
          <ArrowLeft size={18} className="text-slate-500" />
        </button>
        <div>
          <h2 className="text-lg font-semibold text-slate-800 dark:text-slate-100">
            Agent 管理
          </h2>
          <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
            选择一个 Agent 进行配置
          </p>
        </div>
      </div>

      <div className="flex-1 p-6">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
          {agents.map((agent) => (
            <div
              key={agent.name}
              onClick={() => onSelectAgent(agent.name)}
              className="group relative bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl p-5 cursor-pointer hover:border-blue-400 dark:hover:border-blue-500 hover:shadow-md transition-all"
            >
              <div className="flex items-start justify-between mb-3">
                <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-100 truncate">
                  {agent.name}
                </h3>
                <span className="shrink-0 ml-2 px-2 py-0.5 text-[10px] font-medium rounded-full bg-green-50 dark:bg-green-900/20 text-green-600 dark:text-green-400 border border-green-200 dark:border-green-800">
                  {agent.isDefault ? "默认" : "自定义"}
                </span>
              </div>

              <p className="text-xs text-slate-400 dark:text-slate-500 mb-3 truncate">
                {agent.modelDisplayName || agent.model}
              </p>

              <div className="flex flex-wrap gap-1.5">
                {agent.hasSoul && (
                  <span className="px-1.5 py-0.5 text-[10px] rounded bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400">
                    SOUL
                  </span>
                )}
                {agent.hasUser && (
                  <span className="px-1.5 py-0.5 text-[10px] rounded bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400">
                    USER
                  </span>
                )}
                {agent.hasAgents && (
                  <span className="px-1.5 py-0.5 text-[10px] rounded bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400">
                    AGENTS
                  </span>
                )}
              </div>
            </div>
          ))}

          {/* New Agent Card */}
          <div
            onClick={onCreateAgent}
            className="flex flex-col items-center justify-center border-2 border-dashed border-slate-200 dark:border-slate-700 rounded-xl p-5 cursor-pointer hover:border-blue-400 dark:hover:border-blue-500 hover:bg-blue-50/50 dark:hover:bg-blue-900/10 transition-all min-h-[140px]"
          >
            <Plus size={24} className="text-slate-300 dark:text-slate-600 mb-2" />
            <span className="text-sm text-slate-400 dark:text-slate-500">
              新建智能体
            </span>
          </div>
        </div>
      </div>
    </div>
  );
}
