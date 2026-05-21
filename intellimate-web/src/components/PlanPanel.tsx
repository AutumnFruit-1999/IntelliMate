import { useState, useRef, useCallback } from "react";
import { usePlanStore } from "../stores/planStore";
import type { RequestFrame, ResponseFrame } from "../lib/protocol";
import {
  createPlanApprove,
  createPlanApproveAndExecute,
  createPlanPause,
  createPlanResume,
  createPlanCancel,
  createPlanSkipStep,
  createPlanModifyStep,
  createPlanAddStep,
  createPlanReorderSteps,
} from "../lib/protocol";
import PlanStepCard from "./PlanStepCard";
import {
  ChevronRight,
  ChevronLeft,
  Play,
  Pause,
  XCircle,
  CheckCircle2,
  ClipboardList,
  Plus,
  PanelRightClose,
} from "lucide-react";

interface PlanPanelProps {
  onSendAction: (request: RequestFrame) => void;
  onSendPlanActionAndWait: (request: RequestFrame) => Promise<ResponseFrame>;
  onSendMessage: (text: string) => void;
  onClose: () => void;
}

const STATUS_LABELS: Record<string, string> = {
  draft: "等待审批",
  approved: "已审批",
  executing: "执行中",
  paused: "已暂停",
  completed: "已完成",
  failed: "执行失败",
  cancelled: "已取消",
};

const STATUS_BADGE: Record<string, string> = {
  draft:
    "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300",
  approved:
    "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300",
  executing:
    "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300",
  paused:
    "bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-300",
  completed:
    "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300",
  failed: "bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300",
  cancelled:
    "bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400",
};

export default function PlanPanel({
  onSendAction,
  onSendPlanActionAndWait,
  onSendMessage,
  onClose,
}: PlanPanelProps) {
  const activePlan = usePlanStore((s) => s.plan);
  const activeStepToolCalls = usePlanStore((s) => s.stepToolCalls);
  const planHistory = usePlanStore((s) => s.planHistory);
  const currentPlanIndex = usePlanStore((s) => s.currentPlanIndex);
  const viewHistoryPlan = usePlanStore((s) => s.viewHistoryPlan);

  const plan = currentPlanIndex === 0
    ? activePlan
    : (planHistory[currentPlanIndex - 1] ?? null);
  const stepToolCalls = currentPlanIndex === 0 ? activeStepToolCalls : {};
  const isViewingActive = currentPlanIndex === 0;
  const [addingStep, setAddingStep] = useState(false);
  const [newStepTitle, setNewStepTitle] = useState("");
  const [newStepDesc, setNewStepDesc] = useState("");

  const dragIdxRef = useRef<number | null>(null);
  const [dragOverIdx, setDragOverIdx] = useState<number | null>(null);
  const [approveStarting, setApproveStarting] = useState(false);

  const canDrag = isViewingActive && (plan?.status === "draft" || plan?.status === "paused");

  const handleDragStart = useCallback((idx: number) => {
    dragIdxRef.current = idx;
  }, []);

  const handleDragOver = useCallback(
    (e: React.DragEvent, idx: number) => {
      e.preventDefault();
      if (dragIdxRef.current !== null && dragIdxRef.current !== idx) {
        setDragOverIdx(idx);
      }
    },
    [],
  );

  const handleDrop = useCallback(
    (e: React.DragEvent, dropIdx: number) => {
      e.preventDefault();
      setDragOverIdx(null);
      const fromIdx = dragIdxRef.current;
      dragIdxRef.current = null;
      if (fromIdx === null || fromIdx === dropIdx || !plan) return;

      const indices = plan.steps.map((s) => s.index);
      const [moved] = indices.splice(fromIdx, 1);
      indices.splice(dropIdx, 0, moved);
      onSendAction(createPlanReorderSteps(plan.planId, indices));
    },
    [plan, onSendAction],
  );

  const handleDragEnd = useCallback(() => {
    dragIdxRef.current = null;
    setDragOverIdx(null);
  }, []);

  if (!plan) return null;

  const completedCount = plan.steps.filter(
    (s) => s.status === "completed",
  ).length;
  const totalSteps = plan.steps.length;
  const progress = totalSteps > 0 ? (completedCount / totalSteps) * 100 : 0;
  const failedStep = plan.steps.find((s) => s.status === "failed");

  const handleApproveAndExecute = useCallback(async () => {
    if (!plan) return;
    setApproveStarting(true);
    try {
      const res = await onSendPlanActionAndWait(
        createPlanApproveAndExecute(plan.planId),
      );
      if (!res.ok) {
        const currentStatus = (res.payload as Record<string, unknown>)?.currentStatus as string | undefined;
        if (currentStatus === "approved") {
          console.warn("[PlanPanel] approved but execution failed, user can retry via resume");
        }
        console.error("[PlanPanel] plan.approve_and_execute failed:", res.error);
      }
    } catch (e) {
      console.error("[PlanPanel] approve and execute:", e);
    } finally {
      setApproveStarting(false);
    }
  }, [plan, onSendPlanActionAndWait]);

  return (
    <div className="w-[420px] flex-shrink-0 border-l border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 flex flex-col h-full overflow-hidden">
      {/* Header */}
      <div className="px-4 py-3 border-b border-slate-200 dark:border-slate-700 flex items-center gap-2">
        <ClipboardList size={18} className="text-blue-500 flex-shrink-0" />
        <h2 className="text-sm font-semibold truncate flex-1">{plan.title}</h2>
        <span
          className={`text-xs px-2 py-0.5 rounded-full font-medium whitespace-nowrap ${STATUS_BADGE[plan.status]}`}
        >
          {STATUS_LABELS[plan.status]}
        </span>
        <button
          onClick={onClose}
          className="p-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          title="收起面板"
        >
          <PanelRightClose size={16} className="text-slate-400" />
        </button>
      </div>

      {/* Progress bar */}
      {(plan.status === "executing" || plan.status === "completed") && (
        <div className="px-4 pt-3 pb-1">
          <div className="flex items-center justify-between text-xs text-slate-500 dark:text-slate-400 mb-1.5">
            <span>进度</span>
            <span>
              {completedCount}/{totalSteps}
            </span>
          </div>
          <div className="h-1.5 rounded-full bg-slate-100 dark:bg-slate-800 overflow-hidden">
            <div
              className="h-full rounded-full bg-blue-500 transition-all duration-500 ease-out"
              style={{ width: `${progress}%` }}
            />
          </div>
        </div>
      )}

      {/* Failure banner */}
      {plan.status === "paused" && failedStep && (
        <div className="mx-4 mt-3 px-3 py-2 rounded-lg bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-300 text-xs">
          步骤 {failedStep.index} 执行失败
          {failedStep.resultSummary && `：${failedStep.resultSummary}`}
        </div>
      )}

      {/* Steps list */}
      <div className="flex-1 overflow-y-auto px-3 py-3 flex flex-col gap-1">
        {plan.steps.map((step, i) => (
          <div
            key={step.index}
            draggable={canDrag}
            onDragStart={() => canDrag && handleDragStart(i)}
            onDragOver={(e) => canDrag && handleDragOver(e, i)}
            onDrop={(e) => canDrag && handleDrop(e, i)}
            onDragEnd={handleDragEnd}
            className={`transition-all ${canDrag ? "cursor-grab active:cursor-grabbing" : ""} ${
              dragOverIdx === i ? "ring-2 ring-blue-400 rounded-lg" : ""
            }`}
          >
            <PlanStepCard
              step={step}
              planStatus={plan.status}
              toolCalls={stepToolCalls[step.index] ?? []}
              onSkip={() =>
                onSendAction(createPlanSkipStep(plan.planId, step.index))
              }
              onModify={(title, desc) =>
                onSendAction(
                  createPlanModifyStep(plan.planId, step.index, title, desc),
                )
              }
            />
          </div>
        ))}

        {(plan.status === "draft" || plan.status === "paused") && (
          <>
            {addingStep ? (
              <div className="rounded-lg border border-dashed border-blue-300 dark:border-blue-700 p-3 flex flex-col gap-2">
                <input
                  className="border border-slate-200 dark:border-slate-700 rounded-md px-2.5 py-1.5 text-sm w-full bg-white dark:bg-slate-800 outline-none focus:border-blue-400"
                  placeholder="步骤标题"
                  value={newStepTitle}
                  onChange={(e) => setNewStepTitle(e.target.value)}
                  autoFocus
                />
                <textarea
                  className="border border-slate-200 dark:border-slate-700 rounded-md px-2.5 py-1.5 text-sm w-full bg-white dark:bg-slate-800 outline-none focus:border-blue-400 resize-none"
                  rows={2}
                  placeholder="步骤描述（可选）"
                  value={newStepDesc}
                  onChange={(e) => setNewStepDesc(e.target.value)}
                />
                <div className="flex gap-2">
                  <button
                    className="text-xs px-3 py-1 bg-blue-500 text-white rounded-md hover:bg-blue-600 transition-colors disabled:opacity-50"
                    disabled={!newStepTitle.trim()}
                    onClick={() => {
                      const afterIndex = plan.steps.length > 0 ? plan.steps[plan.steps.length - 1].index : -1;
                      onSendAction(createPlanAddStep(plan.planId, afterIndex, newStepTitle.trim(), newStepDesc.trim()));
                      setNewStepTitle("");
                      setNewStepDesc("");
                      setAddingStep(false);
                    }}
                  >
                    添加
                  </button>
                  <button
                    className="text-xs px-3 py-1 bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 rounded-md hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
                    onClick={() => { setAddingStep(false); setNewStepTitle(""); setNewStepDesc(""); }}
                  >
                    取消
                  </button>
                </div>
              </div>
            ) : (
              <button
                className="flex items-center justify-center gap-1 text-xs text-slate-400 hover:text-blue-500 border border-dashed border-slate-200 dark:border-slate-700 hover:border-blue-300 dark:hover:border-blue-700 rounded-lg py-2 transition-colors"
                onClick={() => setAddingStep(true)}
              >
                <Plus size={14} />
                新增步骤
              </button>
            )}
          </>
        )}
      </div>

      {/* Plan history pagination */}
      {planHistory.length > 0 && (() => {
        const total = planHistory.length + 1;
        return (
          <div className="px-4 py-2 border-t border-slate-100 dark:border-slate-800 flex items-center justify-center gap-3 flex-shrink-0">
            <button
              className="p-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors disabled:opacity-30"
              disabled={currentPlanIndex <= 0}
              onClick={() => viewHistoryPlan("prev")}
              title="上一个计划"
            >
              <ChevronLeft size={14} className="text-slate-400" />
            </button>
            <span className="text-[11px] text-slate-400">
              第 {currentPlanIndex + 1} / 共 {total} 个
            </span>
            <button
              className="p-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors disabled:opacity-30"
              disabled={currentPlanIndex >= total - 1}
              onClick={() => viewHistoryPlan("next")}
              title="下一个计划"
            >
              <ChevronRight size={14} className="text-slate-400" />
            </button>
          </div>
        );
      })()}

      {/* Action bar */}
      <div className="px-4 py-3 border-t border-slate-200 dark:border-slate-700 flex items-center gap-2 flex-wrap flex-shrink-0">
        {plan.status === "draft" && (
          <>
            <button
              className="flex items-center gap-1.5 text-sm px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors font-medium shadow-sm disabled:opacity-50"
              disabled={approveStarting}
              onClick={() => void handleApproveAndExecute()}
            >
              <Play size={14} />
              {approveStarting ? "处理中…" : "批准并执行"}
            </button>
            <button
              className="flex items-center gap-1.5 text-sm px-4 py-2 bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 rounded-lg hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
              onClick={() =>
                onSendAction(createPlanApprove(plan.planId, false))
              }
            >
              <XCircle size={14} />
              拒绝
            </button>
          </>
        )}

        {plan.status === "approved" && (
          <button
            className="flex items-center gap-1.5 text-sm px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors font-medium shadow-sm"
            onClick={async () => {
              const res = await onSendPlanActionAndWait(
                createPlanResume(plan.planId),
              );
              if (!res.ok) {
                console.error("[PlanPanel] plan.resume failed:", res.error);
                return;
              }
              onSendMessage("开始执行计划");
            }}
          >
            <Play size={14} />
            开始执行
          </button>
        )}

        {plan.status === "executing" && (
          <>
            <button
              className="flex items-center gap-1.5 text-sm px-4 py-2 bg-orange-500 text-white rounded-lg hover:bg-orange-600 transition-colors font-medium shadow-sm"
              onClick={() => onSendAction(createPlanPause(plan.planId))}
            >
              <Pause size={14} />
              停止（当前步骤完成后）
            </button>
            <button
              className="flex items-center gap-1.5 text-sm px-3 py-2 text-red-600 border border-red-200 dark:border-red-800/40 rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
              onClick={() => onSendAction(createPlanCancel(plan.planId))}
            >
              <XCircle size={14} />
              取消
            </button>
          </>
        )}

        {plan.status === "paused" && (
          <>
            <button
              className="flex items-center gap-1.5 text-sm px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors font-medium shadow-sm"
              onClick={async () => {
                const res = await onSendPlanActionAndWait(
                  createPlanResume(plan.planId),
                );
                if (!res.ok) {
                  console.error("[PlanPanel] plan.resume failed:", res.error);
                  return;
                }
                onSendMessage("继续执行计划");
              }}
            >
              <Play size={14} />
              恢复执行
            </button>
            <button
              className="flex items-center gap-1.5 text-sm px-3 py-2 text-red-600 border border-red-200 dark:border-red-800/40 rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
              onClick={() => onSendAction(createPlanCancel(plan.planId))}
            >
              <XCircle size={14} />
              取消
            </button>
          </>
        )}

        {(plan.status === "completed" || plan.status === "cancelled") && (
          <div className="flex items-center gap-1.5 text-xs text-slate-400">
            <CheckCircle2 size={14} />
            {plan.status === "completed" ? "计划已完成" : "计划已取消"}
          </div>
        )}
      </div>
    </div>
  );
}
