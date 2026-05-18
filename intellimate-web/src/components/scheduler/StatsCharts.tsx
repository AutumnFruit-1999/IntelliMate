import { useEffect, useState } from "react";
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, PieChart, Pie, Cell } from "recharts";
import type { ScheduledJobLog, JobStatsOverview } from "../../lib/schedulerApi";
import { fetchRecentLogs, fetchStatsOverview } from "../../lib/schedulerApi";

const STATUS_COLORS: Record<string, string> = {
  SUCCESS: "#22c55e",
  FAILED: "#ef4444",
  TIMEOUT: "#f97316",
  RUNNING: "#eab308",
  SKIPPED: "#94a3b8",
};

interface DurationPoint {
  time: string;
  duration: number;
  jobName: string;
}

export default function StatsCharts() {
  const [overview, setOverview] = useState<JobStatsOverview | null>(null);
  const [logs, setLogs] = useState<ScheduledJobLog[]>([]);

  useEffect(() => {
    fetchStatsOverview().then(setOverview).catch(() => {});
    fetchRecentLogs(100).then(setLogs).catch(() => {});
  }, []);

  const durationData: DurationPoint[] = logs
    .filter((l) => l.durationMs != null && l.status === "SUCCESS")
    .slice(0, 50)
    .map((l) => ({
      time: new Date(l.fireTime).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" }),
      duration: l.durationMs!,
      jobName: l.jobName,
    }))
    .reverse();

  const statusDistribution = (() => {
    const counts: Record<string, number> = {};
    logs.forEach((l) => {
      counts[l.status] = (counts[l.status] || 0) + 1;
    });
    return Object.entries(counts).map(([name, value]) => ({ name, value }));
  })();

  return (
    <div className="space-y-6">
      {overview && (
        <div className="grid grid-cols-2 sm:grid-cols-5 gap-3">
          <StatCard label="总任务" value={overview.totalJobs} />
          <StatCard label="今日执行" value={overview.todayExecutions} />
          <StatCard label="成功率" value={`${(overview.todaySuccessRate * 100).toFixed(1)}%`} />
          <StatCard label="今日失败" value={overview.todayFailures} highlight={overview.todayFailures > 0} />
          <StatCard label="今日超时" value={overview.todayTimeouts ?? 0} highlight={(overview.todayTimeouts ?? 0) > 0} />
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        <div className="border border-slate-200 dark:border-slate-700 rounded-xl p-4">
          <h4 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">耗时趋势</h4>
          {durationData.length > 0 ? (
            <ResponsiveContainer width="100%" height={180}>
              <LineChart data={durationData}>
                <XAxis dataKey="time" tick={{ fontSize: 10 }} />
                <YAxis tick={{ fontSize: 10 }} unit="ms" />
                <Tooltip contentStyle={{ fontSize: 12 }} />
                <Line type="monotone" dataKey="duration" stroke="#3b82f6" strokeWidth={2} dot={false} />
              </LineChart>
            </ResponsiveContainer>
          ) : (
            <p className="text-xs text-slate-400 text-center py-10">暂无数据</p>
          )}
        </div>

        <div className="border border-slate-200 dark:border-slate-700 rounded-xl p-4">
          <h4 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">执行分布</h4>
          {statusDistribution.length > 0 ? (
            <ResponsiveContainer width="100%" height={180}>
              <PieChart>
                <Pie data={statusDistribution} dataKey="value" nameKey="name" cx="50%" cy="50%" outerRadius={65} label={({ name, percent }) => `${name} ${((percent ?? 0) * 100).toFixed(0)}%`} labelLine={false}>
                  {statusDistribution.map((entry) => (
                    <Cell key={entry.name} fill={STATUS_COLORS[entry.name] || "#94a3b8"} />
                  ))}
                </Pie>
                <Tooltip />
              </PieChart>
            </ResponsiveContainer>
          ) : (
            <p className="text-xs text-slate-400 text-center py-10">暂无数据</p>
          )}
        </div>
      </div>
    </div>
  );
}

function StatCard({ label, value, highlight }: { label: string; value: string | number; highlight?: boolean }) {
  return (
    <div className="border border-slate-200 dark:border-slate-700 rounded-xl px-4 py-3">
      <p className="text-xs text-slate-500 dark:text-slate-400">{label}</p>
      <p className={`text-xl font-bold mt-1 ${highlight ? "text-red-500" : "text-slate-800 dark:text-slate-100"}`}>
        {value}
      </p>
    </div>
  );
}
