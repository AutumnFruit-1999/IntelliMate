import { useEffect, useState, useMemo, useCallback } from "react";
import { Loader2, ChevronDown, ChevronRight } from "lucide-react";
import {
  fetchToolsMetadata,
  type ToolsMetadata,
  type ToolGroupInfo,
} from "../lib/api";

interface ToolsTabProps {
  toolsEnabled: string | null;
  onChange: (value: string | null) => void;
}

const PROFILE_LABELS: Record<string, string> = {
  full: "Full（全部工具）",
  coding: "Coding（文件 + 运行时）",
  messaging: "Messaging（网络工具）",
  minimal: "Minimal（无工具）",
};

export default function ToolsTab({ toolsEnabled, onChange }: ToolsTabProps) {
  const [meta, setMeta] = useState<ToolsMetadata | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());

  useEffect(() => {
    setLoading(true);
    fetchToolsMetadata()
      .then((data) => {
        setMeta(data);
        setExpandedGroups(new Set(data.groups.map((g) => g.name)));
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, []);

  const activeProfile = useMemo(() => {
    if (!toolsEnabled || !meta) return toolsEnabled ?? "full";
    const profiles = meta.profiles.map((p) => p.name);
    if (profiles.includes(toolsEnabled.toLowerCase())) return toolsEnabled.toLowerCase();
    return "custom";
  }, [toolsEnabled, meta]);

  const selectedTools = useMemo(() => {
    if (!meta) return new Set<string>();
    if (!toolsEnabled || toolsEnabled.toLowerCase() === "full") {
      return new Set(meta.tools.map((t) => t.name));
    }
    const profile = meta.profiles.find(
      (p) => p.name === toolsEnabled.toLowerCase(),
    );
    if (profile) return new Set(profile.tools);
    try {
      const parsed = JSON.parse(toolsEnabled);
      if (Array.isArray(parsed)) return new Set<string>(parsed);
    } catch { /* not JSON */ }
    return new Set<string>();
  }, [toolsEnabled, meta]);

  const handleProfileChange = useCallback(
    (profileName: string) => {
      if (profileName === "full") {
        onChange(null);
      } else {
        onChange(profileName);
      }
    },
    [onChange],
  );

  const handleToolToggle = useCallback(
    (toolName: string) => {
      const next = new Set(selectedTools);
      if (next.has(toolName)) {
        next.delete(toolName);
      } else {
        next.add(toolName);
      }
      if (next.size === 0) {
        onChange("minimal");
        return;
      }
      if (meta) {
        const allTools = new Set(meta.tools.map((t) => t.name));
        if (next.size === allTools.size && [...next].every((n) => allTools.has(n))) {
          onChange(null);
          return;
        }
        for (const p of meta.profiles) {
          const pSet = new Set(p.tools);
          if (next.size === pSet.size && [...next].every((n) => pSet.has(n))) {
            onChange(p.name);
            return;
          }
        }
      }
      onChange(JSON.stringify([...next]));
    },
    [selectedTools, meta, onChange],
  );

  const handleGroupToggle = useCallback(
    (group: ToolGroupInfo) => {
      const allSelected = group.tools.every((t) => selectedTools.has(t));
      const next = new Set(selectedTools);
      for (const t of group.tools) {
        if (allSelected) next.delete(t);
        else next.add(t);
      }
      if (next.size === 0) {
        onChange("minimal");
        return;
      }
      if (meta) {
        const allTools = new Set(meta.tools.map((t) => t.name));
        if (next.size === allTools.size && [...next].every((n) => allTools.has(n))) {
          onChange(null);
          return;
        }
        for (const p of meta.profiles) {
          const pSet = new Set(p.tools);
          if (next.size === pSet.size && [...next].every((n) => pSet.has(n))) {
            onChange(p.name);
            return;
          }
        }
      }
      onChange(JSON.stringify([...next]));
    },
    [selectedTools, meta, onChange],
  );

  const toggleGroupExpand = useCallback((groupName: string) => {
    setExpandedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(groupName)) next.delete(groupName);
      else next.add(groupName);
      return next;
    });
  }, []);

  if (loading) {
    return (
      <div className="flex-1 flex items-center justify-center">
        <Loader2 size={24} className="animate-spin text-slate-400" />
      </div>
    );
  }

  if (error || !meta) {
    return (
      <div className="flex-1 flex items-center justify-center text-red-500 text-sm">
        {error ?? "无法加载工具信息"}
      </div>
    );
  }

  return (
    <div className="space-y-5">
      {/* Profile Selector */}
      <div>
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">
          快速预设
        </h3>
        <div className="flex flex-wrap gap-2">
          {meta.profiles.map((p) => (
            <button
              key={p.name}
              type="button"
              onClick={() => handleProfileChange(p.name)}
              className={`px-3 py-1.5 text-xs rounded-lg border transition-colors ${
                activeProfile === p.name
                  ? "border-blue-500 bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400 dark:border-blue-600"
                  : "border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 hover:border-slate-300 dark:hover:border-slate-600"
              }`}
            >
              {PROFILE_LABELS[p.name] ?? p.name}
            </button>
          ))}
          {activeProfile === "custom" && (
            <span className="px-3 py-1.5 text-xs rounded-lg border border-amber-400 bg-amber-50 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400 dark:border-amber-600">
              自定义
            </span>
          )}
        </div>
      </div>

      {/* Tool Group List */}
      <div>
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">
          工具列表
        </h3>
        <div className="space-y-2">
          {meta.groups
            .filter((group) => !group.name.startsWith("MCP:"))
            .map((group) => {
            const expanded = expandedGroups.has(group.name);
            const allSelected = group.tools.every((t) => selectedTools.has(t));
            const someSelected =
              !allSelected && group.tools.some((t) => selectedTools.has(t));

            return (
              <div
                key={group.name}
                className="rounded-lg border border-slate-200 dark:border-slate-700 overflow-hidden"
              >
                <div
                  className="flex items-center justify-between px-3 py-2 bg-slate-50 dark:bg-slate-800/50 cursor-pointer select-none"
                  onClick={() => toggleGroupExpand(group.name)}
                >
                  <div className="flex items-center gap-2">
                    {expanded ? (
                      <ChevronDown size={14} className="text-slate-400" />
                    ) : (
                      <ChevronRight size={14} className="text-slate-400" />
                    )}
                    <span className="text-sm font-medium text-slate-700 dark:text-slate-200">
                      {group.displayName}
                    </span>
                    <span className="text-xs text-slate-400">
                      ({group.tools.length})
                    </span>
                  </div>
                  <label
                    className="flex items-center"
                    onClick={(e) => e.stopPropagation()}
                  >
                    <input
                      type="checkbox"
                      checked={allSelected}
                      ref={(el) => {
                        if (el) el.indeterminate = someSelected;
                      }}
                      onChange={() => handleGroupToggle(group)}
                      className="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                    />
                  </label>
                </div>
                {expanded && (
                  <div className="divide-y divide-slate-100 dark:divide-slate-800">
                    {group.tools.map((toolName) => {
                      const tool = meta.tools.find((t) => t.name === toolName);
                      return (
                        <label
                          key={toolName}
                          className="flex items-center gap-3 px-3 py-2 hover:bg-slate-50 dark:hover:bg-slate-800/30 cursor-pointer"
                        >
                          <input
                            type="checkbox"
                            checked={selectedTools.has(toolName)}
                            onChange={() => handleToolToggle(toolName)}
                            className="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                          />
                          <div className="flex-1 min-w-0">
                            <span className="text-sm font-mono text-slate-700 dark:text-slate-200">
                              {toolName}
                            </span>
                            {tool?.description && (
                              <span className="ml-2 text-xs text-slate-400 dark:text-slate-500">
                                {tool.description.length > 60
                                  ? tool.description.slice(0, 60) + "..."
                                  : tool.description}
                              </span>
                            )}
                          </div>
                        </label>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>

      {/* Current Value Display */}
      <div>
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-1">
          当前配置值
        </h3>
        <div className="rounded-lg bg-slate-50 dark:bg-slate-800/50 px-3 py-2 text-xs font-mono text-slate-500 dark:text-slate-400 break-all">
          {toolsEnabled ?? "(null — 使用全部工具)"}
        </div>
      </div>
    </div>
  );
}
