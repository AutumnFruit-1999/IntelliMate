import { useState } from "react";
import type { ToolCallInfo } from "../stores/chatStore";
import ToolCallCard, { StatusIndicator, getToolIcon } from "./ToolCallCard";
import { Layers, ChevronRight, ChevronDown } from "lucide-react";

interface ToolCallGroupProps {
  calls: ToolCallInfo[];
  turn?: number;
}

export default function ToolCallGroup({ calls }: ToolCallGroupProps) {
  const [expanded, setExpanded] = useState(false);

  if (calls.length === 0) return null;

  if (calls.length === 1) {
    return <ToolCallCard info={calls[0]} />;
  }

  const doneCount = calls.filter((c) => c.status === "done").length;
  const errorCount = calls.filter((c) => c.status === "error").length;
  const finishedCount = doneCount + errorCount;
  const allDone = finishedCount === calls.length;
  const hasError = errorCount > 0;

  let statusText: string;
  if (allDone) {
    statusText = hasError ? `${doneCount} 完成, ${errorCount} 失败` : "全部完成";
  } else {
    statusText = `${finishedCount}/${calls.length} 完成`;
  }

  const borderColor = hasError
    ? "border-red-200 dark:border-red-800/40"
    : allDone
      ? "border-emerald-200 dark:border-emerald-800/40"
      : "border-blue-200 dark:border-blue-800/40";

  const accentText = hasError
    ? "text-red-600 dark:text-red-400"
    : allDone
      ? "text-emerald-600 dark:text-emerald-400"
      : "text-blue-600 dark:text-blue-400";

  return (
    <div className={`my-1.5 overflow-hidden rounded-lg border ${borderColor} bg-white shadow-sm hover:shadow transition-shadow dark:bg-gray-900`}>
      <button
        type="button"
        className="flex w-full items-center gap-2 px-3 py-2.5 text-left cursor-pointer"
        onClick={() => setExpanded(!expanded)}
      >
        <Layers size={14} className={accentText} />
        <span className={`text-sm font-medium ${accentText}`}>
          并行调用 {calls.length} 个工具
        </span>

        <span className="flex items-center gap-1.5 text-xs text-gray-400 dark:text-gray-500">
          {calls.map((tc) => (
            <span key={tc.toolCallId} className="inline-flex items-center gap-0.5 text-gray-400 dark:text-gray-500">
              <StatusIndicator status={tc.status} />
              <span>{tc.name}</span>
            </span>
          ))}
        </span>

        <span className="ml-auto text-xs text-gray-400 dark:text-gray-500">{statusText}</span>
        {expanded
          ? <ChevronDown size={14} className="text-gray-400 dark:text-gray-500" />
          : <ChevronRight size={14} className="text-gray-400 dark:text-gray-500" />}
      </button>

      {expanded && (
        <div className="divide-y divide-gray-100 border-t border-gray-100 dark:divide-gray-800 dark:border-gray-800">
          {calls.map((tc) => (
            <ToolCallCard key={tc.toolCallId} info={tc} compact />
          ))}
        </div>
      )}
    </div>
  );
}
