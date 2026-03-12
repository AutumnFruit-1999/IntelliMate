import { useEffect, useState, useMemo, useCallback } from "react";
import { Loader2, ChevronDown, ChevronRight, RefreshCw, WifiOff } from "lucide-react";
import {
  fetchMcpServers,
  reconnectMcpServers,
  type McpServer,
} from "../lib/api";

interface McpToolsTabProps {
  mcpToolsEnabled: string | null;
  onChange: (value: string | null) => void;
}

interface McpGroup {
  serverId: number;
  serverName: string;
  transportType: string;
  tools: { name: string; description: string }[];
}

function parseDiscoveredTools(raw: string | null): { name: string; description: string }[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed;
  } catch { /* ignore */ }
  return [];
}

export default function McpToolsTab({ mcpToolsEnabled, onChange }: McpToolsTabProps) {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [dbServers, setDbServers] = useState<McpServer[]>([]);
  const [expandedGroups, setExpandedGroups] = useState<Set<number>>(new Set());
  const [reconnecting, setReconnecting] = useState(false);

  const loadData = useCallback(() => {
    setLoading(true);
    setError(null);
    fetchMcpServers()
      .then((servers) => {
        setDbServers(servers);
        const enabledIds = servers.filter((s) => s.enabled === 1).map((s) => s.id);
        setExpandedGroups(new Set(enabledIds));
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const handleReconnect = useCallback(async () => {
    setReconnecting(true);
    try {
      await reconnectMcpServers();
      loadData();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setReconnecting(false);
    }
  }, [loadData]);

  const mcpGroups = useMemo<McpGroup[]>(() => {
    return dbServers
      .filter((s) => s.enabled === 1)
      .map((s) => ({
        serverId: s.id,
        serverName: s.name,
        transportType: s.transportType,
        tools: parseDiscoveredTools(s.toolsDiscovered),
      }));
  }, [dbServers]);

  const allToolNames = useMemo<string[]>(() => {
    return mcpGroups.flatMap((g) => g.tools.map((t) => t.name));
  }, [mcpGroups]);

  const selectedTools = useMemo(() => {
    if (!mcpToolsEnabled || mcpToolsEnabled.trim() === "") {
      return new Set<string>();
    }
    if (mcpToolsEnabled.toLowerCase() === "full") {
      return new Set(allToolNames);
    }
    try {
      const parsed = JSON.parse(mcpToolsEnabled);
      if (Array.isArray(parsed)) return new Set<string>(parsed);
    } catch { /* not JSON */ }
    return new Set<string>();
  }, [mcpToolsEnabled, allToolNames]);

  const mode = useMemo<"none" | "full" | "custom">(() => {
    if (!mcpToolsEnabled || mcpToolsEnabled.trim() === "") return "none";
    if (mcpToolsEnabled.toLowerCase() === "full") return "full";
    return "custom";
  }, [mcpToolsEnabled]);

  const handlePreset = useCallback(
    (preset: "none" | "full") => {
      if (preset === "none") {
        onChange(null);
      } else {
        onChange("full");
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
        onChange(null);
        return;
      }
      if (next.size === allToolNames.length && allToolNames.every((n) => next.has(n))) {
        onChange("full");
        return;
      }
      onChange(JSON.stringify([...next]));
    },
    [selectedTools, allToolNames, onChange],
  );

  const handleGroupToggle = useCallback(
    (group: McpGroup) => {
      const groupToolNames = group.tools.map((t) => t.name);
      const allSelected = groupToolNames.every((t) => selectedTools.has(t));
      const next = new Set(selectedTools);
      for (const t of groupToolNames) {
        if (allSelected) next.delete(t);
        else next.add(t);
      }
      if (next.size === 0) {
        onChange(null);
        return;
      }
      if (next.size === allToolNames.length && allToolNames.every((n) => next.has(n))) {
        onChange("full");
        return;
      }
      onChange(JSON.stringify([...next]));
    },
    [selectedTools, allToolNames, onChange],
  );

  const toggleGroupExpand = useCallback((serverId: number) => {
    setExpandedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(serverId)) next.delete(serverId);
      else next.add(serverId);
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

  if (error) {
    return (
      <div className="flex-1 flex items-center justify-center text-red-500 text-sm">
        {error}
      </div>
    );
  }

  if (mcpGroups.length === 0) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center text-slate-400 dark:text-slate-500 py-12 space-y-2">
        <p className="text-sm">暂无已配置的 MCP 服务</p>
        <p className="text-xs">请先在「工具管理 → MCP 服务」中添加 MCP 服务器</p>
      </div>
    );
  }

  const hasAnyTools = mcpGroups.some((g) => g.tools.length > 0);

  if (!hasAnyTools) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center py-12 space-y-3">
        <WifiOff size={32} className="text-amber-400" />
        <p className="text-sm text-slate-600 dark:text-slate-300">
          已配置 {mcpGroups.length} 个 MCP 服务，但尚未发现工具
        </p>
        <p className="text-xs text-slate-400 dark:text-slate-500">
          请先测试连接以发现可用工具
        </p>
        <button
          type="button"
          onClick={handleReconnect}
          disabled={reconnecting}
          className="flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
        >
          {reconnecting ? (
            <Loader2 size={14} className="animate-spin" />
          ) : (
            <RefreshCw size={14} />
          )}
          {reconnecting ? "重新连接中..." : "重新连接"}
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      {/* Quick Presets */}
      <div>
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">
          快速选择
        </h3>
        <div className="flex flex-wrap gap-2">
          <button
            type="button"
            onClick={() => handlePreset("full")}
            className={`px-3 py-1.5 text-xs rounded-lg border transition-colors ${
              mode === "full"
                ? "border-blue-500 bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400 dark:border-blue-600"
                : "border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 hover:border-slate-300 dark:hover:border-slate-600"
            }`}
          >
            全部 MCP 工具
          </button>
          <button
            type="button"
            onClick={() => handlePreset("none")}
            className={`px-3 py-1.5 text-xs rounded-lg border transition-colors ${
              mode === "none"
                ? "border-blue-500 bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400 dark:border-blue-600"
                : "border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 hover:border-slate-300 dark:hover:border-slate-600"
            }`}
          >
            不使用 MCP 工具
          </button>
          {mode === "custom" && (
            <span className="px-3 py-1.5 text-xs rounded-lg border border-amber-400 bg-amber-50 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400 dark:border-amber-600">
              自定义选择（{selectedTools.size}/{allToolNames.length}）
            </span>
          )}
        </div>
      </div>

      {/* MCP Server Groups */}
      <div>
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">
          MCP 服务工具列表
        </h3>
        <div className="space-y-2">
          {mcpGroups.map((group) => {
            const expanded = expandedGroups.has(group.serverId);
            const groupToolNames = group.tools.map((t) => t.name);
            const allSelected = groupToolNames.length > 0 && groupToolNames.every((t) => selectedTools.has(t));
            const someSelected =
              !allSelected && groupToolNames.some((t) => selectedTools.has(t));

            return (
              <div
                key={group.serverId}
                className="rounded-lg border border-slate-200 dark:border-slate-700 overflow-hidden"
              >
                <div
                  className="flex items-center justify-between px-3 py-2 bg-slate-50 dark:bg-slate-800/50 cursor-pointer select-none"
                  onClick={() => toggleGroupExpand(group.serverId)}
                >
                  <div className="flex items-center gap-2">
                    {expanded ? (
                      <ChevronDown size={14} className="text-slate-400" />
                    ) : (
                      <ChevronRight size={14} className="text-slate-400" />
                    )}
                    <span className="text-sm font-medium text-slate-700 dark:text-slate-200">
                      {group.serverName}
                    </span>
                    <span className="px-1.5 py-0.5 text-[10px] font-medium rounded bg-slate-200 dark:bg-slate-700 text-slate-500 dark:text-slate-400">
                      {group.transportType}
                    </span>
                    <span className="text-xs text-slate-400">
                      ({group.tools.length} 个工具)
                    </span>
                  </div>
                  {groupToolNames.length > 0 && (
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
                  )}
                </div>
                {expanded && (
                  <div className="divide-y divide-slate-100 dark:divide-slate-800">
                    {group.tools.length === 0 ? (
                      <div className="px-3 py-3 text-xs text-slate-400 text-center">
                        未发现工具，请先测试连接
                      </div>
                    ) : (
                      group.tools.map((tool) => (
                        <label
                          key={tool.name}
                          className="flex items-center gap-3 px-3 py-2 hover:bg-slate-50 dark:hover:bg-slate-800/30 cursor-pointer"
                        >
                          <input
                            type="checkbox"
                            checked={selectedTools.has(tool.name)}
                            onChange={() => handleToolToggle(tool.name)}
                            className="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                          />
                          <div className="flex-1 min-w-0">
                            <span className="text-sm font-mono text-slate-700 dark:text-slate-200">
                              {tool.name}
                            </span>
                            {tool.description && (
                              <span className="ml-2 text-xs text-slate-400 dark:text-slate-500">
                                {tool.description.length > 60
                                  ? tool.description.slice(0, 60) + "..."
                                  : tool.description}
                              </span>
                            )}
                          </div>
                        </label>
                      ))
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      </div>

      {/* Reconnect button */}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={handleReconnect}
          disabled={reconnecting}
          className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-slate-600 dark:text-slate-300 border border-slate-200 dark:border-slate-700 rounded-lg hover:bg-slate-50 dark:hover:bg-slate-800 disabled:opacity-50 transition-colors"
        >
          {reconnecting ? (
            <Loader2 size={12} className="animate-spin" />
          ) : (
            <RefreshCw size={12} />
          )}
          {reconnecting ? "连接中..." : "刷新连接"}
        </button>
        <span className="text-xs text-slate-400 dark:text-slate-500">
          重新连接 MCP 服务以更新工具列表
        </span>
      </div>

      {/* Current Value */}
      <div>
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-1">
          当前配置值
        </h3>
        <div className="rounded-lg bg-slate-50 dark:bg-slate-800/50 px-3 py-2 text-xs font-mono text-slate-500 dark:text-slate-400 break-all">
          {mcpToolsEnabled ?? "(null — 不使用 MCP 工具)"}
        </div>
      </div>
    </div>
  );
}
