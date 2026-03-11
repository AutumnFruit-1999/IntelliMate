import { useState, useEffect, useCallback } from "react";
import TopBar from "./components/TopBar";
import Sidebar from "./components/Sidebar";
import ChatPanel from "./components/ChatPanel";
import AgentConfigModal, { type ContextTab } from "./components/AgentConfigModal";
import CreateAgentModal from "./components/CreateAgentModal";
import { useWebSocket } from "./hooks/useWebSocket";
import { useAgentStore } from "./stores/agentStore";
import { useChatStore } from "./stores/chatStore";

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
  const [configModal, setConfigModal] = useState<{ open: boolean; tab: ContextTab }>({
    open: false,
    tab: "soul",
  });
  const [createModalOpen, setCreateModalOpen] = useState(false);

  const { sendMessage } = useWebSocket();
  const activeAgent = useAgentStore((s) => s.activeAgent);
  const fetchAgentList = useAgentStore((s) => s.fetchAgentList);
  const setActiveAgent = useAgentStore((s) => s.setActiveAgent);
  const createAgent = useAgentStore((s) => s.createAgent);

  useEffect(() => {
    fetchAgentList();
  }, [fetchAgentList]);

  useEffect(() => {
    document.documentElement.classList.toggle("dark", darkMode);
    localStorage.setItem("javaclaw-theme", darkMode ? "dark" : "light");
  }, [darkMode]);

  const toggleDark = useCallback(() => setDarkMode((d) => !d), []);

  const openConfig = useCallback((tab: ContextTab) => {
    setConfigModal({ open: true, tab });
  }, []);

  const handleSelectAgent = useCallback(
    (name: string) => {
      if (name !== activeAgent) {
        setActiveAgent(name);
        useChatStore.getState().clearMessages();
      }
    },
    [activeAgent, setActiveAgent],
  );

  const handleCreateAgent = useCallback(
    async (name: string, model: string) => {
      await createAgent(name, model);
      useChatStore.getState().clearMessages();
    },
    [createAgent],
  );

  return (
    <div className="flex h-screen overflow-hidden bg-white dark:bg-slate-900">
      <Sidebar
        onSend={sendMessage}
        open={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
        onOpenConfig={openConfig}
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
        <ChatPanel onSend={sendMessage} />
      </div>
      <AgentConfigModal
        open={configModal.open}
        onClose={() => setConfigModal((s) => ({ ...s, open: false }))}
        agentName={activeAgent ?? "javaclaw"}
        initialTab={configModal.tab}
      />
      <CreateAgentModal
        open={createModalOpen}
        onClose={() => setCreateModalOpen(false)}
        onCreate={handleCreateAgent}
      />
    </div>
  );
}
