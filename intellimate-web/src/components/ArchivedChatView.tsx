import { useState, useEffect } from "react";
import { useParams, useNavigate } from "react-router-dom";
import { fetchSessionMessages, type HistoryMessage } from "../lib/sessionApi";
import { ChevronLeft, Bot, User } from "lucide-react";

export default function ArchivedChatView() {
  const { sessionId } = useParams<{ sessionId: string }>();
  const navigate = useNavigate();
  const [messages, setMessages] = useState<HistoryMessage[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!sessionId) return;
    setLoading(true);
    fetchSessionMessages(parseInt(sessionId, 10), 200)
      .then((resp) => setMessages(resp.messages))
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [sessionId]);

  return (
    <div className="flex-1 overflow-y-auto p-6 max-w-3xl mx-auto w-full">
      <div className="flex items-center gap-3 mb-6">
        <button onClick={() => navigate("/history")} className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800">
          <ChevronLeft size={18} className="text-slate-500" />
        </button>
        <h1 className="text-lg font-semibold text-slate-800 dark:text-slate-100">归档对话</h1>
        <span className="text-xs text-slate-400 bg-slate-100 dark:bg-slate-800 px-2 py-0.5 rounded">只读</span>
      </div>

      {loading && <p className="text-sm text-slate-400 text-center py-8">加载中...</p>}

      <div className="space-y-4">
        {messages
          .filter((m) => m.role === "user" || m.role === "assistant")
          .map((msg) => {
            const isUser = msg.role === "user";
            return (
              <div key={msg.id} className={`flex gap-3 ${isUser ? "flex-row-reverse" : ""}`}>
                <div className={`flex-shrink-0 w-7 h-7 rounded-full flex items-center justify-center ${
                  isUser ? "bg-blue-500 text-white" : "bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300"
                }`}>
                  {isUser ? <User size={14} /> : <Bot size={14} />}
                </div>
                <div className="max-w-[75%]">
                  <div className={`text-[10px] text-slate-400 mb-0.5 ${isUser ? "text-right" : ""}`}>
                    {new Date(msg.createdAt).toLocaleString("zh-CN")}
                  </div>
                  <div className={`rounded-2xl px-4 py-2.5 text-sm whitespace-pre-wrap ${
                    isUser
                      ? "bg-blue-500 text-white rounded-tr-sm"
                      : "bg-slate-100 dark:bg-slate-800 text-slate-800 dark:text-slate-100 rounded-tl-sm"
                  }`}>
                    {msg.content}
                  </div>
                </div>
              </div>
            );
          })}
      </div>
    </div>
  );
}
