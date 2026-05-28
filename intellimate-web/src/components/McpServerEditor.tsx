import { useState, useCallback, useMemo } from "react";
import { ArrowLeft, Loader2, Zap, Check, AlertCircle } from "lucide-react";
import { useToolStore } from "../stores/toolStore";
import type { McpServer, McpServerCreate, McpTestResult } from "../lib/api";

interface McpServerEditorProps {
  server: McpServer | null;
  onSave: () => void;
  onCancel: () => void;
}

const TRANSPORT_OPTIONS = [
  { value: "STREAMABLE_HTTP", label: "Streamable HTTP (推荐)" },
  { value: "SSE", label: "SSE (旧版远程)" },
  { value: "STDIO", label: "STDIO (本地进程)" },
] as const;

function parseHeadersText(json: string | null): string {
  if (!json) return "";
  try {
    const map = JSON.parse(json) as Record<string, string>;
    return Object.entries(map).map(([k, v]) => `${k}: ${v}`).join("\n");
  } catch {
    return "";
  }
}

function headersTextToJson(text: string): string | null {
  const lines = text.split("\n").filter((l) => l.trim());
  if (lines.length === 0) return null;
  const map: Record<string, string> = {};
  for (const line of lines) {
    const idx = line.indexOf(":");
    if (idx > 0) {
      map[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
    }
  }
  return Object.keys(map).length > 0 ? JSON.stringify(map) : null;
}

function parseStdioFields(serverUrl: string): { command: string; args: string; env: string } {
  try {
    const obj = JSON.parse(serverUrl);
    return {
      command: obj.command || "",
      args: Array.isArray(obj.args) ? obj.args.join("\n") : "",
      env: obj.env
        ? Object.entries(obj.env as Record<string, string>).map(([k, v]) => `${k}=${v}`).join("\n")
        : "",
    };
  } catch {
    return { command: "", args: "", env: "" };
  }
}

function stdioFieldsToJson(command: string, argsText: string, envText: string): string {
  const args = argsText.split("\n").map((a) => a.trim()).filter(Boolean);
  const envLines = envText.split("\n").map((l) => l.trim()).filter(Boolean);
  const env: Record<string, string> = {};
  for (const line of envLines) {
    const idx = line.indexOf("=");
    if (idx > 0) {
      env[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
    }
  }
  const obj: Record<string, unknown> = { command, args };
  if (Object.keys(env).length > 0) obj.env = env;
  return JSON.stringify(obj);
}

export default function McpServerEditor({ server, onSave, onCancel }: McpServerEditorProps) {
  const { createMcpServer, updateMcpServer, testMcpServerConfig } = useToolStore();

  const isEdit = server !== null;
  const initStdio = useMemo(
    () => (server?.transportType === "STDIO" ? parseStdioFields(server.serverUrl) : { command: "", args: "", env: "" }),
    [server],
  );

  const [name, setName] = useState(server?.name ?? "");
  const [transportType, setTransportType] = useState<McpServerCreate["transportType"]>(
    (server?.transportType as McpServerCreate["transportType"]) ?? "STREAMABLE_HTTP",
  );

  const isHttpTransport = transportType === "SSE" || transportType === "STREAMABLE_HTTP";

  // SSE / Streamable HTTP fields (shared)
  const [sseUrl, setSseUrl] = useState(
    server?.transportType === "SSE" || server?.transportType === "STREAMABLE_HTTP" ? server.serverUrl : "",
  );
  const [headersText, setHeadersText] = useState(parseHeadersText(server?.authConfig ?? null));

  // STDIO fields
  const [stdioCommand, setStdioCommand] = useState(initStdio.command);
  const [stdioArgs, setStdioArgs] = useState(initStdio.args);
  const [stdioEnv, setStdioEnv] = useState(initStdio.env);

  // Timeout
  const [requestTimeoutSeconds, setRequestTimeoutSeconds] = useState<string>(
    server?.requestTimeoutSeconds != null ? String(server.requestTimeoutSeconds) : "",
  );

  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<McpTestResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handleSave = useCallback(async () => {
    setError(null);
    if (!name.trim()) {
      setError("服务名称不能为空");
      return;
    }
    if (!/^[a-zA-Z][a-zA-Z0-9_-]{0,63}$/.test(name)) {
      setError("名称必须以字母开头，只能包含字母、数字、下划线和短横线");
      return;
    }

    let serverUrl: string;
    let authConfig: object | null = null;

    if (transportType === "SSE" || transportType === "STREAMABLE_HTTP") {
      if (!sseUrl.trim()) {
        setError("服务地址不能为空");
        return;
      }
      serverUrl = sseUrl.trim();
      const headersJson = headersTextToJson(headersText);
      if (headersJson) authConfig = JSON.parse(headersJson);
    } else {
      if (!stdioCommand.trim()) {
        setError("命令不能为空");
        return;
      }
      serverUrl = stdioFieldsToJson(stdioCommand.trim(), stdioArgs, stdioEnv);
    }

    const timeoutVal = requestTimeoutSeconds.trim() ? parseInt(requestTimeoutSeconds, 10) : null;
    if (timeoutVal !== null && (isNaN(timeoutVal) || timeoutVal < 1)) {
      setError("超时时间必须为正整数（秒）");
      return;
    }

    setSaving(true);
    try {
      if (isEdit) {
        await updateMcpServer(server.id, { name, serverUrl, transportType, authConfig, requestTimeoutSeconds: timeoutVal });
      } else {
        await createMcpServer({ name, serverUrl, transportType, authConfig, requestTimeoutSeconds: timeoutVal });
      }
      onSave();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  }, [name, transportType, sseUrl, headersText, stdioCommand, stdioArgs, stdioEnv, requestTimeoutSeconds, isEdit, server, createMcpServer, updateMcpServer, onSave]);

  const buildConfig = useCallback(() => {
    let serverUrlVal: string;
    let authConfigVal: object | null = null;
    if (transportType === "SSE" || transportType === "STREAMABLE_HTTP") {
      serverUrlVal = sseUrl.trim();
      const headersJson = headersTextToJson(headersText);
      if (headersJson) authConfigVal = JSON.parse(headersJson);
    } else {
      serverUrlVal = stdioFieldsToJson(stdioCommand.trim(), stdioArgs, stdioEnv);
    }
    return { name: name || "test", serverUrl: serverUrlVal, transportType, authConfig: authConfigVal };
  }, [name, transportType, sseUrl, headersText, stdioCommand, stdioArgs, stdioEnv]);

  const handleTest = useCallback(async () => {
    setTesting(true);
    setTestResult(null);
    try {
      const config = buildConfig();
      if (!config.serverUrl) {
        setTestResult({ success: false, serverName: config.name, toolsDiscovered: [], error: "服务地址不能为空" });
        return;
      }
      const result = await testMcpServerConfig(config);
      setTestResult(result);
    } catch (e) {
      setTestResult({
        success: false,
        serverName: name || "test",
        toolsDiscovered: [],
        error: e instanceof Error ? e.message : String(e),
      });
    } finally {
      setTesting(false);
    }
  }, [buildConfig, testMcpServerConfig, name]);

  const inputCls = "w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500/40";
  const textareaCls = `${inputCls} font-mono resize-y`;

  return (
    <div className="space-y-5">
      {/* Header */}
      <div className="flex items-center gap-2">
        <button onClick={onCancel} className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors">
          <ArrowLeft size={16} className="text-slate-500" />
        </button>
        <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-100">
          {isEdit ? "编辑 MCP 服务" : "添加 MCP 服务"}
        </h3>
      </div>

      {error && (
        <div className="flex items-start gap-2 text-sm text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 rounded-lg px-3 py-2">
          <AlertCircle size={14} className="mt-0.5 shrink-0" />
          {error}
        </div>
      )}

      {/* Name */}
      <div>
        <label className="block text-xs font-medium text-slate-600 dark:text-slate-300 mb-1">服务名称</label>
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="jina-mcp-server" className={inputCls} />
      </div>

      {/* Transport Type */}
      <div>
        <label className="block text-xs font-medium text-slate-600 dark:text-slate-300 mb-1">传输类型</label>
        <div className="flex gap-2">
          {TRANSPORT_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              onClick={() => setTransportType(opt.value)}
              className={`px-3 py-1.5 text-xs font-medium rounded-lg border transition-colors ${
                transportType === opt.value
                  ? "border-blue-600 bg-blue-50 dark:bg-blue-900/20 text-blue-700 dark:text-blue-400"
                  : "border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800/50"
              }`}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {/* SSE / Streamable HTTP Fields */}
      {isHttpTransport && (
        <>
          <div>
            <label className="block text-xs font-medium text-slate-600 dark:text-slate-300 mb-1">URL</label>
            <input
              value={sseUrl}
              onChange={(e) => setSseUrl(e.target.value)}
              placeholder={transportType === "STREAMABLE_HTTP" ? "https://mcp.jina.ai/v1" : "https://mcp.jina.ai"}
              className={inputCls}
            />
            <p className="text-[10px] text-slate-400 mt-1">
              {transportType === "STREAMABLE_HTTP"
                ? "填写完整 MCP 端点地址"
                : "填写服务根地址，传输层会自动追加 /sse 路径"}
            </p>
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 dark:text-slate-300 mb-1">
              Headers <span className="text-slate-400 font-normal">(每行一个，格式 Key: Value)</span>
            </label>
            <textarea
              value={headersText}
              onChange={(e) => setHeadersText(e.target.value)}
              rows={2}
              placeholder="Authorization: Bearer your-token"
              className={textareaCls}
            />
          </div>
        </>
      )}

      {/* STDIO Fields */}
      {transportType === "STDIO" && (
        <>
          <div>
            <label className="block text-xs font-medium text-slate-600 dark:text-slate-300 mb-1">Command</label>
            <input value={stdioCommand} onChange={(e) => setStdioCommand(e.target.value)} placeholder="npx" className={inputCls} />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 dark:text-slate-300 mb-1">
              Args <span className="text-slate-400 font-normal">(每行一个参数)</span>
            </label>
            <textarea
              value={stdioArgs}
              onChange={(e) => setStdioArgs(e.target.value)}
              rows={3}
              placeholder={"@playwright/mcp@latest"}
              className={textareaCls}
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-slate-600 dark:text-slate-300 mb-1">
              Env <span className="text-slate-400 font-normal">(可选，每行 KEY=VALUE)</span>
            </label>
            <textarea
              value={stdioEnv}
              onChange={(e) => setStdioEnv(e.target.value)}
              rows={2}
              placeholder="API_KEY=your-key"
              className={textareaCls}
            />
          </div>
        </>
      )}

      {/* Request Timeout */}
      <div>
        <label className="block text-xs font-medium text-slate-600 dark:text-slate-300 mb-1">
          请求超时 <span className="text-slate-400 font-normal">(秒，留空使用全局默认 120s)</span>
        </label>
        <input
          type="number"
          min="1"
          value={requestTimeoutSeconds}
          onChange={(e) => setRequestTimeoutSeconds(e.target.value)}
          placeholder="120"
          className={inputCls}
          style={{ maxWidth: 160 }}
        />
      </div>

      {/* Test Connection */}
      {(
        <div className="space-y-2">
          <button
            onClick={handleTest}
            disabled={testing}
            className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-amber-700 dark:text-amber-400 bg-amber-50 dark:bg-amber-900/20 rounded-lg hover:bg-amber-100 dark:hover:bg-amber-900/30 disabled:opacity-50 transition-colors"
          >
            {testing ? <Loader2 size={14} className="animate-spin" /> : <Zap size={14} />}
            测试连接
          </button>

          {testResult && (
            <div className={`rounded-lg border px-3 py-2 text-xs ${
              testResult.success
                ? "border-green-200 dark:border-green-800 bg-green-50 dark:bg-green-900/20"
                : "border-red-200 dark:border-red-800 bg-red-50 dark:bg-red-900/20"
            }`}>
              <div className="flex items-center gap-1.5 mb-1">
                {testResult.success ? (
                  <Check size={12} className="text-green-600" />
                ) : (
                  <AlertCircle size={12} className="text-red-600" />
                )}
                <span className={`font-medium ${testResult.success ? "text-green-700 dark:text-green-400" : "text-red-700 dark:text-red-400"}`}>
                  {testResult.success ? "连接成功" : "连接失败"}
                </span>
              </div>
              {testResult.success && testResult.toolsDiscovered.length > 0 && (
                <div className="mt-1.5 space-y-1">
                  <p className="text-slate-500 dark:text-slate-400">
                    发现 {testResult.toolsDiscovered.length} 个工具:
                  </p>
                  <div className="max-h-40 overflow-y-auto space-y-0.5">
                    {testResult.toolsDiscovered.map((t) => (
                      <div key={t.name} className="flex items-start gap-2 py-0.5">
                        <span className="font-mono text-slate-700 dark:text-slate-300 shrink-0">{t.name}</span>
                        <span className="text-slate-400 dark:text-slate-500 truncate">{t.description}</span>
                      </div>
                    ))}
                  </div>
                </div>
              )}
              {testResult.error && (
                <p className="text-red-600 dark:text-red-400 mt-1">{testResult.error}</p>
              )}
            </div>
          )}
        </div>
      )}

      {/* Actions */}
      <div className="flex justify-end gap-2 pt-2 border-t border-slate-100 dark:border-slate-800">
        <button onClick={onCancel} className="px-4 py-2 text-sm text-slate-600 dark:text-slate-300 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors">
          取消
        </button>
        <button
          onClick={handleSave}
          disabled={saving}
          className="flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
        >
          {saving && <Loader2 size={14} className="animate-spin" />}
          {isEdit ? "更新" : "添加"}
        </button>
      </div>
    </div>
  );
}
