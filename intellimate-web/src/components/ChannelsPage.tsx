import { useEffect, useState } from "react";
import { ArrowLeft, Loader2, Plus } from "lucide-react";
import { useChannelStore } from "../stores/channelStore";
import ChannelConfigModal from "./ChannelConfigModal";

const CHANNEL_META: Record<string, { name: string; icon: string }> = {
  feishu: { name: "飞书", icon: "🔷" },
  dingtalk: { name: "钉钉", icon: "🔵" },
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

  const [showModal, setShowModal] = useState(false);
  const [editingChannel, setEditingChannel] = useState<string | null>(null);
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  useEffect(() => {
    fetchChannels();
  }, [fetchChannels]);

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
