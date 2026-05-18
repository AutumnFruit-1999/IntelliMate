import type { DelegationState } from "./DelegationCard";

interface ParallelGroupProps {
  groupId: string;
  tasks: Array<{ agentName: string; task: string }>;
  agentStates: Record<string, DelegationState>;
}

export default function ParallelGroup({ groupId, tasks, agentStates }: ParallelGroupProps) {
  const completedCount = Object.values(agentStates).filter(
    (s) => s.status === "completed" || s.status === "failed"
  ).length;
  const allDone = completedCount === tasks.length;

  return (
    <div className={`border rounded-lg p-3 my-2 ${allDone ? "border-green-300 bg-green-50" : "border-blue-300 bg-blue-50"}`}>
      <div className="flex items-center gap-2 mb-2">
        <span className="text-sm font-semibold">
          ⫘ 并行执行 ({completedCount}/{tasks.length} 完成)
        </span>
        <span className="text-xs text-gray-400">ID: {groupId}</span>
      </div>
      <div className="space-y-1">
        {tasks.map((t) => {
          const state = agentStates[t.agentName];
          const status = state?.status ?? "pending";
          return (
            <div key={t.agentName} className="flex items-center gap-2 text-sm">
              <span className={`w-2 h-2 rounded-full ${
                status === "completed" ? "bg-green-500"
                : status === "failed" ? "bg-red-500"
                : status === "running" ? "bg-blue-500 animate-pulse"
                : "bg-gray-300"
              }`} />
              <span className="font-mono font-medium">{t.agentName}</span>
              <span className="text-xs text-gray-500 truncate max-w-[200px]">{t.task}</span>
              {state?.durationMs != null && (
                <span className="text-xs text-gray-400 ml-auto">{(state.durationMs / 1000).toFixed(1)}s</span>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
