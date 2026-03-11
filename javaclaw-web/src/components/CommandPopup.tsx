import { useState, useEffect, useCallback } from "react";

const COMMANDS = [
  { name: "/help", description: "显示命令列表" },
  { name: "/reset", description: "清空会话历史" },
  { name: "/status", description: "查看会话状态" },
  { name: "/model", description: "切换模型" },
];

interface CommandPopupProps {
  filter: string;
  onSelect: (command: string) => void;
  onClose: () => void;
}

export default function CommandPopup({ filter, onSelect, onClose }: CommandPopupProps) {
  const [selectedIndex, setSelectedIndex] = useState(0);

  const filtered = COMMANDS.filter((cmd) =>
    cmd.name.startsWith(filter.toLowerCase()),
  );

  useEffect(() => {
    setSelectedIndex(0);
  }, [filter]);

  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (e.key === "ArrowDown") {
        e.preventDefault();
        setSelectedIndex((i) => Math.min(i + 1, filtered.length - 1));
      } else if (e.key === "ArrowUp") {
        e.preventDefault();
        setSelectedIndex((i) => Math.max(i - 1, 0));
      } else if (e.key === "Enter" && filtered.length > 0) {
        e.preventDefault();
        onSelect(filtered[selectedIndex].name);
      } else if (e.key === "Escape") {
        onClose();
      }
    },
    [filtered, selectedIndex, onSelect, onClose],
  );

  useEffect(() => {
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [handleKeyDown]);

  if (filtered.length === 0) return null;

  return (
    <div className="absolute bottom-full left-0 right-0 mb-1 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg shadow-lg overflow-hidden z-10">
      {filtered.map((cmd, i) => (
        <button
          key={cmd.name}
          onClick={() => onSelect(cmd.name)}
          className={`w-full flex items-center gap-3 px-4 py-2.5 text-left text-sm transition-colors ${
            i === selectedIndex
              ? "bg-blue-50 dark:bg-slate-700"
              : "hover:bg-slate-50 dark:hover:bg-slate-700/50"
          }`}
        >
          <span className="font-mono font-medium text-blue-600 dark:text-blue-400">
            {cmd.name}
          </span>
          <span className="text-slate-500 dark:text-slate-400">
            {cmd.description}
          </span>
        </button>
      ))}
    </div>
  );
}
