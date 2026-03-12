import { useEffect, useState, useCallback } from "react";
import { X, Server, Plus, Trash2, Loader2, ChevronDown, ChevronRight } from "lucide-react";
import { useModelStore } from "../stores/modelStore";
import ProviderEditor from "./ProviderEditor";
import ModelList from "./ModelList";

interface ModelManagerModalProps {
  open: boolean;
  onClose: () => void;
}

export default function ModelManagerModal({ open, onClose }: ModelManagerModalProps) {
  const providers = useModelStore((s) => s.providers);
  const loading = useModelStore((s) => s.loading);
  const fetchProviders = useModelStore((s) => s.fetchProviders);
  const selectProvider = useModelStore((s) => s.selectProvider);
  const removeProvider = useModelStore((s) => s.removeProvider);

  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [showNewProvider, setShowNewProvider] = useState(false);

  useEffect(() => {
    if (open) {
      fetchProviders();
      selectProvider(null);
      setExpandedId(null);
      setShowNewProvider(false);
    }
  }, [open, fetchProviders, selectProvider]);

  const handleToggleExpand = useCallback(
    (id: number) => {
      if (expandedId === id) {
        setExpandedId(null);
        selectProvider(null);
      } else {
        setExpandedId(id);
        selectProvider(id);
      }
    },
    [expandedId, selectProvider],
  );

  const handleDelete = useCallback(
    async (id: number, name: string, e: React.MouseEvent) => {
      e.stopPropagation();
      if (!window.confirm(`确定删除厂商「${name}」及其所有模型吗？`)) return;
      await removeProvider(id);
      if (expandedId === id) {
        setExpandedId(null);
      }
    },
    [removeProvider, expandedId],
  );

  const handleBackdrop = useCallback(
    (e: React.MouseEvent) => {
      if (e.target === e.currentTarget) onClose();
    },
    [onClose],
  );

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      onClick={handleBackdrop}
    >
      <div className="flex flex-col w-full max-w-5xl max-h-[90vh] md:max-h-[85vh] bg-white dark:bg-slate-900 rounded-xl shadow-2xl overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-slate-200 dark:border-slate-700">
          <div>
            <h2 className="text-lg font-semibold text-slate-800 dark:text-slate-100">
              模型管理
            </h2>
            <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
              管理模型厂商和模型定义
            </p>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          >
            <X size={18} className="text-slate-500" />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 min-h-0 overflow-y-auto px-6 py-4" style={{ minHeight: "400px" }}>
          {loading ? (
            <div className="flex items-center justify-center py-16">
              <Loader2 size={24} className="animate-spin text-slate-400" />
            </div>
          ) : (
            <div className="space-y-3">
              {/* Add Provider Button */}
              <div className="flex justify-end">
                <button
                  onClick={() => {
                    setShowNewProvider(!showNewProvider);
                    setExpandedId(null);
                    selectProvider(null);
                  }}
                  className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-blue-600 dark:text-blue-400 rounded-lg border border-blue-200 dark:border-blue-800 hover:bg-blue-50 dark:hover:bg-blue-900/20 transition-colors"
                >
                  <Plus size={14} />
                  添加厂商
                </button>
              </div>

              {/* New Provider Form */}
              {showNewProvider && (
                <div className="rounded-lg border border-blue-200 dark:border-blue-800 bg-blue-50/30 dark:bg-blue-900/10 p-4">
                  <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">
                    新建厂商
                  </h3>
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
              )}

              {/* Provider List */}
              {providers.length === 0 && !showNewProvider && (
                <div className="flex flex-col items-center justify-center py-12 text-slate-400 dark:text-slate-500 space-y-2">
                  <Server size={32} />
                  <p className="text-sm">暂无模型厂商</p>
                  <p className="text-xs">点击上方「添加厂商」创建第一个</p>
                </div>
              )}

              {providers.map((provider) => {
                const isExpanded = expandedId === provider.id;
                return (
                  <div
                    key={provider.id}
                    className="rounded-lg border border-slate-200 dark:border-slate-700 overflow-hidden"
                  >
                    {/* Provider Header */}
                    <div
                      className="flex items-center justify-between px-4 py-3 bg-slate-50 dark:bg-slate-800/50 cursor-pointer hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                      onClick={() => handleToggleExpand(provider.id)}
                    >
                      <div className="flex items-center gap-2">
                        {isExpanded ? (
                          <ChevronDown size={14} className="text-slate-400" />
                        ) : (
                          <ChevronRight size={14} className="text-slate-400" />
                        )}
                        <Server size={14} className={isExpanded ? "text-blue-500" : "text-slate-400"} />
                        <span className="text-sm font-medium text-slate-700 dark:text-slate-200">
                          {provider.name}
                        </span>
                        <span className="px-1.5 py-0.5 text-[10px] font-medium rounded bg-slate-200 dark:bg-slate-700 text-slate-500 dark:text-slate-400">
                          {provider.type}
                        </span>
                        {provider.enabled === 1 ? (
                          <span className="px-1.5 py-0.5 text-[10px] rounded bg-green-50 dark:bg-green-900/20 text-green-600 dark:text-green-400">
                            启用
                          </span>
                        ) : (
                          <span className="px-1.5 py-0.5 text-[10px] rounded bg-slate-100 dark:bg-slate-800 text-slate-400">
                            禁用
                          </span>
                        )}
                      </div>
                      <button
                        onClick={(e) => handleDelete(provider.id, provider.name, e)}
                        className="p-1 rounded hover:bg-red-100 dark:hover:bg-red-900/30 transition-colors opacity-0 group-hover:opacity-100"
                        title="删除厂商"
                      >
                        <Trash2 size={13} className="text-red-400" />
                      </button>
                    </div>

                    {/* Expanded: Config + Models */}
                    {isExpanded && (
                      <div className="px-4 py-4 space-y-5 border-t border-slate-100 dark:border-slate-800">
                        <div>
                          <h4 className="text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase tracking-wider mb-3">
                            厂商配置
                          </h4>
                          <ProviderEditor
                            provider={provider}
                            onSaved={() => fetchProviders()}
                          />
                        </div>
                        <div className="border-t border-slate-200 dark:border-slate-700 pt-4">
                          <ModelList providerId={provider.id} />
                        </div>
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
