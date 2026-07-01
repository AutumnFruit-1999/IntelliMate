import { useState, useMemo } from "react";
import type { ChatMessage } from "../stores/chatStore";
import type { PlanData, PlanStatus, PlanStepData, PlanStepStatus } from "../stores/planStore";
import type { RequestFrame } from "../lib/protocol";
import {
  createPlanApprove,
  createPlanPause,
  createPlanResume,
  createPlanCancel,
} from "../lib/protocol";
import {
  Bot,
  ClipboardList,
  Loader2,
  CheckCircle2,
  XCircle,
  Circle,
  CircleSlash,
  ChevronDown,
  ChevronRight,
} from "lucide-react";

interface PlanMessageProps {
  message: ChatMessage;
  onSendAction: (request: RequestFrame) => void;
}

const STATUS_LABELS: Record<PlanStatus, string> = {
  draft: "等待审批",
  approved: "已审批",
  executing: "执行中",
  paused: "已暂停",
  completed: "已完成",
  failed: "执行失败",
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

function resolveMessageId(message: ChatMessage): number | null {
  if (message.messageId != null) return message.messageId;
  if (typeof message.id === "number") return message.id;
  if (typeof message.id === "string" && message.id.startsWith("hist-")) {
    const parsed = parseInt(message.id.replace("hist-", ""), 10);
    return Number.isNaN(parsed) ? null : parsed;
  }
  const asNum = Number(message.id);
  return Number.isNaN(asNum) ? null : asNum;
}

function extractPlanData(message: ChatMessage): PlanData | null {
  const metadata = message.metadata;
  if (!metadata || metadata.type !== "plan") return null;
  const plan = metadata.plan as PlanData | undefined;
  if (!plan || !Array.isArray(plan.steps)) return null;
  return plan;
}

function StepStatusIcon({ status }: { status: PlanStepStatus }) {
  switch (status) {
    case "in_progress":
      return <Loader2 size={16} className="text-blue-500 animate-spin flex-shrink-0" />;
    case "completed":
      return <CheckCircle2 size={16} className="text-emerald-500 flex-shrink-0" />;
    case "failed":
      return <XCircle size={16} className="text-red-500 flex-shrink-0" />;
    case "skipped":
      return <CircleSlash size={16} className="text-slate-400 flex-shrink-0" />;
    default:
      return <Circle size={16} className="text-slate-300 dark:text-slate-600 flex-shrink-0" />;
  }
}

function PlanStepRow({
  step,
  planStatus,
  defaultExpanded,
}: {
  step: PlanStepData;
  planStatus: PlanStatus;
  defaultExpanded: boolean;
}) {
  const [expanded, setExpanded] = useState(defaultExpanded);
  const isActive = step.status === "in_progress";
  const hasDetails = !!(step.description || step.verification || step.resultSummary);
  const terminalPlan = planStatus === "completed" || planStatus === "cancelled";

  return (
    <div
      className={`rounded-lg border transition-colors ${
        isActive
          ? "border-blue-300/60 dark:border-blue-700/50 bg-blue-50/50 dark:bg-blue-900/10 animate-pulse"
          : "border-slate-200/80 dark:border-slate-700/60 bg-white/60 dark:bg-slate-900/40"
      }`}
    >
      <button
        type="button"
        className="flex w-full items-start gap-2.5 px-3 py-2.5 text-left"
        onClick={() => hasDetails && setExpanded((v) => !v)}
        disabled={!hasDetails}
      >
        <div className="mt-0.5">
          <StepStatusIcon status={step.status} />
        </div>
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-1.5">
            <span className="text-sm font-medium text-slate-800 dark:text-slate-100">
              {step.index + 1}. {step.title}
            </span>
            {hasDetails && (
              terminalPlan ? (
                expanded ? (
                  <ChevronDown size={14} className="text-slate-400 flex-shrink-0" />
                ) : (
                  <ChevronRight size={14} className="text-slate-400 flex-shrink-0" />
                )
              ) : null
            )}
          </div>
          {(defaultExpanded || expanded) && step.description && (
            <p className="mt-1 text-xs text-slate-500 dark:text-slate-400 leading-relaxed whitespace-pre-wrap">
              {step.description}
            </p>
          )}
          {(defaultExpanded || expanded) && step.verification && (
            <p className="mt-1 text-[11px] italic text-slate-400 dark:text-slate-500 leading-relaxed">
              验证：{step.verification}
            </p>
          )}
          {step.resultSummary && (
            <p
              className={`mt-1 text-[11px] leading-relaxed ${
                step.status === "failed"
                  ? "text-red-500 dark:text-red-400"
                  : "text-emerald-600 dark:text-emerald-400"
              }`}
            >
              {step.resultSummary}
            </p>
          )}
        </div>
      </button>
    </div>
  );
}

export default function PlanMessage({ message, onSendAction }: PlanMessageProps) {
  const plan = extractPlanData(message);
  const messageId = resolveMessageId(message);

  const completedCount = useMemo(
    () => plan?.steps.filter((s) => s.status === "completed").length ?? 0,
    [plan],
  );

  if (!plan) return null;

  const { status, steps, completionSummary } = plan;
  const defaultExpanded = status === "draft" || status === "executing" || status === "approved" || status === "paused";
  const showTerminalBadge = status === "completed" || status === "cancelled" || status === "failed";

  const sendWithId = (factory: (id: number) => RequestFrame) => {
    if (messageId == null) return;
    onSendAction(factory(messageId));
  };

  return (
    <div className="flex gap-2.5 my-3">
      <div className="flex-shrink-0 w-7 h-7 rounded-lg flex items-center justify-center shadow-sm mt-5 bg-gradient-to-br from-indigo-500 to-violet-600">
        <Bot size={14} className="text-white" />
      </div>
      <div className="max-w-[85%] min-w-0 flex-1">
        <div className="rounded-2xl rounded-bl-md border border-indigo-200/70 dark:border-indigo-800/40 bg-gradient-to-br from-slate-50 to-indigo-50/40 dark:from-slate-800/90 dark:to-indigo-950/30 px-4 py-3 shadow-sm">
          <div className="flex items-center gap-2 mb-3">
            <ClipboardList size={18} className="text-indigo-500 dark:text-indigo-400 flex-shrink-0" />
            <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-100 truncate">
              {message.content || "执行计划"}
            </h3>
          </div>

          {steps.length > 0 && (
            <p className="text-xs text-slate-500 dark:text-slate-400 mb-2.5">
              {completedCount}/{steps.length} 步已完成
            </p>
          )}

          <div className="flex flex-col gap-2 mb-3">
            {steps.map((step) => (
              <PlanStepRow
                key={step.index}
                step={step}
                planStatus={status}
                defaultExpanded={defaultExpanded}
              />
            ))}
          </div>

          {completionSummary && status === "completed" && (
            <p className="text-xs text-emerald-600 dark:text-emerald-400 mb-3 leading-relaxed border-t border-slate-200/60 dark:border-slate-700/50 pt-2.5">
              {completionSummary}
            </p>
          )}

          {status === "draft" && (
            <div className="flex items-center gap-2 pt-1">
              <button
                type="button"
                onClick={() => sendWithId((id) => createPlanApprove(id, true))}
                className="px-3.5 py-1.5 text-xs font-medium rounded-lg bg-blue-600 hover:bg-blue-700 text-white transition-colors"
              >
                执行
              </button>
              <button
                type="button"
                onClick={() => sendWithId((id) => createPlanApprove(id, false))}
                className="px-3.5 py-1.5 text-xs font-medium rounded-lg border border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
              >
                拒绝
              </button>
            </div>
          )}

          {status === "executing" && (
            <div className="flex items-center gap-2 pt-1">
              <button
                type="button"
                onClick={() => sendWithId(createPlanPause)}
                className="px-3.5 py-1.5 text-xs font-medium rounded-lg border border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
              >
                暂停
              </button>
              <button
                type="button"
                onClick={() => sendWithId(createPlanCancel)}
                className="px-3.5 py-1.5 text-xs font-medium rounded-lg border border-red-300 dark:border-red-800 text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
              >
                取消
              </button>
            </div>
          )}

          {status === "paused" && (
            <div className="flex items-center gap-2 pt-1">
              <button
                type="button"
                onClick={() => sendWithId(createPlanResume)}
                className="px-3.5 py-1.5 text-xs font-medium rounded-lg bg-blue-600 hover:bg-blue-700 text-white transition-colors"
              >
                继续
              </button>
              <button
                type="button"
                onClick={() => sendWithId(createPlanCancel)}
                className="px-3.5 py-1.5 text-xs font-medium rounded-lg border border-red-300 dark:border-red-800 text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
              >
                取消
              </button>
            </div>
          )}

          {showTerminalBadge && (
            <div className="pt-1">
              <span
                className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium ${
                  STATUS_BADGE[status] ?? STATUS_BADGE.cancelled
                }`}
              >
                {status === "completed" && <CheckCircle2 size={12} />}
                {status === "cancelled" && <XCircle size={12} />}
                {status === "failed" && <XCircle size={12} />}
                {STATUS_LABELS[status]}
              </span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
