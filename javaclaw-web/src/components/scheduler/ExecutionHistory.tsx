import { useState } from "react";
import { ChevronDown, ChevronRight } from "lucide-react";
import type { ScheduledJobLog } from "../../lib/schedulerApi";

interface ExecutionHistoryProps {
  logs: ScheduledJobLog[];
}

function statusBadge(status: string) {
  const colors: Record<string, string> = {
    SUCCESS: "bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400",
    FAILED: "bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400",
    TIMEOUT: "bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400",
    RUNNING: "bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400",
    SKIPPED: "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-400",
    RETRYING: "bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400",
  };
  return (
    <span className={`px-2 py-0.5 rounded-full text-[10px] font-medium ${colors[status] || colors.SKIPPED}`}>
      {status}
    </span>
  );
}

function formatTime(iso: string | null): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("zh-CN", { hour12: false, month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

export default function ExecutionHistory({ logs }: ExecutionHistoryProps) {
  const [expandedId, setExpandedId] = useState<number | null>(null);

  if (logs.length === 0) {
    return <p className="text-sm text-slate-400 py-8 text-center">暂无执行记录</p>;
  }

  return (
    <div className="space-y-1">
      {logs.map((log) => (
        <div key={log.id} className="border border-slate-200 dark:border-slate-700 rounded-lg overflow-hidden">
          <button
            onClick={() => setExpandedId(expandedId === log.id ? null : log.id)}
            className="w-full flex items-center gap-3 px-3 py-2.5 text-left hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors"
          >
            {expandedId === log.id ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
            <span className="text-xs font-medium text-slate-700 dark:text-slate-200 w-32 truncate">
              {log.jobName}
            </span>
            <span className="text-xs text-slate-500 w-28">{formatTime(log.fireTime)}</span>
            <span className="text-xs text-slate-500 w-16">
              {log.durationMs != null ? `${log.durationMs}ms` : "—"}
            </span>
            {statusBadge(log.status)}
            <span className="text-[10px] text-slate-400 ml-auto">{log.triggerSource}</span>
          </button>

          {expandedId === log.id && (
            <div className="px-4 py-3 border-t border-slate-100 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/30 space-y-2 text-xs">
              {log.resultMessage && (
                <div>
                  <span className="text-slate-400">结果: </span>
                  <span className="text-slate-700 dark:text-slate-200">{log.resultMessage}</span>
                </div>
              )}
              {log.errorMessage && (
                <div>
                  <span className="text-red-400">错误: </span>
                  <span className="text-red-600 dark:text-red-400">{log.errorMessage}</span>
                </div>
              )}
              {log.errorStack && (
                <pre className="mt-2 p-2 bg-slate-900 text-slate-300 rounded text-[10px] overflow-x-auto max-h-40">
                  {log.errorStack}
                </pre>
              )}
              {log.metricsJson && (
                <div>
                  <span className="text-slate-400">指标: </span>
                  <code className="text-slate-600 dark:text-slate-300">{log.metricsJson}</code>
                </div>
              )}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}
