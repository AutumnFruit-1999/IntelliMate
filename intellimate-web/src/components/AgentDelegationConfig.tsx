import { useState, useEffect } from "react";
import { useAgentStore } from "../stores/agentStore";
import { fetchAgents, updateAgentApi, type AgentSummary } from "../lib/api";

export default function AgentDelegationConfig() {
  const config = useAgentStore((s) => s.config);
  const activeAgent = useAgentStore((s) => s.activeAgent);
  const fetchConfig = useAgentStore((s) => s.fetchConfig);

  const [canDelegate, setCanDelegate] = useState(false);
  const [delegateAgents, setDelegateAgents] = useState<string[]>([]);
  const [goal, setGoal] = useState("");
  const [allAgents, setAllAgents] = useState<AgentSummary[]>([]);
  const [saving, setSaving] = useState(false);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    fetchAgents().then(setAllAgents).catch(console.error);
  }, []);

  useEffect(() => {
    if (!config) return;
    setCanDelegate((config as any).canDelegate ?? false);
    const raw = (config as any).delegateAgents;
    if (raw) {
      try { setDelegateAgents(JSON.parse(raw)); } catch { setDelegateAgents([]); }
    } else {
      setDelegateAgents([]);
    }
    setGoal((config as any).goal ?? "");
    setDirty(false);
  }, [config]);

  const otherAgents = allAgents.filter((a) => a.name !== activeAgent);

  const toggleAgent = (name: string) => {
    setDirty(true);
    setDelegateAgents((prev) =>
      prev.includes(name) ? prev.filter((n) => n !== name) : [...prev, name]
    );
  };

  const handleSave = async () => {
    if (!activeAgent) return;
    setSaving(true);
    try {
      await updateAgentApi(activeAgent, {
        canDelegate,
        delegateAgents: JSON.stringify(delegateAgents),
        goal,
      });
      setDirty(false);
      await fetchConfig(activeAgent);
    } catch (e) {
      console.error("Failed to save delegation config:", e);
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3">
        <label className="flex items-center gap-2 cursor-pointer">
          <input
            type="checkbox"
            checked={canDelegate}
            onChange={(e) => { setCanDelegate(e.target.checked); setDirty(true); }}
            className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
          <span className="text-sm font-medium">允许委派任务</span>
        </label>
      </div>

      <div>
        <label className="block text-sm font-medium text-gray-700 mb-1">Agent 目标描述</label>
        <input
          type="text"
          value={goal}
          onChange={(e) => { setGoal(e.target.value); setDirty(true); }}
          placeholder="描述此 Agent 的专长和目标..."
          className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-1 focus:ring-blue-500"
        />
        <p className="text-xs text-gray-400 mt-1">
          当此 Agent 被其他 Agent 委派时，目标描述会展示给调用方。
        </p>
      </div>

      {canDelegate && (
        <div>
          <label className="block text-sm font-medium text-gray-700 mb-2">
            可委派的目标 Agent
          </label>
          {otherAgents.length === 0 ? (
            <p className="text-sm text-gray-400">暂无其他 Agent</p>
          ) : (
            <div className="space-y-1 max-h-60 overflow-y-auto">
              {otherAgents.map((agent) => (
                <label
                  key={agent.name}
                  className="flex items-center gap-2 p-2 rounded hover:bg-gray-50 cursor-pointer"
                >
                  <input
                    type="checkbox"
                    checked={delegateAgents.includes(agent.name)}
                    onChange={() => toggleAgent(agent.name)}
                    className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                  />
                  <span className="text-sm font-mono">{agent.name}</span>
                  <span className="text-xs text-gray-400">{agent.model}</span>
                </label>
              ))}
            </div>
          )}
        </div>
      )}

      {dirty && (
        <button
          onClick={handleSave}
          disabled={saving}
          className="px-4 py-2 text-sm text-white bg-blue-600 rounded-md hover:bg-blue-700 disabled:opacity-50 transition-colors"
        >
          {saving ? "保存中..." : "保存委派配置"}
        </button>
      )}
    </div>
  );
}
