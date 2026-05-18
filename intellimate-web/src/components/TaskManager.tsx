import { useState, useEffect, useCallback } from "react";
import { Plus, Check, Trash2, Clock } from "lucide-react";
import {
  AgentTask,
  fetchTasks,
  createTask,
  updateTask,
  deleteTask,
} from "../lib/heartbeatApi";

interface TaskManagerProps {
  agentId: number;
}

const PRIORITY_LABELS = ["普通", "重要", "紧急"];
const PRIORITY_COLORS = [
  "text-slate-500",
  "text-amber-600 dark:text-amber-400",
  "text-red-600 dark:text-red-400",
];

export default function TaskManager({ agentId }: TaskManagerProps) {
  const [tasks, setTasks] = useState<AgentTask[]>([]);
  const [showForm, setShowForm] = useState(false);
  const [newTitle, setNewTitle] = useState("");
  const [newPriority, setNewPriority] = useState(0);
  const [newDueAt, setNewDueAt] = useState("");
  const [newRemindAt, setNewRemindAt] = useState("");
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    try {
      const data = await fetchTasks(agentId);
      setTasks(data);
    } finally {
      setLoading(false);
    }
  }, [agentId]);

  useEffect(() => { load(); }, [load]);

  const handleCreate = async () => {
    if (!newTitle.trim()) return;
    await createTask(agentId, {
      title: newTitle.trim(),
      priority: newPriority,
      dueAt: newDueAt || null,
      remindAt: newRemindAt || null,
    });
    setNewTitle("");
    setNewPriority(0);
    setNewDueAt("");
    setNewRemindAt("");
    setShowForm(false);
    load();
  };

  const handleComplete = async (task: AgentTask) => {
    if (!task.id) return;
    await updateTask(agentId, task.id, { status: "done" });
    load();
  };

  const handleDelete = async (task: AgentTask) => {
    if (!task.id) return;
    await deleteTask(agentId, task.id);
    load();
  };

  if (loading) {
    return <div className="flex justify-center py-8 text-slate-400">加载中...</div>;
  }

  const pendingTasks = tasks.filter((t) => t.status === "pending");
  const doneTasks = tasks.filter((t) => t.status === "done");

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">
          待办事项 ({pendingTasks.length})
        </h3>
        <button
          onClick={() => setShowForm(!showForm)}
          className="flex items-center gap-1 px-3 py-1.5 text-xs font-medium text-blue-600 dark:text-blue-400 bg-blue-50 dark:bg-blue-900/20 rounded-lg hover:bg-blue-100 dark:hover:bg-blue-900/30 transition-colors"
        >
          <Plus size={14} />
          新建
        </button>
      </div>

      {/* Create Form */}
      {showForm && (
        <div className="p-4 rounded-xl border border-slate-200 dark:border-slate-700 space-y-3">
          <input
            type="text"
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
            placeholder="任务标题..."
            autoFocus
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200"
            onKeyDown={(e) => { if (e.key === "Enter") handleCreate(); }}
          />
          <div className="grid grid-cols-3 gap-3">
            <div>
              <label className="block text-[10px] text-slate-400 mb-1">优先级</label>
              <select
                value={newPriority}
                onChange={(e) => setNewPriority(Number(e.target.value))}
                className="w-full px-2 py-1.5 text-xs rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200"
              >
                <option value={0}>普通</option>
                <option value={1}>重要</option>
                <option value={2}>紧急</option>
              </select>
            </div>
            <div>
              <label className="block text-[10px] text-slate-400 mb-1">截止时间</label>
              <input
                type="datetime-local"
                value={newDueAt}
                onChange={(e) => setNewDueAt(e.target.value)}
                className="w-full px-2 py-1.5 text-xs rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200"
              />
            </div>
            <div>
              <label className="block text-[10px] text-slate-400 mb-1">提醒时间</label>
              <input
                type="datetime-local"
                value={newRemindAt}
                onChange={(e) => setNewRemindAt(e.target.value)}
                className="w-full px-2 py-1.5 text-xs rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200"
              />
            </div>
          </div>
          <div className="flex justify-end gap-2">
            <button
              onClick={() => setShowForm(false)}
              className="px-3 py-1.5 text-xs text-slate-500 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800"
            >
              取消
            </button>
            <button
              onClick={handleCreate}
              disabled={!newTitle.trim()}
              className="px-3 py-1.5 text-xs font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50"
            >
              创建
            </button>
          </div>
        </div>
      )}

      {/* Pending Tasks */}
      {pendingTasks.length === 0 && !showForm && (
        <div className="text-center py-8 text-sm text-slate-400">
          暂无待办事项
        </div>
      )}

      <div className="space-y-2">
        {pendingTasks.map((task) => (
          <div
            key={task.id}
            className="flex items-center gap-3 p-3 rounded-xl bg-slate-50 dark:bg-slate-800/50 group"
          >
            <button
              onClick={() => handleComplete(task)}
              className="shrink-0 w-5 h-5 rounded-full border-2 border-slate-300 dark:border-slate-600 hover:border-green-500 hover:bg-green-50 dark:hover:bg-green-900/20 transition-colors flex items-center justify-center"
            >
              <Check size={10} className="text-transparent group-hover:text-green-500" />
            </button>
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2">
                <span className="text-sm text-slate-700 dark:text-slate-200 truncate">
                  {task.title}
                </span>
                {task.priority > 0 && (
                  <span className={`text-[10px] font-medium ${PRIORITY_COLORS[task.priority]}`}>
                    {PRIORITY_LABELS[task.priority]}
                  </span>
                )}
              </div>
              {task.dueAt && (
                <div className="flex items-center gap-1 mt-0.5 text-[10px] text-slate-400">
                  <Clock size={10} />
                  {new Date(task.dueAt).toLocaleString("zh-CN", {
                    month: "short", day: "numeric", hour: "2-digit", minute: "2-digit"
                  })}
                </div>
              )}
            </div>
            <button
              onClick={() => handleDelete(task)}
              className="shrink-0 p-1 text-slate-300 hover:text-red-500 opacity-0 group-hover:opacity-100 transition-all"
            >
              <Trash2 size={14} />
            </button>
          </div>
        ))}
      </div>

      {/* Done Tasks */}
      {doneTasks.length > 0 && (
        <details className="mt-4">
          <summary className="text-xs text-slate-400 cursor-pointer hover:text-slate-500">
            已完成 ({doneTasks.length})
          </summary>
          <div className="space-y-1 mt-2">
            {doneTasks.map((task) => (
              <div
                key={task.id}
                className="flex items-center gap-3 p-2 rounded-lg text-xs text-slate-400 line-through"
              >
                <div className="w-4 h-4 rounded-full bg-green-100 dark:bg-green-900/30 flex items-center justify-center">
                  <Check size={10} className="text-green-500" />
                </div>
                {task.title}
              </div>
            ))}
          </div>
        </details>
      )}
    </div>
  );
}
