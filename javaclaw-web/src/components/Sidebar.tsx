import { useChatStore } from "../stores/chatStore";
import { useAgentStore } from "../stores/agentStore";
import ConnectionStatus from "./ConnectionStatus";
import AgentList from "./AgentList";
import { RotateCcw, Info, HelpCircle, X, Brain, User, BookOpen } from "lucide-react";
import type { ContextTab } from "./AgentConfigModal";

interface SidebarProps {
  onSend: (text: string) => void;
  open: boolean;
  onClose: () => void;
  onOpenConfig: (tab: ContextTab) => void;
  onCreateAgent: () => void;
  onSelectAgent: (name: string) => void;
}

export default function Sidebar({
  onSend,
  open,
  onClose,
  onOpenConfig,
  onCreateAgent,
  onSelectAgent,
}: SidebarProps) {
  const wsSessionId = useChatStore((s) => s.wsSessionId);
  const agents = useAgentStore((s) => s.agents);
  const activeAgent = useAgentStore((s) => s.activeAgent);
  const removeAgent = useAgentStore((s) => s.removeAgent);

  const commands = [
    { icon: <RotateCcw size={16} />, label: "重置会话", cmd: "/reset" },
    { icon: <Info size={16} />, label: "会话状态", cmd: "/status" },
    { icon: <HelpCircle size={16} />, label: "帮助", cmd: "/help" },
  ];

  const configItems: { icon: React.ReactNode; label: string; tab: ContextTab }[] = [
    { icon: <Brain size={16} />, label: "SOUL 性格设定", tab: "soul" },
    { icon: <User size={16} />, label: "USER 用户信息", tab: "user" },
    { icon: <BookOpen size={16} />, label: "AGENTS 工作规范", tab: "agents" },
  ];

  return (
    <>
      {open && (
        <div
          className="fixed inset-0 bg-black/30 z-20 md:hidden"
          onClick={onClose}
        />
      )}
      <aside
        className={`fixed md:static z-30 top-0 left-0 h-full w-60 bg-slate-50 dark:bg-slate-800 border-r border-slate-200 dark:border-slate-700 flex flex-col transition-transform duration-200 ${
          open ? "translate-x-0" : "-translate-x-full md:translate-x-0"
        }`}
      >
        <div className="flex items-center justify-between p-4 border-b border-slate-200 dark:border-slate-700">
          <h2 className="font-semibold text-sm text-slate-700 dark:text-slate-200">
            JavaClaw
          </h2>
          <button onClick={onClose} className="md:hidden p-1">
            <X size={18} className="text-slate-500" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-4 space-y-4">
          <AgentList
            agents={agents}
            activeAgent={activeAgent}
            onSelect={(name) => {
              onSelectAgent(name);
              onClose();
            }}
            onCreateClick={() => {
              onCreateAgent();
              onClose();
            }}
            onDelete={removeAgent}
          />

          <div>
            <p className="text-xs font-medium text-slate-500 dark:text-slate-400 uppercase tracking-wider mb-2">
              快捷命令
            </p>
            <div className="space-y-1">
              {commands.map((c) => (
                <button
                  key={c.cmd}
                  onClick={() => {
                    onSend(c.cmd);
                    onClose();
                  }}
                  className="w-full flex items-center gap-2 px-3 py-2 text-sm text-slate-600 dark:text-slate-300 rounded-lg hover:bg-slate-200/60 dark:hover:bg-slate-700 transition-colors"
                >
                  {c.icon}
                  {c.label}
                </button>
              ))}
            </div>
          </div>

          <div>
            <p className="text-xs font-medium text-slate-500 dark:text-slate-400 uppercase tracking-wider mb-2">
              智能体配置
            </p>
            <div className="space-y-1">
              {configItems.map((item) => (
                <button
                  key={item.tab}
                  onClick={() => {
                    onOpenConfig(item.tab);
                    onClose();
                  }}
                  className="w-full flex items-center gap-2 px-3 py-2 text-sm text-slate-600 dark:text-slate-300 rounded-lg hover:bg-slate-200/60 dark:hover:bg-slate-700 transition-colors"
                >
                  {item.icon}
                  {item.label}
                </button>
              ))}
            </div>
          </div>

          <div>
            <p className="text-xs font-medium text-slate-500 dark:text-slate-400 uppercase tracking-wider mb-2">
              连接信息
            </p>
            <div className="space-y-2 text-xs text-slate-500 dark:text-slate-400">
              <ConnectionStatus />
              {wsSessionId && (
                <p className="truncate" title={wsSessionId}>
                  Session: {wsSessionId.slice(0, 12)}...
                </p>
              )}
            </div>
          </div>
        </div>
      </aside>
    </>
  );
}
