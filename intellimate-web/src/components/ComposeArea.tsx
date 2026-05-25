import { useState, useRef, useCallback } from "react";
import { Send, Square, Clock, X } from "lucide-react";
import { useChatStore } from "../stores/chatStore";
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
  const queuedMessage = useChatStore((s) => s.queuedMessage);
  const setQueuedMessage = useChatStore((s) => s.setQueuedMessage);

  const canSend = text.trim().length > 0 && !disabled;

  const handleSubmit = useCallback(() => {
    const trimmed = text.trim();
    if (!trimmed || disabled) return;
    if (isWaiting) {
      setQueuedMessage(trimmed);
    } else {
      onSend(trimmed);
    }
    setText("");
    setShowCommands(false);
    if (textareaRef.current) {
      textareaRef.current.style.height = "auto";
    }
  }, [text, disabled, isWaiting, onSend, setQueuedMessage]);

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
        {queuedMessage && (
          <div className="flex items-center gap-2 px-4 py-1.5 text-xs text-amber-600 dark:text-amber-400 bg-amber-50 dark:bg-amber-900/20 rounded-t-2xl border border-b-0 border-slate-200 dark:border-slate-700">
            <Clock size={12} />
            <span className="flex-1 truncate">排队中：{queuedMessage}</span>
            <button
              type="button"
              onClick={() => setQueuedMessage(null)}
              className="text-slate-400 hover:text-red-500"
            >
              <X size={12} />
            </button>
          </div>
        )}
        <div
          className={`flex items-end gap-2 bg-slate-50 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 ${queuedMessage ? "rounded-b-2xl" : "rounded-2xl"} px-4 py-2 focus-within:border-blue-400 dark:focus-within:border-blue-500 transition-colors`}
        >
          <textarea
            ref={textareaRef}
            value={text}
            onChange={handleChange}
            onKeyDown={handleKeyDown}
            placeholder={
              isWaiting
                ? "输入消息...（发送后将排队等待）"
                : disabled
                  ? "连接已断开，输入消息将在重连后发送..."
                  : "输入消息... (/ 查看命令)"
            }
            rows={1}
            className="flex-1 bg-transparent resize-none outline-none text-sm text-slate-800 dark:text-slate-100 placeholder-slate-400 dark:placeholder-slate-500 max-h-[150px]"
          />
          {isWaiting && !text.trim() ? (
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
              type="button"
              onClick={handleSubmit}
              disabled={!canSend}
              className={`flex-shrink-0 p-1.5 rounded-full text-white disabled:opacity-30 disabled:cursor-not-allowed transition-colors ${isWaiting ? "bg-amber-500 hover:bg-amber-600" : "bg-blue-500 hover:bg-blue-600"}`}
              title={isWaiting ? "排队发送" : "发送"}
            >
              {isWaiting ? <Clock size={16} /> : <Send size={16} />}
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
