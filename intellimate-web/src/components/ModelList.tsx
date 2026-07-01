import { useState, useCallback } from "react";
import { Plus, Trash2, Loader2, Pencil, Check, X as XIcon } from "lucide-react";
import { useModelStore } from "../stores/modelStore";
import type { ModelDefinitionDto } from "../lib/api";

interface ModelListProps {
  providerId: number;
}

export default function ModelList({ providerId }: ModelListProps) {
  const models = useModelStore((s) => s.models);
  const modelsLoading = useModelStore((s) => s.modelsLoading);
  const addModel = useModelStore((s) => s.addModel);
  const editModel = useModelStore((s) => s.editModel);
  const removeModel = useModelStore((s) => s.removeModel);

  const [showAdd, setShowAdd] = useState(false);
  const [editingId, setEditingId] = useState<number | null>(null);

  if (modelsLoading) {
    return (
      <div className="flex items-center justify-center py-8">
        <Loader2 size={20} className="animate-spin text-slate-400" />
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between">
        <h4 className="text-xs font-semibold text-slate-600 dark:text-slate-400 uppercase tracking-wider">模型列表</h4>
        <button
          onClick={() => setShowAdd(true)}
          className="flex items-center gap-1 px-2 py-1 text-[11px] text-blue-600 dark:text-blue-400 rounded hover:bg-blue-50 dark:hover:bg-blue-900/20 transition-colors"
        >
          <Plus size={12} />
          添加模型
        </button>
      </div>

      {showAdd && (
        <ModelInlineForm
          providerId={providerId}
          onSave={async (data) => {
            await addModel(data);
            setShowAdd(false);
          }}
          onCancel={() => setShowAdd(false)}
        />
      )}

      {models.length === 0 && !showAdd && (
        <p className="text-xs text-slate-400 py-4 text-center">暂无模型，点击上方添加</p>
      )}

      {models.map((m) => (
        <div key={m.id}>
          {editingId === m.id ? (
            <ModelInlineForm
              providerId={providerId}
              initial={m}
              onSave={async (data) => {
                await editModel(m.id, data);
                setEditingId(null);
              }}
              onCancel={() => setEditingId(null)}
            />
          ) : (
            <ModelRow model={m} onEdit={() => setEditingId(m.id)} onDelete={() => removeModel(m.id)} />
          )}
        </div>
      ))}
    </div>
  );
}

function ModelRow({ model, onEdit, onDelete }: { model: ModelDefinitionDto; onEdit: () => void; onDelete: () => void }) {
  const [deleting, setDeleting] = useState(false);
  const isEmbedding = model.category === "EMBEDDING";

  const handleDelete = useCallback(async () => {
    if (!window.confirm(`确定删除模型「${model.displayName}」吗？`)) return;
    setDeleting(true);
    try {
      await onDelete();
    } finally {
      setDeleting(false);
    }
  }, [model.displayName, onDelete]);

  return (
    <div className="flex items-center gap-3 px-3 py-2 rounded-lg border border-slate-100 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800/50 transition-colors group">
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-200">{model.displayName}</span>
          <code className="text-[10px] px-1.5 py-0.5 rounded bg-slate-100 dark:bg-slate-700 text-slate-500 dark:text-slate-400">
            {model.modelId}
          </code>
          {isEmbedding ? (
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300">
              Embedding
            </span>
          ) : (
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300">
              Chat
            </span>
          )}
        </div>
        {model.description && (
          <p className="text-[11px] text-slate-400 dark:text-slate-500 mt-0.5 truncate">{model.description}</p>
        )}
        {isEmbedding && model.dimensions != null && (
          <p className="text-[11px] text-slate-400 dark:text-slate-500 mt-0.5">维度: {model.dimensions}</p>
        )}
      </div>
      <div className="flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
        <button onClick={onEdit} className="p-1 rounded hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors">
          <Pencil size={12} className="text-slate-400" />
        </button>
        <button onClick={handleDelete} disabled={deleting} className="p-1 rounded hover:bg-red-100 dark:hover:bg-red-900/30 transition-colors">
          {deleting ? <Loader2 size={12} className="animate-spin text-red-400" /> : <Trash2 size={12} className="text-red-400" />}
        </button>
      </div>
    </div>
  );
}

interface ModelInlineFormProps {
  providerId: number;
  initial?: ModelDefinitionDto;
  onSave: (data: {
    providerId: number;
    modelId: string;
    displayName: string;
    description?: string | null;
    category?: string;
    dimensions?: number | null;
  }) => Promise<void>;
  onCancel: () => void;
}

const inputClass =
  "px-2 py-1 text-xs rounded border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-200 focus:outline-none focus:ring-1 focus:ring-blue-500/40";

function ModelInlineForm({ providerId, initial, onSave, onCancel }: ModelInlineFormProps) {
  const [modelId, setModelId] = useState(initial?.modelId ?? "");
  const [category, setCategory] = useState(initial?.category ?? "CHAT");
  const [displayName, setDisplayName] = useState(initial?.displayName ?? "");
  const [description, setDescription] = useState(initial?.description ?? "");
  const [dimensions, setDimensions] = useState(initial?.dimensions ?? 1024);
  const [saving, setSaving] = useState(false);

  const handleSave = useCallback(async () => {
    if (!modelId.trim() || !displayName.trim()) return;
    setSaving(true);
    try {
      await onSave({
        providerId,
        modelId: modelId.trim(),
        displayName: displayName.trim(),
        description: description.trim() || null,
        category,
        dimensions: category === "EMBEDDING" ? dimensions : null,
      });
    } finally {
      setSaving(false);
    }
  }, [providerId, modelId, category, displayName, description, dimensions, onSave]);

  return (
    <div className="flex items-center gap-2 px-3 py-2 rounded-lg border border-blue-200 dark:border-blue-800 bg-blue-50/50 dark:bg-blue-900/10">
      <input
        type="text"
        value={modelId}
        onChange={(e) => setModelId(e.target.value)}
        placeholder="model-id"
        className={`w-28 ${inputClass}`}
      />
      <select
        value={category}
        onChange={(e) => setCategory(e.target.value)}
        className={`w-24 ${inputClass}`}
      >
        <option value="CHAT">Chat</option>
        <option value="EMBEDDING">Embedding</option>
      </select>
      <input
        type="text"
        value={displayName}
        onChange={(e) => setDisplayName(e.target.value)}
        placeholder="显示名称"
        className={`w-28 ${inputClass}`}
      />
      <input
        type="text"
        value={description}
        onChange={(e) => setDescription(e.target.value)}
        placeholder="描述（选填）"
        className={`flex-1 ${inputClass}`}
      />
      {category === "EMBEDDING" && (
        <input
          type="number"
          value={dimensions}
          onChange={(e) => setDimensions(Number(e.target.value))}
          placeholder="1024"
          className={`w-20 ${inputClass}`}
        />
      )}
      <button onClick={handleSave} disabled={saving} className="p-1 rounded hover:bg-green-100 dark:hover:bg-green-900/30 transition-colors">
        {saving ? <Loader2 size={14} className="animate-spin text-green-500" /> : <Check size={14} className="text-green-500" />}
      </button>
      <button onClick={onCancel} className="p-1 rounded hover:bg-slate-200 dark:hover:bg-slate-600 transition-colors">
        <XIcon size={14} className="text-slate-400" />
      </button>
    </div>
  );
}
