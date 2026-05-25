import { useEffect, useRef, useState, useCallback, useMemo } from "react";
import { useShallow } from "zustand/react/shallow";
import { useChatStore } from "../stores/chatStore";
import { useAgentStore } from "../stores/agentStore";
import MessageBubble from "./MessageBubble";
import { ArrowDown } from "lucide-react";

interface MessageListProps {
  onSend: (text: string, forcePlan?: boolean, regenerate?: boolean) => void;
}

export default function MessageList({ onSend }: MessageListProps) {
  const messages = useChatStore((s) => s.messages);
  const sentinelRef = useRef<HTMLDivElement>(null);
  const bottomRef = useRef<HTMLDivElement>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const { historyHasMore, loadingHistory, loadMoreHistory } = useChatStore(
    useShallow((s) => ({
      historyHasMore: s.historyHasMore,
      loadingHistory: s.loadingHistory,
      loadMoreHistory: s.loadMoreHistory,
    }))
  );
  const activeAgent = useAgentStore((s) => s.activeAgent);
  const [autoScroll, setAutoScroll] = useState(true);
  const [showScrollBtn, setShowScrollBtn] = useState(false);

  const lastAssistantIdx = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      const m = messages[i];
      if (m.role === "assistant" && !m.streaming) return i;
    }
    return -1;
  }, [messages]);

  const handleRegenerate = useCallback(() => {
    const msgs = useChatStore.getState().messages;
    const lastUserMsg = [...msgs].reverse().find((m) => m.role === "user");
    if (!lastUserMsg) return;
    useChatStore.getState().removeLastAssistantMessage();
    onSend(lastUserMsg.content, false, true);
  }, [onSend]);

  const lastAssistantWithToolsId = useMemo(() => {
    for (let i = messages.length - 1; i >= 0; i--) {
      const m = messages[i];
      if (m.role === "assistant" && m.toolCalls && m.toolCalls.length > 0) {
        return m.id;
      }
    }
    return null;
  }, [messages]);

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

  useEffect(() => {
    if (!sentinelRef.current || !historyHasMore) return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries[0].isIntersecting && !loadingHistory && activeAgent) {
          loadMoreHistory(activeAgent);
        }
      },
      { threshold: 0.1 }
    );
    observer.observe(sentinelRef.current);
    return () => observer.disconnect();
  }, [historyHasMore, loadingHistory, activeAgent, loadMoreHistory]);

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
        <div ref={sentinelRef} className="h-1" />
        {loadingHistory && (
          <div className="flex justify-center py-2">
            <div className="text-xs text-slate-400">加载更早的消息...</div>
          </div>
        )}
        {messages.length === 0 && (
          <div className="flex flex-col items-center justify-center h-full text-slate-400 dark:text-slate-500">
            <div className="text-4xl mb-4">🤖</div>
            <p className="text-lg font-medium">IntelliMate</p>
            <p className="text-sm mt-1">输入消息开始对话</p>
          </div>
        )}
        {messages.map((msg, idx) => (
          <MessageBubble
            key={msg.id}
            message={msg}
            isLastAssistantWithTools={msg.id === lastAssistantWithToolsId}
            isLastAssistant={idx === lastAssistantIdx}
            onRegenerate={idx === lastAssistantIdx ? handleRegenerate : undefined}
          />
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
