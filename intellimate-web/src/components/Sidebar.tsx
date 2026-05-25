import { useNavigate, useLocation } from "react-router-dom";
import { useChatStore } from "../stores/chatStore";
import { useAgentStore } from "../stores/agentStore";
import ConnectionStatus from "./ConnectionStatus";
import AgentList from "./AgentList";
import { X, Bot, Settings, Sparkles, Cpu, ClipboardList, Brain, Timer } from "lucide-react";

interface SidebarProps {
  open: boolean;
  onClose: () => void;
  onCreateAgent: () => void;
  onSelectAgent: (name: string) => void;
}

export default function Sidebar({
  open,
  onClose,
  onCreateAgent,
  onSelectAgent,
}: SidebarProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const wsSessionId = useChatStore((s) => s.wsSessionId);
  const agents = useAgentStore((s) => s.agents);
  const activeAgent = useAgentStore((s) => s.activeAgent);
  const removeAgent = useAgentStore((s) => s.removeAgent);

  const navButtonClass = (path: string) =>
    `w-full flex items-center gap-2 px-3 py-2 text-sm text-slate-600 dark:text-slate-300 rounded-lg hover:bg-slate-200/60 dark:hover:bg-slate-700 transition-colors ${
      location.pathname === path ? "bg-slate-200/80 dark:bg-slate-700" : ""
    }`;

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
            IntelliMate
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

          <button
            onClick={() => {
              navigate("/history");
              onClose();
            }}
            className={`w-full flex items-center gap-2 px-3 py-2 text-sm rounded-lg transition-colors ${
              location.pathname.startsWith("/history")
                ? "bg-slate-200/80 dark:bg-slate-700 text-slate-800 dark:text-slate-100"
                : "text-slate-600 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800"
            }`}
          >
            <ClipboardList size={16} />
            历史
          </button>

          <div>
            <p className="text-xs font-medium text-slate-500 dark:text-slate-400 uppercase tracking-wider mb-2">
              管理
            </p>
            <div className="space-y-1">
              <button
                onClick={() => {
                  navigate("/agents");
                  onClose();
                }}
                className={navButtonClass("/agents")}
              >
                <Bot size={16} />
                Agent 配置
              </button>
              <button
                onClick={() => {
                  navigate("/tools");
                  onClose();
                }}
                className={navButtonClass("/tools")}
              >
                <Settings size={16} />
                工具管理
              </button>
              <button
                onClick={() => {
                  navigate("/skills");
                  onClose();
                }}
                className={navButtonClass("/skills")}
              >
                <Sparkles size={16} />
                Skills 管理
              </button>
              <button
                onClick={() => {
                  navigate("/models");
                  onClose();
                }}
                className={navButtonClass("/models")}
              >
                <Cpu size={16} />
                模型管理
              </button>
              <button
                onClick={() => {
                  navigate("/memory");
                  onClose();
                }}
                className={navButtonClass("/memory")}
              >
                <Brain size={16} />
                记忆观测
              </button>
              <button
                onClick={() => {
                  navigate("/scheduler");
                  onClose();
                }}
                className={navButtonClass("/scheduler")}
              >
                <Timer size={16} />
                调度中心
              </button>
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
