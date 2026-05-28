import { useState, useEffect, useRef } from "react";
import { useNavigate } from "react-router-dom";
import { useAgentStore } from "../stores/agentStore";
import { fetchArchivedSessions, searchMessages, deleteArchivedSession, type ArchivedSession, type SearchResult } from "../lib/sessionApi";
import { apiFetch, apiFetchRaw } from "../lib/httpClient";
import { MessageSquare, ClipboardList, Search, ChevronLeft, Clock, Archive, Loader2, User, Bot, Trash2, CheckCircle2, XCircle, AlertTriangle, ChevronDown, ChevronRight } from "lucide-react";

type TabType = "chats" | "tasks";

interface PlanSummary {
  planId: number;
  title: string;
  status: string;
  totalSteps: number;
  completedSteps: number;
  createdAt: string | null;
  agentName: string | null;
  completionSummary?: string | null;
}

const PLAN_STATUS_LABELS: Record<string, string> = {
  draft: "草稿",
  approved: "已审批",
  executing: "执行中",
  paused: "已暂停",
  completed: "已完成",
  failed: "失败",
  cancelled: "已取消",
};

const PLAN_STATUS_STYLE: Record<string, string> = {
  completed: "bg-emerald-50 dark:bg-emerald-900/20 text-emerald-600 dark:text-emerald-400",
  failed: "bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400",
  executing: "bg-blue-50 dark:bg-blue-900/20 text-blue-600 dark:text-blue-400",
  cancelled: "bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400",
  draft: "bg-amber-50 dark:bg-amber-900/20 text-amber-600 dark:text-amber-400",
  approved: "bg-blue-50 dark:bg-blue-900/20 text-blue-600 dark:text-blue-400",
  paused: "bg-orange-50 dark:bg-orange-900/20 text-orange-600 dark:text-orange-400",
};

function formatRelativeTime(dateStr: string): string {
  const date = new Date(dateStr);
  const now = new Date();
  const diffMs = now.getTime() - date.getTime();
  const diffMin = Math.floor(diffMs / 60000);
  const diffHour = Math.floor(diffMs / 3600000);
  const diffDay = Math.floor(diffMs / 86400000);

  if (diffMin < 1) return "刚刚";
  if (diffMin < 60) return `${diffMin} 分钟前`;
  if (diffHour < 24) return `${diffHour} 小时前`;
  if (diffDay < 7) return `${diffDay} 天前`;
  return date.toLocaleDateString("zh-CN", { month: "short", day: "numeric" });
}

function planStatusIcon(status: string) {
  if (status === "completed") return <CheckCircle2 size={14} className="text-emerald-500" />;
  if (status === "failed") return <XCircle size={14} className="text-red-500" />;
  if (status === "cancelled") return <AlertTriangle size={14} className="text-slate-400" />;
  if (status === "executing") return <Clock size={14} className="text-blue-400 animate-spin" />;
  return <ClipboardList size={14} className="text-slate-400" />;
}

export default function HistoryPage() {
  const navigate = useNavigate();
  const activeAgent = useAgentStore((s) => s.activeAgent);
  const [tab, setTab] = useState<TabType>("chats");
  const [sessions, setSessions] = useState<ArchivedSession[]>([]);
  const [plans, setPlans] = useState<PlanSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  const [searchResults, setSearchResults] = useState<SearchResult[]>([]);
  const [searching, setSearching] = useState(false);
  const [expandedPlanId, setExpandedPlanId] = useState<number | null>(null);
  const searchTimeoutRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  useEffect(() => {
    if (!activeAgent) return;
    setLoading(true);
    fetchArchivedSessions(activeAgent, 50, 0)
      .then((resp) => setSessions(resp.sessions))
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [activeAgent]);

  useEffect(() => {
    if (tab !== "tasks") return;
    setLoading(true);
    const params = activeAgent ? `?agentName=${encodeURIComponent(activeAgent)}` : "";
    apiFetch<PlanSummary[]>(`/api/plans${params}`)
      .then(setPlans)
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [tab, activeAgent]);

  const handleDeletePlan = async (planId: number, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!window.confirm("确定删除这个任务吗？")) return;
    try {
      await apiFetchRaw(`/api/plans/${planId}`, { method: "DELETE" });
      setPlans((prev) => prev.filter((p) => p.planId !== planId));
    } catch (err) {
      console.error("Failed to delete plan:", err);
    }
  };

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

  const handleDelete = async (sessionId: number, e: React.MouseEvent) => {
    e.stopPropagation();
    if (!window.confirm("确定删除这条对话记录吗？")) return;
    try {
      await deleteArchivedSession(sessionId);
      setSessions((prev) => prev.filter((s) => s.id !== sessionId));
    } catch (err) {
      console.error("Failed to delete session:", err);
    }
  };

  const showSearchMode = searchQuery.trim().length > 0;

  return (
    <div className="flex-1 overflow-y-auto">
      <div className="w-full px-8 py-6 lg:px-12">
        {/* Header */}
        <div className="flex items-center gap-3 mb-6">
          <button
            onClick={() => navigate("/chat")}
            className="p-2 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          >
            <ChevronLeft size={18} className="text-slate-500" />
          </button>
          <div>
            <h1 className="text-lg font-semibold text-slate-800 dark:text-slate-100">历史记录</h1>
            <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
              {activeAgent && `${activeAgent} 的对话与任务`}
            </p>
          </div>
        </div>

        {/* Search */}
        <div className="relative mb-5">
          <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
          <input
            type="text"
            value={searchQuery}
            onChange={handleSearchChange}
            placeholder="搜索对话内容..."
            className="w-full pl-10 pr-4 py-2.5 text-sm rounded-xl border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/50 text-slate-700 dark:text-slate-200 placeholder-slate-400 outline-none focus:border-blue-400 focus:bg-white dark:focus:bg-slate-800 transition-colors"
          />
          {searching && (
            <Loader2 size={14} className="absolute right-3 top-1/2 -translate-y-1/2 text-blue-400 animate-spin" />
          )}
        </div>

        {/* Tabs */}
        {!showSearchMode && (
          <div className="flex gap-1 mb-5 p-1 bg-slate-100/80 dark:bg-slate-800/80 rounded-xl">
            <button
              onClick={() => setTab("chats")}
              className={`flex-1 flex items-center justify-center gap-2 px-4 py-2 text-sm font-medium rounded-lg transition-all duration-200 ${
                tab === "chats"
                  ? "bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm"
                  : "text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300"
              }`}
            >
              <MessageSquare size={14} /> 对话
            </button>
            <button
              onClick={() => setTab("tasks")}
              className={`flex-1 flex items-center justify-center gap-2 px-4 py-2 text-sm font-medium rounded-lg transition-all duration-200 ${
                tab === "tasks"
                  ? "bg-white dark:bg-slate-700 text-slate-800 dark:text-slate-100 shadow-sm"
                  : "text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300"
              }`}
            >
              <ClipboardList size={14} /> 任务
            </button>
          </div>
        )}

        {/* Search Results */}
        {showSearchMode && (
          <div className="space-y-3">
            <p className="text-xs font-medium text-slate-500 dark:text-slate-400 mb-3">
              {searching ? "搜索中..." : `搜索结果 (${searchResults.filter((r) => r.role === "user" || r.role === "assistant").length})`}
            </p>
            {!searching && searchResults.length === 0 && (
              <div className="flex flex-col items-center py-12 text-slate-400">
                <Search size={32} className="mb-3 opacity-40" />
                <p className="text-sm">未找到匹配内容</p>
                <p className="text-xs mt-1">试试换个关键词</p>
              </div>
            )}
            {searchResults
              .filter((r) => r.role === "user" || r.role === "assistant")
              .map((result) => (
                <div
                  key={result.id}
                  className="px-5 py-4 rounded-xl border border-slate-200 dark:border-slate-700 hover:border-blue-200 dark:hover:border-blue-800 hover:bg-blue-50/30 dark:hover:bg-blue-900/10 transition-colors cursor-pointer"
                >
                  <div className="flex items-center gap-2 mb-2.5">
                    <span className={`flex items-center gap-1.5 text-xs font-medium px-2 py-0.5 rounded-full ${
                      result.role === "user"
                        ? "bg-slate-100 dark:bg-slate-700 text-slate-600 dark:text-slate-300"
                        : "bg-blue-50 dark:bg-blue-900/30 text-blue-600 dark:text-blue-400"
                    }`}>
                      {result.role === "user" ? <User size={10} /> : <Bot size={10} />}
                      {result.role === "user" ? "你" : "助手"}
                    </span>
                    <span className="text-[11px] text-slate-400">
                      {formatRelativeTime(result.createdAt)}
                    </span>
                  </div>
                  <p className="text-sm text-slate-700 dark:text-slate-200 line-clamp-2 leading-relaxed">
                    {result.content}
                  </p>
                </div>
              ))}
          </div>
        )}

        {/* Chat History */}
        {!showSearchMode && tab === "chats" && (
          <div className="space-y-3">
            {loading && (
              <div className="flex items-center justify-center py-12">
                <Loader2 size={20} className="text-blue-400 animate-spin" />
              </div>
            )}
            {!loading && sessions.length === 0 && (
              <div className="flex flex-col items-center py-16 text-slate-400">
                <Archive size={36} className="mb-3 opacity-30" />
                <p className="text-sm font-medium">暂无归档对话</p>
                <p className="text-xs mt-1.5 text-slate-400/80">
                  使用 /clear 命令归档当前对话后会出现在这里
                </p>
              </div>
            )}
            {sessions.map((session) => (
              <div
                key={session.id}
                role="button"
                tabIndex={0}
                onClick={() => navigate(`/history/chat/${session.id}`)}
                onKeyDown={(e) => e.key === "Enter" && navigate(`/history/chat/${session.id}`)}
                className="w-full text-left px-5 py-4 rounded-xl border border-slate-200 dark:border-slate-700 hover:border-blue-200 dark:hover:border-blue-800 hover:bg-blue-50/30 dark:hover:bg-blue-900/10 transition-all duration-150 group cursor-pointer"
              >
                <div className="flex items-center justify-between gap-4">
                  <div className="flex items-center gap-4 min-w-0">
                    <div className="p-2.5 rounded-xl bg-slate-100 dark:bg-slate-700 group-hover:bg-blue-100 dark:group-hover:bg-blue-900/30 transition-colors shrink-0">
                      <MessageSquare size={18} className="text-slate-400 group-hover:text-blue-500 transition-colors" />
                    </div>
                    <div className="min-w-0">
                      <p className="text-[15px] font-medium text-slate-700 dark:text-slate-200 truncate">
                        {session.title || "无标题对话"}
                      </p>
                      <p className="text-xs text-slate-400 dark:text-slate-500 mt-1.5 flex items-center gap-1.5">
                        <Clock size={11} />
                        {formatRelativeTime(session.lastActiveAt)}
                      </p>
                    </div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    <button
                      onClick={(e) => handleDelete(session.id, e)}
                      className="p-1.5 rounded-lg text-slate-300 dark:text-slate-600 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20 opacity-0 group-hover:opacity-100 transition-all"
                      title="删除对话"
                    >
                      <Trash2 size={15} />
                    </button>
                    <ChevronLeft size={16} className="text-slate-300 dark:text-slate-600 rotate-180 opacity-0 group-hover:opacity-100 transition-opacity" />
                  </div>
                </div>
              </div>
            ))}
          </div>
        )}

        {/* Plan History */}
        {!showSearchMode && tab === "tasks" && (
          <div className="space-y-3">
            {loading && (
              <div className="flex items-center justify-center py-12">
                <Loader2 size={20} className="text-blue-400 animate-spin" />
              </div>
            )}
            {!loading && plans.length === 0 && (
              <div className="flex flex-col items-center py-16 text-slate-400">
                <ClipboardList size={36} className="mb-3 opacity-30" />
                <p className="text-sm font-medium">暂无任务记录</p>
                <p className="text-xs mt-1.5 text-slate-400/80">
                  Plan 任务执行后的记录会出现在这里
                </p>
              </div>
            )}
            {!loading && plans.map((plan) => (
              <div
                key={plan.planId}
                className="w-full px-5 py-4 rounded-xl border border-slate-200 dark:border-slate-700 hover:border-blue-200 dark:hover:border-blue-800 hover:bg-blue-50/30 dark:hover:bg-blue-900/10 transition-all duration-150 group"
              >
                <div
                  role="button"
                  tabIndex={0}
                  onClick={() => setExpandedPlanId(expandedPlanId === plan.planId ? null : plan.planId)}
                  onKeyDown={(e) => e.key === "Enter" && setExpandedPlanId(expandedPlanId === plan.planId ? null : plan.planId)}
                  className="flex items-center justify-between gap-4 cursor-pointer"
                >
                  <div className="flex items-center gap-3 min-w-0">
                    <div className="p-2.5 rounded-xl bg-slate-100 dark:bg-slate-700 shrink-0">
                      {planStatusIcon(plan.status)}
                    </div>
                    <div className="min-w-0">
                      <p className="text-[15px] font-medium text-slate-700 dark:text-slate-200 truncate">
                        {plan.title}
                      </p>
                      <div className="flex items-center gap-3 mt-1.5 text-xs text-slate-400 dark:text-slate-500">
                        {plan.createdAt && (
                          <span className="flex items-center gap-1">
                            <Clock size={11} />
                            {formatRelativeTime(plan.createdAt)}
                          </span>
                        )}
                        <span>进度 {plan.completedSteps}/{plan.totalSteps}</span>
                        {plan.agentName && (
                          <span className="px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-700 text-[11px]">
                            {plan.agentName}
                          </span>
                        )}
                      </div>
                    </div>
                  </div>
                  <div className="flex items-center gap-2 shrink-0">
                    <span className={`text-xs font-medium px-2 py-1 rounded-full ${
                      PLAN_STATUS_STYLE[plan.status] ?? "bg-slate-100 dark:bg-slate-700 text-slate-500"
                    }`}>
                      {PLAN_STATUS_LABELS[plan.status] ?? plan.status}
                    </span>
                    <button
                      onClick={(e) => handleDeletePlan(plan.planId, e)}
                      className="p-1.5 rounded-lg text-slate-300 dark:text-slate-600 hover:text-red-500 hover:bg-red-50 dark:hover:bg-red-900/20 opacity-0 group-hover:opacity-100 transition-all"
                      title="删除任务"
                    >
                      <Trash2 size={15} />
                    </button>
                    {expandedPlanId === plan.planId
                      ? <ChevronDown size={16} className="text-slate-400" />
                      : <ChevronRight size={16} className="text-slate-400" />
                    }
                  </div>
                </div>
                {expandedPlanId === plan.planId && plan.completionSummary && (
                  <div className="mt-3 pl-11 text-xs text-slate-500 dark:text-slate-400 leading-relaxed">
                    {plan.completionSummary}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
