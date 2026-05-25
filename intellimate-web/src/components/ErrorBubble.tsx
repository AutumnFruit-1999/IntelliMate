import { useState } from "react";
import { AlertTriangle, ChevronDown, ChevronRight } from "lucide-react";
import type { FriendlyError } from "../lib/errorMessages";

interface ErrorBubbleProps {
  error: FriendlyError;
}

export default function ErrorBubble({ error }: ErrorBubbleProps) {
  const [showDetails, setShowDetails] = useState(false);

  return (
    <div className="rounded-lg border border-red-200 dark:border-red-800/50 bg-red-50 dark:bg-red-900/20 p-3 space-y-2">
      <div className="flex items-start gap-2">
        <AlertTriangle size={16} className="text-red-500 flex-shrink-0 mt-0.5" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-medium text-red-700 dark:text-red-400">
            {error.title}
          </p>
          <p className="text-xs text-red-600/80 dark:text-red-400/70 mt-0.5">
            {error.suggestion}
          </p>
        </div>
      </div>

      <button
        type="button"
        onClick={() => setShowDetails(!showDetails)}
        className="flex items-center gap-1 text-[11px] text-red-500/60 dark:text-red-400/50 hover:text-red-500 dark:hover:text-red-400 transition-colors"
      >
        {showDetails ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
        查看技术详情
      </button>

      {showDetails && (
        <pre className="text-[11px] leading-relaxed whitespace-pre-wrap break-all bg-red-100/50 dark:bg-red-900/30 rounded px-2 py-1.5 text-red-600 dark:text-red-400 max-h-32 overflow-auto">
          {error.rawError}
        </pre>
      )}
    </div>
  );
}
