import { useMemo, useState, useEffect, memo } from "react";
import { useShallow } from "zustand/react/shallow";
import type { ChatMessage, ToolCallInfo } from "../stores/chatStore";
import { usePlanStore } from "../stores/planStore";
import type { StepToolCall } from "../stores/planStore";
import StreamingText from "./StreamingText";
import ActivityStrip from "./ActivityStrip";
import ErrorBubble from "./ErrorBubble";
import ToolCallGroup from "./ToolCallGroup";
import WorkflowTimeline from "./workflow/WorkflowTimeline";
import {
  Bot, User, Wrench, ChevronDown, ChevronRight,
  Loader2, CheckCircle2, XCircle, Circle, MinusCircle,
} from "lucide-react";

interface MessageBubbleProps {
  message: ChatMessage;
  isLastAssistantWithTools?: boolean;
}

function TurnIndicator({ turn, maxTurns }: { turn: number; maxTurns: number }) {
  return (
    <div className="mb-1 flex items-center gap-1 text-xs text-gray-400">
      <span className="inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-blue-400" />
      Turn {turn}/{maxTurns}
    </div>
  );
}

export default memo(function MessageBubble({ message, isLastAssistantWithTools }: MessageBubbleProps) {
  const isUser = message.role === "user";
  const isSystem = message.role === "system";

  if (isSystem) {
    return (
      <div className="flex justify-center my-2">
        <div className="bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 text-xs px-3 py-1.5 rounded-full">
          {message.content}
        </div>
      </div>
    );
  }

  const hasToolCalls = !isUser && message.toolCalls && message.toolCalls.length > 0;

  const { plan, stepToolCalls, currentStepIndex } = usePlanStore(
    useShallow((s) => ({
      plan: s.plan,
      stepToolCalls: s.stepToolCalls,
      currentStepIndex: s.currentStepIndex,
    }))
  );
  const planWithSteps = !!(plan && plan.steps.length > 0);
  const planActive =
    plan &&
    plan.status !== "draft" &&
    plan.status !== "cancelled" &&
    plan.status !== "completed" &&
    plan.status !== "failed";
  const hasPlanTools = hasToolCalls && message.toolCalls!.some(
    (tc) => tc.name === "updatePlan" || tc.name === "writePlan",
  );

  const showLiveStepView =
    hasToolCalls &&
    planWithSteps &&
    planActive &&
    !!isLastAssistantWithTools;

  const hasSnapshot = !!(message.stepGroupSnapshot && message.stepGroupSnapshot.steps.length > 0);
  const showSnapshotStepView = hasToolCalls && !showLiveStepView && hasSnapshot;
  const showStepView = showLiveStepView || showSnapshotStepView;

  const toolCallGroups = useMemo(() => {
    if (!hasToolCalls) return [];
    if (showStepView) return [];
    const calls = hasPlanTools
      ? message.toolCalls!.filter(tc => tc.name !== "writePlan" && tc.name !== "updatePlan")
      : message.toolCalls!;
    if (calls.length === 0) return [];
    return groupByTurn(calls);
  }, [
    hasToolCalls,
    showStepView,
    hasPlanTools,
    message.toolCalls,
  ]);

  return (
    <div className={`flex gap-3 my-4 ${isUser ? "flex-row-reverse" : ""}`}>
      <div
        className={`flex-shrink-0 w-8 h-8 rounded-full flex items-center justify-center ${
          isUser
            ? "bg-blue-500 text-white"
            : "bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300"
        }`}
      >
        {isUser ? <User size={16} /> : <Bot size={16} />}
      </div>
      <div className="max-w-[75%]">
        {!isUser && message.streaming && <ActivityStrip />}

        {showLiveStepView && (
          <div className="mb-2">
            <StepGroupedTools
              steps={plan!.steps}
              stepToolCalls={stepToolCalls}
              currentStepIndex={currentStepIndex}
            />
          </div>
        )}

        {showSnapshotStepView && (
          <div className="mb-2">
            <StepGroupedTools
              steps={message.stepGroupSnapshot!.steps}
              stepToolCalls={message.stepGroupSnapshot!.stepToolCalls}
              currentStepIndex={null}
            />
          </div>
        )}

        {toolCallGroups.length > 0 && (
          <div className="mb-2">
            {toolCallGroups.map((group) => (
              <ToolCallGroup
                key={group.key}
                calls={group.calls}
                turn={group.turn}
              />
            ))}
          </div>
        )}

        {message.workflowEntries && message.workflowEntries.length > 0 && (
          <div className="mb-2">
            <WorkflowTimeline entries={message.workflowEntries} />
          </div>
        )}

        <div
          className={`rounded-2xl px-4 py-2.5 ${
            isUser
              ? "bg-blue-500 text-white"
              : "bg-slate-100 dark:bg-slate-800 text-slate-800 dark:text-slate-100"
          }`}
        >
          {isUser ? (
            <p className="text-sm whitespace-pre-wrap">{message.content}</p>
          ) : message.error ? (
            <ErrorBubble error={message.error} />
          ) : (
            <StreamingText
              content={message.content}
              streaming={message.streaming}
            />
          )}
        </div>

        {!isUser && message.totalTurns != null && message.totalTurns > 1 && !message.streaming && (
          <div className="mt-1 text-xs text-gray-400">
            共 {message.totalTurns} 轮推理
          </div>
        )}
      </div>
    </div>
  );
});

interface ToolCallGroupData {
  key: string;
  turn?: number;
  calls: ToolCallInfo[];
}

function groupByTurn(toolCalls: ToolCallInfo[]): ToolCallGroupData[] {
  const groups: ToolCallGroupData[] = [];
  let currentTurn: number | undefined;
  let currentCalls: ToolCallInfo[] = [];

  for (const tc of toolCalls) {
    if (tc.turn !== currentTurn && currentCalls.length > 0) {
      groups.push({
        key: `turn-${currentTurn ?? "none"}-${groups.length}`,
        turn: currentTurn,
        calls: currentCalls,
      });
      currentCalls = [];
    }
    currentTurn = tc.turn;
    currentCalls.push(tc);
  }

  if (currentCalls.length > 0) {
    groups.push({
      key: `turn-${currentTurn ?? "none"}-${groups.length}`,
      turn: currentTurn,
      calls: currentCalls,
    });
  }

  return groups;
}

function StepStatusBadge({ status }: { status: string }) {
  switch (status) {
    case "in_progress":
      return <Loader2 size={14} className="text-blue-500 animate-spin" />;
    case "completed":
      return <CheckCircle2 size={14} className="text-emerald-500" />;
    case "failed":
      return <XCircle size={14} className="text-red-500" />;
    case "skipped":
      return <MinusCircle size={14} className="text-slate-400" />;
    default:
      return <Circle size={14} className="text-slate-300" />;
  }
}

function StepGroupedTools({
  steps,
  stepToolCalls,
  currentStepIndex,
}: {
  steps: Array<{ index: number; title: string; status: string; resultSummary?: string }>;
  stepToolCalls: Record<number, StepToolCall[]>;
  currentStepIndex: number | null;
}) {
  const stepsWithTools = steps.filter(
    (s) => s.status !== "pending" || s.index === currentStepIndex,
  );

  if (stepsWithTools.length === 0) return null;

  return (
    <div className="flex flex-col gap-1 rounded-xl bg-slate-50 dark:bg-slate-800/50 p-2">
      {stepsWithTools.map((step) => (
        <StepToolSection
          key={step.index}
          step={step}
          tools={stepToolCalls[step.index] ?? []}
          isActive={step.index === currentStepIndex}
        />
      ))}
    </div>
  );
}

function StepToolSection({
  step,
  tools,
  isActive,
}: {
  step: { index: number; title: string; status: string; resultSummary?: string };
  tools: StepToolCall[];
  isActive: boolean;
}) {
  const [expanded, setExpanded] = useState(isActive);

  useEffect(() => {
    setExpanded(isActive);
  }, [isActive]);

  const hasContent = tools.length > 0 || step.resultSummary;

  return (
    <div className="rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 overflow-hidden">
      <button
        type="button"
        className="flex w-full items-center gap-2 px-3 py-2 text-left hover:bg-slate-50 dark:hover:bg-slate-800/30 transition-colors"
        onClick={() => hasContent && setExpanded(!expanded)}
      >
        <StepStatusBadge status={step.status} />
        <span className="text-xs font-medium text-slate-700 dark:text-slate-200 flex-1 truncate">
          {step.index}. {step.title}
        </span>
        {tools.length > 0 && (
          <span className="text-[10px] text-slate-400">
            {tools.length} 工具
          </span>
        )}
        {hasContent && (expanded ? (
          <ChevronDown size={12} className="text-slate-400 flex-shrink-0" />
        ) : (
          <ChevronRight size={12} className="text-slate-400 flex-shrink-0" />
        ))}
      </button>

      {expanded && tools.length > 0 && (
        <div className="border-t border-slate-100 dark:border-slate-800 max-h-60 overflow-y-auto divide-y divide-slate-100 dark:divide-slate-800">
          {tools.map((tc) => (
            <ToolCallDetail key={tc.toolCallId} tc={tc} />
          ))}
        </div>
      )}

      {expanded && step.resultSummary && (
        <div className={`border-t border-slate-100 dark:border-slate-800 px-3 py-1.5 text-[11px] ${
          step.status === "failed" ? "text-red-500" : "text-emerald-500"
        }`}>
          {step.resultSummary}
        </div>
      )}
    </div>
  );
}

function ToolCallDetail({ tc }: { tc: StepToolCall }) {
  const [open, setOpen] = useState(false);
  const hasDetail = !!(tc.arguments || tc.result);

  return (
    <div>
      <button
        type="button"
        className="flex w-full items-center gap-1.5 px-3 py-1 text-left hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors"
        onClick={() => hasDetail && setOpen(!open)}
      >
        {tc.status === "calling" ? (
          <Loader2 size={10} className="text-blue-500 animate-spin flex-shrink-0" />
        ) : tc.status === "done" ? (
          <CheckCircle2 size={10} className="text-emerald-500 flex-shrink-0" />
        ) : (
          <XCircle size={10} className="text-red-500 flex-shrink-0" />
        )}
        <div className="flex-1 min-w-0">
          <span className="text-[11px] text-slate-600 dark:text-slate-300 truncate block">
            {tc.name}
          </span>
          {tc.description && (
            <span className="text-[10px] text-slate-400 dark:text-slate-500 truncate block">
              {tc.description}
            </span>
          )}
        </div>
        {hasDetail && (
          open ? (
            <ChevronDown size={10} className="text-slate-400 flex-shrink-0" />
          ) : (
            <ChevronRight size={10} className="text-slate-400 flex-shrink-0" />
          )
        )}
      </button>
      {open && (
        <div className="px-3 pb-1.5 space-y-1">
          {tc.arguments && (
            <pre className="text-[10px] leading-relaxed whitespace-pre-wrap break-all max-h-24 overflow-auto rounded bg-slate-50 dark:bg-slate-800/60 px-2 py-1 text-slate-500 dark:text-slate-400">
              {tc.arguments.length > 300 ? tc.arguments.slice(0, 300) + "..." : tc.arguments}
            </pre>
          )}
          {tc.result && (
            <pre className={`text-[10px] leading-relaxed whitespace-pre-wrap break-all max-h-32 overflow-auto rounded px-2 py-1 ${
              tc.success === false
                ? "bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400"
                : "bg-emerald-50 dark:bg-emerald-900/20 text-emerald-700 dark:text-emerald-400"
            }`}>
              {tc.result.length > 500 ? tc.result.slice(0, 500) + "..." : tc.result}
            </pre>
          )}
        </div>
      )}
    </div>
  );
}
