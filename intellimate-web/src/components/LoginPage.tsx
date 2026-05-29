import { useState, useCallback, FormEvent } from "react";
import { Loader2, Sparkles } from "lucide-react";
import { useAuthStore } from "../stores/authStore";

type TabMode = "login" | "register";

function formatAuthError(error: unknown): string {
  if (!(error instanceof Error)) return "操作失败，请重试";

  const msg = error.message;
  const jsonMatch = msg.match(/\{[\s\S]*\}/);
  if (jsonMatch) {
    try {
      const parsed = JSON.parse(jsonMatch[0]) as { message?: string };
      if (parsed.message) return parsed.message;
    } catch {
      // ignore parse errors
    }
  }

  if (msg.includes("401") || msg.includes("403")) return "用户名或密码错误";
  if (msg.includes("409")) return "用户名已被占用";

  const stripped = msg.replace(/^API \d+: /, "").trim();
  return stripped || "操作失败，请重试";
}

const inputClassName =
  "w-full px-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-sm text-slate-800 dark:text-slate-200 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500/40";

export default function LoginPage() {
  const login = useAuthStore((s) => s.login);
  const register = useAuthStore((s) => s.register);

  const [tab, setTab] = useState<TabMode>("login");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const switchTab = useCallback((next: TabMode) => {
    setTab(next);
    setError(null);
  }, []);

  const handleSubmit = useCallback(
    async (e: FormEvent) => {
      e.preventDefault();

      if (!username.trim()) {
        setError("用户名不能为空");
        return;
      }
      if (!password) {
        setError("密码不能为空");
        return;
      }

      setLoading(true);
      setError(null);

      try {
        if (tab === "login") {
          await login(username.trim(), password);
        } else {
          await register(
            username.trim(),
            password,
            displayName.trim() || undefined,
          );
        }
        window.location.reload();
        return;
      } catch (err) {
        setError(formatAuthError(err));
      } finally {
        setLoading(false);
      }
    },
    [tab, username, password, displayName, login, register],
  );

  return (
    <div className="flex flex-1 min-h-0 items-center justify-center bg-slate-50 dark:bg-slate-900 px-4 py-10">
      <div className="w-full max-w-md bg-white dark:bg-slate-900 rounded-xl shadow-xl border border-slate-200 dark:border-slate-700 overflow-hidden">
        <div className="px-8 pt-8 pb-6 text-center border-b border-slate-200 dark:border-slate-700">
          <div className="mx-auto mb-4 w-12 h-12 rounded-xl bg-gradient-to-br from-blue-600 to-cyan-500 flex items-center justify-center shadow-lg shadow-blue-500/20">
            <Sparkles size={22} className="text-white" />
          </div>
          <h1 className="text-2xl font-bold bg-gradient-to-r from-blue-600 to-cyan-500 bg-clip-text text-transparent">
            IntelliMate
          </h1>
          <p className="mt-1.5 text-sm text-slate-500 dark:text-slate-400">
            {tab === "login" ? "登录以继续使用" : "创建账号开始使用"}
          </p>
        </div>

        <div className="px-8 py-6">
          <div className="flex p-1 mb-6 bg-slate-100 dark:bg-slate-800 rounded-lg">
            <button
              type="button"
              onClick={() => switchTab("login")}
              className={`flex-1 py-2 text-sm font-medium rounded-md transition-colors ${
                tab === "login"
                  ? "bg-white dark:bg-slate-700 text-blue-600 dark:text-blue-400 shadow-sm"
                  : "text-slate-600 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200"
              }`}
            >
              登录
            </button>
            <button
              type="button"
              onClick={() => switchTab("register")}
              className={`flex-1 py-2 text-sm font-medium rounded-md transition-colors ${
                tab === "register"
                  ? "bg-white dark:bg-slate-700 text-blue-600 dark:text-blue-400 shadow-sm"
                  : "text-slate-600 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200"
              }`}
            >
              注册
            </button>
          </div>

          <form onSubmit={handleSubmit} className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                用户名 <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="请输入用户名"
                autoComplete="username"
                className={inputClassName}
              />
            </div>

            <div>
              <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                密码 <span className="text-red-500">*</span>
              </label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="请输入密码"
                autoComplete={tab === "login" ? "current-password" : "new-password"}
                className={inputClassName}
              />
            </div>

            {tab === "register" && (
              <div>
                <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
                  显示名称
                </label>
                <input
                  type="text"
                  value={displayName}
                  onChange={(e) => setDisplayName(e.target.value)}
                  placeholder="可选，用于展示"
                  autoComplete="name"
                  className={inputClassName}
                />
              </div>
            )}

            {error && (
              <div className="px-3 py-2 text-sm text-red-600 bg-red-50 dark:bg-red-900/20 dark:text-red-400 rounded-lg">
                {error}
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="w-full flex items-center justify-center gap-1.5 py-2.5 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
            >
              {loading && <Loader2 size={16} className="animate-spin" />}
              {tab === "login" ? "登录" : "注册"}
            </button>
          </form>
        </div>
      </div>
    </div>
  );
}
