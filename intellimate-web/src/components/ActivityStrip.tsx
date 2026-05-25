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
    waiting: { icon: <Loader2 size={14} className="animate-spin" />, label: "准备中" },
    thinking: { icon: <Brain size={14} className="animate-pulse" />, label: "思考中" },
    streaming: { icon: <Sparkles size={14} className="animate-pulse" />, label: "回复中" },
    tool_calling: { icon: <Wrench size={14} className="animate-spin" />, label: "调用工具" },
    cancelled: { icon: <Loader2 size={14} />, label: "已取消" },
  };

  const config = phaseConfig[activity.phase as keyof typeof phaseConfig];
  if (!config) return null;

  const parts: string[] = [];
  if (activity.modelName) parts.push(activity.modelName);
  parts.push(config.label);
  if (activity.phase === "tool_calling" && activity.currentTool) {
    parts.push(activity.currentTool);
  }
  if (activity.turn > 0 && activity.maxTurns > 0) {
    parts.push(`Turn ${activity.turn}/${activity.maxTurns}`);
  }

  return (
    <div className="flex items-center gap-1.5 text-xs text-slate-400 dark:text-slate-500 py-1">
      {config.icon}
      <span>{parts.join(" · ")}</span>
      {elapsed > 0 && (
        <span className="text-slate-300 dark:text-slate-600">{elapsed}s</span>
      )}
    </div>
  );
}
