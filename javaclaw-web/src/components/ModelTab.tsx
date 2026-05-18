import { useEffect, useState, useCallback } from "react";
import { Loader2, ChevronDown, ChevronRight, Check, Server } from "lucide-react";
import {
  fetchModelProviders,
  fetchModelDefinitions,
  type ModelProviderDto,
  type ModelDefinitionDto,
} from "../lib/api";
import { useAgentStore } from "../stores/agentStore";

interface ModelTabProps {
  currentModel: string;
}

export default function ModelTab({ currentModel }: ModelTabProps) {
  const [providers, setProviders] = useState<ModelProviderDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [models, setModels] = useState<ModelDefinitionDto[]>([]);
  const [modelsLoading, setModelsLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [selectedModel, setSelectedModel] = useState(currentModel);
  const [currentModelName, setCurrentModelName] = useState<string>("");

  const loadProviders = useCallback(() => {
    setLoading(true);
    setExpandedId(null);
    setModels([]);
    fetchModelProviders()
      .then((data) => {
        const enabled = data.filter((p) => p.enabled === 1);
        setProviders(enabled);
        resolveModelName(currentModel, enabled);
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, []);

  const resolveModelName = useCallback(
    async (modelRef: string, providerList: ModelProviderDto[]) => {
      if (!modelRef) {
        setCurrentModelName("");
        return;
      }
      const defId = Number(modelRef);
      if (!Number.isNaN(defId)) {
        for (const p of providerList) {
          try {
            const defs = await fetchModelDefinitions(p.id);
            const found = defs.find((d) => d.id === defId);
            if (found) {
              setCurrentModelName(`${found.displayName} (${p.name})`);
              return;
            }
          } catch { /* skip */ }
        }
      }
      setCurrentModelName(modelRef);
    },
    [],
  );

  useEffect(() => {
    loadProviders();
  }, [loadProviders, currentModel]);

  useEffect(() => {
    setSelectedModel(currentModel);
  }, [currentModel]);

  const handleExpandProvider = useCallback(
    (providerId: number) => {
      if (expandedId === providerId) {
        setExpandedId(null);
        setModels([]);
        return;
      }
      setExpandedId(providerId);
      setModelsLoading(true);
      fetchModelDefinitions(providerId)
        .then((data) => setModels(data))
        .catch(() => setModels([]))
        .finally(() => setModelsLoading(false));
    },
    [expandedId],
  );

  const handleSelectModel = useCallback(
    async (definitionId: string) => {
      if (definitionId === selectedModel) return;
      setSaving(true);
      try {
        const { saveModel } = useAgentStore.getState();
        await saveModel(definitionId);
        setSelectedModel(definitionId);
        const selected = models.find((m) => String(m.id) === definitionId);
        const provider = providers.find((p) => p.id === selected?.providerId);
        if (selected && provider) {
          setCurrentModelName(`${selected.displayName} (${provider.name})`);
        }
      } catch {
        // keep current
      } finally {
        setSaving(false);
      }
    },
    [selectedModel, models, providers],
  );

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <Loader2 size={24} className="animate-spin text-slate-400" />
      </div>
    );
  }

  if (providers.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-12 text-slate-400 dark:text-slate-500 space-y-2">
        <Server size={32} />
        <p className="text-sm">暂无可用的模型厂商</p>
        <p className="text-xs">请先在「模型管理」中添加厂商和模型</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div>
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-1">
          当前模型
        </h3>
        <p className="text-xs text-slate-400 dark:text-slate-500 font-mono">
          {currentModelName || selectedModel || "未选择"}
        </p>
      </div>

      <div>
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">
          选择模型
        </h3>
        <div className="space-y-2">
          {providers.map((provider) => {
            const isExpanded = expandedId === provider.id;
            return (
              <div
                key={provider.id}
                className="rounded-lg border border-slate-200 dark:border-slate-700 overflow-hidden"
              >
                <button
                  type="button"
                  onClick={() => handleExpandProvider(provider.id)}
                  className="w-full flex items-center justify-between px-4 py-3 bg-slate-50 dark:bg-slate-800/50 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors text-left"
                >
                  <div className="flex items-center gap-2">
                    {isExpanded ? (
                      <ChevronDown size={14} className="text-slate-400" />
                    ) : (
                      <ChevronRight size={14} className="text-slate-400" />
                    )}
                    <span className="text-sm font-medium text-slate-700 dark:text-slate-200">
                      {provider.name}
                    </span>
                    <span className="px-1.5 py-0.5 text-[10px] font-medium rounded bg-slate-200 dark:bg-slate-700 text-slate-500 dark:text-slate-400">
                      {provider.type}
                    </span>
                  </div>
                </button>

                {isExpanded && (
                  <div className="divide-y divide-slate-100 dark:divide-slate-800">
                    {modelsLoading ? (
                      <div className="flex items-center justify-center py-6">
                        <Loader2 size={16} className="animate-spin text-slate-400" />
                      </div>
                    ) : models.length === 0 ? (
                      <div className="px-4 py-4 text-xs text-slate-400 text-center">
                        该厂商暂无可用模型
                      </div>
                    ) : (
                      models.map((model) => {
                        const isActive = String(model.id) === selectedModel;
                        return (
                          <button
                            key={model.id}
                            type="button"
                            onClick={() => handleSelectModel(String(model.id))}
                            disabled={saving}
                            className={`w-full flex items-center justify-between px-4 py-3 text-left transition-colors ${
                              isActive
                                ? "bg-blue-50 dark:bg-blue-900/20"
                                : "hover:bg-slate-50 dark:hover:bg-slate-800/30"
                            }`}
                          >
                            <div className="min-w-0 flex-1">
                              <div className="flex items-center gap-2">
                                <span className={`text-sm font-medium ${
                                  isActive
                                    ? "text-blue-700 dark:text-blue-300"
                                    : "text-slate-700 dark:text-slate-200"
                                }`}>
                                  {model.displayName}
                                </span>
                                <span className="text-[10px] text-slate-400 font-mono">
                                  {model.modelId}
                                </span>
                              </div>
                              {model.description && (
                                <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5 truncate">
                                  {model.description}
                                </p>
                              )}
                            </div>
                            {isActive && (
                              <Check size={16} className="text-blue-500 shrink-0 ml-2" />
                            )}
                          </button>
                        );
                      })
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
