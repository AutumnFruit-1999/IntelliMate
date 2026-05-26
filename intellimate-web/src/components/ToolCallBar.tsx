import { useState, useMemo } from "react";
import type { ToolCallInfo } from "../stores/chatStore";
import {
  CheckCircle2, XCircle, Loader2, AlertTriangle,
  ChevronRight,
} from "lucide-react";
import ToolCallTimelineModal from "./ToolCallTimelineModal";

interface ToolCallBarProps {
  toolCalls: ToolCallInfo[];
}

function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function getWallClockDuration(toolCalls: ToolCallInfo[]): string {
  let earliest = Infinity;
  let latest = 0;
  for (const tc of toolCalls) {
    if (tc.startTime != null) {
      earliest = Math.min(earliest, tc.startTime);
      const end = tc.startTime + (tc.duration ?? 0);
      latest = Math.max(latest, end);
    }
  }
  if (earliest === Infinity || latest === 0) return "";
  const elapsed = latest - earliest;
  if (elapsed <= 0) return "";
  return formatDuration(elapsed);
}

export default function ToolCallBar({ toolCalls }: ToolCallBarProps) {
  const [modalOpen, setModalOpen] = useState(false);

  const { doneCount, errorCount, callingCount, currentToolName } = useMemo(() => {
    let done = 0;
    let error = 0;
    let calling = 0;
    let currentName = "";
    for (const tc of toolCalls) {
      if (tc.status === "done") done++;
      else if (tc.status === "error") error++;
      else if (tc.status === "calling") {
        calling++;
        currentName = tc.name;
      }
    }
    return { doneCount: done, errorCount: error, callingCount: calling, currentToolName: currentName };
  }, [toolCalls]);

  const isRunning = callingCount > 0;
  const allDone = !isRunning;
  const hasError = errorCount > 0;
  const allFailed = errorCount === toolCalls.length;

  const borderColor = isRunning
    ? "border-l-blue-500"
    : allFailed
      ? "border-l-red-500"
      : hasError
        ? "border-l-amber-500"
        : "border-l-emerald-500";

  const durationText = allDone ? getWallClockDuration(toolCalls) : "";

  let label: string;
  let icon: React.ReactNode;

  if (isRunning) {
    label = callingCount > 1
      ? `正在并行调用 ${callingCount} 个工具...`
      : `正在调用 ${currentToolName}...`;
    icon = <Loader2 size={14} className="animate-spin text-blue-500 flex-shrink-0" />;
  } else if (allFailed) {
    label = `${toolCalls.length} 个调用全部失败`;
    icon = <XCircle size={14} className="text-red-500 flex-shrink-0" />;
  } else if (hasError) {
    label = `${doneCount} 完成, ${errorCount} 失败`;
    icon = <AlertTriangle size={14} className="text-amber-500 flex-shrink-0" />;
  } else {
    label = `已完成 ${toolCalls.length} 个工具调用`;
    icon = <CheckCircle2 size={14} className="text-emerald-500 flex-shrink-0" />;
  }

  const completedCount = doneCount + errorCount;

  return (
    <>
      <button
        type="button"
        className={`my-1.5 flex w-full items-center gap-2 rounded-[10px] border border-slate-200 dark:border-slate-700/50 border-l-[3px] ${borderColor} bg-white dark:bg-slate-900 px-3.5 py-2.5 text-left shadow-sm hover:shadow transition-shadow cursor-pointer`}
        onClick={() => setModalOpen(true)}
      >
        {icon}
        <span className="text-[13px] font-medium text-slate-700 dark:text-slate-200 flex-1 truncate">
          {label}
        </span>
        {isRunning && completedCount > 0 && (
          <span className="text-[11px] text-slate-400 dark:text-slate-500">
            {completedCount} 已完成
          </span>
        )}
        {durationText && (
          <span className="text-[11px] text-slate-400 dark:text-slate-500">{durationText}</span>
        )}
        <ChevronRight size={12} className="text-slate-400 dark:text-slate-500 flex-shrink-0" />
      </button>
      <ToolCallTimelineModal
        toolCalls={toolCalls}
        open={modalOpen}
        onClose={() => setModalOpen(false)}
      />
    </>
  );
}
