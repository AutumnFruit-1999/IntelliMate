import { useChatStore } from "../stores/chatStore";

const stateConfig = {
  connecting: { label: "连接中...", dot: "bg-yellow-400 pulse-dot" },
  connected: { label: "已连接", dot: "bg-green-500" },
  disconnected: { label: "已断开", dot: "bg-red-500" },
  reconnecting: { label: "重连中...", dot: "bg-yellow-400 pulse-dot" },
} as const;

export default function ConnectionStatus() {
  const connectionState = useChatStore((s) => s.connectionState);
  const cfg = stateConfig[connectionState];

  return (
    <div className="flex items-center gap-2 text-xs text-slate-500 dark:text-slate-400">
      <span className={`inline-block w-2 h-2 rounded-full ${cfg.dot}`} />
      <span>{cfg.label}</span>
    </div>
  );
}
