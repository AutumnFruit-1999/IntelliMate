import { useEffect, useRef, useState, useCallback } from "react";
import { createPortal } from "react-dom";
import { ChevronDown, Loader2 } from "lucide-react";
import { fetchModelGroups, type ModelGroup, type ModelItem } from "../lib/api";

interface ModelSelectorProps {
  value: string;
  onChange: (modelId: string) => void;
  disabled?: boolean;
}

interface DropdownPos {
  top: number;
  left: number;
}

export default function ModelSelector({ value, onChange, disabled }: ModelSelectorProps) {
  const [groups, setGroups] = useState<ModelGroup[]>([]);
  const [loading, setLoading] = useState(true);
  const [open, setOpen] = useState(false);
  const [pos, setPos] = useState<DropdownPos>({ top: 0, left: 0 });
  const btnRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    fetchModelGroups()
      .then((data) => { if (!cancelled) setGroups(data); })
      .catch(() => {})
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  const handleToggle = useCallback(() => {
    if (disabled) return;
    if (!open && btnRef.current) {
      const rect = btnRef.current.getBoundingClientRect();
      setPos({ top: rect.bottom + 4, left: rect.left });
    }
    setOpen((v) => !v);
  }, [disabled, open]);

  const allModels: (ModelItem & { providerName: string })[] = groups.flatMap((g) =>
    g.models.map((m) => ({ ...m, providerName: g.providerName })),
  );
  const selected = allModels.find((m) => m.modelId === value);

  if (loading) {
    return (
      <div className="flex items-center gap-1.5 text-xs text-slate-400">
        <Loader2 size={12} className="animate-spin" />
        <span>加载模型...</span>
      </div>
    );
  }

  if (groups.length === 0) {
    return (
      <select
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={disabled}
        className="px-2 py-1 text-xs rounded border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 focus:outline-none focus:ring-1 focus:ring-blue-500/40"
      >
        <option value={value}>{value}</option>
      </select>
    );
  }

  return (
    <div className="relative">
      <button
        ref={btnRef}
        type="button"
        onClick={handleToggle}
        disabled={disabled}
        className="flex items-center gap-1.5 px-2.5 py-1.5 text-xs font-medium rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors disabled:opacity-50"
      >
        <span className="truncate max-w-[160px]">
          {selected ? selected.displayName : value}
        </span>
        <ChevronDown size={12} className={`transition-transform ${open ? "rotate-180" : ""}`} />
      </button>

      {open && createPortal(
        <>
          <div className="fixed inset-0 z-[9998]" onClick={() => setOpen(false)} />
          <div
            className="fixed z-[9999] w-64 max-h-72 overflow-y-auto rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 shadow-xl"
            style={{ top: pos.top, left: pos.left }}
          >
            {groups.map((group) => (
              <div key={group.providerId}>
                <div className="px-3 py-1.5 text-[10px] font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider bg-slate-50 dark:bg-slate-800/80 sticky top-0">
                  {group.providerName}
                </div>
                {group.models.map((m) => {
                  const isActive = m.modelId === value;
                  return (
                    <button
                      key={m.id}
                      onClick={() => {
                        onChange(m.modelId);
                        setOpen(false);
                      }}
                      className={`w-full text-left px-3 py-2 text-xs transition-colors ${
                        isActive
                          ? "bg-blue-50 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300"
                          : "text-slate-600 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-700"
                      }`}
                    >
                      <div className="font-medium">{m.displayName}</div>
                      {m.description && (
                        <div className="text-[10px] text-slate-400 dark:text-slate-500 mt-0.5 truncate">
                          {m.description}
                        </div>
                      )}
                    </button>
                  );
                })}
              </div>
            ))}
          </div>
        </>,
        document.body,
      )}
    </div>
  );
}
