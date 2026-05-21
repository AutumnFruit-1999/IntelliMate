import React, { useState } from "react";
import { pauseJob, resumeJob } from "../../lib/schedulerApi";

interface JobData {
  jobName: string;
  displayName: string;
  triggerType: string;
  triggerValue: string;
  jobType: string;
  jobGroup: string;
  enabled: boolean;
  nextFireTime?: string | null;
}

interface ScheduledJobCardProps {
  action: string;
  job?: JobData;
  jobs?: JobData[];
  total?: number;
}

function describeTrigger(type: string, value: string): string {
  if (type === "CRON") {
    if (value === "0 0 9 * * ?") return "每天 09:00";
    if (value === "0 0 * * * ?") return "每小时";
    return `Cron: ${value}`;
  }
  const secs = parseInt(value);
  if (!isNaN(secs)) {
    if (secs >= 3600) return `每 ${secs / 3600} 小时`;
    if (secs >= 60) return `每 ${secs / 60} 分钟`;
    return `每 ${secs} 秒`;
  }
  return value;
}

export const ScheduledJobCard: React.FC<ScheduledJobCardProps> = ({ action, job, jobs, total }) => {
  const [localEnabled, setLocalEnabled] = useState(job?.enabled);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleToggle = async () => {
    if (!job || loading) return;
    setLoading(true);
    setError(null);
    try {
      if (localEnabled) {
        await pauseJob(job.jobName);
      } else {
        await resumeJob(job.jobName);
      }
      setLocalEnabled(!localEnabled);
    } catch {
      setError("操作失败，请重试");
    } finally {
      setLoading(false);
    }
  };

  if (action === "listed" && jobs) {
    return (
      <div className="rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 p-3 my-2 max-w-md">
        <div className="text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
          ⏱️ 定时任务（{total} 项）
        </div>
        <div className="space-y-1.5">
          {jobs.map((j) => (
            <div key={j.jobName} className="flex items-center gap-2 text-sm">
              <span className={j.enabled ? "text-green-500" : "text-gray-400"}>●</span>
              <span className="flex-1 truncate">{j.displayName}</span>
              <span className="text-xs text-gray-400">{describeTrigger(j.triggerType, j.triggerValue)}</span>
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (!job) return null;
  const effectiveEnabled = localEnabled ?? job.enabled;
  const actionLabel = action === "created" ? "已创建" : action === "updated" ? "已更新" : action === "deleted" ? "已删除" : "";
  const jobTypeLabel = job.jobType === "agent-prompt" ? "Agent 提示词" : "HTTP 回调";

  return (
    <div className="rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 p-3 my-2 max-w-md">
      <div className="text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
        ⏱️ 定时任务{actionLabel}
      </div>
      <div className="space-y-1">
        <div className="font-medium text-gray-900 dark:text-gray-100">{job.displayName}</div>
        <div className="text-xs text-gray-400">🔄 {describeTrigger(job.triggerType, job.triggerValue)}</div>
        <div className="text-xs text-gray-400">🤖 类型：{jobTypeLabel}</div>
        <div className="text-xs">
          {effectiveEnabled
            ? <span className="text-green-500">✅ 已启用</span>
            : <span className="text-gray-400">⏸ 已暂停</span>}
        </div>
      </div>
      {error && <div className="text-xs text-red-500 mt-1">{error}</div>}
      {action !== "deleted" && (
        <div className="flex gap-2 mt-2 pt-2 border-t border-gray-100 dark:border-gray-700">
          <button onClick={handleToggle} disabled={loading}
                  className={`text-xs px-2 py-1 rounded disabled:opacity-50 ${effectiveEnabled
                    ? "bg-yellow-50 text-yellow-600 hover:bg-yellow-100"
                    : "bg-green-50 text-green-600 hover:bg-green-100"}`}>
            {loading ? "处理中..." : effectiveEnabled ? "暂停" : "恢复"}
          </button>
        </div>
      )}
    </div>
  );
};
