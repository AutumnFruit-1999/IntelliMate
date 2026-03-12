import { useState, useCallback, useEffect } from "react";
import { Loader2, Check, Wifi } from "lucide-react";
import { useModelStore } from "../stores/modelStore";
import type { ModelProviderDto } from "../lib/api";

const PROVIDER_TYPES = [
  { value: "DASHSCOPE", label: "DashScope (阿里通义)" },
  { value: "OPENAI_COMPATIBLE", label: "OpenAI Compatible" },
  { value: "ANTHROPIC", label: "Anthropic (Claude)" },
];

interface ProviderEditorProps {
  provider: ModelProviderDto | null;
  isNew?: boolean;
  onSaved?: () => void;
  onCancel?: () => void;
}

export default function ProviderEditor({ provider, isNew, onSaved, onCancel }: ProviderEditorProps) {
  const [name, setName] = useState("");
  const [type, setType] = useState("DASHSCOPE");
  const [baseUrl, setBaseUrl] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ success: boolean; error?: string } | null>(null);
  const [error, setError] = useState<string | null>(null);

  const addProvider = useModelStore((s) => s.addProvider);
  const editProvider = useModelStore((s) => s.editProvider);
  const testProvider = useModelStore((s) => s.testProvider);

  useEffect(() => {
    if (provider) {
      setName(provider.name);
      setType(provider.type);
      setBaseUrl(provider.baseUrl ?? "");
      setApiKey("");
    } else {
      setName("");
      setType("DASHSCOPE");
      setBaseUrl("");
      setApiKey("");
    }
    setError(null);
    setTestResult(null);
  }, [provider]);

  const handleSave = useCallback(async () => {
    if (!name.trim()) { setError("名称不能为空"); return; }
    setSaving(true);
    setError(null);
    try {
      if (isNew || !provider) {
        if (!apiKey.trim()) { setError("API Key 不能为空"); setSaving(false); return; }
        await addProvider({ name: name.trim(), type, baseUrl: baseUrl.trim() || null, apiKey: apiKey.trim() });
      } else {
        const data: Record<string, unknown> = { name: name.trim(), type, baseUrl: baseUrl.trim() || null };
        if (apiKey.trim()) data.apiKey = apiKey.trim();
        await editProvider(provider.id, data);
      }
      onSaved?.();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  }, [name, type, baseUrl, apiKey, isNew, provider, addProvider, editProvider, onSaved]);

  const handleTest = useCallback(async () => {
    if (!provider) return;
    setTesting(true);
    setTestResult(null);
    try {
      const result = await testProvider(provider.id);
      setTestResult(result);
    } catch (e) {
      setTestResult({ success: false, error: e instanceof Error ? e.message : String(e) });
    } finally {
      setTesting(false);
    }
  }, [provider, testProvider]);

  return (
    <div className="space-y-4">
      <div>
        <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">厂商名称</label>
        <input
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="例：阿里 DashScope"
          className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500/40"
        />
      </div>

      <div>
        <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">类型</label>
        <select
          value={type}
          onChange={(e) => setType(e.target.value)}
          className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500/40"
        >
          {PROVIDER_TYPES.map((pt) => (
            <option key={pt.value} value={pt.value}>{pt.label}</option>
          ))}
        </select>
      </div>

      <div>
        <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">
          API 地址 <span className="text-slate-400 font-normal">(留空使用默认)</span>
        </label>
        <input
          type="text"
          value={baseUrl}
          onChange={(e) => setBaseUrl(e.target.value)}
          placeholder={type === "OPENAI_COMPATIBLE" ? "https://api.openai.com" : ""}
          className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500/40"
        />
      </div>

      <div>
        <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">
          API Key
          {provider && !isNew && (
            <span className="text-slate-400 font-normal ml-1">(当前: {provider.apiKeyMasked}，留空不修改)</span>
          )}
        </label>
        <input
          type="password"
          value={apiKey}
          onChange={(e) => setApiKey(e.target.value)}
          placeholder={isNew ? "sk-..." : "留空不修改"}
          className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500/40"
        />
      </div>

      {error && <p className="text-xs text-red-500">{error}</p>}

      {testResult && (
        <div className={`text-xs px-3 py-2 rounded-lg ${testResult.success ? "bg-green-50 dark:bg-green-900/20 text-green-700 dark:text-green-400" : "bg-red-50 dark:bg-red-900/20 text-red-600 dark:text-red-400"}`}>
          {testResult.success ? "连接成功" : `测试失败: ${testResult.error}`}
        </div>
      )}

      <div className="flex gap-2 pt-2">
        {onCancel && (
          <button onClick={onCancel} className="px-3 py-1.5 text-xs text-slate-500 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors">
            取消
          </button>
        )}
        {provider && !isNew && (
          <button
            onClick={handleTest}
            disabled={testing}
            className="flex items-center gap-1 px-3 py-1.5 text-xs text-slate-600 dark:text-slate-300 rounded-lg border border-slate-200 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors disabled:opacity-50"
          >
            {testing ? <Loader2 size={12} className="animate-spin" /> : <Wifi size={12} />}
            测试连接
          </button>
        )}
        <button
          onClick={handleSave}
          disabled={saving}
          className="flex items-center gap-1 px-4 py-1.5 text-xs font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors ml-auto"
        >
          {saving ? <Loader2 size={12} className="animate-spin" /> : <Check size={12} />}
          保存
        </button>
      </div>
    </div>
  );
}
