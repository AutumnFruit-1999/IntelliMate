import { useState, useEffect, useCallback } from "react";
import TopBar from "./components/TopBar";
import Sidebar from "./components/Sidebar";
import ChatPanel from "./components/ChatPanel";
import AgentConfigModal, { type ContextTab } from "./components/AgentConfigModal";
import { useWebSocket } from "./hooks/useWebSocket";

const AGENT_NAME = import.meta.env.VITE_AGENT_NAME ?? "javaclaw";

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
  const { sendMessage } = useWebSocket();

  useEffect(() => {
    document.documentElement.classList.toggle("dark", darkMode);
    localStorage.setItem("javaclaw-theme", darkMode ? "dark" : "light");
  }, [darkMode]);

  const toggleDark = useCallback(() => setDarkMode((d) => !d), []);

  const openConfig = useCallback((tab: ContextTab) => {
    setConfigModal({ open: true, tab });
  }, []);

  return (
    <div className="flex h-screen overflow-hidden bg-white dark:bg-slate-900">
      <Sidebar
        onSend={sendMessage}
        open={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
        onOpenConfig={openConfig}
      />
      <div className="flex flex-col flex-1 min-w-0 min-h-0">
        <TopBar
          darkMode={darkMode}
          onToggleDark={toggleDark}
          onMenuClick={() => setSidebarOpen(true)}
        />
        <ChatPanel onSend={sendMessage} />
      </div>
      <AgentConfigModal
        open={configModal.open}
        onClose={() => setConfigModal((s) => ({ ...s, open: false }))}
        agentName={AGENT_NAME}
        initialTab={configModal.tab}
      />
    </div>
  );
}
