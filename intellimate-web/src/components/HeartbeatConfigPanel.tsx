import { useState, useEffect, useCallback } from "react";
import {
  HeartbeatConfig,
  HeartbeatState,
  HeartbeatLog,
  fetchHeartbeatConfig,
  updateHeartbeatConfig,
  fetchHeartbeatState,
  fetchHeartbeatLogs,
} from "../lib/heartbeatApi";

interface HeartbeatConfigPanelProps {
  agentId: number;
}

const STATE_COLORS: Record<string, string> = {
  SLEEPING: "bg-indigo-100 text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-300",
  WAKING: "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300",
  ACTIVE: "bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300",
  WINDING_DOWN: "bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300",
  UNCONFIGURED: "bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400",
};

export default function HeartbeatConfigPanel({ agentId }: HeartbeatConfigPanelProps) {
  const [config, setConfig] = useState<HeartbeatConfig | null>(null);
  const [state, setState] = useState<HeartbeatState | null>(null);
  const [logs, setLogs] = useState<HeartbeatLog[]>([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [cfg, st, lg] = await Promise.all([
        fetchHeartbeatConfig(agentId),
        fetchHeartbeatState(agentId),
        fetchHeartbeatLogs(agentId, 10),
      ]);
      setConfig(cfg);
      setState(st);
      setLogs(lg);
      setError(null);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "加载失败");
    }
  }, [agentId]);

  useEffect(() => { load(); }, [load]);

  const handleToggle = async () => {
    if (!config) return;
    setSaving(true);
    try {
      const updated = await updateHeartbeatConfig(agentId, {
        enabled: config.enabled === 1 ? 0 : 1,
      });
      setConfig(updated);
    } finally {
      setSaving(false);
    }
  };

  const handleSave = async () => {
    if (!config) return;
    setSaving(true);
    try {
      const updated = await updateHeartbeatConfig(agentId, config);
      setConfig(updated);
      setError(null);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : "保存失败");
    } finally {
      setSaving(false);
    }
  };

  if (!config) {
    return <div className="flex justify-center py-8 text-slate-400">加载中...</div>;
  }

  return (
    <div className="space-y-6">
      {error && (
        <div className="text-sm text-red-500 bg-red-50 dark:bg-red-900/20 px-3 py-2 rounded-lg">
          {error}
        </div>
      )}

      {/* Status Card */}
      <div className="flex items-center justify-between p-4 rounded-xl bg-slate-50 dark:bg-slate-800/50">
        <div className="flex items-center gap-3">
          <div className={`px-3 py-1 rounded-full text-xs font-medium ${STATE_COLORS[state?.currentState ?? "UNCONFIGURED"]}`}>
            {state?.stateDescription ?? "未配置"}
          </div>
          {state?.currentTime && (
            <span className="text-xs text-slate-400">{state.currentTime}</span>
          )}
        </div>
        <button
          onClick={handleToggle}
          disabled={saving}
          className={`relative w-12 h-6 rounded-full transition-colors ${
            config.enabled === 1 ? "bg-green-500" : "bg-slate-300 dark:bg-slate-600"
          }`}
        >
          <div
            className={`absolute top-0.5 w-5 h-5 bg-white rounded-full shadow transition-transform ${
              config.enabled === 1 ? "translate-x-6" : "translate-x-0.5"
            }`}
          />
        </button>
      </div>

      {/* Config Fields */}
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">
            起床时间
          </label>
          <input
            type="time"
            value={config.wakeTime}
            onChange={(e) => setConfig({ ...config, wakeTime: e.target.value })}
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">
            睡眠时间
          </label>
          <input
            type="time"
            value={config.sleepTime}
            onChange={(e) => setConfig({ ...config, sleepTime: e.target.value })}
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">
            心跳间隔（分钟）
          </label>
          <input
            type="number"
            min={1}
            value={config.heartbeatIntervalMinutes}
            onChange={(e) => setConfig({ ...config, heartbeatIntervalMinutes: parseInt(e.target.value) || 60 })}
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200"
          />
        </div>
        <div>
          <label className="block text-xs font-medium text-slate-500 dark:text-slate-400 mb-1">
            时区
          </label>
          <input
            type="text"
            value={config.timezone}
            onChange={(e) => setConfig({ ...config, timezone: e.target.value })}
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200"
          />
        </div>
      </div>

      <button
        onClick={handleSave}
        disabled={saving}
        className="w-full py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
      >
        {saving ? "保存中..." : "保存配置"}
      </button>

      {/* Recent Logs */}
      {logs.length > 0 && (
        <div>
          <h4 className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-2">
            最近心跳记录
          </h4>
          <div className="space-y-2 max-h-48 overflow-y-auto">
            {logs.map((log) => (
              <div
                key={log.id}
                className="flex items-start gap-2 p-2 rounded-lg bg-slate-50 dark:bg-slate-800/50 text-xs"
              >
                <span className={`shrink-0 px-1.5 py-0.5 rounded text-[10px] font-medium ${STATE_COLORS[log.state] ?? ""}`}>
                  {log.state}
                </span>
                <span className="text-slate-600 dark:text-slate-300 flex-1 line-clamp-2">
                  {log.response}
                </span>
                <span className="shrink-0 text-slate-400">
                  {new Date(log.triggeredAt).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" })}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
