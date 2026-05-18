import { useEffect, useState, useCallback } from "react";
import { Loader2, Timer, BarChart3, List, Plus } from "lucide-react";
import { useSchedulerStore } from "../stores/schedulerStore";
import { fetchRecentLogs } from "../lib/schedulerApi";
import type { ScheduledJobConfig, ScheduledJobLog } from "../lib/schedulerApi";
import JobCard from "./scheduler/JobCard";
import JobConfigModal from "./scheduler/JobConfigModal";
import CreateJobModal from "./scheduler/CreateJobModal";
import ExecutionHistory from "./scheduler/ExecutionHistory";
import StatsCharts from "./scheduler/StatsCharts";

type Tab = "overview" | "history" | "stats";

export default function SchedulerDashboard() {
  const { jobs, loading, error, loadJobs, trigger, pause, resume } = useSchedulerStore();
  const [activeTab, setActiveTab] = useState<Tab>("overview");
  const [editingJob, setEditingJob] = useState<ScheduledJobConfig | null>(null);
  const [showCreate, setShowCreate] = useState(false);
  const [recentLogs, setRecentLogs] = useState<ScheduledJobLog[]>([]);
  const [confirmAction, setConfirmAction] = useState<{ type: string; jobName: string } | null>(null);

  useEffect(() => {
    loadJobs();
    const interval = setInterval(loadJobs, 15_000);
    return () => clearInterval(interval);
  }, [loadJobs]);

  useEffect(() => {
    if (activeTab === "history") {
      fetchRecentLogs(100).then(setRecentLogs).catch(() => {});
    }
  }, [activeTab]);

  const handleTrigger = useCallback((jobName: string) => {
    setConfirmAction({ type: "trigger", jobName });
  }, []);

  const handlePause = useCallback((jobName: string) => {
    setConfirmAction({ type: "pause", jobName });
  }, []);

  const handleResume = useCallback((jobName: string) => {
    setConfirmAction({ type: "resume", jobName });
  }, []);

  const executeAction = async () => {
    if (!confirmAction) return;
    const { type, jobName } = confirmAction;
    setConfirmAction(null);
    if (type === "trigger") await trigger(jobName);
    else if (type === "pause") await pause(jobName);
    else if (type === "resume") await resume(jobName);
  };

  const groupedJobs = jobs.reduce<Record<string, ScheduledJobConfig[]>>((acc, job) => {
    const group = job.jobGroup || "system";
    if (!acc[group]) acc[group] = [];
    acc[group].push(job);
    return acc;
  }, {});

  const groupLabels: Record<string, string> = {
    agent: "Agent 任务",
    data: "数据维护",
    monitor: "监控任务",
    system: "系统任务",
    custom: "自定义任务",
  };

  if (loading && jobs.length === 0) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <Loader2 size={24} className="animate-spin text-slate-400" />
      </div>
    );
  }

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      <div className="px-6 py-4 border-b border-slate-200 dark:border-slate-700">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-bold text-slate-800 dark:text-slate-100">调度中心</h2>
          <button
            onClick={() => setShowCreate(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-lg bg-blue-600 text-white hover:bg-blue-700 transition-colors"
          >
            <Plus size={14} />
            新建任务
          </button>
        </div>
        <div className="flex gap-1 mt-3">
          <TabButton active={activeTab === "overview"} onClick={() => setActiveTab("overview")} icon={<Timer size={14} />} label="任务总览" />
          <TabButton active={activeTab === "history"} onClick={() => setActiveTab("history")} icon={<List size={14} />} label="执行历史" />
          <TabButton active={activeTab === "stats"} onClick={() => setActiveTab("stats")} icon={<BarChart3 size={14} />} label="统计" />
        </div>
      </div>

      {error && (
        <div className="mx-6 mt-3 p-2 bg-red-50 dark:bg-red-900/20 text-red-600 text-sm rounded-lg">{error}</div>
      )}

      <div className="flex-1 overflow-y-auto px-6 py-4">
        {activeTab === "overview" && (
          <div className="space-y-6">
            {Object.entries(groupedJobs).map(([group, groupJobs]) => (
              <div key={group}>
                <h3 className="text-sm font-semibold text-slate-600 dark:text-slate-300 mb-3">
                  {groupLabels[group] || group}
                </h3>
                <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-3">
                  {groupJobs.map((job) => (
                    <JobCard
                      key={job.jobName}
                      job={job}
                      onTrigger={handleTrigger}
                      onPause={handlePause}
                      onResume={handleResume}
                      onEdit={setEditingJob}
                    />
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}

        {activeTab === "history" && <ExecutionHistory logs={recentLogs} />}
        {activeTab === "stats" && <StatsCharts />}
      </div>

      <JobConfigModal
        job={editingJob}
        onClose={() => setEditingJob(null)}
        onSaved={loadJobs}
      />

      {showCreate && (
        <CreateJobModal
          onClose={() => setShowCreate(false)}
          onCreated={loadJobs}
        />
      )}

      {confirmAction && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
          <div className="bg-white dark:bg-slate-900 rounded-xl shadow-xl p-6 max-w-sm mx-4">
            <p className="text-sm text-slate-700 dark:text-slate-200">
              确定要
              {confirmAction.type === "trigger" && "立即触发"}
              {confirmAction.type === "pause" && "暂停"}
              {confirmAction.type === "resume" && "恢复"}
              {" "}
              <span className="font-semibold">{confirmAction.jobName}</span> 吗？
            </p>
            <div className="flex justify-end gap-2 mt-4">
              <button
                onClick={() => setConfirmAction(null)}
                className="px-3 py-1.5 text-sm rounded-lg border border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400"
              >
                取消
              </button>
              <button
                onClick={executeAction}
                className="px-3 py-1.5 text-sm rounded-lg bg-blue-600 text-white hover:bg-blue-700"
              >
                确定
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function TabButton({ active, onClick, icon, label }: { active: boolean; onClick: () => void; icon: React.ReactNode; label: string }) {
  return (
    <button
      onClick={onClick}
      className={`flex items-center gap-1.5 px-3 py-1.5 text-xs rounded-lg transition-colors ${
        active
          ? "bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400"
          : "text-slate-500 hover:text-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800"
      }`}
    >
      {icon}
      {label}
    </button>
  );
}
