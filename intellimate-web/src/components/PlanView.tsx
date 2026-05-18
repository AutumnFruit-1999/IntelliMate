import { usePlanStore } from "../stores/planStore";
import type { RequestFrame } from "../lib/protocol";
import {
  createPlanApprove,
  createPlanPause,
  createPlanResume,
  createPlanCancel,
  createPlanSkipStep,
  createPlanModifyStep,
} from "../lib/protocol";
import PlanStepCard from "./PlanStepCard";

interface PlanViewProps {
  onSendAction: (request: RequestFrame) => void;
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

const STATUS_BADGE_COLORS: Record<string, string> = {
  draft: "bg-yellow-100 text-yellow-800 dark:bg-yellow-900/30 dark:text-yellow-300",
  approved: "bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-300",
  executing:
    "bg-blue-100 text-blue-800 dark:bg-blue-900/30 dark:text-blue-300",
  paused:
    "bg-orange-100 text-orange-800 dark:bg-orange-900/30 dark:text-orange-300",
  completed:
    "bg-green-100 text-green-800 dark:bg-green-900/30 dark:text-green-300",
  failed: "bg-red-100 text-red-800 dark:bg-red-900/30 dark:text-red-300",
  cancelled:
    "bg-gray-100 text-gray-800 dark:bg-gray-900/30 dark:text-gray-300",
};

export default function PlanView({ onSendAction }: PlanViewProps) {
  const plan = usePlanStore((s) => s.plan);

  if (!plan) return null;

  const completedCount = plan.steps.filter(
    (s) => s.status === "completed",
  ).length;
  const failedStep = plan.steps.find((s) => s.status === "failed");

  return (
    <div className="border rounded-xl shadow-sm bg-white dark:bg-gray-900 dark:border-gray-700 overflow-hidden mx-4 mb-3">
      {/* Header */}
      <div className="px-4 py-3 border-b dark:border-gray-700 flex items-center justify-between">
        <div className="flex items-center gap-2 min-w-0">
          <span className="text-sm font-semibold truncate">{plan.title}</span>
          <span
            className={`text-xs px-2 py-0.5 rounded-full font-medium ${STATUS_BADGE_COLORS[plan.status]}`}
          >
            {STATUS_LABELS[plan.status]}
            {plan.status === "executing" &&
              ` (${completedCount}/${plan.steps.length})`}
          </span>
        </div>
      </div>

      {/* Paused with failure info */}
      {plan.status === "paused" && failedStep && (
        <div className="px-4 py-2 bg-red-50 dark:bg-red-900/20 text-red-700 dark:text-red-300 text-xs">
          步骤 {failedStep.index} 执行失败
          {failedStep.resultSummary && `：${failedStep.resultSummary}`}
        </div>
      )}

      {/* Steps */}
      <div className="px-2 py-2 flex flex-col gap-1 max-h-80 overflow-y-auto">
        {plan.steps.map((step) => (
          <PlanStepCard
            key={step.index}
            step={step}
            planStatus={plan.status}
            onSkip={() => onSendAction(createPlanSkipStep(plan.planId, step.index))}
            onModify={(title, desc) =>
              onSendAction(
                createPlanModifyStep(plan.planId, step.index, title, desc),
              )
            }
          />
        ))}
      </div>

      {/* Actions */}
      <div className="px-4 py-3 border-t dark:border-gray-700 flex items-center gap-2 flex-wrap">
        {plan.status === "draft" && (
          <>
            <button
              className="text-sm px-4 py-1.5 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
              onClick={() => onSendAction(createPlanApprove(plan.planId, true))}
            >
              批准执行
            </button>
            <button
              className="text-sm px-4 py-1.5 bg-gray-200 dark:bg-gray-700 rounded-lg hover:bg-gray-300 dark:hover:bg-gray-600 transition-colors"
              onClick={() =>
                onSendAction(createPlanApprove(plan.planId, false))
              }
            >
              取消
            </button>
          </>
        )}

        {plan.status === "executing" && (
          <>
            <button
              className="text-sm px-4 py-1.5 bg-orange-500 text-white rounded-lg hover:bg-orange-600 transition-colors"
              onClick={() => onSendAction(createPlanPause(plan.planId))}
            >
              暂停
            </button>
            <button
              className="text-sm px-4 py-1.5 text-red-600 border border-red-300 rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
              onClick={() => onSendAction(createPlanCancel(plan.planId))}
            >
              取消计划
            </button>
          </>
        )}

        {plan.status === "paused" && (
          <>
            <button
              className="text-sm px-4 py-1.5 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
              onClick={() => onSendAction(createPlanResume(plan.planId))}
            >
              恢复执行
            </button>
            <button
              className="text-sm px-4 py-1.5 text-red-600 border border-red-300 rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
              onClick={() => onSendAction(createPlanCancel(plan.planId))}
            >
              取消计划
            </button>
            <span className="text-xs text-gray-400 ml-auto">
              你可以在下方继续与 Agent 对话
            </span>
          </>
        )}

        {(plan.status === "completed" || plan.status === "cancelled") && (
          <span className="text-xs text-gray-400">
            {plan.status === "completed" ? "计划已完成" : "计划已取消"}
          </span>
        )}
      </div>
    </div>
  );
}
