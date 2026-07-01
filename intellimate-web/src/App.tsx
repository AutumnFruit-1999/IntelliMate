import { useState, useEffect, useCallback } from "react";
import { Routes, Route, Navigate, useNavigate } from "react-router-dom";
import TopBar from "./components/TopBar";
import Sidebar from "./components/Sidebar";
import ChatPanel from "./components/ChatPanel";
import AgentCardGrid from "./components/AgentCardGrid";
import AgentConfigModal from "./components/AgentConfigModal";
import ToolManagerPage from "./components/ToolManagerModal";
import SkillManagerPage from "./components/SkillManagerModal";
import ModelManagerPage from "./components/ModelManagerModal";
import CreateAgentModal from "./components/CreateAgentModal";
import HistoryPage from "./components/HistoryPage";
import ArchivedChatView from "./components/ArchivedChatView";
import MemoryManagerPage from "./components/MemoryManagerPage";
import SchedulerDashboard from "./components/SchedulerDashboard";
import MonitoringPage from "./components/MonitoringPage";
import ChannelsPage from "./components/ChannelsPage";
import LoginPage from "./components/LoginPage";
import { useWebSocket } from "./hooks/useWebSocket";
import { useAgentStore } from "./stores/agentStore";
import { useChatStore } from "./stores/chatStore";
import { usePlanStore } from "./stores/planStore";
import { useAuthStore } from "./stores/authStore";

export default function App() {
  const isAuthenticated = useAuthStore((s) => s.isAuthenticated);
  const navigate = useNavigate();

  const [darkMode, setDarkMode] = useState(() => {
    if (typeof window !== "undefined") {
      const saved = localStorage.getItem("intellimate-theme");
      if (saved) return saved === "dark";
      return window.matchMedia("(prefers-color-scheme: dark)").matches;
    }
    return false;
  });
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [agentConfigTarget, setAgentConfigTarget] = useState<string | null>(null);
  const [createModalOpen, setCreateModalOpen] = useState(false);
  const { sendMessage, sendPlanAction, cancelRequest } = useWebSocket();
  const activeAgent = useAgentStore((s) => s.activeAgent);
  const fetchAgentList = useAgentStore((s) => s.fetchAgentList);
  const setActiveAgent = useAgentStore((s) => s.setActiveAgent);
  const createAgent = useAgentStore((s) => s.createAgent);

  const pendingForcePlan = useChatStore((s) => s.pendingForcePlan);

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
    document.documentElement.classList.toggle("dark", darkMode);
    localStorage.setItem("intellimate-theme", darkMode ? "dark" : "light");
  }, [darkMode]);

  const toggleDark = useCallback(() => setDarkMode((d) => !d), []);

  const handleSelectAgent = useCallback(
    (name: string) => {
      if (name !== activeAgent) {
        setActiveAgent(name);
        useChatStore.getState().setCurrentAgent(name);
        usePlanStore.getState().clearActivePlan();
      }
      navigate("/chat");
    },
    [activeAgent, setActiveAgent, navigate],
  );

  const handleCreateAgent = useCallback(
    async (name: string, model: string) => {
      await createAgent(name, model);
      useChatStore.getState().setCurrentAgent(name);
      navigate("/chat");
    },
    [createAgent, navigate],
  );

  const handleAgentCardClick = useCallback((name: string) => {
    setAgentConfigTarget(name);
  }, []);

  if (!isAuthenticated) {
    return <LoginPage />;
  }

  return (
    <div className="flex h-screen overflow-hidden bg-white dark:bg-slate-900">
      <Sidebar
        open={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
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
          <Routes>
            <Route path="/" element={<Navigate to="/chat" replace />} />
            <Route
              path="/chat"
              element={
                <ChatPanel onSend={sendMessage} onCancel={cancelRequest} onSendPlanAction={sendPlanAction} />
              }
            />
            <Route path="/history" element={<HistoryPage />} />
            <Route path="/history/chat/:sessionId" element={<ArchivedChatView />} />
            <Route
              path="/agents"
              element={
                <AgentCardGrid
                  onSelectAgent={handleAgentCardClick}
                  onCreateAgent={() => setCreateModalOpen(true)}
                  onBack={() => navigate("/chat")}
                />
              }
            />
            <Route path="/tools" element={<ToolManagerPage onBack={() => navigate("/chat")} />} />
            <Route path="/skills" element={<SkillManagerPage onBack={() => navigate("/chat")} />} />
            <Route path="/models" element={<ModelManagerPage onBack={() => navigate("/chat")} />} />
            <Route path="/channels" element={<ChannelsPage onBack={() => navigate("/chat")} />} />
            <Route
              path="/memory"
              element={
                <MemoryManagerPage onBack={() => navigate("/chat")} activeAgent={activeAgent ?? undefined} />
              }
            />
            <Route path="/scheduler" element={<SchedulerDashboard />} />
            <Route path="/monitoring" element={<MonitoringPage />} />
          </Routes>
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
