import { useEffect, useRef } from "react";
import { useChatStore } from "../stores/chatStore";

export default function ConnectionStatus() {
  const connectionState = useChatStore((s) => s.connectionState);
  const attempt = useChatStore((s) => s.reconnectAttempt);
  const countdown = useChatStore((s) => s.reconnectCountdown);
  const tickCountdown = useChatStore((s) => s.tickReconnectCountdown);

  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    if (connectionState === "reconnecting" && countdown > 0) {
      timerRef.current = setInterval(() => {
        tickCountdown();
      }, 1000);
      return () => {
        if (timerRef.current) clearInterval(timerRef.current);
      };
    }
    if (timerRef.current) {
      clearInterval(timerRef.current);
      timerRef.current = null;
    }
  }, [connectionState, countdown > 0, tickCountdown]);

  const cfg = getConfig(connectionState, attempt, countdown);

  return (
    <div className="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
      <span className={`inline-block w-2 h-2 rounded-full ${cfg.dot}`} />
      <span>{cfg.label}</span>
    </div>
  );
}

function getConfig(
  state: string,
  attempt: number,
  countdown: number,
): { label: string; dot: string } {
  switch (state) {
    case "connecting":
      return { label: "连接中...", dot: "bg-yellow-400 pulse-dot" };
    case "connected":
      return { label: "已连接", dot: "bg-green-500" };
    case "reconnecting":
      if (countdown > 0) {
        return {
          label: `重连中... 第 ${attempt} 次，${countdown}s 后重试`,
          dot: "bg-yellow-400 pulse-dot",
        };
      }
      return {
        label: `重连中... 第 ${attempt} 次`,
        dot: "bg-yellow-400 pulse-dot",
      };
    case "disconnected":
      return { label: "已断开", dot: "bg-red-500" };
    default:
      return { label: state, dot: "bg-slate-400" };
  }
}
