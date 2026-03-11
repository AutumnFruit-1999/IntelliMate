import { useEffect, useState, useCallback } from "react";
import { Plus, Pencil, Trash2, Loader2, Globe, ToggleLeft, ToggleRight } from "lucide-react";
import { useToolStore } from "../stores/toolStore";
import type { ToolDefinition } from "../lib/api";
import CustomToolEditor from "./CustomToolEditor";

export default function CustomToolsPanel() {
  const { definitions, loading, error, fetchDefinitions, deleteDefinition, updateDefinition } = useToolStore();
  const [editing, setEditing] = useState<ToolDefinition | null | "new">(null);
  const [deleting, setDeleting] = useState<number | null>(null);

  useEffect(() => {
    fetchDefinitions();
  }, [fetchDefinitions]);

  const handleDelete = useCallback(async (id: number, name: string) => {
    if (!window.confirm(`确定删除工具「${name}」？`)) return;
    setDeleting(id);
    try {
      await deleteDefinition(id);
    } finally {
      setDeleting(null);
    }
  }, [deleteDefinition]);

  const handleToggleEnabled = useCallback(async (tool: ToolDefinition) => {
    const nextEnabled = tool.enabled === 1 ? 0 : 1;
    await updateDefinition(tool.id, { ...({} as Record<string, unknown>), enabled: nextEnabled } as never);
    fetchDefinitions();
  }, [updateDefinition, fetchDefinitions]);

  if (editing !== null) {
    return (
      <CustomToolEditor
        tool={editing === "new" ? null : editing}
        onSave={() => { setEditing(null); fetchDefinitions(); }}
        onCancel={() => setEditing(null)}
      />
    );
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center py-16">
        <Loader2 size={24} className="animate-spin text-slate-400" />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-sm text-slate-500 dark:text-slate-400">
          通过 HTTP API 创建自定义工具，供 Agent 调用
        </p>
        <button
          onClick={() => setEditing("new")}
          className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors"
        >
          <Plus size={14} /> 新建工具
        </button>
      </div>

      {error && (
        <div className="text-sm text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 rounded-lg px-3 py-2">
          {error}
        </div>
      )}

      {definitions.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-12 text-slate-400 dark:text-slate-500">
          <Globe size={32} className="mb-2" />
          <p className="text-sm">暂无自定义工具</p>
          <p className="text-xs mt-1">点击「新建工具」来添加 HTTP API 工具</p>
        </div>
      ) : (
        <div className="space-y-2">
          {definitions.map((tool) => (
            <div
              key={tool.id}
              className="flex items-center justify-between px-4 py-3 rounded-lg border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800/30 transition-colors"
            >
              <div className="flex-1 min-w-0 mr-3">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-mono font-medium text-slate-800 dark:text-slate-200">
                    {tool.name}
                  </span>
                  <span className="px-1.5 py-0.5 text-[10px] font-medium rounded bg-blue-100 dark:bg-blue-900/30 text-blue-700 dark:text-blue-400">
                    {tool.type}
                  </span>
                  {tool.enabled !== 1 && (
                    <span className="px-1.5 py-0.5 text-[10px] font-medium rounded bg-slate-100 dark:bg-slate-800 text-slate-500">
                      已禁用
                    </span>
                  )}
                </div>
                {tool.description && (
                  <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5 truncate">
                    {tool.description}
                  </p>
                )}
              </div>
              <div className="flex items-center gap-1">
                <button
                  onClick={() => handleToggleEnabled(tool)}
                  title={tool.enabled === 1 ? "禁用" : "启用"}
                  className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                >
                  {tool.enabled === 1 ? (
                    <ToggleRight size={16} className="text-green-500" />
                  ) : (
                    <ToggleLeft size={16} className="text-slate-400" />
                  )}
                </button>
                <button
                  onClick={() => setEditing(tool)}
                  className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                >
                  <Pencil size={14} className="text-slate-500" />
                </button>
                <button
                  onClick={() => handleDelete(tool.id, tool.name)}
                  disabled={deleting === tool.id}
                  className="p-1.5 rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors disabled:opacity-50"
                >
                  {deleting === tool.id ? (
                    <Loader2 size={14} className="animate-spin text-slate-400" />
                  ) : (
                    <Trash2 size={14} className="text-red-500" />
                  )}
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
