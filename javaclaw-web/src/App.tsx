import { useState, useEffect, useCallback } from "react";
import TopBar from "./components/TopBar";
import Sidebar from "./components/Sidebar";
import ChatPanel from "./components/ChatPanel";
import AgentCardGrid from "./components/AgentCardGrid";
import AgentConfigModal from "./components/AgentConfigModal";
import ToolManagerModal from "./components/ToolManagerModal";
import ModelManagerModal from "./components/ModelManagerModal";
import CreateAgentModal from "./components/CreateAgentModal";
import { useWebSocket } from "./hooks/useWebSocket";
import { useAgentStore } from "./stores/agentStore";
import { useChatStore } from "./stores/chatStore";

type ViewMode = "chat" | "agents";

export default function App() {
  const [darkMode, setDarkMode] = useState(() => {
    if (typeof window !== "undefined") {
      const saved = localStorage.getItem("javaclaw-theme");
      if (saved) return saved === "dark";
      return window.matchMedia("(prefers-color-scheme: dark)").matches;
    }
    return false;
  });
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [viewMode, setViewMode] = useState<ViewMode>("chat");
  const [agentConfigTarget, setAgentConfigTarget] = useState<string | null>(null);
  const [toolManagerOpen, setToolManagerOpen] = useState(false);
  const [modelManagerOpen, setModelManagerOpen] = useState(false);
  const [createModalOpen, setCreateModalOpen] = useState(false);

  const { sendMessage } = useWebSocket();
  const activeAgent = useAgentStore((s) => s.activeAgent);
  const fetchAgentList = useAgentStore((s) => s.fetchAgentList);
  const setActiveAgent = useAgentStore((s) => s.setActiveAgent);
  const createAgent = useAgentStore((s) => s.createAgent);

  useEffect(() => {
    fetchAgentList().then(() => {
      const agent = useAgentStore.getState().activeAgent;
      if (agent) useChatStore.getState().setCurrentAgent(agent);
    });
  }, [fetchAgentList]);

  useEffect(() => {
    document.documentElement.classList.toggle("dark", darkMode);
    localStorage.setItem("javaclaw-theme", darkMode ? "dark" : "light");
  }, [darkMode]);

  const toggleDark = useCallback(() => setDarkMode((d) => !d), []);

  const handleSelectAgent = useCallback(
    (name: string) => {
      if (name !== activeAgent) {
        setActiveAgent(name);
        useChatStore.getState().setCurrentAgent(name);
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
        onSend={sendMessage}
        open={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
        onOpenAgentManager={handleOpenAgentManager}
        onOpenToolManager={() => setToolManagerOpen(true)}
        onOpenModelManager={() => setModelManagerOpen(true)}
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
        {viewMode === "chat" ? (
          <ChatPanel onSend={sendMessage} />
        ) : (
          <AgentCardGrid
            onSelectAgent={handleAgentCardClick}
            onCreateAgent={() => setCreateModalOpen(true)}
            onBack={() => setViewMode("chat")}
          />
        )}
      </div>
      <AgentConfigModal
        open={agentConfigTarget !== null}
        onClose={() => setAgentConfigTarget(null)}
        initialAgent={agentConfigTarget ?? undefined}
      />
      <ToolManagerModal
        open={toolManagerOpen}
        onClose={() => setToolManagerOpen(false)}
      />
      <ModelManagerModal
        open={modelManagerOpen}
        onClose={() => setModelManagerOpen(false)}
      />
      <CreateAgentModal
        open={createModalOpen}
        onClose={() => setCreateModalOpen(false)}
        onCreate={handleCreateAgent}
      />
    </div>
  );
}
