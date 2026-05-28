import { useState, useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { ChevronLeft, Activity, Cpu, Brain, Wifi, ClipboardList, RefreshCw } from "lucide-react";
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from "recharts";

interface MetricMeasurement {
  statistic: string;
  value: number;
}

interface MetricResponse {
  name: string;
  measurements: MetricMeasurement[];
  availableTags?: { tag: string; values: string[] }[];
}

interface MetricSnapshot {
  time: string;
  wsActive: number;
  memoryUsage: number;
  toolLatency: number;
  llmLatency: number;
}

const METRIC_DEFINITIONS = [
  { key: "ws.connections.active", label: "WebSocket 连接", unit: "", icon: Wifi, category: "ws" },
  { key: "ws.messages.received?tag=method:conversation.message", label: "用户消息", unit: "", icon: Wifi, category: "ws" },
  { key: "ws.messages.received?tag=method:agent.bind", label: "Agent 绑定", unit: "", icon: Wifi, category: "ws" },
  { key: "agent.llm.requests", label: "LLM 调用", unit: "", icon: Cpu, category: "llm" },
  { key: "agent.llm.latency", label: "LLM 延迟", unit: "s", icon: Cpu, category: "llm" },
  { key: "agent.tool.requests", label: "工具调用", unit: "", icon: Activity, category: "tool" },
  { key: "agent.tool.latency", label: "工具延迟", unit: "s", icon: Activity, category: "tool" },
  { key: "agent.tool.cache.hits", label: "缓存命中", unit: "", icon: Activity, category: "tool" },
  { key: "agent.tool.retries", label: "工具重试", unit: "", icon: Activity, category: "tool" },
  { key: "agent.tool.loop_detected", label: "循环检测", unit: "", icon: Activity, category: "tool" },
  { key: "memory.working.usage_ratio", label: "工作记忆使用率", unit: "%", icon: Brain, category: "memory" },
  { key: "memory.consolidation.triggered", label: "记忆压缩", unit: "", icon: Brain, category: "memory" },
  { key: "memory.longterm.retrieval.latency", label: "长期记忆检索", unit: "s", icon: Brain, category: "memory" },
  { key: "memory.longterm.store.count", label: "长期记忆存储", unit: "", icon: Brain, category: "memory" },
  { key: "plan.created", label: "Plan 创建", unit: "", icon: ClipboardList, category: "plan" },
  { key: "plan.completed", label: "Plan 完成", unit: "", icon: ClipboardList, category: "plan" },
  { key: "plan.step.duration", label: "步骤耗时", unit: "s", icon: ClipboardList, category: "plan" },
];

async function fetchMetric(nameWithParams: string): Promise<MetricResponse | null> {
  try {
    const [name, query] = nameWithParams.split("?");
    const url = query ? `/actuator/metrics/${name}?${query}` : `/actuator/metrics/${name}`;
    const resp = await fetch(url);
    if (!resp.ok) return null;
    return await resp.json();
  } catch {
    return null;
  }
}

function extractValue(metric: MetricResponse | null, stat = "VALUE"): number | null {
  if (!metric) return null;
  const m = metric.measurements.find((m) => m.statistic === stat);
  return m ? m.value : null;
}

function formatMetricValue(value: number | null, unit: string): string {
  if (value === null) return "—";
  if (unit === "%") return `${(value * 100).toFixed(1)}%`;
  if (unit === "s") return value < 1 ? `${(value * 1000).toFixed(0)}ms` : `${value.toFixed(2)}s`;
  if (value >= 1000000) return `${(value / 1000000).toFixed(1)}M`;
  if (value >= 1000) return `${(value / 1000).toFixed(1)}K`;
  return Number.isInteger(value) ? value.toString() : value.toFixed(2);
}

type CategoryKey = "ws" | "llm" | "tool" | "memory" | "plan";
const CATEGORIES: { key: CategoryKey; label: string }[] = [
  { key: "ws", label: "WebSocket" },
  { key: "llm", label: "LLM" },
  { key: "tool", label: "工具" },
  { key: "memory", label: "记忆" },
  { key: "plan", label: "Plan" },
];

export default function MonitoringPage() {
  const navigate = useNavigate();
  const [metrics, setMetrics] = useState<Record<string, number | null>>({});
  const [history, setHistory] = useState<MetricSnapshot[]>([]);
  const [loading, setLoading] = useState(true);
  const [autoRefresh, setAutoRefresh] = useState(true);
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchAllMetrics = async () => {
    const results: Record<string, number | null> = {};
    const promises = METRIC_DEFINITIONS.map(async (def) => {
      const data = await fetchMetric(def.key);
      if (def.key.includes("latency") || def.key.includes("duration")) {
        results[def.key] = extractValue(data, "TOTAL_TIME") ?? extractValue(data, "VALUE");
        const count = extractValue(data, "COUNT");
        if (count && count > 0) {
          const totalTime = extractValue(data, "TOTAL_TIME");
          results[def.key] = totalTime ? totalTime / count : null;
        }
      } else {
        results[def.key] = extractValue(data, "VALUE") ?? extractValue(data, "COUNT");
      }
    });
    await Promise.all(promises);
    setMetrics(results);
    setLoading(false);

    const now = new Date().toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
    setHistory((prev) => {
      const entry: MetricSnapshot = {
        time: now,
        wsActive: results["ws.connections.active"] ?? 0,
        memoryUsage: (results["memory.working.usage_ratio"] ?? 0) * 100,
        toolLatency: (results["agent.tool.latency"] ?? 0) * 1000,
        llmLatency: (results["agent.llm.latency"] ?? 0) * 1000,
      };
      const updated = [...prev, entry];
      return updated.length > 60 ? updated.slice(-60) : updated;
    });
  };

  useEffect(() => {
    fetchAllMetrics();
  }, []);

  useEffect(() => {
    if (autoRefresh) {
      intervalRef.current = setInterval(fetchAllMetrics, 5000);
    }
    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current);
    };
  }, [autoRefresh]);

  return (
    <div className="flex-1 overflow-y-auto">
      <div className="w-full px-8 py-6 lg:px-12 max-w-6xl mx-auto">
        {/* Header */}
        <div className="flex items-center justify-between mb-6">
          <div className="flex items-center gap-3">
            <button
              onClick={() => navigate("/chat")}
              className="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
            >
              <ChevronLeft size={18} className="text-slate-500" />
            </button>
            <div>
              <h1 className="text-lg font-semibold text-slate-800 dark:text-slate-100">系统监控</h1>
              <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
                实时指标概览 {autoRefresh && "· 每 5s 刷新"}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => setAutoRefresh(!autoRefresh)}
              className={`flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium rounded-lg transition-colors ${
                autoRefresh
                  ? "bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600 dark:text-emerald-400 border border-emerald-200 dark:border-emerald-800"
                  : "bg-slate-100 dark:bg-slate-700 text-slate-500 border border-slate-200 dark:border-slate-600"
              }`}
            >
              <RefreshCw size={12} className={autoRefresh ? "animate-spin" : ""} style={autoRefresh ? { animationDuration: "3s" } : {}} />
              {autoRefresh ? "自动刷新" : "已暂停"}
            </button>
            <button
              onClick={fetchAllMetrics}
              className="px-3 py-1.5 text-xs font-medium rounded-lg bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300 border border-slate-200 dark:border-slate-600 hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors"
            >
              立即刷新
            </button>
          </div>
        </div>

        {loading ? (
          <div className="flex items-center justify-center py-20">
            <RefreshCw size={24} className="text-blue-400 animate-spin" />
          </div>
        ) : (
          <>
            {/* Metric Cards by Category */}
            {CATEGORIES.map((cat) => {
              const catMetrics = METRIC_DEFINITIONS.filter((d) => d.category === cat.key);
              return (
                <div key={cat.key} className="mb-6">
                  <h2 className="text-sm font-semibold text-slate-600 dark:text-slate-300 mb-3 flex items-center gap-2">
                    {cat.label}
                  </h2>
                  <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3">
                    {catMetrics.map((def) => {
                      const Icon = def.icon;
                      const value = metrics[def.key];
                      const hasData = value !== null && value !== undefined;
                      return (
                        <div
                          key={def.key}
                          className={`border rounded-xl px-4 py-3 transition-colors ${
                            hasData
                              ? "border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800/50"
                              : "border-slate-100 dark:border-slate-800 bg-slate-50/50 dark:bg-slate-800/20 opacity-50"
                          }`}
                        >
                          <div className="flex items-center gap-2 mb-1.5">
                            <Icon size={13} className="text-slate-400" />
                            <span className="text-[11px] text-slate-400 dark:text-slate-500 truncate">
                              {def.label}
                            </span>
                          </div>
                          <p className="text-xl font-bold text-slate-800 dark:text-slate-100">
                            {formatMetricValue(value ?? null, def.unit)}
                          </p>
                        </div>
                      );
                    })}
                  </div>
                </div>
              );
            })}

            {/* Trend Charts */}
            {history.length > 1 && (
              <div className="mt-8">
                <h2 className="text-sm font-semibold text-slate-600 dark:text-slate-300 mb-4">实时趋势</h2>
                <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                  <ChartCard title="WebSocket 连接数" dataKey="wsActive" data={history} unit="" color="#3b82f6" />
                  <ChartCard title="工作记忆使用率" dataKey="memoryUsage" data={history} unit="%" color="#10b981" />
                  <ChartCard title="工具平均延迟" dataKey="toolLatency" data={history} unit="ms" color="#f59e0b" />
                  <ChartCard title="LLM 平均延迟" dataKey="llmLatency" data={history} unit="ms" color="#8b5cf6" />
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

function ChartCard({ title, dataKey, data, unit, color }: {
  title: string;
  dataKey: string;
  data: MetricSnapshot[];
  unit: string;
  color: string;
}) {
  return (
    <div className="border border-slate-200 dark:border-slate-700 rounded-xl p-4 bg-white dark:bg-slate-800/50">
      <p className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-3">{title}</p>
      <ResponsiveContainer width="100%" height={120}>
        <LineChart data={data}>
          <XAxis dataKey="time" tick={{ fontSize: 9 }} interval="preserveStartEnd" />
          <YAxis tick={{ fontSize: 9 }} unit={unit} width={40} />
          <Tooltip contentStyle={{ fontSize: 11 }} formatter={(value) => [`${value}${unit}`, title]} />
          <Line type="monotone" dataKey={dataKey} stroke={color} strokeWidth={2} dot={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
