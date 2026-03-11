import { useState, useCallback } from "react";
import { X, Plus, Trash2, Play, Loader2, ChevronDown, ChevronRight } from "lucide-react";
import { useToolStore } from "../stores/toolStore";
import type { ToolDefinition, ToolDefinitionCreate, ToolTestResult } from "../lib/api";

interface ParameterDef {
  name: string;
  type: string;
  description: string;
  required: boolean;
}

interface HttpConfig {
  url: string;
  method: string;
  headers: Record<string, string>;
  bodyTemplate: string;
  responseExtract: string;
}

interface CustomToolEditorProps {
  tool: ToolDefinition | null;
  onSave: () => void;
  onCancel: () => void;
}

function parseParameters(schema: string | null): ParameterDef[] {
  if (!schema) return [];
  try {
    const obj = typeof schema === "string" ? JSON.parse(schema) : schema;
    const props = obj.properties || {};
    const required = new Set(obj.required || []);
    return Object.entries(props).map(([name, val]) => {
      const v = val as Record<string, string>;
      return { name, type: v.type || "string", description: v.description || "", required: required.has(name) };
    });
  } catch {
    return [];
  }
}

function buildSchema(params: ParameterDef[]): object {
  const properties: Record<string, object> = {};
  const required: string[] = [];
  for (const p of params) {
    properties[p.name] = { type: p.type, description: p.description };
    if (p.required) required.push(p.name);
  }
  return { type: "object", properties, required };
}

function parseHttpConfig(config: string | null): HttpConfig {
  if (!config) return { url: "", method: "GET", headers: {}, bodyTemplate: "", responseExtract: "" };
  try {
    const obj = typeof config === "string" ? JSON.parse(config) : config;
    return {
      url: obj.url || "",
      method: obj.method || "GET",
      headers: obj.headers || {},
      bodyTemplate: obj.bodyTemplate || "",
      responseExtract: obj.responseExtract || "",
    };
  } catch {
    return { url: "", method: "GET", headers: {}, bodyTemplate: "", responseExtract: "" };
  }
}

export default function CustomToolEditor({ tool, onSave, onCancel }: CustomToolEditorProps) {
  const { createDefinition, updateDefinition, testDefinition } = useToolStore();

  const [name, setName] = useState(tool?.name ?? "");
  const [description, setDescription] = useState(tool?.description ?? "");
  const [timeoutSeconds, setTimeoutSeconds] = useState(tool?.timeoutSeconds ?? 30);
  const [httpConfig, setHttpConfig] = useState<HttpConfig>(() => parseHttpConfig(tool?.executionConfig ?? null));
  const [parameters, setParameters] = useState<ParameterDef[]>(() => parseParameters(tool?.parametersSchema ?? null));
  const [headersText, setHeadersText] = useState(() => {
    const h = parseHttpConfig(tool?.executionConfig ?? null).headers;
    return Object.entries(h).map(([k, v]) => `${k}: ${v}`).join("\n");
  });

  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [testExpanded, setTestExpanded] = useState(false);
  const [testArgs, setTestArgs] = useState("{}");
  const [testResult, setTestResult] = useState<ToolTestResult | null>(null);
  const [testing, setTesting] = useState(false);

  const handleSave = useCallback(async () => {
    setError(null);
    if (!name.trim()) { setError("名称不能为空"); return; }
    if (!httpConfig.url.trim()) { setError("URL 不能为空"); return; }

    setSaving(true);
    try {
      const headers: Record<string, string> = {};
      for (const line of headersText.split("\n")) {
        const idx = line.indexOf(":");
        if (idx > 0) headers[line.slice(0, idx).trim()] = line.slice(idx + 1).trim();
      }
      const execConfig = {
        url: httpConfig.url,
        method: httpConfig.method,
        headers,
        bodyTemplate: httpConfig.bodyTemplate || undefined,
        responseExtract: httpConfig.responseExtract || undefined,
      };
      const data: ToolDefinitionCreate = {
        name: name.trim(),
        type: "HTTP_API",
        description: description.trim() || undefined,
        parametersSchema: buildSchema(parameters),
        executionConfig: execConfig,
        timeoutSeconds,
      };
      if (tool) {
        await updateDefinition(tool.id, data);
      } else {
        await createDefinition(data);
      }
      onSave();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  }, [name, description, httpConfig, headersText, parameters, timeoutSeconds, tool, createDefinition, updateDefinition, onSave]);

  const handleTest = useCallback(async () => {
    if (!tool) return;
    setTesting(true);
    setTestResult(null);
    try {
      const args = JSON.parse(testArgs);
      const result = await testDefinition(tool.id, args);
      setTestResult(result);
    } catch (e) {
      setTestResult({ success: false, error: e instanceof Error ? e.message : String(e), durationMs: 0 });
    } finally {
      setTesting(false);
    }
  }, [tool, testArgs, testDefinition]);

  const addParameter = useCallback(() => {
    setParameters((prev) => [...prev, { name: "", type: "string", description: "", required: false }]);
  }, []);

  const removeParameter = useCallback((idx: number) => {
    setParameters((prev) => prev.filter((_, i) => i !== idx));
  }, []);

  const updateParameter = useCallback((idx: number, field: keyof ParameterDef, value: string | boolean) => {
    setParameters((prev) => prev.map((p, i) => i === idx ? { ...p, [field]: value } : p));
  }, []);

  const inputCls = "w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-2 focus:ring-blue-500/40";
  const labelCls = "block text-xs font-medium text-slate-600 dark:text-slate-400 mb-1";

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <h3 className="text-base font-semibold text-slate-800 dark:text-slate-100">
          {tool ? "编辑工具" : "创建 HTTP 工具"}
        </h3>
        <button onClick={onCancel} className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors">
          <X size={16} className="text-slate-500" />
        </button>
      </div>

      {error && (
        <div className="text-sm text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 rounded-lg px-3 py-2">
          {error}
        </div>
      )}

      {/* Basic Info */}
      <div className="grid grid-cols-2 gap-4">
        <div>
          <label className={labelCls}>工具名称 *</label>
          <input className={inputCls} value={name} onChange={(e) => setName(e.target.value)}
            placeholder="例: translate_text" disabled={!!tool} />
        </div>
        <div>
          <label className={labelCls}>超时 (秒)</label>
          <input className={inputCls} type="number" min={1} max={120}
            value={timeoutSeconds} onChange={(e) => setTimeoutSeconds(Number(e.target.value))} />
        </div>
      </div>

      <div>
        <label className={labelCls}>描述</label>
        <input className={inputCls} value={description} onChange={(e) => setDescription(e.target.value)}
          placeholder="LLM 可见的工具描述" />
      </div>

      {/* HTTP Config */}
      <div className="space-y-3">
        <h4 className="text-sm font-semibold text-slate-700 dark:text-slate-200">HTTP 配置</h4>
        <div className="grid grid-cols-4 gap-3">
          <div className="col-span-1">
            <label className={labelCls}>Method</label>
            <select className={inputCls} value={httpConfig.method}
              onChange={(e) => setHttpConfig((c) => ({ ...c, method: e.target.value }))}>
              <option>GET</option>
              <option>POST</option>
              <option>PUT</option>
              <option>PATCH</option>
              <option>DELETE</option>
            </select>
          </div>
          <div className="col-span-3">
            <label className={labelCls}>URL *</label>
            <input className={inputCls} value={httpConfig.url}
              onChange={(e) => setHttpConfig((c) => ({ ...c, url: e.target.value }))}
              placeholder="https://api.example.com/endpoint" />
          </div>
        </div>

        <div>
          <label className={labelCls}>Headers (每行一个 Key: Value)</label>
          <textarea className={inputCls + " font-mono text-xs"} rows={3}
            value={headersText} onChange={(e) => setHeadersText(e.target.value)}
            placeholder={"Content-Type: application/json\nAuthorization: Bearer ${env:API_KEY}"} />
        </div>

        <div>
          <label className={labelCls}>Body 模板</label>
          <textarea className={inputCls + " font-mono text-xs"} rows={4}
            value={httpConfig.bodyTemplate}
            onChange={(e) => setHttpConfig((c) => ({ ...c, bodyTemplate: e.target.value }))}
            placeholder={'{"text":"${text}","target":"${targetLang}"}'} />
        </div>

        <div>
          <label className={labelCls}>响应提取 (JSONPath, 可选)</label>
          <input className={inputCls + " font-mono text-xs"} value={httpConfig.responseExtract}
            onChange={(e) => setHttpConfig((c) => ({ ...c, responseExtract: e.target.value }))}
            placeholder="$.result.translation" />
        </div>
      </div>

      {/* Parameters */}
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <h4 className="text-sm font-semibold text-slate-700 dark:text-slate-200">参数定义</h4>
          <button onClick={addParameter}
            className="flex items-center gap-1 text-xs text-blue-600 dark:text-blue-400 hover:text-blue-700">
            <Plus size={14} /> 添加参数
          </button>
        </div>
        {parameters.length === 0 ? (
          <p className="text-xs text-slate-400">暂无参数</p>
        ) : (
          <div className="space-y-2">
            {parameters.map((p, idx) => (
              <div key={idx} className="grid grid-cols-12 gap-2 items-center">
                <input className={inputCls + " col-span-3"} placeholder="名称" value={p.name}
                  onChange={(e) => updateParameter(idx, "name", e.target.value)} />
                <select className={inputCls + " col-span-2"} value={p.type}
                  onChange={(e) => updateParameter(idx, "type", e.target.value)}>
                  <option value="string">string</option>
                  <option value="number">number</option>
                  <option value="boolean">boolean</option>
                  <option value="integer">integer</option>
                </select>
                <input className={inputCls + " col-span-4"} placeholder="描述" value={p.description}
                  onChange={(e) => updateParameter(idx, "description", e.target.value)} />
                <label className="col-span-2 flex items-center gap-1 text-xs text-slate-600 dark:text-slate-400">
                  <input type="checkbox" checked={p.required}
                    onChange={(e) => updateParameter(idx, "required", e.target.checked)}
                    className="h-3.5 w-3.5 rounded border-slate-300 text-blue-600" />
                  必填
                </label>
                <button onClick={() => removeParameter(idx)} className="col-span-1 p-1 text-slate-400 hover:text-red-500">
                  <Trash2 size={14} />
                </button>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Test Panel (only for existing tools) */}
      {tool && (
        <div className="rounded-lg border border-slate-200 dark:border-slate-700 overflow-hidden">
          <div className="flex items-center justify-between px-3 py-2 bg-slate-50 dark:bg-slate-800/50 cursor-pointer select-none"
            onClick={() => setTestExpanded(!testExpanded)}>
            <div className="flex items-center gap-2">
              {testExpanded ? <ChevronDown size={14} className="text-slate-400" /> : <ChevronRight size={14} className="text-slate-400" />}
              <span className="text-sm font-medium text-slate-700 dark:text-slate-200">测试执行</span>
            </div>
          </div>
          {testExpanded && (
            <div className="px-3 py-3 space-y-3">
              <div>
                <label className={labelCls}>测试参数 (JSON)</label>
                <textarea className={inputCls + " font-mono text-xs"} rows={3}
                  value={testArgs} onChange={(e) => setTestArgs(e.target.value)} />
              </div>
              <button onClick={handleTest} disabled={testing}
                className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-white bg-green-600 rounded-lg hover:bg-green-700 disabled:opacity-50 transition-colors">
                {testing ? <Loader2 size={12} className="animate-spin" /> : <Play size={12} />}
                执行测试
              </button>
              {testResult && (
                <div className={`rounded-lg px-3 py-2 text-xs font-mono break-all ${
                  testResult.success
                    ? "bg-green-50 dark:bg-green-900/20 text-green-800 dark:text-green-300"
                    : "bg-red-50 dark:bg-red-900/20 text-red-800 dark:text-red-300"
                }`}>
                  <div className="text-slate-500 dark:text-slate-400 mb-1">
                    {testResult.success ? "成功" : "失败"} · {testResult.durationMs}ms
                  </div>
                  <pre className="whitespace-pre-wrap">{testResult.success ? testResult.result : testResult.error}</pre>
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* Actions */}
      <div className="flex justify-end gap-2 pt-2 border-t border-slate-100 dark:border-slate-800">
        <button onClick={onCancel}
          className="px-4 py-2 text-sm text-slate-600 dark:text-slate-300 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors">
          取消
        </button>
        <button onClick={handleSave} disabled={saving}
          className="flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors">
          {saving && <Loader2 size={14} className="animate-spin" />}
          {tool ? "更新" : "创建"}
        </button>
      </div>
    </div>
  );
}
