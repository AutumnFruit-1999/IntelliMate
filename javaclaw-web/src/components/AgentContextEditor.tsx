import { useCallback, useRef, type KeyboardEvent } from "react";

interface AgentContextEditorProps {
  value: string;
  onChange: (value: string) => void;
  placeholder: string;
  maxLength?: number;
}

const DEFAULT_MAX = 20_000;

export default function AgentContextEditor({
  value,
  onChange,
  placeholder,
  maxLength = DEFAULT_MAX,
}: AgentContextEditorProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      const text = e.target.value;
      if (text.length <= maxLength) {
        onChange(text);
      }
    },
    [onChange, maxLength],
  );

  const handleKeyDown = useCallback((e: KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === "Tab") {
      e.preventDefault();
      const ta = textareaRef.current;
      if (!ta) return;
      const start = ta.selectionStart;
      const end = ta.selectionEnd;
      const val = ta.value;
      const newVal = val.substring(0, start) + "  " + val.substring(end);
      onChange(newVal);
      requestAnimationFrame(() => {
        ta.selectionStart = ta.selectionEnd = start + 2;
      });
    }
  }, [onChange]);

  const len = value.length;
  const overLimit = len >= maxLength;

  return (
    <div className="flex flex-col flex-1 min-h-0">
      <textarea
        ref={textareaRef}
        value={value}
        onChange={handleChange}
        onKeyDown={handleKeyDown}
        placeholder={placeholder}
        spellCheck={false}
        className="flex-1 min-h-[300px] w-full resize-none rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 p-4 font-mono text-sm text-slate-800 dark:text-slate-200 placeholder:text-slate-400 dark:placeholder:text-slate-500 focus:outline-none focus:ring-2 focus:ring-blue-500/40"
      />
      <div className="flex justify-end mt-1.5 px-1">
        <span
          className={`text-xs tabular-nums ${
            overLimit
              ? "text-red-500 font-semibold"
              : "text-slate-400 dark:text-slate-500"
          }`}
        >
          {len.toLocaleString()} / {maxLength.toLocaleString()}
        </span>
      </div>
    </div>
  );
}
