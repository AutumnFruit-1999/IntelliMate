import React, { useState } from "react";
import { updateTask } from "../../lib/heartbeatApi";

interface TaskData {
  id: number;
  title: string;
  description?: string | null;
  dueAt?: string | null;
  remindAt?: string | null;
  priority: number;
  status: string;
}

interface TaskCardProps {
  action: string;
  task?: TaskData;
  tasks?: TaskData[];
  total?: number;
  agentId?: number;
}

const priorityLabels: Record<number, { label: string; color: string }> = {
  0: { label: "普通", color: "text-gray-500" },
  1: { label: "重要", color: "text-orange-500" },
  2: { label: "紧急", color: "text-red-500" },
};

function formatTime(iso: string | null | undefined): string {
  if (!iso) return "";
  try {
    return new Date(iso).toLocaleString("zh-CN", {
      month: "numeric", day: "numeric",
      hour: "2-digit", minute: "2-digit",
    });
  } catch { return iso; }
}

export const TaskCard: React.FC<TaskCardProps> = ({ action, task, tasks, total, agentId }) => {
  const [localStatus, setLocalStatus] = useState(task?.status);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleStatusChange = async (newStatus: string) => {
    if (!task?.id || !agentId || loading) return;
    setLoading(true);
    setError(null);
    try {
      await updateTask(agentId, task.id, { status: newStatus });
      setLocalStatus(newStatus);
    } catch {
      setError("操作失败，请重试");
    } finally {
      setLoading(false);
    }
  };

  if (action === "listed" && tasks) {
    return (
      <div className="rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 p-3 my-2 max-w-md">
        <div className="text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
          📋 待办任务（{total} 项）
        </div>
        <div className="space-y-1.5">
          {tasks.map((t) => {
            const p = priorityLabels[t.priority] ?? priorityLabels[0];
            return (
              <div key={t.id} className="flex items-center gap-2 text-sm">
                <span className={p.color}>●</span>
                <span className="flex-1 truncate">{t.title}</span>
                {t.dueAt && <span className="text-xs text-gray-400">{formatTime(t.dueAt)}</span>}
              </div>
            );
          })}
        </div>
      </div>
    );
  }

  if (!task) return null;
  const p = priorityLabels[task.priority] ?? priorityLabels[0];
  const effectiveStatus = localStatus ?? task.status;
  const isDone = effectiveStatus === "done" || effectiveStatus === "cancelled";
  const actionLabel = action === "created" ? "已创建" : action === "updated" ? "已更新" : action === "deleted" ? "已删除" : "";

  return (
    <div className="rounded-lg border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 p-3 my-2 max-w-md">
      <div className="text-sm font-medium text-gray-700 dark:text-gray-300 mb-2">
        ✅ 待办任务{actionLabel}
      </div>
      <div className="space-y-1">
        <div className="font-medium text-gray-900 dark:text-gray-100">{task.title}</div>
        {task.description && <div className="text-sm text-gray-500">{task.description}</div>}
        {task.dueAt && <div className="text-xs text-gray-400">⏰ 截止：{formatTime(task.dueAt)}</div>}
        {task.remindAt && <div className="text-xs text-gray-400">🔔 提醒：{formatTime(task.remindAt)}</div>}
        <div className="text-xs"><span className={p.color}>● {p.label}</span></div>
      </div>
      {error && <div className="text-xs text-red-500 mt-1">{error}</div>}
      {!isDone && action !== "deleted" && (
        <div className="flex gap-2 mt-2 pt-2 border-t border-gray-100 dark:border-gray-700">
          <button onClick={() => handleStatusChange("done")} disabled={loading}
                  className="text-xs px-2 py-1 rounded bg-green-50 text-green-600 hover:bg-green-100 disabled:opacity-50">
            {loading ? "处理中..." : "标记完成"}
          </button>
          <button onClick={() => handleStatusChange("cancelled")} disabled={loading}
                  className="text-xs px-2 py-1 rounded bg-gray-50 text-gray-500 hover:bg-gray-100 disabled:opacity-50">
            取消
          </button>
        </div>
      )}
      {isDone && <div className="text-xs text-gray-400 mt-1">状态：{effectiveStatus === "done" ? "已完成" : "已取消"}</div>}
    </div>
  );
};
