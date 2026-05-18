import { useState, useEffect, useCallback } from "react";
import TopBar from "./components/TopBar";
import Sidebar from "./components/Sidebar";
import ChatPanel from "./components/ChatPanel";
import PlanPanel from "./components/PlanPanel";
import AgentCardGrid from "./components/AgentCardGrid";
import AgentConfigModal from "./components/AgentConfigModal";
import ToolManagerPage from "./components/ToolManagerModal";
import SkillManagerPage from "./components/SkillManagerModal";
import ModelManagerPage from "./components/ModelManagerModal";
import CreateAgentModal from "./components/CreateAgentModal";
import PlanHistoryTab from "./components/PlanHistoryTab";
import MemoryManagerPage from "./components/MemoryManagerPage";
import SchedulerDashboard from "./components/SchedulerDashboard";
import { useWebSocket } from "./hooks/useWebSocket";
import { useAgentStore } from "./stores/agentStore";
import { useChatStore } from "./stores/chatStore";
import { usePlanStore } from "./stores/planStore";

type ViewMode = "chat" | "agents" | "planHistory" | "toolManager" | "skillManager" | "modelManager" | "memoryManager" | "scheduler";

export default function App() {
  const [darkMode, setDarkMode] = useState(() => {
    if (typeof window !== "undefined") {
      const saved = localStorage.getItem("intellimate-theme");
      if (saved) return saved === "dark";
      return window.matchMedia("(prefers-color-scheme: dark)").matches;
    }
    return false;
  });
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [viewMode, setViewMode] = useState<ViewMode>("chat");
  const [agentConfigTarget, setAgentConfigTarget] = useState<string | null>(null);
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const [planPanelOpen, setPlanPanelOpen] = useState(true);
  const [planPanelCollapsed, setPlanPanelCollapsed] = useState(false);

  const { sendMessage, sendPlanAction, sendPlanActionAndWait } = useWebSocket();
  const activeAgent = useAgentStore((s) => s.activeAgent);
  const fetchAgentList = useAgentStore((s) => s.fetchAgentList);
  const setActiveAgent = useAgentStore((s) => s.setActiveAgent);
  const createAgent = useAgentStore((s) => s.createAgent);

  const pendingForcePlan = useChatStore((s) => s.pendingForcePlan);
  const plan = usePlanStore((s) => s.plan);
  const planHistory = usePlanStore((s) => s.planHistory);
  const dismissed = usePlanStore((s) => s.dismissed);
  const showPlanPanel = (plan !== null || planHistory.length > 0) && planPanelOpen && !dismissed;

  useEffect(() => {
    fetchAgentList().then(() => {
      const agent = useAgentStore.getState().activeAgent;
      if (agent) useChatStore.getState().setCurrentAgent(agent);
    });
  }, [fetchAgentList]);

  useEffect(() => {
    if (pendingForcePlan) {
      useChatStore.getState().clearPendingForcePlan();
      sendMessage(pendingForcePlan.text, true);
    }
  }, [pendingForcePlan, sendMessage]);

  useEffect(() => {
    if (plan) {
      if (!planPanelOpen) setPlanPanelOpen(true);
      setPlanPanelCollapsed(false);
    }
  }, [plan?.planId]);

  useEffect(() => {
    document.documentElement.classList.toggle("dark", darkMode);
    localStorage.setItem("intellimate-theme", darkMode ? "dark" : "light");
  }, [darkMode]);

  const toggleDark = useCallback(() => setDarkMode((d) => !d), []);

  const handleSelectAgent = useCallback(
    (name: string) => {
      if (name !== activeAgent) {
        setActiveAgent(name);
        useChatStore.getState().setCurrentAgent(name);
        usePlanStore.getState().clearPlan();
      }
      setViewMode("chat");
    },
    [activeAgent, setActiveAgent],
  );

  const handleCreateAgent = useCallback(
    async (name: string, model: string) => {
      await createAgent(name, model);
      useChatStore.getState().setCurrentAgent(name);
      setViewMode("chat");
    },
    [createAgent],
  );

  const handleOpenAgentManager = useCallback(() => {
    setViewMode("agents");
  }, []);

  const handleAgentCardClick = useCallback((name: string) => {
    setAgentConfigTarget(name);
  }, []);

  return (
    <div className="flex h-screen overflow-hidden bg-white dark:bg-slate-900">
      <Sidebar
        open={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
        onOpenAgentManager={handleOpenAgentManager}
        onOpenToolManager={() => setViewMode("toolManager")}
        onOpenSkillManager={() => setViewMode("skillManager")}
        onOpenModelManager={() => setViewMode("modelManager")}
        onOpenPlanHistory={() => setViewMode("planHistory")}
        onOpenMemoryManager={() => setViewMode("memoryManager")}
        onOpenScheduler={() => setViewMode("scheduler")}
        onCreateAgent={() => setCreateModalOpen(true)}
        onSelectAgent={handleSelectAgent}
      />
      <div className="flex flex-col flex-1 min-w-0 min-h-0">
        <TopBar
          darkMode={darkMode}
          onToggleDark={toggleDark}
          onMenuClick={() => setSidebarOpen(true)}
          agentName={activeAgent}
        />
        <div className="flex flex-1 min-h-0">
          {viewMode === "chat" ? (
            <ChatPanel onSend={sendMessage} onSendPlanAction={sendPlanAction} />
          ) : viewMode === "planHistory" ? (
            <PlanHistoryTab onBack={() => setViewMode("chat")} />
          ) : viewMode === "toolManager" ? (
            <ToolManagerPage onBack={() => setViewMode("chat")} />
          ) : viewMode === "skillManager" ? (
            <SkillManagerPage onBack={() => setViewMode("chat")} />
          ) : viewMode === "modelManager" ? (
            <ModelManagerPage onBack={() => setViewMode("chat")} />
          ) : viewMode === "memoryManager" ? (
            <MemoryManagerPage onBack={() => setViewMode("chat")} activeAgent={activeAgent ?? undefined} />
          ) : viewMode === "scheduler" ? (
            <SchedulerDashboard />
          ) : (
            <AgentCardGrid
              onSelectAgent={handleAgentCardClick}
              onCreateAgent={() => setCreateModalOpen(true)}
              onBack={() => setViewMode("chat")}
            />
          )}
          {showPlanPanel && viewMode === "chat" && (
            planPanelCollapsed ? (
              <div className="w-10 flex-shrink-0 border-l border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-900 flex flex-col items-center py-3 gap-2">
                <button
                  onClick={() => setPlanPanelCollapsed(false)}
                  className="p-1.5 rounded hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                  title="展开计划面板"
                >
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className="text-slate-400"><polyline points="15 18 9 12 15 6" /></svg>
                </button>
                <span className="text-[10px] text-slate-400 writing-mode-vertical" style={{ writingMode: "vertical-rl" }}>
                  {plan?.title}
                </span>
              </div>
            ) : (
              <PlanPanel
                onSendAction={sendPlanAction}
                onSendPlanActionAndWait={sendPlanActionAndWait}
                onSendMessage={sendMessage}
                onClose={() => setPlanPanelCollapsed(true)}
              />
            )
          )}
        </div>
      </div>
      <AgentConfigModal
        open={agentConfigTarget !== null}
        onClose={() => setAgentConfigTarget(null)}
        initialAgent={agentConfigTarget ?? undefined}
      />
      <CreateAgentModal
        open={createModalOpen}
        onClose={() => setCreateModalOpen(false)}
        onCreate={handleCreateAgent}
      />
    </div>
  );
}
