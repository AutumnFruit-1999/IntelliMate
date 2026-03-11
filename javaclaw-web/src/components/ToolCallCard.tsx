import { useState } from "react";
import type { ToolCallInfo } from "../stores/chatStore";

const MAX_PREVIEW = 300;

function StatusIcon({ status }: { status: ToolCallInfo["status"] }) {
  switch (status) {
    case "calling":
      return (
        <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-blue-400 border-t-transparent" />
      );
    case "done":
      return (
        <svg className="h-4 w-4 text-green-500" viewBox="0 0 20 20" fill="currentColor">
          <path fillRule="evenodd" d="M16.707 5.293a1 1 0 010 1.414l-8 8a1 1 0 01-1.414 0l-4-4a1 1 0 011.414-1.414L8 12.586l7.293-7.293a1 1 0 011.414 0z" clipRule="evenodd" />
        </svg>
      );
    case "error":
      return (
        <svg className="h-4 w-4 text-red-500" viewBox="0 0 20 20" fill="currentColor">
          <path fillRule="evenodd" d="M4.293 4.293a1 1 0 011.414 0L10 8.586l4.293-4.293a1 1 0 111.414 1.414L11.414 10l4.293 4.293a1 1 0 01-1.414 1.414L10 11.414l-4.293 4.293a1 1 0 01-1.414-1.414L8.586 10 4.293 5.707a1 1 0 010-1.414z" clipRule="evenodd" />
        </svg>
      );
  }
}

function Collapsible({
  label,
  content,
}: {
  label: string;
  content: string;
}) {
  const [open, setOpen] = useState(false);
  const truncated = content.length > MAX_PREVIEW && !open;

  return (
    <div className="mt-1">
      <button
        type="button"
        className="text-xs font-medium text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-200"
        onClick={() => setOpen(!open)}
      >
        {open ? "▼" : "▶"} {label}
      </button>
      {open && (
        <pre className="mt-1 max-h-48 overflow-auto rounded bg-gray-100 p-2 text-xs whitespace-pre-wrap break-all dark:bg-gray-800">
          {truncated ? content.slice(0, MAX_PREVIEW) + "…" : content}
          {truncated && (
            <button
              type="button"
              className="ml-1 text-blue-500 underline"
              onClick={() => setOpen(true)}
            >
              展开全部
            </button>
          )}
        </pre>
      )}
    </div>
  );
}

export default function ToolCallCard({ info }: { info: ToolCallInfo }) {
  const statusLabel =
    info.status === "calling"
      ? "调用中"
      : info.status === "done"
        ? "完成"
        : "失败";

  return (
    <div className="my-1.5 rounded-lg border border-gray-200 bg-white p-3 shadow-sm dark:border-gray-700 dark:bg-gray-900">
      <div className="flex items-center gap-2">
        <StatusIcon status={info.status} />
        <span className="text-sm font-semibold text-gray-800 dark:text-gray-200">
          {info.name}
        </span>
        <span className="text-xs text-gray-400">{statusLabel}</span>
      </div>

      {info.arguments && (
        <Collapsible label="参数" content={info.arguments} />
      )}

      {info.result !== undefined && (
        <Collapsible
          label={info.success ? "结果" : "错误"}
          content={info.result}
        />
      )}
    </div>
  );
}
