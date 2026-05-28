import { useChatStore } from "../stores/chatStore";
import { Loader2, Wrench, Brain, Sparkles } from "lucide-react";
import { useEffect, useState } from "react";

export default function ActivityStrip() {
  const activity = useChatStore((s) => s.activity);
  const [elapsed, setElapsed] = useState(0);

  useEffect(() => {
    if (!activity.startedAt || activity.phase === "idle") {
      setElapsed(0);
      return;
    }
    const interval = setInterval(() => {
      setElapsed(Math.floor((Date.now() - activity.startedAt!) / 1000));
    }, 1000);
    return () => clearInterval(interval);
  }, [activity.startedAt, activity.phase]);

  if (activity.phase === "idle") return null;

  const phaseConfig = {
    waiting: { icon: <Loader2 size={12} className="animate-spin" />, label: "准备中" },
    thinking: { icon: <Brain size={12} className="animate-pulse" />, label: "思考中" },
    streaming: { icon: <Sparkles size={12} className="animate-pulse" />, label: "回复中" },
    tool_calling: { icon: <Wrench size={12} className="animate-spin" />, label: "调用工具" },
    cancelled: { icon: <Loader2 size={12} />, label: "已取消" },
  };

  const config = phaseConfig[activity.phase as keyof typeof phaseConfig];
  if (!config) return null;

  return (
    <div className="relative inline-flex items-center gap-2 px-3 py-1.5 rounded-full mb-1 overflow-hidden">
      <div className="absolute inset-0 bg-gradient-to-r from-blue-50 via-slate-50 to-blue-50 dark:from-blue-950/30 dark:via-slate-800/40 dark:to-blue-950/30 border border-blue-100/60 dark:border-blue-800/30 rounded-full" />
      <div className="absolute inset-0 bg-gradient-to-r from-transparent via-blue-100/50 to-transparent dark:via-blue-500/10 rounded-full animate-[shimmer_2s_ease-in-out_infinite]" />
      <div className="relative text-blue-500 dark:text-blue-400">
        {config.icon}
      </div>
      <div className="relative flex items-center gap-1.5 text-[11px]">
        {activity.modelName && (
          <span className="font-medium text-slate-600 dark:text-slate-300">
            {activity.modelName}
          </span>
        )}
        <span className="text-blue-500/70 dark:text-blue-400/70">{config.label}</span>
        {activity.phase === "tool_calling" && activity.currentTool && (
          <span className="text-slate-500 dark:text-slate-400 font-mono text-[10px]">
            {activity.currentTool}
          </span>
        )}
        {activity.turn > 0 && activity.maxTurns > 0 && (
          <span className="text-slate-400 dark:text-slate-500">
            Turn {activity.turn}/{activity.maxTurns}
          </span>
        )}
        {elapsed > 0 && (
          <span className="text-slate-400 dark:text-slate-500">{elapsed}s</span>
        )}
      </div>
    </div>
  );
}
