import { useState, useRef, useCallback } from "react";
import { Send, Square } from "lucide-react";
import CommandPopup from "./CommandPopup";

interface ComposeAreaProps {
  onSend: (text: string) => void;
  onCancel?: () => void;
  disabled?: boolean;
  isWaiting?: boolean;
}

export default function ComposeArea({ onSend, onCancel, disabled, isWaiting }: ComposeAreaProps) {
  const [text, setText] = useState("");
  const [showCommands, setShowCommands] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const canSend = text.trim().length > 0 && !disabled && !isWaiting;

  const handleSubmit = useCallback(() => {
    const trimmed = text.trim();
    if (!trimmed || disabled || isWaiting) return;
    onSend(trimmed);
    setText("");
    setShowCommands(false);
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
    }
  }, [text, disabled, isWaiting, onSend]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (e.key === "Enter" && !e.shiftKey && !showCommands) {
        e.preventDefault();
        handleSubmit();
      }
    },
    [handleSubmit, showCommands],
  );

  const handleChange = useCallback(
    (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      const val = e.target.value;
      setText(val);

      if (val.startsWith("/") && !val.includes(" ")) {
        setShowCommands(true);
      } else {
        setShowCommands(false);
      }

      const el = e.target;
      el.style.height = "auto";
      el.style.height = Math.min(el.scrollHeight, 150) + "px";
    },
    [],
  );

  const handleCommandSelect = useCallback(
    (command: string) => {
      if (command === "/model") {
        setText(command + " ");
      } else {
        onSend(command);
        setText("");
      }
      setShowCommands(false);
      textareaRef.current?.focus();
    },
    [onSend],
  );

  return (
    <div className="border-t border-slate-200 dark:border-slate-700 p-4">
      <div className="relative max-w-3xl mx-auto">
        {showCommands && (
          <CommandPopup
            filter={text}
            onSelect={handleCommandSelect}
            onClose={() => setShowCommands(false)}
          />
        )}
        <div className="flex items-end gap-2 bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-2xl px-4 py-2 focus-within:border-blue-400 dark:focus-within:border-blue-500 transition-colors">
          <textarea
            ref={textareaRef}
            value={text}
            onChange={handleChange}
            onKeyDown={handleKeyDown}
            placeholder={isWaiting ? "等待回复中..." : disabled ? "连接已断开，输入消息将在重连后发送..." : "输入消息... (/ 查看命令)"}
            rows={1}
            disabled={isWaiting}
            className="flex-1 bg-transparent resize-none outline-none text-sm text-slate-800 dark:text-slate-100 placeholder-slate-400 dark:placeholder-slate-500 max-h-[150px] disabled:opacity-50"
          />
          {isWaiting ? (
            <button
              type="button"
              onClick={onCancel}
              className="flex-shrink-0 p-1.5 rounded-full bg-red-100 dark:bg-red-900/30 text-red-500 hover:bg-red-200 dark:hover:bg-red-900/50 transition-colors"
              title="取消"
            >
              <Square size={16} />
            </button>
          ) : (
            <button
              onClick={handleSubmit}
              disabled={!canSend}
              className="flex-shrink-0 p-1.5 rounded-full bg-blue-500 text-white disabled:opacity-30 disabled:cursor-not-allowed hover:bg-blue-600 transition-colors"
            >
              <Send size={16} />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
