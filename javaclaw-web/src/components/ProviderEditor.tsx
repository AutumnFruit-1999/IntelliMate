import { useState, useCallback, useEffect } from "react";
import { Loader2, Check } from "lucide-react";
import { useModelStore } from "../stores/modelStore";
import type { ModelProviderDto } from "../lib/api";

const PROVIDER_TYPES = [
  { value: "DASHSCOPE", label: "DashScope (阿里通义)" },
  { value: "OPENAI_COMPATIBLE", label: "OpenAI Compatible" },
  { value: "ANTHROPIC", label: "Anthropic (Claude)" },
  { value: "DEEPSEEK", label: "DeepSeek" },
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
  const [thinkingMode, setThinkingMode] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const addProvider = useModelStore((s) => s.addProvider);
  const editProvider = useModelStore((s) => s.editProvider);

  useEffect(() => {
    if (provider) {
      setName(provider.name);
      setType(provider.type);
      setBaseUrl(provider.baseUrl ?? "");
      setApiKey("");
      setThinkingMode(provider.thinkingMode);
    } else {
      setName("");
      setType("DASHSCOPE");
      setBaseUrl("");
      setApiKey("");
      setThinkingMode(null);
    }
    setError(null);
  }, [provider]);

  const handleSave = useCallback(async () => {
    if (!name.trim()) { setError("名称不能为空"); return; }
    setSaving(true);
    setError(null);
    try {
      if (isNew || !provider) {
        if (!apiKey.trim()) { setError("API Key 不能为空"); setSaving(false); return; }
        await addProvider({
          name: name.trim(), type, baseUrl: baseUrl.trim() || null,
          apiKey: apiKey.trim(), thinkingMode: type === "DEEPSEEK" ? thinkingMode : null,
        });
      } else {
        const data: Record<string, unknown> = { name: name.trim(), type, baseUrl: baseUrl.trim() || null };
        if (apiKey.trim()) data.apiKey = apiKey.trim();
        data.thinkingMode = type === "DEEPSEEK" ? thinkingMode : null;
        await editProvider(provider.id, data);
      }
      onSaved?.();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  }, [name, type, baseUrl, apiKey, thinkingMode, isNew, provider, addProvider, editProvider, onSaved]);

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
          placeholder={type === "OPENAI_COMPATIBLE" ? "https://api.openai.com" : type === "DEEPSEEK" ? "https://api.deepseek.com" : ""}
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

      {type === "DEEPSEEK" && (
        <div>
          <label className="block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1">
            Thinking 模式
            <span className="text-slate-400 font-normal ml-1">(深度思考)</span>
          </label>
          <select
            value={thinkingMode ?? "disabled"}
            onChange={(e) => setThinkingMode(e.target.value)}
            className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500/40"
          >
            <option value="disabled">关闭 (推荐，避免 tool-call 兼容性问题)</option>
            <option value="enabled">开启 (需要 Spring AI 2.0+ 支持)</option>
          </select>
        </div>
      )}

      {error && <p className="text-xs text-red-500">{error}</p>}

      <div className="flex gap-2 pt-2">
        {onCancel && (
          <button onClick={onCancel} className="px-3 py-1.5 text-xs text-slate-500 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-700 transition-colors">
            取消
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
