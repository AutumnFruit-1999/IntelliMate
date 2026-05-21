import { useState, useEffect, useCallback, useMemo } from "react";
import { useAgentStore } from "../stores/agentStore";
import {
  ChevronDown,
  ChevronRight,
  Trash2,
  ClipboardList,
  ArrowLeft,
  ArrowUpDown,
  ArrowUp,
  ArrowDown,
} from "lucide-react";

interface PlanSummary {
  planId: number;
  title: string;
  status: string;
  totalSteps: number;
  completedSteps: number;
  createdAt: string | null;
  agentName: string | null;
  completionSummary?: string | null;
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
  completed:
    "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300",
  failed: "bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300",
  cancelled:
    "bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400",
};

const ALL_STATUSES = Object.keys(STATUS_LABELS);

type SortDir = "desc" | "asc";

export default function PlanHistoryTab({ onBack }: { onBack: () => void }) {
  const agents = useAgentStore((s) => s.agents);
  const [plans, setPlans] = useState<PlanSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [agentFilter, setAgentFilter] = useState("");
  const [statusFilter, setStatusFilter] = useState("");
  const [sortDir, setSortDir] = useState<SortDir>("desc");
  const [expandedPlanId, setExpandedPlanId] = useState<number | null>(null);
  const [stepDetails, setStepDetails] = useState<
    Record<number, PlanStepDetail[]>
  >({});
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [confirmBatchDelete, setConfirmBatchDelete] = useState(false);
  const [confirmDeleteId, setConfirmDeleteId] = useState<number | null>(null);

  const fetchPlans = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (agentFilter) params.set("agentName", agentFilter);
      if (statusFilter) params.set("status", statusFilter);
      const qs = params.toString();
      const res = await fetch(`/api/plans${qs ? `?${qs}` : ""}`);
      if (res.ok) {
        const data: PlanSummary[] = await res.json();
        setPlans(data);
        setSelectedIds(new Set());
      }
    } catch (e) {
      console.error("Failed to fetch plans:", e);
    }
    setLoading(false);
  }, [agentFilter, statusFilter]);

  useEffect(() => {
    fetchPlans();
  }, [fetchPlans]);

  const sortedPlans = useMemo(() => {
    if (sortDir === "desc") return plans;
    return [...plans].reverse();
  }, [plans, sortDir]);

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
        setStepDetails((prev) => {
          const next = { ...prev };
          delete next[planId];
          return next;
        });
        setSelectedIds((prev) => {
          const next = new Set(prev);
          next.delete(planId);
          return next;
        });
        setExpandedPlanId((prev) => (prev === planId ? null : prev));
        setConfirmDeleteId(null);
      }
    } catch (e) {
      console.error("Failed to delete plan:", e);
    }
  }

  async function handleBatchDelete() {
    if (selectedIds.size === 0) return;
    try {
      const res = await fetch("/api/plans/batch", {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(Array.from(selectedIds)),
      });
      if (res.ok) {
        setPlans((prev) => prev.filter((p) => !selectedIds.has(p.planId)));
        setStepDetails((prev) => {
          const next = { ...prev };
          selectedIds.forEach((id) => delete next[id]);
          return next;
        });
        if (expandedPlanId !== null && selectedIds.has(expandedPlanId)) {
          setExpandedPlanId(null);
        }
        setSelectedIds(new Set());
        setConfirmBatchDelete(false);
      }
    } catch (e) {
      console.error("Failed to batch delete plans:", e);
    }
  }

  function toggleSelect(planId: number) {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(planId)) next.delete(planId);
      else next.add(planId);
      return next;
    });
  }

  function toggleSelectAll() {
    if (selectedIds.size === sortedPlans.length) {
      setSelectedIds(new Set());
    } else {
      setSelectedIds(new Set(sortedPlans.map((p) => p.planId)));
    }
  }

  const allSelected =
    sortedPlans.length > 0 && selectedIds.size === sortedPlans.length;
  const someSelected = selectedIds.size > 0;

  const selectClasses =
    "text-sm border border-slate-200 dark:border-slate-700 rounded-lg px-3 py-1.5 bg-white dark:bg-slate-800 outline-none focus:border-blue-400";

  return (
    <div className="flex flex-col flex-1 min-w-0 min-h-0">
      {/* Header */}
      <div className="px-6 py-4 border-b border-slate-200 dark:border-slate-700 flex items-center gap-3">
        <button
          onClick={onBack}
          className="p-1.5 rounded hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
        >
          <ArrowLeft size={18} className="text-slate-500" />
        </button>
        <ClipboardList size={20} className="text-blue-500" />
        <h1 className="text-lg font-semibold">任务历史</h1>

        <div className="ml-auto flex items-center gap-3">
          <select
            className={selectClasses}
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
          <select
            className={selectClasses}
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
          >
            <option value="">全部状态</option>
            {ALL_STATUSES.map((s) => (
              <option key={s} value={s}>
                {STATUS_LABELS[s]}
              </option>
            ))}
          </select>
        </div>
      </div>

      {/* Batch action bar */}
      {someSelected && (
        <div className="px-6 py-2.5 border-b border-slate-200 dark:border-slate-700 bg-blue-50 dark:bg-blue-950/30 flex items-center gap-3">
          <span className="text-sm text-blue-700 dark:text-blue-300">
            已选择 {selectedIds.size} 项
          </span>
          {confirmBatchDelete ? (
            <div className="flex items-center gap-2">
              <span className="text-sm text-red-600 dark:text-red-400">
                确认删除 {selectedIds.size} 个任务？
              </span>
              <button
                className="text-xs px-3 py-1 bg-red-500 text-white rounded-md hover:bg-red-600 transition-colors"
                onClick={handleBatchDelete}
              >
                确认
              </button>
              <button
                className="text-xs px-3 py-1 bg-slate-200 dark:bg-slate-700 rounded-md hover:bg-slate-300 dark:hover:bg-slate-600 transition-colors"
                onClick={() => setConfirmBatchDelete(false)}
              >
                取消
              </button>
            </div>
          ) : (
            <button
              className="flex items-center gap-1.5 text-xs px-3 py-1 bg-red-500 text-white rounded-md hover:bg-red-600 transition-colors"
              onClick={() => setConfirmBatchDelete(true)}
            >
              <Trash2 size={12} />
              批量删除
            </button>
          )}
          <button
            className="ml-auto text-xs text-slate-500 hover:text-slate-700 dark:hover:text-slate-300"
            onClick={() => {
              setSelectedIds(new Set());
              setConfirmBatchDelete(false);
            }}
          >
            取消选择
          </button>
        </div>
      )}

      {/* Table */}
      <div className="flex-1 overflow-auto">
        {loading ? (
          <div className="text-center text-slate-400 py-12">加载中...</div>
        ) : plans.length === 0 ? (
          <div className="text-center text-slate-400 py-12">暂无任务记录</div>
        ) : (
          <table className="w-full text-sm">
            <thead className="sticky top-0 z-10 bg-slate-50 dark:bg-slate-800/80 border-b border-slate-200 dark:border-slate-700">
              <tr>
                <th className="w-10 px-3 py-2.5 text-left">
                  <input
                    type="checkbox"
                    checked={allSelected}
                    onChange={toggleSelectAll}
                    className="rounded border-slate-300 dark:border-slate-600 cursor-pointer"
                  />
                </th>
                <th className="w-8 px-1 py-2.5" />
                <th className="px-3 py-2.5 text-left font-medium text-slate-600 dark:text-slate-300">
                  任务标题
                </th>
                <th className="px-3 py-2.5 text-left font-medium text-slate-600 dark:text-slate-300 whitespace-nowrap">
                  Agent
                </th>
                <th className="px-3 py-2.5 text-left font-medium text-slate-600 dark:text-slate-300 whitespace-nowrap">
                  状态
                </th>
                <th className="px-3 py-2.5 text-left font-medium text-slate-600 dark:text-slate-300 whitespace-nowrap">
                  进度
                </th>
                <th
                  className="px-3 py-2.5 text-left font-medium text-slate-600 dark:text-slate-300 whitespace-nowrap cursor-pointer select-none hover:text-blue-500 transition-colors"
                  onClick={() =>
                    setSortDir((d) => (d === "desc" ? "asc" : "desc"))
                  }
                >
                  <span className="inline-flex items-center gap-1">
                    创建时间
                    {sortDir === "desc" ? (
                      <ArrowDown size={14} />
                    ) : sortDir === "asc" ? (
                      <ArrowUp size={14} />
                    ) : (
                      <ArrowUpDown size={14} />
                    )}
                  </span>
                </th>
                <th className="w-16 px-3 py-2.5 text-center font-medium text-slate-600 dark:text-slate-300">
                  操作
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
              {sortedPlans.map((plan) => (
                <PlanRow
                  key={plan.planId}
                  plan={plan}
                  isExpanded={expandedPlanId === plan.planId}
                  isSelected={selectedIds.has(plan.planId)}
                  isConfirmingDelete={confirmDeleteId === plan.planId}
                  stepDetails={stepDetails[plan.planId]}
                  onToggleExpand={() => handleExpand(plan.planId)}
                  onToggleSelect={() => toggleSelect(plan.planId)}
                  onRequestDelete={() => setConfirmDeleteId(plan.planId)}
                  onConfirmDelete={() => handleDelete(plan.planId)}
                  onCancelDelete={() => setConfirmDeleteId(null)}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

function PlanRow({
  plan,
  isExpanded,
  isSelected,
  isConfirmingDelete,
  stepDetails,
  onToggleExpand,
  onToggleSelect,
  onRequestDelete,
  onConfirmDelete,
  onCancelDelete,
}: {
  plan: PlanSummary;
  isExpanded: boolean;
  isSelected: boolean;
  isConfirmingDelete: boolean;
  stepDetails: PlanStepDetail[] | undefined;
  onToggleExpand: () => void;
  onToggleSelect: () => void;
  onRequestDelete: () => void;
  onConfirmDelete: () => void;
  onCancelDelete: () => void;
}) {
  return (
    <>
      <tr
        className={`hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors ${
          isSelected ? "bg-blue-50/50 dark:bg-blue-950/20" : ""
        }`}
      >
        <td className="px-3 py-2.5" onClick={(e) => e.stopPropagation()}>
          <input
            type="checkbox"
            checked={isSelected}
            onChange={onToggleSelect}
            className="rounded border-slate-300 dark:border-slate-600 cursor-pointer"
          />
        </td>
        <td className="px-1 py-2.5">
          <button
            onClick={onToggleExpand}
            className="p-0.5 rounded hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors"
          >
            {isExpanded ? (
              <ChevronDown
                size={14}
                className="text-slate-400"
              />
            ) : (
              <ChevronRight
                size={14}
                className="text-slate-400"
              />
            )}
          </button>
        </td>
        <td
          className="px-3 py-2.5 font-medium truncate max-w-xs cursor-pointer"
          onClick={onToggleExpand}
          title={plan.title}
        >
          {plan.title}
        </td>
        <td className="px-3 py-2.5 text-slate-500 dark:text-slate-400 whitespace-nowrap">
          {plan.agentName ?? "-"}
        </td>
        <td className="px-3 py-2.5">
          <span
            className={`text-xs px-2 py-0.5 rounded-full font-medium whitespace-nowrap ${
              STATUS_BADGE[plan.status] ?? ""
            }`}
          >
            {STATUS_LABELS[plan.status] ?? plan.status}
          </span>
        </td>
        <td className="px-3 py-2.5 text-slate-500 dark:text-slate-400 whitespace-nowrap">
          {plan.completedSteps}/{plan.totalSteps}
        </td>
        <td className="px-3 py-2.5 text-slate-500 dark:text-slate-400 whitespace-nowrap">
          {plan.createdAt
            ? plan.createdAt.replace("T", " ").slice(0, 16)
            : "-"}
        </td>
        <td className="px-3 py-2.5 text-center" onClick={(e) => e.stopPropagation()}>
          {isConfirmingDelete ? (
            <div className="flex items-center justify-center gap-1">
              <button
                className="text-xs px-2 py-0.5 bg-red-500 text-white rounded hover:bg-red-600 transition-colors"
                onClick={onConfirmDelete}
              >
                确认
              </button>
              <button
                className="text-xs px-2 py-0.5 bg-slate-200 dark:bg-slate-700 rounded hover:bg-slate-300 dark:hover:bg-slate-600 transition-colors"
                onClick={onCancelDelete}
              >
                取消
              </button>
            </div>
          ) : (
            <button
              className="p-1 rounded hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
              onClick={onRequestDelete}
              title="删除"
            >
              <Trash2 size={14} className="text-red-400" />
            </button>
          )}
        </td>
      </tr>
      {isExpanded && (
        <tr>
          <td colSpan={8} className="bg-slate-50/50 dark:bg-slate-800/30">
            <div className="px-12 py-3">
              {plan.completionSummary && (
                <div className="text-xs text-slate-500 dark:text-slate-400 mb-2 line-clamp-3 italic">
                  {plan.completionSummary}
                </div>
              )}
              {!stepDetails ? (
                <div className="text-xs text-slate-400">加载步骤...</div>
              ) : stepDetails.length === 0 ? (
                <div className="text-xs text-slate-400">暂无步骤</div>
              ) : (
                <div className="space-y-1.5">
                  {stepDetails.map((step) => (
                    <div
                      key={step.index}
                      className="flex items-start gap-2 text-sm"
                    >
                      <span
                        className={`mt-0.5 flex-shrink-0 w-5 h-5 rounded-full flex items-center justify-center text-[10px] font-bold ${
                          step.status === "completed"
                            ? "bg-emerald-100 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-400"
                            : step.status === "failed"
                              ? "bg-red-100 text-red-600 dark:bg-red-900/30 dark:text-red-400"
                              : step.status === "skipped"
                                ? "bg-slate-100 text-slate-400 dark:bg-slate-700 dark:text-slate-500"
                                : "bg-slate-100 text-slate-500 dark:bg-slate-700 dark:text-slate-400"
                        }`}
                      >
                        {step.index}
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
                          <div
                            className={`text-xs mt-0.5 ${
                              step.status === "failed"
                                ? "text-red-500"
                                : "text-emerald-500"
                            }`}
                          >
                            {step.resultSummary}
                          </div>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </td>
        </tr>
      )}
    </>
  );
}
