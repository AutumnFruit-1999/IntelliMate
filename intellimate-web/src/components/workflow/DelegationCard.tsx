import { useState } from "react";

export interface DelegationState {
  delegationId: string;
  workerAgent: string;
  task: string;
  status: "running" | "completed" | "failed";
  nestedToolCalls: Array<{ name: string; arguments: string; success?: boolean }>;
  textChunks: string[];
  result?: string;
  turnsUsed?: number;
  durationMs?: number;
}

interface DelegationCardProps {
  delegation: DelegationState;
}

export default function DelegationCard({ delegation }: DelegationCardProps) {
  const [expanded, setExpanded] = useState(false);

  const statusColors: Record<string, string> = {
    running: "border-blue-400 bg-blue-50",
    completed: "border-green-400 bg-green-50",
    failed: "border-red-400 bg-red-50",
  };

  const statusIcons: Record<string, string> = {
    running: "⏳",
    completed: "✓",
    failed: "✗",
  };

  return (
    <div className={`border-l-4 rounded-md p-3 my-2 ${statusColors[delegation.status] ?? "border-gray-300 bg-gray-50"}`}>
      <div
        className="flex items-center justify-between cursor-pointer select-none"
        onClick={() => setExpanded(!expanded)}
      >
        <div className="flex items-center gap-2">
          <span className="text-sm font-mono font-semibold">
            {statusIcons[delegation.status]} 委派: {delegation.workerAgent}
          </span>
          <span className="text-xs text-gray-500 truncate max-w-[300px]">
            {delegation.task}
          </span>
        </div>
        <div className="flex items-center gap-2 text-xs text-gray-400">
          {delegation.durationMs != null && (
            <span>{(delegation.durationMs / 1000).toFixed(1)}s</span>
          )}
          {delegation.turnsUsed != null && (
            <span>{delegation.turnsUsed} turns</span>
          )}
          <span>{expanded ? "▲" : "▼"}</span>
        </div>
      </div>

      {expanded && (
        <div className="mt-2 pl-2 border-l border-gray-200 space-y-1">
          {delegation.nestedToolCalls.map((tc, i) => (
            <div key={i} className="flex items-center gap-1 text-xs text-gray-600">
              <span className="font-mono">{tc.success === false ? "✗" : tc.success === true ? "✓" : "…"}</span>
              <span className="font-semibold">{tc.name}</span>
              <span className="truncate max-w-[200px] text-gray-400">{tc.arguments}</span>
            </div>
          ))}
          {delegation.result && (
            <div className="mt-2 text-sm text-gray-700 whitespace-pre-wrap bg-white rounded p-2 border border-gray-100">
              {delegation.result.length > 500
                ? delegation.result.slice(0, 500) + "..."
                : delegation.result}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
