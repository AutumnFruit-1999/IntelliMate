import { useState, useEffect } from "react";
import { X } from "lucide-react";
import type { ScheduledJobConfig } from "../../lib/schedulerApi";
import { updateJob } from "../../lib/schedulerApi";

const CRON_PRESETS = [
  { label: "每分钟", value: "0 * * * * ?" },
  { label: "每 5 分钟", value: "0 */5 * * * ?" },
  { label: "每小时", value: "0 0 * * * ?" },
  { label: "每天凌晨 3 点", value: "0 0 3 * * ?" },
  { label: "每天上午 9 点", value: "0 0 9 * * ?" },
  { label: "工作日 9 点", value: "0 0 9 * * MON-FRI" },
];

const INTERVAL_PRESETS = [
  { label: "30 秒", value: "30000" },
  { label: "1 分钟", value: "60000" },
  { label: "5 分钟", value: "300000" },
  { label: "10 分钟", value: "600000" },
  { label: "30 分钟", value: "1800000" },
  { label: "1 小时", value: "3600000" },
];

interface JobConfigModalProps {
  job: ScheduledJobConfig | null;
  onClose: () => void;
  onSaved: () => void;
}

export default function JobConfigModal({ job, onClose, onSaved }: JobConfigModalProps) {
  const [displayName, setDisplayName] = useState("");
  const [description, setDescription] = useState("");
  const [triggerType, setTriggerType] = useState("");
  const [triggerValue, setTriggerValue] = useState("");
  const [timeoutMs, setTimeoutMs] = useState(300000);
  const [maxRetryCount, setMaxRetryCount] = useState(0);
  const [retryBackoffMs, setRetryBackoffMs] = useState(5000);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (job) {
      setDisplayName(job.displayName);
      setDescription(job.description || "");
      setTriggerType(job.triggerType);
      setTriggerValue(job.triggerValue);
      setTimeoutMs(job.timeoutMs);
      setMaxRetryCount(job.maxRetryCount);
      setRetryBackoffMs(job.retryBackoffMs);
    }
  }, [job]);

  if (!job) return null;

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    try {
      await updateJob(job.jobName, { displayName, description, triggerType, triggerValue, timeoutMs, maxRetryCount, retryBackoffMs });
      onSaved();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-xl w-full max-w-md mx-4 p-6">
        <div className="flex items-center justify-between mb-4">
          <h3 className="text-lg font-semibold text-slate-800 dark:text-slate-100">
            编辑 {job.displayName}
          </h3>
          <button onClick={onClose} className="p-1 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800">
            <X size={18} />
          </button>
        </div>

        {error && (
          <div className="mb-4 p-2 bg-red-50 dark:bg-red-900/20 text-red-600 text-sm rounded-lg">{error}</div>
        )}

        <div className="space-y-4">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-sm text-slate-600 dark:text-slate-400">显示名称</label>
              <input
                type="text"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-2 text-sm"
              />
            </div>
            <div>
              <label className="text-sm text-slate-600 dark:text-slate-400">任务标识</label>
              <input
                type="text"
                value={job.jobName}
                readOnly
                className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/50 px-3 py-2 text-sm font-mono text-slate-500"
              />
            </div>
          </div>

          <div>
            <label className="text-sm text-slate-600 dark:text-slate-400">任务描述</label>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              rows={2}
              className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-2 text-sm resize-none"
              placeholder="描述此任务的功能和用途"
            />
          </div>

          <div className="border-t border-slate-200 dark:border-slate-700 pt-4">
            <label className="text-sm text-slate-600 dark:text-slate-400">触发类型</label>
            <select
              value={triggerType}
              onChange={(e) => setTriggerType(e.target.value)}
              className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-2 text-sm"
            >
              <option value="CRON">CRON</option>
              <option value="FIXED_RATE">FIXED_RATE</option>
              <option value="FIXED_DELAY">FIXED_DELAY</option>
            </select>
          </div>


          <div>
            <label className="text-sm text-slate-600 dark:text-slate-400">
              {triggerType === "CRON" ? "CRON 表达式" : "间隔（毫秒）"}
            </label>
            <input
              type="text"
              value={triggerValue}
              onChange={(e) => setTriggerValue(e.target.value)}
              className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-2 text-sm font-mono"
              placeholder={triggerType === "CRON" ? "0 0 3 * * ?" : "60000"}
            />
            {triggerType === "CRON" && (
              <div className="mt-2 flex flex-wrap gap-1">
                {CRON_PRESETS.map((p) => (
                  <button
                    key={p.value}
                    type="button"
                    onClick={() => setTriggerValue(p.value)}
                    className={`px-2 py-0.5 text-xs rounded-md border transition-colors ${
                      triggerValue === p.value
                        ? "bg-blue-100 dark:bg-blue-900/40 border-blue-300 dark:border-blue-700 text-blue-700 dark:text-blue-300"
                        : "border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800"
                    }`}
                  >
                    {p.label}
                  </button>
                ))}
              </div>
            )}
            {triggerType !== "CRON" && (
              <div className="mt-2 flex flex-wrap gap-1">
                {INTERVAL_PRESETS.map((p) => (
                  <button
                    key={p.value}
                    type="button"
                    onClick={() => setTriggerValue(p.value)}
                    className={`px-2 py-0.5 text-xs rounded-md border transition-colors ${
                      triggerValue === p.value
                        ? "bg-blue-100 dark:bg-blue-900/40 border-blue-300 dark:border-blue-700 text-blue-700 dark:text-blue-300"
                        : "border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800"
                    }`}
                  >
                    {p.label}
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="text-sm text-slate-600 dark:text-slate-400">超时（ms）</label>
              <input
                type="number"
                value={timeoutMs}
                onChange={(e) => setTimeoutMs(Number(e.target.value))}
                className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-2 text-sm"
              />
            </div>
            <div>
              <label className="text-sm text-slate-600 dark:text-slate-400">最大重试次数</label>
              <input
                type="number"
                value={maxRetryCount}
                onChange={(e) => setMaxRetryCount(Number(e.target.value))}
                className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-2 text-sm"
              />
            </div>
          </div>

          <div>
            <label className="text-sm text-slate-600 dark:text-slate-400">重试退避（ms）</label>
            <input
              type="number"
              value={retryBackoffMs}
              onChange={(e) => setRetryBackoffMs(Number(e.target.value))}
              className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-2 text-sm"
            />
          </div>
        </div>

        <div className="flex justify-end gap-2 mt-6">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800"
          >
            取消
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className="px-4 py-2 text-sm rounded-lg bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? "保存中..." : "保存"}
          </button>
        </div>
      </div>
    </div>
  );
}
