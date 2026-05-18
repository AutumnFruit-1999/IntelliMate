import { Play, Pause, Settings, Zap } from "lucide-react";
import type { ScheduledJobConfig } from "../../lib/schedulerApi";

interface JobCardProps {
  job: ScheduledJobConfig;
  onTrigger: (jobName: string) => void;
  onPause: (jobName: string) => void;
  onResume: (jobName: string) => void;
  onEdit: (job: ScheduledJobConfig) => void;
}

function getStatusColor(job: ScheduledJobConfig) {
  if (!job.enabled) return "bg-slate-400";
  if (job.running) return "bg-yellow-400 animate-pulse";
  if (job.lastStatus === "SUCCESS") return "bg-green-400";
  if (job.lastStatus === "FAILED" || job.lastStatus === "TIMEOUT") return "bg-red-400";
  return "bg-slate-300";
}

function formatCountdown(nextFireTime: string | null): string {
  if (!nextFireTime) return "—";
  const diff = new Date(nextFireTime).getTime() - Date.now();
  if (diff <= 0) return "即将执行";
  if (diff < 60_000) return `${Math.round(diff / 1000)}s`;
  if (diff < 3600_000) return `${Math.round(diff / 60_000)}m`;
  return `${Math.round(diff / 3600_000)}h`;
}

export default function JobCard({ job, onTrigger, onPause, onResume, onEdit }: JobCardProps) {
  return (
    <div className={`relative rounded-xl border border-slate-200 dark:border-slate-700 p-4 transition-all hover:shadow-md ${job.running ? "ring-2 ring-yellow-300 dark:ring-yellow-600" : ""}`}>
      {job.consecutiveFailures >= 3 && (
        <div className="absolute top-0 left-0 right-0 bg-red-50 dark:bg-red-900/30 text-red-600 dark:text-red-400 text-xs px-3 py-1 rounded-t-xl text-center">
          连续失败 {job.consecutiveFailures} 次
        </div>
      )}

      <div className={`flex items-center gap-3 ${job.consecutiveFailures >= 3 ? "mt-5" : ""}`}>
        <div className={`w-3 h-3 rounded-full shrink-0 ${getStatusColor(job)}`} />
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold text-slate-800 dark:text-slate-100 truncate">
              {job.displayName}
            </span>
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-800 text-slate-500 font-mono">
              {job.jobGroup}
            </span>
          </div>
          {job.description && (
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5 line-clamp-1">
              {job.description}
            </p>
          )}
          <p className="text-[11px] text-slate-400 dark:text-slate-500 font-mono mt-0.5">
            {job.triggerType === "CRON" ? job.triggerValue : `${Number(job.triggerValue) / 1000}s`}
          </p>
        </div>
      </div>

      <div className="grid grid-cols-2 gap-2 mt-3 text-xs text-slate-500 dark:text-slate-400">
        <div>
          <span className="text-slate-400">下次执行</span>
          <p className="font-medium text-slate-700 dark:text-slate-200">{formatCountdown(job.nextFireTime)}</p>
        </div>
        <div>
          <span className="text-slate-400">状态</span>
          <p className="font-medium text-slate-700 dark:text-slate-200">{job.lastStatus ?? "—"}</p>
        </div>
      </div>

      <div className="flex items-center gap-1.5 mt-3 pt-3 border-t border-slate-100 dark:border-slate-700">
        {job.enabled ? (
          <button
            onClick={() => onPause(job.jobName)}
            className="p-1.5 rounded-lg text-slate-400 hover:text-orange-500 hover:bg-orange-50 dark:hover:bg-orange-900/20 transition-colors"
            title="暂停"
          >
            <Pause size={14} />
          </button>
        ) : (
          <button
            onClick={() => onResume(job.jobName)}
            className="p-1.5 rounded-lg text-slate-400 hover:text-green-500 hover:bg-green-50 dark:hover:bg-green-900/20 transition-colors"
            title="恢复"
          >
            <Play size={14} />
          </button>
        )}
        <button
          onClick={() => onTrigger(job.jobName)}
          className="p-1.5 rounded-lg text-slate-400 hover:text-blue-500 hover:bg-blue-50 dark:hover:bg-blue-900/20 transition-colors"
          title="手动触发"
          disabled={job.running}
        >
          <Zap size={14} />
        </button>
        <button
          onClick={() => onEdit(job)}
          className="p-1.5 rounded-lg text-slate-400 hover:text-slate-600 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors ml-auto"
          title="编辑配置"
        >
          <Settings size={14} />
        </button>
      </div>
    </div>
  );
}
