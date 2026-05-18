import type { HandoffInfo } from "./WorkflowTimeline";

interface HandoffIndicatorProps {
  info: HandoffInfo;
}

export default function HandoffIndicator({ info }: HandoffIndicatorProps) {
  return (
    <div className="flex items-center gap-2 my-2 p-2 bg-amber-50 border border-amber-200 rounded-md">
      <span className="text-amber-600 font-semibold text-sm">↗</span>
      <div className="text-sm">
        <span className="font-mono text-amber-700">{info.fromAgent}</span>
        <span className="text-gray-500 mx-1">→</span>
        <span className="font-mono text-amber-700">{info.toAgent}</span>
      </div>
      {info.reason && (
        <span className="text-xs text-gray-500 ml-2 truncate max-w-[300px]">
          {info.reason}
        </span>
      )}
    </div>
  );
}
