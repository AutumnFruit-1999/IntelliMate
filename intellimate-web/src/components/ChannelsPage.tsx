import { useEffect, useState, useCallback } from "react";
import { ArrowLeft, Loader2, Plus, Link, Unlink, Copy, Check, Trash2 } from "lucide-react";
import { useChannelStore } from "../stores/channelStore";
import { useAuthStore } from "../stores/authStore";
import { generateBindingCode, listBoundIdentities, unbindIdentity, type ChannelIdentity } from "../lib/channelApi";
import ChannelConfigModal from "./ChannelConfigModal";

const CHANNEL_META: Record<string, { name: string; icon: string }> = {
  feishu: { name: "飞书", icon: "🔷" },
  dingtalk: { name: "钉钉", icon: "🔵" },
  "dingtalk-stream": { name: "钉钉", icon: "🔵" },
  wechat: { name: "微信", icon: "🟢" },
  webchat: { name: "Web", icon: "🌐" },
};

const STATUS_STYLES: Record<string, { dot: string; label: string }> = {
  CONNECTED: {
    dot: "bg-green-500",
    label: "已连接",
  },
  CONNECTING: {
    dot: "bg-yellow-500",
    label: "连接中",
  },
  DISCONNECTED: {
    dot: "bg-slate-400",
    label: "未连接",
  },
  RECONNECTING: {
    dot: "bg-yellow-500",
    label: "重连中",
  },
  ERROR: {
    dot: "bg-red-500",
    label: "错误",
  },
};

interface ChannelsPageProps {
  onBack: () => void;
}

export default function ChannelsPage({ onBack }: ChannelsPageProps) {
  const channels = useChannelStore((s) => s.channels);
  const loading = useChannelStore((s) => s.loading);
  const error = useChannelStore((s) => s.error);
  const fetchChannels = useChannelStore((s) => s.fetchChannels);
  const connectChannel = useChannelStore((s) => s.connectChannel);
  const disconnectChannel = useChannelStore((s) => s.disconnectChannel);
  const deleteChannel = useChannelStore((s) => s.deleteChannel);

  const [showModal, setShowModal] = useState(false);
  const [editingChannel, setEditingChannel] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  const unifiedUserId = useAuthStore((s) => s.unifiedUserId);
  const [identities, setIdentities] = useState<ChannelIdentity[]>([]);
  const [bindingCode, setBindingCode] = useState<string | null>(null);
  const [codeExpiresIn, setCodeExpiresIn] = useState(0);
  const [bindingLoading, setBindingLoading] = useState(false);
  const [codeCopied, setCodeCopied] = useState(false);

  useEffect(() => {
    fetchChannels();
  }, [fetchChannels]);

  const loadIdentities = useCallback(() => {
    if (!unifiedUserId) return;
    listBoundIdentities(unifiedUserId).then(setIdentities).catch(() => {});
  }, [unifiedUserId]);

  useEffect(() => { loadIdentities(); }, [loadIdentities]);

  useEffect(() => {
    if (codeExpiresIn <= 0 || !bindingCode) return;
    const timer = setInterval(() => {
      setCodeExpiresIn((prev) => {
        if (prev <= 1) {
          clearInterval(timer);
          setBindingCode(null);
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
    return () => clearInterval(timer);
  }, [bindingCode]);

  useEffect(() => {
    const handler = () => {
      loadIdentities();
      setBindingCode(null);
    };
    window.addEventListener("binding-success", handler);
    return () => window.removeEventListener("binding-success", handler);
  }, [loadIdentities]);

  const handleGenerateCode = async () => {
    if (!unifiedUserId) return;
    setBindingLoading(true);
    try {
      const result = await generateBindingCode(unifiedUserId);
      setBindingCode(result.code);
      setCodeExpiresIn(result.expiresIn);
    } finally {
      setBindingLoading(false);
    }
  };

  const handleCopyCode = () => {
    if (bindingCode) {
      navigator.clipboard.writeText(bindingCode);
      setCodeCopied(true);
      setTimeout(() => setCodeCopied(false), 2000);
    }
  };

  const handleUnbind = async (identityId: number) => {
    await unbindIdentity(identityId);
    loadIdentities();
  };

  const displayChannels = channels.filter((c) => c.channelId !== "webchat");

  const handleConnectToggle = async (
    e: React.MouseEvent,
    channelId: string,
    isConnected: boolean,
  ) => {
    e.stopPropagation();
    setActionLoading(channelId);
    try {
      if (isConnected) {
        await disconnectChannel(channelId);
      } else {
        await connectChannel(channelId);
      }
    } finally {
      setActionLoading(null);
    }
  };

  return (
    <div className="flex flex-col flex-1 min-w-0 min-h-0 overflow-y-auto">
      <div className="px-6 py-5 border-b border-slate-200 dark:border-slate-700 flex items-center gap-3">
        <button
          onClick={onBack}
          className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
        >
          <ArrowLeft size={18} className="text-slate-500" />
        </button>
        <div className="flex-1 min-w-0">
          <h2 className="text-lg font-semibold text-slate-800 dark:text-slate-100">
            渠道管理
          </h2>
          <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
            接入飞书、钉钉、微信等即时通讯平台
          </p>
        </div>
        <button
          onClick={() => {
            setEditingChannel(null);
            setShowModal(true);
          }}
          className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors"
        >
          <Plus size={14} />
          添加渠道
        </button>
      </div>

      <div className="flex-1 p-6">
        {/* 身份绑定区域 */}
        {unifiedUserId && (
          <div className="mb-6 bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl p-5">
            <div className="flex items-center gap-2 mb-4">
              <Link size={16} className="text-blue-500" />
              <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-100">
                跨渠道身份绑定
              </h3>
              <span className="text-[11px] text-slate-400 dark:text-slate-500">
                将钉钉/飞书账号与 Web 账号关联，实现对话共享
              </span>
            </div>

            <div className="flex gap-4 flex-wrap mb-4">
              <div className="flex-1 min-w-[260px]">
                <div className="text-xs text-slate-500 dark:text-slate-400 mb-2 space-y-1">
                  <p className="font-medium">绑定步骤：</p>
                  <p>1. 点击「生成绑定码」获取 6 位数字码</p>
                  <p>2. 在钉钉/飞书中找到机器人，直接发送该绑定码</p>
                  <p>3. 收到「绑定成功」提示后，此页面自动更新</p>
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={handleGenerateCode}
                    disabled={bindingLoading}
                    className="px-3 py-1.5 text-xs font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
                  >
                    {bindingLoading ? "生成中…" : "生成绑定码"}
                  </button>
                  {bindingCode && (
                    <div className="flex items-center gap-2 px-3 py-1.5 bg-blue-50 dark:bg-blue-900/20 rounded-lg border border-blue-200 dark:border-blue-800">
                      <span className="text-lg font-mono font-bold text-blue-600 dark:text-blue-400 tracking-widest">
                        {bindingCode}
                      </span>
                      <button onClick={handleCopyCode} className="p-0.5 text-blue-500 hover:text-blue-700">
                        {codeCopied ? <Check size={14} /> : <Copy size={14} />}
                      </button>
                      <span className="text-[10px] text-blue-400">
                        {codeExpiresIn > 60
                          ? `${Math.floor(codeExpiresIn / 60)}分${codeExpiresIn % 60}秒`
                          : `${codeExpiresIn}秒`}
                      </span>
                    </div>
                  )}
                </div>
              </div>
            </div>

            {identities.filter(i => i.channelId !== "webchat").length > 0 && (
              <div>
                <p className="text-xs font-medium text-slate-600 dark:text-slate-300 mb-2">已绑定身份</p>
                <div className="flex flex-wrap gap-2">
                  {identities.filter(i => i.channelId !== "webchat").map(identity => {
                    const meta = CHANNEL_META[identity.channelId] ?? { name: identity.channelId, icon: "📡" };
                    return (
                      <div key={identity.id} className="flex items-center gap-2 px-3 py-1.5 bg-slate-50 dark:bg-slate-700/50 rounded-lg border border-slate-200 dark:border-slate-600">
                        <span className="text-sm">{meta.icon}</span>
                        <span className="text-xs text-slate-700 dark:text-slate-300">{meta.name}</span>
                        {identity.externalName && (
                          <span className="text-[11px] text-slate-400">({identity.externalName})</span>
                        )}
                        <button
                          onClick={() => handleUnbind(identity.id)}
                          className="p-0.5 text-slate-400 hover:text-red-500 transition-colors"
                          title="解绑"
                        >
                          <Unlink size={12} />
                        </button>
                      </div>
                    );
                  })}
                </div>
              </div>
            )}
          </div>
        )}

        {error && (
          <div className="mb-4 px-4 py-3 text-sm text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg">
            {error}
          </div>
        )}

        {loading && displayChannels.length === 0 ? (
          <div className="flex items-center justify-center py-24">
            <Loader2 size={28} className="animate-spin text-slate-400" />
          </div>
        ) : displayChannels.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-24 text-slate-400 dark:text-slate-500">
            <span className="text-4xl mb-3">📡</span>
            <p className="text-sm">暂无渠道配置</p>
            <p className="text-xs mt-1">点击右上角「添加渠道」开始接入</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {displayChannels.map((channel) => {
              const meta = CHANNEL_META[channel.channelId] ?? {
                name: channel.channelId,
                icon: "📡",
              };
              const statusStyle = STATUS_STYLES[channel.status] ?? STATUS_STYLES.DISCONNECTED;
              const isConnected = channel.status === "CONNECTED";
              const isBusy = actionLoading === channel.channelId;

              return (
                <div
                  key={channel.channelId}
                  onClick={() => {
                    setEditingChannel(channel.channelId);
                    setShowModal(true);
                  }}
                  className="group relative bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-xl p-5 cursor-pointer hover:border-blue-400 dark:hover:border-blue-500 hover:shadow-md transition-all"
                >
                  <div className="flex items-start justify-between mb-3">
                    <div className="flex items-center gap-2.5 min-w-0">
                      <span className="text-2xl shrink-0">{meta.icon}</span>
                      <div className="min-w-0">
                        <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-100 truncate">
                          {meta.name}
                        </h3>
                        <p className="text-[11px] text-slate-400 dark:text-slate-500 truncate">
                          {channel.channelId}
                        </p>
                      </div>
                    </div>
                    <div className="flex items-center gap-1.5 shrink-0 ml-2">
                      <span className={`w-2 h-2 rounded-full ${statusStyle.dot}`} />
                      <span className="text-[11px] text-slate-500 dark:text-slate-400">
                        {statusStyle.label}
                      </span>
                    </div>
                  </div>

                  <div className="flex items-center gap-2 mb-3">
                    <span
                      className={`px-2 py-0.5 text-[10px] font-medium rounded-full border ${
                        channel.enabled
                          ? "bg-green-50 dark:bg-green-900/20 text-green-600 dark:text-green-400 border-green-200 dark:border-green-800"
                          : "bg-slate-50 dark:bg-slate-700/50 text-slate-500 dark:text-slate-400 border-slate-200 dark:border-slate-600"
                      }`}
                    >
                      {channel.enabled ? "已启用" : "已禁用"}
                    </span>
                  </div>

                  <div className="flex gap-2" onClick={(e) => e.stopPropagation()}>
                    {isConnected ? (
                      <button
                        onClick={(e) => handleConnectToggle(e, channel.channelId, true)}
                        disabled={isBusy}
                        className="px-3 py-1 text-xs border border-slate-200 dark:border-slate-600 rounded-md text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700/60 disabled:opacity-50 transition-colors"
                      >
                        {isBusy ? "处理中…" : "断开"}
                      </button>
                    ) : (
                      <button
                        onClick={(e) => handleConnectToggle(e, channel.channelId, false)}
                        disabled={isBusy}
                        className="px-3 py-1 text-xs bg-green-600 text-white rounded-md hover:bg-green-700 disabled:opacity-50 transition-colors"
                      >
                        {isBusy ? "处理中…" : "连接"}
                      </button>
                    )}
                    <button
                      onClick={async (e) => {
                        e.stopPropagation();
                        if (!confirm(`确定删除渠道「${meta.name}」吗？`)) return;
                        setActionLoading(channel.channelId);
                        try {
                          await deleteChannel(channel.channelId);
                        } finally {
                          setActionLoading(null);
                        }
                      }}
                      disabled={isBusy}
                      className="px-2 py-1 text-xs border border-red-200 dark:border-red-800 rounded-md text-red-500 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20 disabled:opacity-50 transition-colors"
                      title="删除渠道"
                    >
                      <Trash2 size={12} />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {showModal && (
        <ChannelConfigModal
          channelId={editingChannel}
          onClose={() => {
            setShowModal(false);
            setEditingChannel(null);
          }}
        />
      )}
    </div>
  );
}
