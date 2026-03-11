import { Sun, Moon, Menu, Bot } from "lucide-react";
import ConnectionStatus from "./ConnectionStatus";

interface TopBarProps {
  darkMode: boolean;
  onToggleDark: () => void;
  onMenuClick: () => void;
  agentName?: string | null;
}

export default function TopBar({ darkMode, onToggleDark, onMenuClick, agentName }: TopBarProps) {
  return (
    <header className="flex items-center justify-between px-4 py-3 border-b border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900">
      <div className="flex items-center gap-3">
        <button
          onClick={onMenuClick}
          className="md:hidden p-1 text-slate-500 hover:text-slate-700 dark:hover:text-slate-300"
        >
          <Menu size={20} />
        </button>
        <h1 className="text-lg font-bold bg-gradient-to-r from-blue-600 to-cyan-500 bg-clip-text text-transparent">
          JavaClaw
        </h1>
        {agentName && (
          <div className="hidden sm:flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-slate-100 dark:bg-slate-800 text-xs text-slate-600 dark:text-slate-300">
            <Bot size={12} />
            {agentName}
          </div>
        )}
      </div>

      <div className="flex items-center gap-4">
        <ConnectionStatus />
        <button
          onClick={onToggleDark}
          className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          title={darkMode ? "切换亮色" : "切换暗色"}
        >
          {darkMode ? (
            <Sun size={18} className="text-yellow-400" />
          ) : (
            <Moon size={18} className="text-slate-500" />
          )}
        </button>
      </div>
    </header>
  );
}
