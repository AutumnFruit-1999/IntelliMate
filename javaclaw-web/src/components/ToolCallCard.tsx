import { useState } from "react";
import type { ToolCallInfo } from "../stores/chatStore";
import {
  ChevronRight,
  ChevronDown,
  Terminal,
  FileText,
  FilePen,
  Search,
  Globe,
  Wrench,
  BookOpen,
  Loader2,
  CheckCircle2,
  XCircle,
} from "lucide-react";

const MAX_CONTENT = 2000;

export function getToolIcon(name: string, size = 14) {
  const iconProps = { size, className: "flex-shrink-0" };
  switch (name) {
    case "exec":
      return <Terminal {...iconProps} />;
    case "readFile":
      return <FileText {...iconProps} />;
    case "writeFile":
    case "editFile":
      return <FilePen {...iconProps} />;
    case "webSearch":
      return <Search {...iconProps} />;
    case "webFetch":
      return <Globe {...iconProps} />;
    case "getSkillContent":
      return <BookOpen {...iconProps} />;
    default:
      return <Wrench {...iconProps} />;
  }
}

export function StatusIndicator({ status }: { status: ToolCallInfo["status"] }) {
  switch (status) {
    case "calling":
      return <Loader2 size={14} className="flex-shrink-0 animate-spin text-blue-500" />;
    case "done":
      return <CheckCircle2 size={14} className="flex-shrink-0 text-emerald-500" />;
    case "error":
      return <XCircle size={14} className="flex-shrink-0 text-red-500" />;
  }
}

const statusBorderColor: Record<ToolCallInfo["status"], string> = {
  calling: "border-l-blue-400",
  done: "border-l-emerald-400",
  error: "border-l-red-400",
};

function DetailBlock({ label, content, isError }: { label: string; content: string; isError?: boolean }) {
  const [showAll, setShowAll] = useState(false);
  const truncated = content.length > MAX_CONTENT && !showAll;
  const displayed = truncated ? content.slice(0, MAX_CONTENT) + "..." : content;

  return (
    <div>
      <div className={`mb-1 text-xs font-medium ${isError ? "text-red-500 dark:text-red-400" : "text-gray-500 dark:text-gray-400"}`}>
        {label}
      </div>
      <pre className="max-h-60 overflow-auto rounded-md bg-gray-50 px-2.5 py-2 text-xs leading-relaxed whitespace-pre-wrap break-all text-gray-700 dark:bg-gray-800/60 dark:text-gray-300">
        {displayed}
      </pre>
      {truncated && (
        <button
          type="button"
          className="mt-1 text-xs text-blue-500 hover:text-blue-600 dark:text-blue-400"
          onClick={() => setShowAll(true)}
        >
          展开全部
        </button>
      )}
    </div>
  );
}

interface ToolCallCardProps {
  info: ToolCallInfo;
  compact?: boolean;
}

export default function ToolCallCard({ info, compact = false }: ToolCallCardProps) {
  const [expanded, setExpanded] = useState(false);
  const statusLabel =
    info.status === "calling" ? "调用中" : info.status === "done" ? "完成" : "失败";
  const hasDetail = !!(info.arguments || info.result !== undefined);

  const header = (
    <button
      type="button"
      className={`flex w-full items-center gap-2 text-left ${compact ? "px-3 py-2" : "px-3 py-2.5"} ${hasDetail ? "cursor-pointer" : "cursor-default"}`}
      onClick={() => hasDetail && setExpanded(!expanded)}
    >
      <StatusIndicator status={info.status} />
      <span className="text-gray-500 dark:text-gray-400">{getToolIcon(info.name)}</span>
      <span className="text-sm font-medium text-gray-800 dark:text-gray-200">
        {info.name}
      </span>
      <span className="text-xs text-gray-400 dark:text-gray-500">{statusLabel}</span>
      <span className="ml-auto" />
      {hasDetail && (
        expanded
          ? <ChevronDown size={14} className="text-gray-400 dark:text-gray-500" />
          : <ChevronRight size={14} className="text-gray-400 dark:text-gray-500" />
      )}
    </button>
  );

  const detail = expanded && (
    <div className={`space-y-2 border-t border-gray-100 px-3 pb-2.5 pt-2 dark:border-gray-800`}>
      {info.arguments && <DetailBlock label="参数" content={info.arguments} />}
      {info.result !== undefined && (
        <DetailBlock label={info.success ? "结果" : "错误"} content={info.result} isError={!info.success} />
      )}
    </div>
  );

  if (compact) {
    return (
      <div className="hover:bg-gray-50/50 dark:hover:bg-gray-800/30 transition-colors">
        {header}
        {detail}
      </div>
    );
  }

  return (
    <div
      className={`my-1.5 overflow-hidden rounded-lg border-l-[3px] border border-gray-100 bg-white shadow-sm hover:shadow transition-shadow dark:bg-gray-900 dark:border-gray-700/50 ${statusBorderColor[info.status]}`}
    >
      {header}
      {detail}
    </div>
  );
}
