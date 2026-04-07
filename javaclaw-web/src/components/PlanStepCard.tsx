import { useState } from "react";
import type { PlanStep, PlanStatus, StepToolCall } from "../stores/planStore";
import {
  ChevronDown,
  ChevronRight,
  Pencil,
  Trash2,
  SkipForward,
  Loader2,
  CheckCircle2,
  XCircle,
  MinusCircle,
  Circle,
  Wrench,
} from "lucide-react";

interface PlanStepCardProps {
  step: PlanStep;
  planStatus: PlanStatus;
  toolCalls?: StepToolCall[];
  onSkip?: () => void;
  onModify?: (title: string, description: string) => void;
  onRemove?: () => void;
}

function StepStatusIcon({ status }: { status: PlanStep["status"] }) {
  switch (status) {
    case "pending":
      return <Circle size={16} className="text-slate-300 dark:text-slate-600" />;
    case "in_progress":
      return (
        <Loader2 size={16} className="text-blue-500 animate-spin" />
      );
    case "completed":
      return <CheckCircle2 size={16} className="text-emerald-500" />;
    case "failed":
      return <XCircle size={16} className="text-red-500" />;
    case "skipped":
      return <MinusCircle size={16} className="text-slate-400" />;
  }
}



function StepToolsList({ calls }: { calls: StepToolCall[] }) {
  const [expanded, setExpanded] = useState(false);
  if (calls.length === 0) return null;
  return (
    <div className="mt-2 border-t border-slate-100 dark:border-slate-800 pt-2">
      <button
        type="button"
        className="flex w-full items-center gap-1 text-left text-[11px] text-slate-500 dark:text-slate-400 hover:text-blue-600 dark:hover:text-blue-400"
        onClick={() => setExpanded(!expanded)}
      >
        <Wrench size={12} className="flex-shrink-0" />
        <span>工具调用 ({calls.length})</span>
        {expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} className="ml-auto" />}
      </button>
      {expanded && (
        <div className="mt-1.5 space-y-1.5 max-h-48 overflow-y-auto">
          {calls.map((tc) => (
            <div
              key={tc.toolCallId}
              className="rounded-md bg-slate-50 dark:bg-slate-800/70 px-2 py-1.5 text-[10px]"
            >
              <div className="font-medium text-slate-700 dark:text-slate-200 flex items-center gap-1">
                {tc.name}
                <span className="text-slate-400 font-normal">
                  {tc.status === "calling" ? "· 执行中" : tc.status === "done" ? "· 完成" : "· 失败"}
                </span>
              </div>
              {tc.description && (
                <div className="text-slate-400 dark:text-slate-500 truncate mt-0.5">
                  {tc.description}
                </div>
              )}
              {tc.arguments ? (
                <pre className="mt-1 whitespace-pre-wrap break-all text-slate-500 dark:text-slate-400 max-h-16 overflow-auto leading-snug">
                  {tc.arguments.length > 400 ? tc.arguments.slice(0, 400) + "…" : tc.arguments}
                </pre>
              ) : null}
              {tc.result != null && tc.result !== "" ? (
                <pre
                  className={`mt-1 whitespace-pre-wrap break-all max-h-20 overflow-auto leading-snug ${
                    tc.success === false
                      ? "text-red-600 dark:text-red-400"
                      : "text-emerald-700 dark:text-emerald-400"
                  }`}
                >
                  {tc.result.length > 500 ? tc.result.slice(0, 500) + "…" : tc.result}
                </pre>
              ) : null}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export default function PlanStepCard({
  step,
  planStatus,
  toolCalls,
  onSkip,
  onModify,
  onRemove,
}: PlanStepCardProps) {
  const [editing, setEditing] = useState(false);
  const [editTitle, setEditTitle] = useState(step.title);
  const [editDesc, setEditDesc] = useState(step.description);

  const canEdit =
    step.status === "pending" &&
    (planStatus === "draft" ||
      planStatus === "paused" ||
      planStatus === "executing");
  const canSkip = step.status === "failed" && planStatus === "paused";
  const canRemove = step.status === "pending" && planStatus === "draft";

  const isActive = step.status === "in_progress";

  function handleSave() {
    onModify?.(editTitle, editDesc);
    setEditing(false);
  }

  const bgClass = isActive
    ? "bg-blue-50/50 dark:bg-blue-900/10 border-blue-200 dark:border-blue-800/40"
    : step.status === "failed"
      ? "bg-red-50/50 dark:bg-red-900/10 border-red-200 dark:border-red-800/40"
      : step.status === "completed"
        ? "border-emerald-200 dark:border-emerald-800/40"
        : "border-slate-150 dark:border-slate-800";

  return (
    <div
      className={`rounded-lg border transition-colors ${bgClass} overflow-hidden`}
    >
      <div className="flex items-start gap-2.5 px-3 py-2.5">
        <div className="mt-0.5 flex-shrink-0">
          <StepStatusIcon status={step.status} />
        </div>

        <div className="flex-1 min-w-0">
          {editing ? (
            <div className="flex flex-col gap-2">
              <input
                className="border border-slate-200 dark:border-slate-700 rounded-md px-2.5 py-1.5 text-sm w-full bg-white dark:bg-slate-800 outline-none focus:border-blue-400"
                value={editTitle}
                onChange={(e) => setEditTitle(e.target.value)}
                autoFocus
              />
              <textarea
                className="border border-slate-200 dark:border-slate-700 rounded-md px-2.5 py-1.5 text-sm w-full bg-white dark:bg-slate-800 outline-none focus:border-blue-400 resize-none"
                rows={2}
                value={editDesc}
                onChange={(e) => setEditDesc(e.target.value)}
              />
              <div className="flex gap-2">
                <button
                  className="text-xs px-3 py-1 bg-blue-500 text-white rounded-md hover:bg-blue-600 transition-colors"
                  onClick={handleSave}
                >
                  保存
                </button>
                <button
                  className="text-xs px-3 py-1 bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 rounded-md hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
                  onClick={() => setEditing(false)}
                >
                  取消
                </button>
              </div>
            </div>
          ) : (
            <>
              <div className="text-sm font-medium text-slate-800 dark:text-slate-100">
                {step.index + 1}. {step.title}
              </div>
              {step.description && (
                <div className="text-xs text-slate-500 dark:text-slate-400 mt-0.5 leading-relaxed">
                  {step.description}
                </div>
              )}
              {step.resultSummary && (
                <div
                  className={`text-xs mt-1 ${
                    step.status === "failed"
                      ? "text-red-600 dark:text-red-400"
                      : "text-emerald-600 dark:text-emerald-400"
                  }`}
                >
                  {step.status === "failed" ? "失败: " : "结果: "}
                  {step.resultSummary}
                </div>
              )}
              {toolCalls && toolCalls.length > 0 && (
                <StepToolsList calls={toolCalls} />
              )}
            </>
          )}
        </div>

        {!editing && (
          <div className="flex gap-0.5 shrink-0">
            {canEdit && (
              <button
                className="p-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                onClick={() => setEditing(true)}
                title="编辑"
              >
                <Pencil size={13} className="text-blue-500" />
              </button>
            )}
            {canRemove && onRemove && (
              <button
                className="p-1 rounded hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
                onClick={onRemove}
                title="删除"
              >
                <Trash2 size={13} className="text-red-500" />
              </button>
            )}
            {canSkip && (
              <button
                className="p-1 rounded hover:bg-orange-50 dark:hover:bg-orange-900/20 transition-colors"
                onClick={onSkip}
                title="跳过"
              >
                <SkipForward size={13} className="text-orange-500" />
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
