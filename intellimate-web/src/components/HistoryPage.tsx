import { useState, useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { useAgentStore } from "../stores/agentStore";
import { fetchArchivedSessions, searchMessages, type ArchivedSession, type SearchResult } from "../lib/sessionApi";
import { MessageSquare, ClipboardList, Search, ChevronLeft } from "lucide-react";

type TabType = "chats" | "plans";

export default function HistoryPage() {
  const navigate = useNavigate();
  const activeAgent = useAgentStore((s) => s.activeAgent);
  const [tab, setTab] = useState<TabType>("chats");
  const [sessions, setSessions] = useState<ArchivedSession[]>([]);
  const [loading, setLoading] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const [searching, setSearching] = useState(false);
  const searchTimeoutRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    if (!activeAgent) return;
    setLoading(true);
    fetchArchivedSessions(activeAgent, 50, 0)
      .then((resp) => setSessions(resp.sessions))
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [activeAgent]);

  const handleSearchChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const query = e.target.value;
    setSearchQuery(query);

    if (searchTimeoutRef.current) clearTimeout(searchTimeoutRef.current);

    if (!query.trim() || !activeAgent) {
      setSearchResults([]);
      return;
    }

    searchTimeoutRef.current = setTimeout(() => {
      setSearching(true);
      searchMessages(activeAgent, query.trim())
        .then((resp) => setSearchResults(resp.results))
        .catch(console.error)
        .finally(() => setSearching(false));
    }, 300);
  };

  return (
    <div className="flex-1 overflow-y-auto p-6 max-w-3xl mx-auto w-full">
      <div className="flex items-center gap-3 mb-6">
        <button onClick={() => navigate("/chat")} className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800">
          <ChevronLeft size={18} className="text-slate-500" />
        </button>
        <h1 className="text-lg font-semibold text-slate-800 dark:text-slate-100">历史</h1>
      </div>

      <div className="relative mb-4">
        <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
        <input
          type="text"
          value={searchQuery}
          onChange={handleSearchChange}
          placeholder="搜索历史对话..."
          className="w-full pl-9 pr-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 placeholder-slate-400 outline-none focus:border-blue-400"
        />
      </div>

      <div className="flex gap-1 mb-4 p-1 bg-slate-100 dark:bg-slate-800 rounded-lg">
        <button
          onClick={() => setTab("chats")}
          className={`flex-1 flex items-center justify-center gap-1.5 px-3 py-1.5 text-sm rounded-md transition-colors ${
            tab === "chats" ? "bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm" : "text-slate-500"
          }`}
        >
          <MessageSquare size={14} /> 对话
        </button>
        <button
          onClick={() => setTab("plans")}
          className={`flex-1 flex items-center justify-center gap-1.5 px-3 py-1.5 text-sm rounded-md transition-colors ${
            tab === "plans" ? "bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm" : "text-slate-500"
          }`}
        >
          <ClipboardList size={14} /> 计划
        </button>
      </div>

      {tab === "chats" && (
        <div className="space-y-2">
          {searchQuery.trim() ? (
            <>
              {searching && <p className="text-sm text-slate-400 text-center py-2">搜索中...</p>}
              {!searching && searchResults.length === 0 && (
                <p className="text-sm text-slate-400 text-center py-4">无匹配结果</p>
              )}
              {searchResults
                .filter((r) => r.role === "user" || r.role === "assistant")
                .map((result) => (
                  <div
                    key={result.id}
                    className="p-3 rounded-lg border border-slate-200 dark:border-slate-700"
                  >
                    <p className="text-xs text-slate-400 mb-1">
                      {result.role === "user" ? "你" : "助手"} ·{" "}
                      {new Date(result.createdAt).toLocaleString("zh-CN")}
                    </p>
                    <p className="text-sm text-slate-700 dark:text-slate-200 line-clamp-2">
                      {result.content}
                    </p>
                  </div>
                ))}
            </>
          ) : (
            <>
              {loading && <p className="text-sm text-slate-400 text-center py-4">加载中...</p>}
              {!loading && sessions.length === 0 && (
                <p className="text-sm text-slate-400 text-center py-8">暂无归档对话</p>
              )}
              {sessions.map((session) => (
                <button
                  key={session.id}
                  onClick={() => navigate(`/history/chat/${session.id}`)}
                  className="w-full text-left p-3 rounded-lg border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors"
                >
                  <p className="text-sm font-medium text-slate-700 dark:text-slate-200 truncate">
                    {session.title || "无标题对话"}
                  </p>
                  <p className="text-xs text-slate-400 mt-1">
                    {new Date(session.lastActiveAt).toLocaleString("zh-CN")}
                  </p>
                </button>
              ))}
            </>
          )}
        </div>
      )}

      {tab === "plans" && (
        <div className="text-sm text-slate-400 text-center py-8">
          计划历史（开发中）
        </div>
      )}
    </div>
  );
}
