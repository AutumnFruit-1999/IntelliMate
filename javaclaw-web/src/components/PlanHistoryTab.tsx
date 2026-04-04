import { useState, useEffect, useCallback } from "react";
import { useAgentStore } from "../stores/agentStore";
import {
  ChevronDown,
  ChevronRight,
  Trash2,
  ClipboardList,
  ArrowLeft,
  Filter,
} from "lucide-react";

interface PlanSummary {
  planId: number;
  title: string;
  status: string;
  totalSteps: number;
  completedSteps: number;
  createdAt: string | null;
}

interface PlanStepDetail {
  index: number;
  title: string;
  description: string;
  status: string;
  resultSummary: string | null;
}

const STATUS_LABELS: Record<string, string> = {
  draft: "草稿",
  approved: "已审批",
  executing: "执行中",
  paused: "已暂停",
  completed: "已完成",
  failed: "失败",
  cancelled: "已取消",
};

const STATUS_BADGE: Record<string, string> = {
  draft: "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300",
  approved: "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300",
  executing: "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300",
  paused: "bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300",
  completed: "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300",
  failed: "bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300",
  cancelled: "bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400",
};

export default function PlanHistoryTab({ onBack }: { onBack: () => void }) {
  const agents = useAgentStore((s) => s.agents);
  const [plans, setPlans] = useState<PlanSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [agentFilter, setAgentFilter] = useState("");
  const [expandedPlanId, setExpandedPlanId] = useState<number | null>(null);
  const [stepDetails, setStepDetails] = useState<Record<number, PlanStepDetail[]>>({});
  const [confirmDelete, setConfirmDelete] = useState<number | null>(null);

  const fetchPlans = useCallback(async () => {
    setLoading(true);
    try {
      const params = agentFilter ? `?agentName=${encodeURIComponent(agentFilter)}` : "";
      const res = await fetch(`/api/plans${params}`);
      if (res.ok) {
        setPlans(await res.json());
      }
    } catch (e) {
      console.error("Failed to fetch plans:", e);
    }
    setLoading(false);
  }, [agentFilter]);

  useEffect(() => {
    fetchPlans();
  }, [fetchPlans]);

  async function handleExpand(planId: number) {
    if (expandedPlanId === planId) {
      setExpandedPlanId(null);
      return;
    }
    setExpandedPlanId(planId);
    if (!stepDetails[planId]) {
      try {
        const res = await fetch(`/api/plans/${planId}/steps`);
        if (res.ok) {
          const steps: PlanStepDetail[] = await res.json();
          setStepDetails((prev) => ({ ...prev, [planId]: steps }));
        }
      } catch (e) {
        console.error("Failed to fetch steps:", e);
      }
    }
  }

  async function handleDelete(planId: number) {
    try {
      const res = await fetch(`/api/plans/${planId}`, { method: "DELETE" });
      if (res.ok) {
        setPlans((prev) => prev.filter((p) => p.planId !== planId));
        setConfirmDelete(null);
      }
    } catch (e) {
      console.error("Failed to delete plan:", e);
    }
  }

  return (
    <div className="flex flex-col flex-1 min-w-0 min-h-0">
      <div className="px-6 py-4 border-b border-slate-200 dark:border-slate-700 flex items-center gap-3">
        <button
          onClick={onBack}
          className="p-1.5 rounded hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
        >
          <ArrowLeft size={18} className="text-slate-500" />
        </button>
        <ClipboardList size={20} className="text-blue-500" />
        <h1 className="text-lg font-semibold">任务历史</h1>

        <div className="ml-auto flex items-center gap-2">
          <Filter size={14} className="text-slate-400" />
          <select
            className="text-sm border border-slate-200 dark:border-slate-700 rounded-lg px-3 py-1.5 bg-white dark:bg-slate-800 outline-none focus:border-blue-400"
            value={agentFilter}
            onChange={(e) => setAgentFilter(e.target.value)}
          >
            <option value="">全部 Agent</option>
            {agents.map((a) => (
              <option key={a.name} value={a.name}>
                {a.name}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-6">
        {loading ? (
          <div className="text-center text-slate-400 py-12">加载中...</div>
        ) : plans.length === 0 ? (
          <div className="text-center text-slate-400 py-12">暂无任务记录</div>
        ) : (
          <div className="space-y-2 max-w-3xl mx-auto">
            {plans.map((plan) => (
              <div
                key={plan.planId}
                className="border border-slate-200 dark:border-slate-700 rounded-lg bg-white dark:bg-slate-900 overflow-hidden"
              >
                <div
                  className="flex items-center gap-3 px-4 py-3 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors"
                  onClick={() => handleExpand(plan.planId)}
                >
                  {expandedPlanId === plan.planId ? (
                    <ChevronDown size={16} className="text-slate-400 flex-shrink-0" />
                  ) : (
                    <ChevronRight size={16} className="text-slate-400 flex-shrink-0" />
                  )}
                  <span className="text-sm font-medium flex-1 truncate">
                    {plan.title}
                  </span>
                  <span
                    className={`text-xs px-2 py-0.5 rounded-full font-medium whitespace-nowrap ${STATUS_BADGE[plan.status] ?? ""}`}
                  >
                    {STATUS_LABELS[plan.status] ?? plan.status}
                  </span>
                  <span className="text-xs text-slate-400 whitespace-nowrap">
                    {plan.completedSteps}/{plan.totalSteps} 步
                  </span>
                  {plan.createdAt && (
                    <span className="text-xs text-slate-400 whitespace-nowrap">
                      {plan.createdAt.replace("T", " ").slice(0, 16)}
                    </span>
                  )}
                  {confirmDelete === plan.planId ? (
                    <div className="flex gap-1" onClick={(e) => e.stopPropagation()}>
                      <button
                        className="text-xs px-2 py-1 bg-red-500 text-white rounded hover:bg-red-600"
                        onClick={() => handleDelete(plan.planId)}
                      >
                        确认
                      </button>
                      <button
                        className="text-xs px-2 py-1 bg-slate-100 dark:bg-slate-800 rounded"
                        onClick={() => setConfirmDelete(null)}
                      >
                        取消
                      </button>
                    </div>
                  ) : (
                    <button
                      className="p-1 rounded hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
                      onClick={(e) => {
                        e.stopPropagation();
                        setConfirmDelete(plan.planId);
                      }}
                      title="删除"
                    >
                      <Trash2 size={14} className="text-red-400" />
                    </button>
                  )}
                </div>

                {expandedPlanId === plan.planId && (
                  <div className="border-t border-slate-100 dark:border-slate-800 px-4 py-3">
                    {!stepDetails[plan.planId] ? (
                      <div className="text-xs text-slate-400">加载步骤...</div>
                    ) : (
                      <div className="space-y-2">
                        {stepDetails[plan.planId].map((step) => (
                          <div
                            key={step.index}
                            className="flex items-start gap-2 text-sm"
                          >
                            <span className={`mt-0.5 flex-shrink-0 w-4 h-4 rounded-full flex items-center justify-center text-[10px] font-bold ${
                              step.status === "completed"
                                ? "bg-emerald-100 text-emerald-600"
                                : step.status === "failed"
                                  ? "bg-red-100 text-red-600"
                                  : step.status === "skipped"
                                    ? "bg-slate-100 text-slate-400"
                                    : "bg-slate-100 text-slate-500"
                            }`}>
                              {step.index + 1}
                            </span>
                            <div className="flex-1 min-w-0">
                              <div className="font-medium text-slate-700 dark:text-slate-200">
                                {step.title}
                              </div>
                              {step.description && (
                                <div className="text-xs text-slate-400 mt-0.5">
                                  {step.description}
                                </div>
                              )}
                              {step.resultSummary && (
                                <div className={`text-xs mt-0.5 ${
                                  step.status === "failed" ? "text-red-500" : "text-emerald-500"
                                }`}>
                                  {step.resultSummary}
                                </div>
                              )}
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
