import { useEffect, useState, useCallback } from "react";
import { Plus, Pencil, Trash2, Loader2, Server, ToggleLeft, ToggleRight, Zap, Wifi, WifiOff, ChevronDown, ChevronRight } from "lucide-react";
import { useToolStore } from "../stores/toolStore";
import type { McpServer } from "../lib/api";
import McpServerEditor from "./McpServerEditor";

function getDisplayUrl(server: McpServer): string {
  if (server.transportType === "STDIO") {
    try {
      const obj = JSON.parse(server.serverUrl);
      return `${obj.command} ${(obj.args || []).join(" ")}`;
    } catch {
      return server.serverUrl;
    }
  }
  return server.serverUrl;
}

function parseDiscoveredTools(json: string | null): string[] {
  if (!json) return [];
  try {
    const arr = JSON.parse(json);
    return Array.isArray(arr) ? arr : [];
  } catch {
    return [];
  }
}

export default function McpServerPanel() {
  const {
    mcpServers,
    mcpLoading,
    mcpError,
    fetchMcpServers,
    deleteMcpServer,
    updateMcpServer,
    testMcpServer,
  } = useToolStore();
  const [editing, setEditing] = useState<McpServer | null | "new">(null);
  const [deleting, setDeleting] = useState<number | null>(null);
  const [testingId, setTestingId] = useState<number | null>(null);
  const [expandedTools, setExpandedTools] = useState<Set<number>>(new Set());

  useEffect(() => {
    fetchMcpServers();
  }, [fetchMcpServers]);

  const handleDelete = useCallback(
    async (id: number, name: string) => {
      if (!window.confirm(`确定删除 MCP 服务「${name}」？连接将被断开。`)) return;
      setDeleting(id);
      try {
        await deleteMcpServer(id);
      } finally {
        setDeleting(null);
      }
    },
    [deleteMcpServer],
  );

  const handleToggleEnabled = useCallback(
    async (server: McpServer) => {
      const nextEnabled = server.enabled === 1 ? 0 : 1;
      await updateMcpServer(server.id, { enabled: nextEnabled });
      fetchMcpServers();
    },
    [updateMcpServer, fetchMcpServers],
  );

  const handleQuickTest = useCallback(
    async (id: number) => {
      setTestingId(id);
      try {
        const result = await testMcpServer(id);
        if (result.success) {
          alert(`连接成功！发现 ${result.toolsDiscovered.length} 个工具。`);
          fetchMcpServers();
        } else {
          alert(`连接失败: ${result.error}`);
        }
      } catch (e) {
        alert(`测试出错: ${e instanceof Error ? e.message : String(e)}`);
      } finally {
        setTestingId(null);
      }
    },
    [testMcpServer, fetchMcpServers],
  );

  const toggleToolsExpanded = useCallback((id: number) => {
    setExpandedTools((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  if (editing !== null) {
    return (
      <McpServerEditor
        server={editing === "new" ? null : editing}
        onSave={() => {
          setEditing(null);
          fetchMcpServers();
        }}
        onCancel={() => setEditing(null)}
      />
    );
  }

  if (mcpLoading) {
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
          连接 MCP 服务器，自动发现并注册远程工具
        </p>
        <button
          onClick={() => setEditing("new")}
          className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors"
        >
          <Plus size={14} /> 添加服务
        </button>
      </div>

      {mcpError && (
        <div className="text-sm text-red-600 dark:text-red-400 bg-red-50 dark:bg-red-900/20 rounded-lg px-3 py-2">
          {mcpError}
        </div>
      )}

      {mcpServers.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-12 text-slate-400 dark:text-slate-500">
          <Server size={32} className="mb-2" />
          <p className="text-sm">暂无 MCP 服务</p>
          <p className="text-xs mt-1">点击「添加服务」来连接 MCP 远程工具服务器</p>
        </div>
      ) : (
        <div className="space-y-2">
          {mcpServers.map((server) => {
            const tools = parseDiscoveredTools(server.toolsDiscovered);
            const isExpanded = expandedTools.has(server.id);
            const COLLAPSE_THRESHOLD = 5;
            const showToggle = tools.length > COLLAPSE_THRESHOLD;
            const visibleTools = isExpanded ? tools : tools.slice(0, COLLAPSE_THRESHOLD);

            return (
              <div
                key={server.id}
                className="rounded-lg border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800/30 transition-colors"
              >
                <div className="flex items-center justify-between px-4 py-3">
                  <div className="flex-1 min-w-0 mr-3">
                    <div className="flex items-center gap-2">
                      {server.enabled === 1 ? (
                        <Wifi size={14} className="text-green-500 shrink-0" />
                      ) : (
                        <WifiOff size={14} className="text-slate-400 shrink-0" />
                      )}
                      <span className="text-sm font-medium text-slate-800 dark:text-slate-200">
                        {server.name}
                      </span>
                      <span className="px-1.5 py-0.5 text-[10px] font-medium rounded bg-purple-100 dark:bg-purple-900/30 text-purple-700 dark:text-purple-400">
                        {server.transportType}
                      </span>
                      {tools.length > 0 && (
                        <span className="px-1.5 py-0.5 text-[10px] font-medium rounded bg-emerald-100 dark:bg-emerald-900/30 text-emerald-700 dark:text-emerald-400">
                          {tools.length} 工具
                        </span>
                      )}
                      {server.enabled !== 1 && (
                        <span className="px-1.5 py-0.5 text-[10px] font-medium rounded bg-slate-100 dark:bg-slate-800 text-slate-500">
                          已禁用
                        </span>
                      )}
                    </div>
                    <p className="text-xs text-slate-400 dark:text-slate-500 mt-0.5 truncate">
                      {getDisplayUrl(server)}
                      {server.lastConnectedAt && (
                        <span className="ml-2 text-[10px]">
                          最后连接: {new Date(server.lastConnectedAt).toLocaleString()}
                        </span>
                      )}
                    </p>
                  </div>
                  <div className="flex items-center gap-1">
                    <button
                      onClick={() => handleQuickTest(server.id)}
                      disabled={testingId === server.id}
                      title="测试连接"
                      className="p-1.5 rounded-lg hover:bg-amber-50 dark:hover:bg-amber-900/20 transition-colors disabled:opacity-50"
                    >
                      {testingId === server.id ? (
                        <Loader2 size={14} className="animate-spin text-slate-400" />
                      ) : (
                        <Zap size={14} className="text-amber-500" />
                      )}
                    </button>
                    <button
                      onClick={() => handleToggleEnabled(server)}
                      title={server.enabled === 1 ? "禁用" : "启用"}
                      className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                    >
                      {server.enabled === 1 ? (
                        <ToggleRight size={16} className="text-green-500" />
                      ) : (
                        <ToggleLeft size={16} className="text-slate-400" />
                      )}
                    </button>
                    <button
                      onClick={() => setEditing(server)}
                      className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                    >
                      <Pencil size={14} className="text-slate-500" />
                    </button>
                    <button
                      onClick={() => handleDelete(server.id, server.name)}
                      disabled={deleting === server.id}
                      className="p-1.5 rounded-lg hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors disabled:opacity-50"
                    >
                      {deleting === server.id ? (
                        <Loader2 size={14} className="animate-spin text-slate-400" />
                      ) : (
                        <Trash2 size={14} className="text-red-500" />
                      )}
                    </button>
                  </div>
                </div>

                {/* Discovered tools */}
                {tools.length > 0 && (
                  <div className="px-4 pb-3 pt-0">
                    <div className="flex flex-wrap gap-1.5">
                      {visibleTools.map((toolName) => (
                        <span
                          key={toolName}
                          className="px-2 py-0.5 text-[10px] font-mono rounded-md bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-400"
                        >
                          {toolName}
                        </span>
                      ))}
                      {showToggle && (
                        <button
                          onClick={() => toggleToolsExpanded(server.id)}
                          className="flex items-center gap-0.5 px-2 py-0.5 text-[10px] font-medium rounded-md bg-slate-100 dark:bg-slate-800 text-blue-600 dark:text-blue-400 hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
                        >
                          {isExpanded ? (
                            <>
                              <ChevronDown size={10} /> 收起
                            </>
                          ) : (
                            <>
                              <ChevronRight size={10} /> 还有 {tools.length - COLLAPSE_THRESHOLD} 个
                            </>
                          )}
                        </button>
                      )}
                    </div>
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
