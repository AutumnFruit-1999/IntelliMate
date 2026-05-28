import { useState, useEffect, useCallback } from "react";
import type { ToolCallInfo } from "../stores/chatStore";
import {
  X,
  ChevronRight, ChevronDown,
} from "lucide-react";

interface ToolCallTimelineModalProps {
  toolCalls: ToolCallInfo[];
  open: boolean;
  onClose: () => void;
}

function formatDuration(ms?: number): string {
  if (ms == null) return "";
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function TimelineNode({ tc }: { tc: ToolCallInfo }) {
  const [expanded, setExpanded] = useState(false);
  const hasDetail = !!(tc.arguments || tc.result !== undefined);
  const MAX_CONTENT = 2000;

  const dotClass =
    tc.status === "calling"
      ? "bg-blue-500 animate-pulse"
      : tc.status === "done"
        ? "bg-emerald-500"
        : "bg-red-500";

  return (
    <div className="relative py-2">
      <div
        className={`absolute -left-[23px] top-[14px] h-3 w-3 rounded-full border-2 border-white shadow-[0_0_0_1px_#e2e8f0] dark:border-slate-900 dark:shadow-[0_0_0_1px_#334155] ${dotClass}`}
      />
      <button
        type="button"
        className="flex w-full items-center gap-2 text-left hover:bg-slate-50 dark:hover:bg-slate-800/30 -mx-2 px-2 rounded-lg transition-colors"
        onClick={() => hasDetail && setExpanded(!expanded)}
      >
        <span className="text-[13px] font-medium text-slate-700 dark:text-slate-200 flex-1 truncate">
          {tc.name}
        </span>
        <span
          className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${
            tc.status === "done"
              ? "text-emerald-600 bg-emerald-50 dark:text-emerald-400 dark:bg-emerald-900/30"
              : tc.status === "error"
                ? "text-red-600 bg-red-50 dark:text-red-400 dark:bg-red-900/30"
                : "text-blue-600 bg-blue-50 dark:text-blue-400 dark:bg-blue-900/30"
          }`}
        >
          {tc.status === "calling" ? "调用中" : tc.status === "done" ? "完成" : "失败"}
        </span>
        {tc.duration != null && (
          <span className="text-[10px] text-slate-400 dark:text-slate-500">
            {formatDuration(tc.duration)}
          </span>
        )}
        {hasDetail && (
          expanded
            ? <ChevronDown size={10} className="text-slate-400 flex-shrink-0" />
            : <ChevronRight size={10} className="text-slate-400 flex-shrink-0" />
        )}
      </button>
      {tc.description && (
        <div className="text-[11px] text-slate-400 dark:text-slate-500 mt-0.5 truncate">
          {tc.description}
        </div>
      )}
      {expanded && (
        <div className="mt-2 space-y-1.5">
          {tc.arguments && (
            <pre className="text-[10px] leading-relaxed whitespace-pre-wrap break-all max-h-28 overflow-auto rounded-md bg-slate-50 dark:bg-slate-800/60 px-2.5 py-1.5 text-slate-500 dark:text-slate-400">
              {tc.arguments.length > MAX_CONTENT ? tc.arguments.slice(0, MAX_CONTENT) + "..." : tc.arguments}
            </pre>
          )}
          {tc.result !== undefined && (
            <pre
              className={`text-[10px] leading-relaxed whitespace-pre-wrap break-all max-h-32 overflow-auto rounded-md px-2.5 py-1.5 ${
                tc.success === false
                  ? "bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400"
                  : "bg-emerald-50 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-400"
              }`}
            >
              {tc.result.length > MAX_CONTENT ? tc.result.slice(0, MAX_CONTENT) + "..." : tc.result}
            </pre>
          )}
        </div>
      )}
    </div>
  );
}

export default function ToolCallTimelineModal({ toolCalls, open, onClose }: ToolCallTimelineModalProps) {
  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    },
    [onClose],
  );

  useEffect(() => {
    if (open) {
      document.addEventListener("keydown", handleKeyDown);
      return () => document.removeEventListener("keydown", handleKeyDown);
    }
  }, [open, handleKeyDown]);

  if (!open) return null;

  const doneCount = toolCalls.filter((tc) => tc.status === "done").length;
  const errorCount = toolCalls.filter((tc) => tc.status === "error").length;
  const callingCount = toolCalls.filter((tc) => tc.status === "calling").length;
  let earliest = Infinity;
  let latest = 0;
  for (const tc of toolCalls) {
    if (tc.startTime != null) {
      earliest = Math.min(earliest, tc.startTime);
      const end = tc.startTime + (tc.duration ?? 0);
      latest = Math.max(latest, end);
    }
  }
  const wallClockDuration = earliest < Infinity && latest > 0 ? latest - earliest : 0;

  const summaryParts: string[] = [];
  summaryParts.push(`共 ${toolCalls.length} 次调用`);
  if (doneCount > 0) summaryParts.push(`${doneCount} 成功`);
  if (errorCount > 0) summaryParts.push(`${errorCount} 失败`);
  if (callingCount > 0) summaryParts.push(`${callingCount} 进行中`);
  if (wallClockDuration > 0) summaryParts.push(formatDuration(wallClockDuration));

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        className="w-[480px] max-h-[80vh] bg-white dark:bg-slate-900 rounded-2xl shadow-2xl overflow-hidden animate-[modalIn_200ms_ease-out]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-5 py-4 border-b border-slate-100 dark:border-slate-800">
          <div>
            <h3 className="text-[15px] font-semibold text-slate-800 dark:text-slate-100">
              工具调用链路
            </h3>
            <div className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
              {summaryParts.join(" · ")}
            </div>
          </div>
          <button
            type="button"
            className="w-7 h-7 rounded-lg bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 flex items-center justify-center hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
            onClick={onClose}
          >
            <X size={14} />
          </button>
        </div>
        <div className="px-5 py-4 max-h-[60vh] overflow-y-auto">
          <div className="ml-2 border-l-2 border-slate-200 dark:border-slate-700 pl-5">
            {toolCalls.map((tc) => (
              <TimelineNode key={tc.toolCallId} tc={tc} />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
