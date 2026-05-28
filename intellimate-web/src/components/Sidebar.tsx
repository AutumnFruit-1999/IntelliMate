import { useState, useEffect, useRef } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import { useChatStore } from "../stores/chatStore";
import { useAgentStore } from "../stores/agentStore";
import ConnectionStatus from "./ConnectionStatus";
import { X, Bot, Settings, Sparkles, Cpu, Brain, Timer, ChevronDown, ChevronRight, History, Plus, Trash2, Activity } from "lucide-react";

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

  const [agentDropdownOpen, setAgentDropdownOpen] = useState(false);
  const agentDropdownRef = useRef<HTMLDivElement>(null);

  const managementPaths = ["/agents", "/tools", "/skills", "/models"];
  const isInManagement = managementPaths.some((p) => location.pathname === p);
  const [managementExpanded, setManagementExpanded] = useState(true);

  useEffect(() => {
    if (isInManagement && !managementExpanded) {
      setManagementExpanded(true);
    }
  }, [isInManagement]);

  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (agentDropdownRef.current && !agentDropdownRef.current.contains(e.target as Node)) {
        setAgentDropdownOpen(false);
      }
    }
    if (agentDropdownOpen) {
      document.addEventListener("mousedown", handleClickOutside);
      return () => document.removeEventListener("mousedown", handleClickOutside);
    }
  }, [agentDropdownOpen]);

  const navItemClass = (path: string) => {
    const isActive = location.pathname === path;
    return `w-full flex items-center gap-2.5 px-3 py-[7px] text-[13px] rounded-md transition-colors outline-none ${
      isActive
        ? "bg-blue-50 dark:bg-blue-900/20 text-blue-600 dark:text-blue-400 font-medium"
        : "text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700/60"
    }`;
  };

  const currentAgentObj = agents.find((a) => a.name === activeAgent);

  return (
    <>
      {open && (
        <div
          className="fixed inset-0 bg-black/30 z-20 md:hidden"
          onClick={onClose}
        />
      )}
      <aside
        className={`fixed md:static z-30 top-0 left-0 h-full w-60 bg-[#f8fafc] dark:bg-slate-800 border-r border-slate-200 dark:border-slate-700 flex flex-col transition-transform duration-200 ${
          open ? "translate-x-0" : "-translate-x-full md:translate-x-0"
        }`}
      >
        {/* Header */}
        <div className="flex items-center justify-between h-[52px] px-5 border-b border-slate-200/80 dark:border-slate-700">
          <h2 className="font-semibold text-[15px] text-slate-800 dark:text-slate-100 tracking-tight">
            IntelliMate
          </h2>
          <button onClick={onClose} className="md:hidden p-1 outline-none rounded hover:bg-slate-200 dark:hover:bg-slate-700">
            <X size={16} className="text-slate-500" />
          </button>
        </div>

        {/* Main Content */}
        <div className="flex-1 overflow-y-auto py-3 px-3">
          {/* Current Agent Selector */}
          <div className="relative" ref={agentDropdownRef}>
            <div className="flex items-center gap-0 rounded-lg bg-blue-50/80 dark:bg-blue-900/15 border border-blue-200/60 dark:border-blue-800/40">
              <button
                onClick={() => {
                  navigate("/chat");
                  onClose();
                }}
                className="flex-1 flex items-center gap-2.5 px-3 py-2.5 rounded-l-lg hover:bg-blue-100/60 dark:hover:bg-blue-900/30 transition-colors outline-none min-w-0"
              >
                <div className="p-1.5 rounded-md bg-blue-100 dark:bg-blue-800/30">
                  <Bot size={14} className="text-blue-500" />
                </div>
                <div className="flex-1 min-w-0 text-left">
                  <p className="text-[13px] font-medium text-blue-700 dark:text-blue-300 truncate">
                    {activeAgent || "未选择"}
                  </p>
                  {currentAgentObj && (
                    <p className="text-[10px] text-blue-400/80 dark:text-blue-500/80 truncate mt-0.5">
                      {currentAgentObj.modelDisplayName || currentAgentObj.model}
                    </p>
                  )}
                </div>
              </button>
              <button
                onClick={() => setAgentDropdownOpen(!agentDropdownOpen)}
                className="px-2 py-3 rounded-r-lg hover:bg-blue-100/60 dark:hover:bg-blue-900/30 transition-colors outline-none border-l border-blue-200/40 dark:border-blue-800/30"
              >
                <ChevronDown size={14} className={`text-blue-400 transition-transform ${agentDropdownOpen ? "rotate-180" : ""}`} />
              </button>
            </div>

            {agentDropdownOpen && (
              <div className="absolute left-0 right-0 top-full mt-1 z-50 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl shadow-lg shadow-slate-200/50 dark:shadow-slate-900/50 py-1.5 max-h-60 overflow-y-auto">
                {agents.map((agent) => {
                  const isActive = agent.name === activeAgent;
                  return (
                    <div
                      key={agent.name}
                      className={`group flex items-center gap-2.5 px-3 py-2 mx-1.5 rounded-lg cursor-pointer transition-colors ${
                        isActive
                          ? "bg-blue-50 dark:bg-blue-900/20"
                          : "hover:bg-slate-50 dark:hover:bg-slate-700/60"
                      }`}
                      onClick={() => {
                        onSelectAgent(agent.name);
                        setAgentDropdownOpen(false);
                      }}
                    >
                      <Bot size={13} className={isActive ? "text-blue-500" : "text-slate-400"} />
                      <div className="flex-1 min-w-0">
                        <p className={`text-[13px] truncate ${
                          isActive ? "font-medium text-blue-600 dark:text-blue-300" : "text-slate-700 dark:text-slate-200"
                        }`}>
                          {agent.name}
                        </p>
                      </div>
                      {isActive && <span className="w-1.5 h-1.5 rounded-full bg-blue-500" />}
                      {!agent.isDefault && (
                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            if (window.confirm(`确定删除智能体「${agent.name}」吗？`)) {
                              removeAgent(agent.name);
                            }
                          }}
                          className="hidden group-hover:flex items-center justify-center w-5 h-5 rounded hover:bg-red-50 dark:hover:bg-red-900/20"
                        >
                          <Trash2 size={11} className="text-red-400" />
                        </button>
                      )}
                    </div>
                  );
                })}
                <div className="h-px bg-slate-100 dark:bg-slate-700 my-1.5 mx-3" />
                <button
                  onClick={() => {
                    onCreateAgent();
                    setAgentDropdownOpen(false);
                    onClose();
                  }}
                  className="w-full flex items-center gap-2.5 px-3 py-2 mx-1.5 rounded-lg text-[13px] text-slate-400 hover:text-slate-600 hover:bg-slate-50 dark:hover:bg-slate-700/60 dark:hover:text-slate-300 transition-colors"
                  style={{ width: "calc(100% - 12px)" }}
                >
                  <Plus size={12} />
                  新建智能体
                </button>
              </div>
            )}
          </div>

          {/* Divider */}
          <div className="h-px bg-slate-200/70 dark:bg-slate-700/70 my-3 mx-2" />

          {/* Navigation */}
          <div className="space-y-0.5">
            <button
              onClick={() => {
                navigate("/history");
                onClose();
              }}
              className={navItemClass("/history")}
            >
              <History size={15} />
              历史记录
            </button>
          </div>

          {/* Divider */}
          <div className="h-px bg-slate-200/70 dark:bg-slate-700/70 my-3 mx-2" />

          {/* Management */}
          <div>
            <button
              onClick={() => setManagementExpanded(!managementExpanded)}
              className="w-full flex items-center gap-2 px-3 py-[7px] text-[13px] font-medium text-slate-500 dark:text-slate-400 rounded-md hover:bg-slate-100 dark:hover:bg-slate-700/60 transition-colors outline-none"
            >
              {managementExpanded ? <ChevronDown size={13} /> : <ChevronRight size={13} />}
              管理
            </button>
            {managementExpanded && (
              <div className="mt-1 space-y-0.5 pl-3">
                <button onClick={() => { navigate("/agents"); onClose(); }} className={navItemClass("/agents")}>
                  <Bot size={14} /> Agent 配置
                </button>
                <button onClick={() => { navigate("/tools"); onClose(); }} className={navItemClass("/tools")}>
                  <Settings size={14} /> 工具管理
                </button>
                <button onClick={() => { navigate("/skills"); onClose(); }} className={navItemClass("/skills")}>
                  <Sparkles size={14} /> Skills 管理
                </button>
                <button onClick={() => { navigate("/models"); onClose(); }} className={navItemClass("/models")}>
                  <Cpu size={14} /> 模型管理
                </button>
              </div>
            )}
          </div>

          {/* Divider */}
          <div className="h-px bg-slate-200/70 dark:bg-slate-700/70 my-3 mx-2" />

          {/* Standalone nav items */}
          <div className="space-y-0.5">
            <button onClick={() => { navigate("/scheduler"); onClose(); }} className={navItemClass("/scheduler")}>
              <Timer size={15} /> 调度中心
            </button>
            <button onClick={() => { navigate("/memory"); onClose(); }} className={navItemClass("/memory")}>
              <Brain size={15} /> 记忆观测
            </button>
            <button onClick={() => { navigate("/monitoring"); onClose(); }} className={navItemClass("/monitoring")}>
              <Activity size={15} /> 系统监控
            </button>
          </div>
        </div>

        {/* Footer - Connection */}
        <div className="px-4 py-2.5 border-t border-slate-200/80 dark:border-slate-700 flex items-center gap-2">
          <ConnectionStatus />
          {wsSessionId && (
            <span className="text-[11px] text-slate-400 dark:text-slate-500 truncate" title={wsSessionId}>
              {wsSessionId.slice(0, 8)}
            </span>
          )}
        </div>
      </aside>
    </>
  );
}
