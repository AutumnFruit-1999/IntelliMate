import { useState, useEffect, useCallback } from "react";
import { X, Copy, Check, Loader2 } from "lucide-react";
import { useChannelStore } from "../stores/channelStore";
import { useAgentStore } from "../stores/agentStore";
import { fetchChannel } from "../lib/channelApi";
import { apiUrl } from "../lib/httpClient";

interface ChannelConfigModalProps {
  channelId: string | null;
  onClose: () => void;
}

const AVAILABLE_CHANNELS = [
  { id: "feishu", name: "飞书", icon: "🔷" },
  { id: "dingtalk", name: "钉钉", icon: "🔵", subtitle: "Webhook" },
  { id: "dingtalk-stream", name: "钉钉", icon: "🔵", subtitle: "Stream" },
  { id: "wechat", name: "微信", icon: "🟢" },
] as const;

const SETUP_GUIDES: Record<string, string> = {
  feishu:
    "在飞书开放平台创建企业自建应用，开启机器人能力，将下方 Webhook URL 填入「事件订阅」请求地址，并填写相同的 Verification Token 与 Encrypt Key。",
  dingtalk:
    "在钉钉开放平台创建应用，配置机器人，将 Webhook URL 填入回调地址，并配置 App Key、App Secret 与签名密钥。",
  "dingtalk-stream":
    "在钉钉开放平台创建应用，选择 Stream 模式（推荐），无需公网 IP 和回调地址，只需填写 App Key 和 App Secret 即可。",
  wechat:
    "在微信公众平台或企业微信管理后台配置服务器 URL，将 Webhook URL 填入，Token 与 Encoding AES Key 需与下方配置一致。",
};

function configToStrings(config: Record<string, unknown>): Record<string, string> {
  const result: Record<string, string> = {};
  for (const [k, v] of Object.entries(config)) {
    if (v != null && typeof v !== "object") {
      result[k] = String(v);
    }
  }
  return result;
}

export default function ChannelConfigModal({ channelId, onClose }: ChannelConfigModalProps) {
  const createChannel = useChannelStore((s) => s.createChannel);
  const updateChannel = useChannelStore((s) => s.updateChannel);
  const deleteChannel = useChannelStore((s) => s.deleteChannel);
  const agents = useAgentStore((s) => s.agents);

  const isEdit = channelId !== null;

  const [selectedType, setSelectedType] = useState(channelId || "");
  const [enabled, setEnabled] = useState(true);
  const [config, setConfig] = useState<Record<string, string>>({});
  const [loading, setLoading] = useState(false);
  const [fetching, setFetching] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    if (isEdit && channelId) {
      setFetching(true);
      setError(null);
      fetchChannel(channelId)
        .then((info) => {
          setSelectedType(info.channelId);
          setEnabled(info.enabled);
          setConfig(configToStrings(info.config ?? {}));
        })
        .catch((e) => {
          setError(e instanceof Error ? e.message : String(e));
        })
        .finally(() => setFetching(false));
    } else {
      setSelectedType("");
      setEnabled(true);
      setConfig({});
    }
  }, [channelId, isEdit]);

  const webhookUrl = selectedType ? apiUrl(`/webhook/${selectedType}`) : "";

  const handleCopyWebhook = useCallback(async () => {
    if (!webhookUrl) return;
    try {
      await navigator.clipboard.writeText(webhookUrl);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      /* clipboard may be unavailable */
    }
  }, [webhookUrl]);

  const handleSave = async () => {
    if (!selectedType) return;
    setLoading(true);
    setError(null);
    try {
      const configPayload: Record<string, unknown> = { ...config };
      if (isEdit && channelId) {
        await updateChannel(channelId, { enabled, config: configPayload });
      } else {
        await createChannel({ channelId: selectedType, enabled, config: configPayload });
      }
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async () => {
    if (!channelId || !window.confirm("确定删除该渠道配置？")) return;
    setLoading(true);
    setError(null);
    try {
      await deleteChannel(channelId);
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setLoading(false);
    }
  };

  const platformName =
    AVAILABLE_CHANNELS.find((c) => c.id === selectedType)?.name ??
    (selectedType === "feishu" ? "飞书" : selectedType === "dingtalk" ? "钉钉" : selectedType === "wechat" ? "微信" : "");

  return (
    <div
      className="fixed inset-0 bg-black/50 flex items-center justify-center z-50 p-4"
      onClick={onClose}
    >
      <div
        className="bg-white dark:bg-slate-900 rounded-xl w-full max-w-lg max-h-[85vh] overflow-hidden flex flex-col shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-200 dark:border-slate-700">
          <h2 className="text-lg font-semibold text-slate-800 dark:text-slate-100">
            {isEdit ? "编辑渠道" : "添加渠道"}
          </h2>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          >
            <X size={18} className="text-slate-500" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-6 py-4">
          {fetching ? (
            <div className="flex items-center justify-center py-12">
              <Loader2 size={24} className="animate-spin text-slate-400" />
            </div>
          ) : (
            <>
              {error && (
                <div className="mb-4 px-3 py-2 text-sm text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg">
                  {error}
                </div>
              )}

              {!isEdit && (
                <div className="mb-5">
                  <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-2">
                    选择平台
                  </label>
                  <div className="grid grid-cols-4 gap-2">
                    {AVAILABLE_CHANNELS.map((ch) => (
                      <button
                        key={ch.id}
                        type="button"
                        onClick={() => setSelectedType(ch.id)}
                        className={`p-3 rounded-lg border text-center transition-colors ${
                          selectedType === ch.id
                            ? "border-blue-500 bg-blue-50 dark:bg-blue-900/20"
                            : "border-slate-200 dark:border-slate-700 hover:border-slate-300 dark:hover:border-slate-600"
                        }`}
                      >
                        <div className="text-2xl">{ch.icon}</div>
                        <div className="text-sm mt-1 text-slate-700 dark:text-slate-300">{ch.name}</div>
                        {"subtitle" in ch && ch.subtitle && (
                          <div className="text-xs text-slate-500 dark:text-slate-400">{ch.subtitle}</div>
                        )}
                      </button>
                    ))}
                  </div>
                </div>
              )}

              {selectedType && (
                <>
                  <div className="mb-4 p-3 bg-slate-50 dark:bg-slate-800/80 rounded-lg border border-slate-200 dark:border-slate-700">
                    {selectedType !== "dingtalk-stream" && (
                      <>
                        <div className="flex items-center justify-between mb-1">
                          <span className="text-sm font-medium text-slate-700 dark:text-slate-300">
                            Webhook URL
                          </span>
                          <button
                            type="button"
                            onClick={handleCopyWebhook}
                            className="flex items-center gap-1 text-xs text-blue-600 dark:text-blue-400 hover:underline"
                          >
                            {copied ? <Check size={12} /> : <Copy size={12} />}
                            {copied ? "已复制" : "复制"}
                          </button>
                        </div>
                        <code className="block text-xs break-all text-slate-600 dark:text-slate-400 font-mono">
                          {webhookUrl}
                        </code>
                      </>
                    )}
                    <p className="text-xs text-slate-500 dark:text-slate-500 mt-2 leading-relaxed">
                      {SETUP_GUIDES[selectedType] ??
                        `请将此 URL 配置到${platformName || "对应"}平台的事件订阅 / 回调地址。`}
                    </p>
                  </div>

                  <div className="space-y-3 mb-4">
                    {selectedType === "feishu" && (
                      <>
                        <InputField label="App ID" value={config.appId ?? ""} onChange={(v) => setConfig({ ...config, appId: v })} />
                        <InputField label="App Secret" value={config.appSecret ?? ""} onChange={(v) => setConfig({ ...config, appSecret: v })} type="password" />
                        <InputField label="Encrypt Key" value={config.encryptKey ?? ""} onChange={(v) => setConfig({ ...config, encryptKey: v })} />
                        <InputField label="Verification Token" value={config.verificationToken ?? ""} onChange={(v) => setConfig({ ...config, verificationToken: v })} />
                      </>
                    )}
                    {selectedType === "dingtalk" && (
                      <>
                        <InputField label="App Key" value={config.appKey ?? ""} onChange={(v) => setConfig({ ...config, appKey: v })} />
                        <InputField label="App Secret" value={config.appSecret ?? ""} onChange={(v) => setConfig({ ...config, appSecret: v })} type="password" />
                        <InputField label="签名密钥" value={config.signSecret ?? ""} onChange={(v) => setConfig({ ...config, signSecret: v })} />
                      </>
                    )}
                    {selectedType === "dingtalk-stream" && (
                      <>
                        <InputField label="App Key" value={config.appKey ?? ""} onChange={(v) => setConfig({ ...config, appKey: v })} />
                        <InputField label="App Secret" value={config.appSecret ?? ""} onChange={(v) => setConfig({ ...config, appSecret: v })} type="password" />
                      </>
                    )}
                    {selectedType === "wechat" && (
                      <>
                        <InputField label="App ID" value={config.appId ?? ""} onChange={(v) => setConfig({ ...config, appId: v })} />
                        <InputField label="App Secret" value={config.appSecret ?? ""} onChange={(v) => setConfig({ ...config, appSecret: v })} type="password" />
                        <InputField label="Token" value={config.token ?? ""} onChange={(v) => setConfig({ ...config, token: v })} />
                        <InputField label="Encoding AES Key" value={config.encodingAesKey ?? ""} onChange={(v) => setConfig({ ...config, encodingAesKey: v })} />
                      </>
                    )}
                    <div>
                      <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">
                        默认 Agent
                      </label>
                      <select
                        value={config.defaultAgent ?? ""}
                        onChange={(e) => setConfig({ ...config, defaultAgent: e.target.value })}
                        className="w-full px-3 py-2 border border-slate-200 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-800 text-sm text-slate-800 dark:text-slate-200"
                      >
                        <option value="">留空使用 default</option>
                        {agents.map((a) => (
                          <option key={a.name} value={a.name}>
                            {a.name}
                          </option>
                        ))}
                      </select>
                    </div>
                  </div>

                  <label className="flex items-center gap-2 mb-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={enabled}
                      onChange={(e) => setEnabled(e.target.checked)}
                      className="rounded border-slate-300 dark:border-slate-600"
                    />
                    <span className="text-sm text-slate-700 dark:text-slate-300">
                      启用（启动时自动连接）
                    </span>
                  </label>
                </>
              )}
            </>
          )}
        </div>

        <div className="flex items-center justify-between px-6 py-4 border-t border-slate-200 dark:border-slate-700 bg-slate-50/50 dark:bg-slate-800/30">
          <div>
            {isEdit && (
              <button
                type="button"
                onClick={handleDelete}
                disabled={loading}
                className="px-4 py-2 text-sm text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20 rounded-lg transition-colors disabled:opacity-50"
              >
                删除
              </button>
            )}
          </div>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-sm border border-slate-200 dark:border-slate-600 rounded-lg text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
            >
              取消
            </button>
            <button
              type="button"
              onClick={handleSave}
              disabled={!selectedType || loading || fetching}
              className="px-4 py-2 text-sm bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
            >
              {loading ? "保存中…" : "保存"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

function InputField({
  label,
  value,
  onChange,
  type = "text",
  placeholder = "",
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
  type?: string;
  placeholder?: string;
}) {
  return (
    <div>
      <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">
        {label}
      </label>
      <input
        type={type}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        className="w-full px-3 py-2 border border-slate-200 dark:border-slate-600 rounded-lg bg-white dark:bg-slate-800 text-sm text-slate-800 dark:text-slate-200"
      />
    </div>
  );
}
