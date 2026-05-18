import { useState, useCallback } from "react";
import { X, Loader2, Bot } from "lucide-react";
import ModelSelector from "./ModelSelector";

interface CreateAgentModalProps {
  open: boolean;
  onClose: () => void;
  onCreate: (name: string, model: string) => Promise<void>;
}

export default function CreateAgentModal({ open, onClose, onCreate }: CreateAgentModalProps) {
  const [name, setName] = useState("");
  const [model, setModel] = useState("qwen-plus");
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleCreate = useCallback(async () => {
    if (!name.trim()) {
      setError("名称不能为空");
      return;
    }
    if (!/^[a-zA-Z0-9_-]+$/.test(name.trim())) {
      setError("名称只能包含字母、数字、下划线和连字符");
      return;
    }
    setCreating(true);
    setError(null);
    try {
      await onCreate(name.trim(), model);
      setName("");
      setModel("qwen-plus");
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setCreating(false);
    }
  }, [name, model, onCreate, onClose]);

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
      <div className="w-full max-w-lg bg-white dark:bg-slate-900 rounded-xl shadow-2xl overflow-hidden">
        <div className="flex items-center gap-3 px-6 py-5 border-b border-slate-200 dark:border-slate-700">
          <div className="w-10 h-10 rounded-lg bg-blue-50 dark:bg-blue-900/30 flex items-center justify-center">
            <Bot size={20} className="text-blue-600 dark:text-blue-400" />
          </div>
          <div className="flex-1">
            <h2 className="text-lg font-semibold text-slate-800 dark:text-slate-100">
              新建智能体
            </h2>
            <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5">
              创建一个新的 Agent 并选择默认模型
            </p>
          </div>
          <button
            onClick={onClose}
            className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
          >
            <X size={18} className="text-slate-500" />
          </button>
        </div>

        <div className="px-6 py-6 space-y-5">
          <div>
            <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
              名称 <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="my-assistant"
              className="w-full px-4 py-2.5 rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-sm text-slate-800 dark:text-slate-200 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500/40"
              onKeyDown={(e) => { if (e.key === "Enter") handleCreate(); }}
            />
            <p className="text-[11px] text-slate-400 mt-1.5">
              只能包含字母、数字、下划线和连字符
            </p>
          </div>

          <div>
            <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1.5">
              默认模型
            </label>
            <ModelSelector value={model} onChange={(m) => setModel(m)} />
          </div>

          {error && (
            <div className="px-3 py-2 text-sm text-red-600 bg-red-50 dark:bg-red-900/20 dark:text-red-400 rounded-lg">
              {error}
            </div>
          )}
        </div>

        <div className="flex justify-end gap-2 px-6 py-4 border-t border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/30">
          <button
            onClick={onClose}
            className="px-4 py-2 text-sm text-slate-600 dark:text-slate-300 rounded-lg hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
          >
            取消
          </button>
          <button
            onClick={handleCreate}
            disabled={creating}
            className="flex items-center gap-1.5 px-5 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            {creating && <Loader2 size={14} className="animate-spin" />}
            创建
          </button>
        </div>
      </div>
    </div>
  );
}
