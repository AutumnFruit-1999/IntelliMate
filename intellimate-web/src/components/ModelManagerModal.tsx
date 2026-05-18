import { useEffect, useState, useCallback } from "react";
import { ArrowLeft, Cpu, Server, Plus, Trash2, Loader2 } from "lucide-react";
import { useModelStore } from "../stores/modelStore";
import ProviderEditor from "./ProviderEditor";
import ModelList from "./ModelList";

interface ModelManagerPageProps {
  onBack: () => void;
}

export default function ModelManagerPage({ onBack }: ModelManagerPageProps) {
  const providers = useModelStore((s) => s.providers);
  const loading = useModelStore((s) => s.loading);
  const fetchProviders = useModelStore((s) => s.fetchProviders);
  const selectProvider = useModelStore((s) => s.selectProvider);
  const removeProvider = useModelStore((s) => s.removeProvider);

  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [showNewProvider, setShowNewProvider] = useState(false);

  useEffect(() => {
    fetchProviders();
    selectProvider(null);
    setSelectedId(null);
    setShowNewProvider(false);
  }, [fetchProviders, selectProvider]);

  const handleSelect = useCallback(
    (id: number) => {
      setShowNewProvider(false);
      if (selectedId === id) {
        setSelectedId(null);
        selectProvider(null);
      } else {
        setSelectedId(id);
        selectProvider(id);
      }
    },
    [selectedId, selectProvider],
  );

  const handleDelete = useCallback(
    async (id: number, name: string, e: React.MouseEvent) => {
      e.stopPropagation();
      if (!window.confirm(`确定删除厂商「${name}」及其所有模型吗？`)) return;
      await removeProvider(id);
      if (selectedId === id) {
        setSelectedId(null);
      }
    },
    [removeProvider, selectedId],
  );

  const selectedProvider = providers.find((p) => p.id === selectedId) ?? null;

  return (
    <div className="flex flex-col flex-1 min-w-0 min-h-0">
      {/* Header */}
      <div className="px-6 py-4 border-b border-slate-200 dark:border-slate-700 flex items-center gap-3">
        <button
          onClick={onBack}
          className="p-1.5 rounded hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
        >
          <ArrowLeft size={18} className="text-slate-500" />
        </button>
        <Cpu size={20} className="text-blue-500" />
        <h1 className="text-lg font-semibold">模型管理</h1>

        <div className="ml-auto flex items-center gap-2">
          <button
            onClick={() => {
              setShowNewProvider(true);
              setSelectedId(null);
              selectProvider(null);
            }}
            className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors"
          >
            <Plus size={14} />
            添加厂商
          </button>
        </div>
      </div>

      {/* Body: two-panel layout */}
      <div className="flex flex-1 min-h-0">
        {/* Left: Provider List */}
        <div className="w-64 flex-shrink-0 border-r border-slate-200 dark:border-slate-700 overflow-y-auto">
          {loading ? (
            <div className="flex items-center justify-center py-16">
              <Loader2 size={24} className="animate-spin text-slate-400" />
            </div>
          ) : providers.length === 0 ? (
            <div className="flex flex-col items-center justify-center py-12 text-slate-400 dark:text-slate-500 space-y-2 px-4">
              <Server size={28} className="opacity-50" />
              <p className="text-xs text-center">暂无厂商</p>
            </div>
          ) : (
            <div className="py-2">
              {providers.map((provider) => {
                const isActive = selectedId === provider.id;
                return (
                  <div
                    key={provider.id}
                    onClick={() => handleSelect(provider.id)}
                    className={`group flex items-center gap-2.5 px-4 py-3 cursor-pointer transition-colors ${
                      isActive
                        ? "bg-blue-50 dark:bg-blue-900/20 border-r-2 border-blue-600 dark:border-blue-400"
                        : "hover:bg-slate-50 dark:hover:bg-slate-800/50"
                    }`}
                  >
                    <Server size={14} className={isActive ? "text-blue-500" : "text-slate-400"} />
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-1.5">
                        <span className={`text-sm font-medium truncate ${
                          isActive ? "text-blue-700 dark:text-blue-300" : "text-slate-700 dark:text-slate-200"
                        }`}>
                          {provider.name}
                        </span>
                      </div>
                      <div className="flex items-center gap-1.5 mt-0.5">
                        <span className="text-[10px] text-slate-400 dark:text-slate-500">
                          {provider.type}
                        </span>
                        {provider.enabled === 1 ? (
                          <span className="w-1.5 h-1.5 rounded-full bg-green-500" />
                        ) : (
                          <span className="w-1.5 h-1.5 rounded-full bg-slate-300 dark:bg-slate-600" />
                        )}
                      </div>
                    </div>
                    <button
                      onClick={(e) => handleDelete(provider.id, provider.name, e)}
                      className="p-1 rounded hover:bg-red-100 dark:hover:bg-red-900/30 transition-colors opacity-0 group-hover:opacity-100"
                      title="删除厂商"
                    >
                      <Trash2 size={12} className="text-red-400" />
                    </button>
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* Right: Detail Panel */}
        <div className="flex-1 min-w-0 overflow-y-auto px-6 py-4">
          {showNewProvider ? (
            <div className="space-y-4">
              <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">新建厂商</h3>
              <ProviderEditor
                provider={null}
                isNew
                onSaved={() => {
                  setShowNewProvider(false);
                  fetchProviders();
                }}
                onCancel={() => setShowNewProvider(false)}
              />
            </div>
          ) : selectedProvider ? (
            <div className="space-y-6">
              <div>
                <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">厂商配置</h3>
                <ProviderEditor
                  provider={selectedProvider}
                  onSaved={() => fetchProviders()}
                />
              </div>
              <div className="border-t border-slate-200 dark:border-slate-700 pt-4">
                <ModelList providerId={selectedProvider.id} />
              </div>
            </div>
          ) : (
            <div className="flex flex-col items-center justify-center h-full text-slate-400 dark:text-slate-500 space-y-2">
              <Cpu size={32} className="opacity-30" />
              <p className="text-sm">选择左侧厂商查看详情</p>
              <p className="text-xs">或点击「添加厂商」创建新的</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
