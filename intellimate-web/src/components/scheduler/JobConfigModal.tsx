import { useState, useEffect, useRef } from "react";
import { X, Zap, Brain } from "lucide-react";
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

const TEMPLATE_VARS = [
  { key: "time", label: "时间", desc: "当前时间 HH:mm" },
  { key: "date", label: "日期", desc: "yyyy-MM-dd" },
  { key: "datetime", label: "完整时间", desc: "yyyy-MM-dd HH:mm" },
  { key: "dayOfWeek", label: "星期", desc: "星期一~日" },
  { key: "timeOfDay", label: "时段", desc: "凌晨/早上/上午/中午/下午/晚上/深夜" },
  { key: "lastResponse", label: "上次回复", desc: "上次执行的摘要" },
  { key: "executionCount", label: "执行次数", desc: "累计执行次数" },
  { key: "daysSinceLastRun", label: "间隔天数", desc: "距上次运行天数" },
];

interface JobConfigModalProps {
  job: ScheduledJobConfig | null;
  onClose: () => void;
  onSaved: () => void;
}

function parseParamsJson(paramsJson?: string): Record<string, unknown> {
  if (!paramsJson) return {};
  try {
    return JSON.parse(paramsJson);
  } catch {
    return {};
  }
}

export default function JobConfigModal({ job, onClose, onSaved }: JobConfigModalProps) {
  const [displayName, setDisplayName] = useState("");
  const [description, setDescription] = useState("");
  const [triggerType, setTriggerType] = useState("");
  const [triggerValue, setTriggerValue] = useState("");
  const [timeoutMs, setTimeoutMs] = useState(300000);
  const [maxRetryCount, setMaxRetryCount] = useState(0);
  const [retryBackoffMs, setRetryBackoffMs] = useState(5000);

  const [prompt, setPrompt] = useState("");
  const [templateMode, setTemplateMode] = useState(false);
  const [enableMemoryRecall, setEnableMemoryRecall] = useState(true);
  const [maxRecallTokens, setMaxRecallTokens] = useState(500);
  const [agentName, setAgentName] = useState("");

  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const isAgentPrompt = job?.jobClass === "agent-prompt";

  useEffect(() => {
    if (job) {
      setDisplayName(job.displayName);
      setDescription(job.description || "");
      setTriggerType(job.triggerType);
      setTriggerValue(job.triggerValue);
      setTimeoutMs(job.timeoutMs);
      setMaxRetryCount(job.maxRetryCount);
      setRetryBackoffMs(job.retryBackoffMs);

      if (job.jobClass === "agent-prompt") {
        const params = parseParamsJson(job.paramsJson);
        setAgentName((params.agentName as string) || "");
        const hasTemplate = !!params.promptTemplate;
        setTemplateMode(hasTemplate);
        setPrompt(((hasTemplate ? params.promptTemplate : params.prompt) as string) || "");
        setEnableMemoryRecall(params.enableMemoryRecall !== false);
        setMaxRecallTokens(
          typeof params.maxRecallTokens === "number" ? params.maxRecallTokens : 500
        );
      }
    }
  }, [job]);

  if (!job) return null;

  const insertVariable = (varKey: string) => {
    const tag = `{{${varKey}}}`;
    const ta = textareaRef.current;
    if (ta) {
      const start = ta.selectionStart;
      const end = ta.selectionEnd;
      const newValue = prompt.substring(0, start) + tag + prompt.substring(end);
      setPrompt(newValue);
      setTimeout(() => {
        ta.focus();
        ta.setSelectionRange(start + tag.length, start + tag.length);
      }, 0);
    } else {
      setPrompt(prompt + tag);
    }
  };

  const handleSave = async () => {
    setSaving(true);
    setError(null);
    try {
      const updates: Record<string, unknown> = {
        displayName,
        description,
        triggerType,
        triggerValue,
        timeoutMs,
        maxRetryCount,
        retryBackoffMs,
      };

      if (isAgentPrompt) {
        const params: Record<string, unknown> = { agentName };
        if (templateMode) {
          params.promptTemplate = prompt.trim();
        } else {
          params.prompt = prompt.trim();
        }
        params.enableMemoryRecall = enableMemoryRecall;
        if (enableMemoryRecall) {
          params.maxRecallTokens = maxRecallTokens;
        }
        updates.paramsJson = JSON.stringify(params);
      }

      await updateJob(job.jobName, updates);
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
      <div className="bg-white dark:bg-slate-900 rounded-2xl shadow-xl w-full max-w-lg mx-4 p-6 max-h-[90vh] overflow-y-auto">
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

          {isAgentPrompt && (
            <div className="border-t border-slate-200 dark:border-slate-700 pt-4">
              <h4 className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-3">Agent 配置</h4>

              <div>
                <label className="text-sm text-slate-600 dark:text-slate-400">绑定 Agent</label>
                <input
                  type="text"
                  value={agentName}
                  readOnly
                  className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/50 px-3 py-2 text-sm font-mono text-slate-500"
                />
              </div>

              <div className="mt-3 flex items-center justify-between">
                <label className="text-sm text-slate-600 dark:text-slate-400">Prompt 指令</label>
                <button
                  type="button"
                  onClick={() => setTemplateMode(!templateMode)}
                  className={`flex items-center gap-1 px-2 py-0.5 text-xs rounded-md border transition-colors ${
                    templateMode
                      ? "bg-amber-100 dark:bg-amber-900/30 border-amber-300 dark:border-amber-700 text-amber-700 dark:text-amber-300"
                      : "border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800"
                  }`}
                >
                  <Zap size={12} />
                  {templateMode ? "模板模式" : "静态模式"}
                </button>
              </div>

              <textarea
                ref={textareaRef}
                value={prompt}
                onChange={(e) => setPrompt(e.target.value)}
                rows={templateMode ? 5 : 3}
                className={`mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-2 text-sm resize-none ${
                  templateMode ? "font-mono text-xs leading-relaxed" : ""
                }`}
                placeholder={templateMode
                  ? "现在是{{timeOfDay}}，{{dayOfWeek}} {{time}}。请问候用户。"
                  : "请检查今天的待办事项并发送提醒"
                }
              />

              {templateMode && (
                <div className="mt-2 space-y-2">
                  <p className="text-xs text-slate-400">点击插入变量：</p>
                  <div className="flex flex-wrap gap-1">
                    {TEMPLATE_VARS.map((v) => (
                      <button
                        key={v.key}
                        type="button"
                        onClick={() => insertVariable(v.key)}
                        title={v.desc}
                        className="px-2 py-0.5 text-xs rounded-md border border-amber-200 dark:border-amber-800 bg-amber-50 dark:bg-amber-900/20 text-amber-700 dark:text-amber-300 hover:bg-amber-100 dark:hover:bg-amber-900/40 transition-colors font-mono"
                      >
                        {`{{${v.key}}}`}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {!templateMode && (
                <p className="text-xs text-slate-400 mt-1">到达触发时间后，系统会将此 Prompt 发送给 Agent 执行</p>
              )}

              <div className="mt-4 flex items-center justify-between">
                <h4 className="text-sm font-medium text-slate-700 dark:text-slate-300 flex items-center gap-1.5">
                  <Brain size={14} />
                  记忆注入
                </h4>
                <label className="relative inline-flex items-center cursor-pointer">
                  <input
                    type="checkbox"
                    checked={enableMemoryRecall}
                    onChange={(e) => setEnableMemoryRecall(e.target.checked)}
                    className="sr-only peer"
                  />
                  <div className="w-9 h-5 bg-slate-200 dark:bg-slate-700 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-slate-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-blue-600"></div>
                </label>
              </div>
              <p className="text-xs text-slate-400 mt-1">
                {enableMemoryRecall
                  ? "执行前会检索与 Prompt 相关的长期记忆，让 Agent 回复更有上下文"
                  : "不注入记忆，Agent 仅根据 Prompt 本身回复"}
              </p>
              {enableMemoryRecall && (
                <div className="mt-2">
                  <label className="text-xs text-slate-500 dark:text-slate-400">最大记忆 token</label>
                  <input
                    type="number"
                    value={maxRecallTokens}
                    onChange={(e) => setMaxRecallTokens(Number(e.target.value))}
                    className="mt-1 w-32 rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-2 py-1 text-xs"
                    min={100}
                    max={2000}
                    step={100}
                  />
                </div>
              )}
            </div>
          )}

          <div className="border-t border-slate-200 dark:border-slate-700 pt-4">
            <h4 className="text-sm font-medium text-slate-700 dark:text-slate-300 mb-3">调度配置</h4>
            <div>
              <label className="text-sm text-slate-600 dark:text-slate-400">触发类型</label>
              <select
                value={triggerType}
                onChange={(e) => setTriggerType(e.target.value)}
                className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-2 text-sm"
              >
                <option value="CRON">CRON</option>
                <option value="FIXED_RATE">固定频率</option>
                <option value="FIXED_DELAY">固定延迟</option>
              </select>
            </div>

            <div className="mt-3">
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
              <div className="mt-2 flex flex-wrap gap-1">
                {(triggerType === "CRON" ? CRON_PRESETS : INTERVAL_PRESETS).map((p) => (
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
            </div>

            <div className="grid grid-cols-3 gap-3 mt-3">
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
                <label className="text-sm text-slate-600 dark:text-slate-400">重试次数</label>
                <input
                  type="number"
                  value={maxRetryCount}
                  onChange={(e) => setMaxRetryCount(Number(e.target.value))}
                  className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-2 text-sm"
                />
              </div>
              <div>
                <label className="text-sm text-slate-600 dark:text-slate-400">退避（ms）</label>
                <input
                  type="number"
                  value={retryBackoffMs}
                  onChange={(e) => setRetryBackoffMs(Number(e.target.value))}
                  className="mt-1 w-full rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-2 text-sm"
                />
              </div>
            </div>
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
