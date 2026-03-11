import { useEffect, useRef, useState, useCallback } from "react";
import { useChatStore } from "../stores/chatStore";
import MessageBubble from "./MessageBubble";
import { ArrowDown } from "lucide-react";

export default function MessageList() {
  const messages = useChatStore((s) => s.messages);
  const bottomRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);
  const [showScrollBtn, setShowScrollBtn] = useState(false);

  const scrollToBottom = useCallback(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
    setAutoScroll(true);
    setShowScrollBtn(false);
  }, []);

  useEffect(() => {
    if (autoScroll) {
      bottomRef.current?.scrollIntoView({ behavior: "smooth" });
    }
  }, [messages, autoScroll]);

  const handleScroll = useCallback(() => {
    const el = containerRef.current;
    if (!el) return;
    const distFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    const isNearBottom = distFromBottom < 100;
    setAutoScroll(isNearBottom);
    setShowScrollBtn(!isNearBottom);
  }, []);

  return (
    <div className="relative flex-1 overflow-hidden">
      <div
        ref={containerRef}
        onScroll={handleScroll}
        className="h-full overflow-y-auto px-4 md:px-6"
      >
        {messages.length === 0 && (
          <div className="flex flex-col items-center justify-center h-full text-slate-400 dark:text-slate-500">
            <div className="text-4xl mb-4">🤖</div>
            <p className="text-lg font-medium">JavaClaw</p>
            <p className="text-sm mt-1">输入消息开始对话</p>
          </div>
        )}
        {messages.map((msg) => (
          <MessageBubble key={msg.id} message={msg} />
        ))}
        <div ref={bottomRef} />
      </div>

      {showScrollBtn && (
        <button
          onClick={scrollToBottom}
          className="absolute bottom-4 left-1/2 -translate-x-1/2 bg-white dark:bg-slate-700 shadow-lg rounded-full p-2 hover:bg-slate-50 dark:hover:bg-slate-600 transition-colors"
        >
          <ArrowDown size={18} className="text-slate-600 dark:text-slate-300" />
        </button>
      )}
    </div>
  );
}
