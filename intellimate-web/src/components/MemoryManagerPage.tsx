import { useState, useEffect } from "react";
import { useMemoryStore, type MemorySnapshotData, type ConsolidationLogEntry } from "../stores/memoryStore";
import { ArrowLeft, Brain, Database, Settings, Trash2, RefreshCw, RotateCcw, HelpCircle } from "lucide-react";
import type { MemoryConfigItem, LongTermMemoryItem, ArchivedMemoryItem, ModelGroup, AgentSummary } from "../lib/api";
import { fetchModelGroups, fetchAgents, fetchArchivedMemories, fetchWorkingMemoryByAgent } from "../lib/api";

interface Props {
  onBack: () => void;
  activeAgent?: string;
}

type TabId = "overview" | "longTerm" | "config" | "forgettingLog";

export default function MemoryManagerPage({ onBack, activeAgent }: Props) {
  const [tab, setTab] = useState<TabId>("overview");
  const [agents, setAgents] = useState<AgentSummary[]>([]);
  const selectedAgentId = useMemoryStore((s) => s.selectedAgentId);
  const setSelectedAgentId = useMemoryStore((s) => s.setSelectedAgentId);

  useEffect(() => {
    fetchAgents().then((list) => {
      setAgents(list);
      if (activeAgent && list.some((a) => a.name === activeAgent)) {
        setSelectedAgentId(activeAgent);
      } else if (list.length > 0 && selectedAgentId === "default") {
        setSelectedAgentId(list[0].name);
      }
    }).catch(() => setAgents([]));
  }, [activeAgent]);

  return (
    <div className="flex-1 flex flex-col min-h-0 bg-white dark:bg-slate-900">
      <div className="flex items-center gap-3 px-6 py-4 border-b border-slate-200 dark:border-slate-700">
        <button onClick={onBack} className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors">
          <ArrowLeft size={18} className="text-slate-500" />
        </button>
        <Brain size={20} className="text-indigo-500" />
        <h1 className="text-lg font-semibold text-slate-800 dark:text-slate-100">记忆观测</h1>
        <div className="ml-auto flex items-center gap-2">
          <span className="text-xs text-slate-500 dark:text-slate-400">Agent:</span>
          <select
            value={selectedAgentId}
            onChange={(e) => setSelectedAgentId(e.target.value)}
            className="px-2 py-1 text-sm rounded-lg border border-slate-200 dark:border-slate-600 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200"
          >
            {agents.map((a) => (
              <option key={a.name} value={a.name}>
                {a.name}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="flex border-b border-slate-200 dark:border-slate-700 px-6">
        {([
          ["overview", "记忆总览"],
          ["longTerm", "长期记忆"],
          ["config", "记忆配置"],
          ["forgettingLog", "遗忘日志"],
        ] as [TabId, string][]).map(([id, label]) => (
          <button
            key={id}
            onClick={() => setTab(id)}
            className={`px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
              tab === id
                ? "border-indigo-500 text-indigo-600 dark:text-indigo-400"
                : "border-transparent text-slate-500 hover:text-slate-700 dark:hover:text-slate-300"
            }`}
          >
            {label}
          </button>
        ))}
      </div>

      <div className="flex-1 overflow-y-auto p-6">
        {tab === "overview" && <OverviewTab />}
        {tab === "longTerm" && <LongTermTab />}
        {tab === "config" && <ConfigTab />}
        {tab === "forgettingLog" && <ForgettingLogTab />}
      </div>
    </div>
  );
}

function OverviewTab() {
  const snapshot = useMemoryStore((s) => s.workingMemory);
  const stats = useMemoryStore((s) => s.memoryStats);
  const consolidationLog = useMemoryStore((s) => s.consolidationLog);
  const fetchStats = useMemoryStore((s) => s.fetchStats);
  const handleMemorySnapshot = useMemoryStore((s) => s.handleMemorySnapshot);
  const selectedAgentId = useMemoryStore((s) => s.selectedAgentId);

  useEffect(() => {
    void fetchStats();
    if (!snapshot && selectedAgentId) {
      fetchWorkingMemoryByAgent(selectedAgentId).then((data) => {
        if (data && "tokenBudget" in data) {
          handleMemorySnapshot(data as unknown as MemorySnapshotData);
        }
      }).catch(() => {});
    }
  }, [fetchStats, selectedAgentId]);

  return (
    <div className="space-y-6 max-w-5xl">
      <div className="grid grid-cols-4 gap-4">
        <StatCard
          label="Token 使用"
          value={snapshot ? `${snapshot.tokenUsed.toLocaleString()} / ${snapshot.tokenBudget.toLocaleString()}` : "N/A"}
        />
        <StatCard label="使用率" value={snapshot ? `${(snapshot.usageRatio * 100).toFixed(1)}%` : "N/A"} color={snapshot && snapshot.usageRatio > 0.75 ? "text-amber-500" : undefined} />
        <StatCard label="Chunk 数" value={snapshot?.chunkCount?.toString() ?? "0"} />
        <StatCard label="长期记忆" value={stats?.totalCount?.toString() ?? "0"} />
      </div>

      {snapshot && (
        <div>
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">工作记忆 Token 使用</h3>
          <div className="w-full bg-slate-200 dark:bg-slate-700 rounded-full h-3">
            <div
              className={`h-3 rounded-full transition-all ${
                snapshot.usageRatio > 0.9 ? "bg-red-500" : snapshot.usageRatio > 0.75 ? "bg-amber-500" : "bg-indigo-500"
              }`}
              style={{ width: `${Math.min(100, snapshot.usageRatio * 100)}%` }}
            />
          </div>
          <p className="text-xs text-slate-500 mt-1">
            {snapshot.tokenUsed.toLocaleString()} / {snapshot.tokenBudget.toLocaleString()} tokens（API 基准 + 轮间增量估计）
          </p>
          {snapshot.tokenEstimated != null && (
            <p className="text-xs text-slate-400 mt-0.5">
              分块启发式合计：{snapshot.tokenEstimated.toLocaleString()} tokens
            </p>
          )}
        </div>
      )}

      {snapshot && snapshot.chunks.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">Chunk 列表</h3>
          <div className="space-y-2">
            {snapshot.chunks.map((chunk) => (
              <ChunkCard key={chunk.id} chunk={chunk} />
            ))}
          </div>
        </div>
      )}

      {consolidationLog.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">巩固日志</h3>
          <div className="space-y-2">
            {consolidationLog.map((entry, i) => (
              <ConsolidationCard key={i} entry={entry} />
            ))}
          </div>
        </div>
      )}

      {!snapshot && consolidationLog.length === 0 && (
        <div className="text-center py-12 text-slate-400">
          <Brain size={48} className="mx-auto mb-3 opacity-30" />
          <p>暂无工作记忆数据。发送消息后记忆系统将自动激活。</p>
        </div>
      )}
    </div>
  );
}

function StatCard({ label, value, color }: { label: string; value: string; color?: string }) {
  return (
    <div className="bg-slate-50 dark:bg-slate-800 rounded-xl p-4 border border-slate-200 dark:border-slate-700">
      <p className="text-xs text-slate-500 dark:text-slate-400 mb-1">{label}</p>
      <p className={`text-xl font-bold ${color ?? "text-slate-800 dark:text-slate-100"}`}>{value}</p>
    </div>
  );
}

function ChunkCard({ chunk }: { chunk: MemorySnapshotData["chunks"][0] }) {
  const [expanded, setExpanded] = useState(false);
  const typeColors: Record<string, string> = {
    SYSTEM: "bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-300",
    USER: "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300",
    ASSISTANT: "bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300",
    TOOL_INTERACTION: "bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300",
    CONSOLIDATED: "bg-indigo-100 text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-300",
    RECALLED: "bg-pink-100 text-pink-700 dark:bg-pink-900/30 dark:text-pink-300",
  };

  return (
    <div
      className="bg-white dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700 p-3 cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-750 transition-colors"
      onClick={() => setExpanded(!expanded)}
    >
      <div className="flex items-center gap-2">
        <span className={`text-xs px-2 py-0.5 rounded-full font-medium ${typeColors[chunk.type] ?? "bg-slate-100 text-slate-600"}`}>
          {chunk.type}
        </span>
        <span className="text-xs text-slate-500">{chunk.category}</span>
        <span className="text-xs text-slate-400 ml-auto">{chunk.tokens} tokens</span>
        <ImportanceBar importance={chunk.importance} />
      </div>
      {expanded && (
        <pre className="mt-2 text-xs text-slate-600 dark:text-slate-300 whitespace-pre-wrap break-words max-h-40 overflow-y-auto">
          {chunk.contentPreview}
        </pre>
      )}
    </div>
  );
}

function ImportanceBar({ importance }: { importance: number }) {
  const color = importance > 0.8 ? "bg-red-400" : importance > 0.5 ? "bg-amber-400" : "bg-slate-300";
  return (
    <div className="w-12 h-1.5 bg-slate-200 dark:bg-slate-700 rounded-full overflow-hidden" title={`importance: ${importance.toFixed(2)}`}>
      <div className={`h-full rounded-full ${color}`} style={{ width: `${importance * 100}%` }} />
    </div>
  );
}

function ConsolidationCard({ entry }: { entry: ConsolidationLogEntry }) {
  const saved = entry.tokensBefore - entry.tokensAfter;
  const [expanded, setExpanded] = useState(false);
  return (
    <div className="bg-indigo-50 dark:bg-indigo-900/20 rounded-lg border border-indigo-200 dark:border-indigo-800 p-3">
      <div className="flex items-center gap-3 text-xs text-indigo-700 dark:text-indigo-300">
        <span>{new Date(entry.timestamp).toLocaleTimeString()}</span>
        <span>{entry.chunksSelected} chunks</span>
        <span>{entry.tokensBefore} → {entry.tokensAfter} tokens (节省 {saved})</span>
        {entry.factsStoredToLongTerm !== undefined && (
          <span className={entry.factsStoredToLongTerm ? "text-green-600 dark:text-green-400" : "text-slate-400"}>
            {entry.factsStoredToLongTerm ? "✓ 已存入长期记忆" : "○ 未存入长期记忆"}
          </span>
        )}
      </div>

      {entry.candidates && entry.candidates.length > 0 && (
        <div className="mt-2">
          <button
            onClick={() => setExpanded(!expanded)}
            className="text-xs text-indigo-500 hover:text-indigo-700 dark:text-indigo-400"
          >
            {expanded ? "▼ 收起压缩详情" : "▶ 展开压缩详情"}
          </button>
          {expanded && (
            <div className="mt-1 space-y-1 pl-2 border-l-2 border-indigo-200 dark:border-indigo-700">
              {entry.candidates.map((chunk, i) => (
                <div key={i} className="text-xs text-slate-600 dark:text-slate-400">
                  <span className="inline-flex items-center gap-1.5">
                    <span className="px-1 py-0.5 rounded bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300 font-mono">
                      {chunk.type}
                    </span>
                    <span className="text-slate-400">{chunk.tokens}t</span>
                    <span className="text-slate-400">imp:{chunk.importance.toFixed(2)}</span>
                  </span>
                  <p className="mt-0.5 text-slate-500 dark:text-slate-400 truncate">{chunk.preview}</p>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {entry.extractedFacts.length > 0 && (
        <div className="mt-2 space-y-1">
          <p className="text-xs font-medium text-indigo-600 dark:text-indigo-400">提取的事实：</p>
          {entry.extractedFacts.map((fact, i) => (
            <p key={i} className="text-xs text-slate-600 dark:text-slate-400">• {fact}</p>
          ))}
        </div>
      )}
    </div>
  );
}

function LongTermTab() {
  const [subTab, setSubTab] = useState<"episodic" | "semantic" | "procedural">("episodic");
  const memories = useMemoryStore((s) => s.longTermMemories);
  const fetchMem = useMemoryStore((s) => s.fetchLongTermMemories);
  const deleteMem = useMemoryStore((s) => s.deleteLongTermMemory);
  const selectedAgentId = useMemoryStore((s) => s.selectedAgentId);
  const [search, setSearch] = useState("");

  useEffect(() => {
    void fetchMem(undefined, subTab);
  }, [fetchMem, subTab, selectedAgentId]);

  const filtered = search
    ? memories.filter((m) => m.content.toLowerCase().includes(search.toLowerCase()))
    : memories;

  return (
    <div className="max-w-5xl space-y-4">
      <div className="flex gap-2">
        {(["episodic", "semantic", "procedural"] as const).map((t) => (
          <button
            key={t}
            onClick={() => setSubTab(t)}
            className={`px-3 py-1.5 text-xs rounded-lg transition-colors ${
              subTab === t
                ? "bg-indigo-100 text-indigo-700 dark:bg-indigo-900/40 dark:text-indigo-300"
                : "bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-400 hover:bg-slate-200 dark:hover:bg-slate-700"
            }`}
          >
            {t === "episodic" ? "情景记忆" : t === "semantic" ? "语义记忆" : "程序记忆"}
          </button>
        ))}
        <input
          type="text"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="搜索..."
          className="ml-auto px-3 py-1.5 text-xs rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200 w-48"
        />
        <button onClick={() => fetchMem("default", subTab)} className="p-1.5 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800">
          <RefreshCw size={14} className="text-slate-500" />
        </button>
      </div>

      {filtered.length === 0 ? (
        <div className="text-center py-12 text-slate-400">
          <Database size={36} className="mx-auto mb-2 opacity-30" />
          <p className="text-sm">暂无{subTab === "episodic" ? "情景" : subTab === "semantic" ? "语义" : "程序"}记忆</p>
        </div>
      ) : (
        <div className="space-y-2">
          {filtered.map((mem) => (
            <MemoryItem key={mem.id} memory={mem} onDelete={() => deleteMem(mem.id)} />
          ))}
        </div>
      )}
    </div>
  );
}

function MemoryItem({ memory, onDelete }: { memory: LongTermMemoryItem; onDelete: () => void }) {
  return (
    <div className="bg-white dark:bg-slate-800 rounded-lg border border-slate-200 dark:border-slate-700 p-3">
      <div className="flex items-start gap-3">
        <div className="flex-1 min-w-0">
          <p className="text-sm text-slate-700 dark:text-slate-200 break-words">{memory.content}</p>
          <div className="flex items-center gap-3 mt-2 text-xs text-slate-400">
            <ImportanceBar importance={memory.importance} />
            <span>访问 {memory.accessCount} 次</span>
            {memory.lastAccessedAt && <span>最近: {new Date(memory.lastAccessedAt).toLocaleDateString()}</span>}
            <span>创建: {new Date(memory.createdAt).toLocaleDateString()}</span>
          </div>
        </div>
        <button onClick={onDelete} className="p-1.5 rounded hover:bg-red-50 dark:hover:bg-red-900/30 text-slate-400 hover:text-red-500 transition-colors">
          <Trash2 size={14} />
        </button>
      </div>
    </div>
  );
}

function ConfigTab() {
  const config = useMemoryStore((s) => s.memoryConfig);
  const loading = useMemoryStore((s) => s.configLoading);
  const configError = useMemoryStore((s) => s.configError);
  const fetchConfig = useMemoryStore((s) => s.fetchConfig);
  const saveConfig = useMemoryStore((s) => s.saveConfig);
  const resetCfg = useMemoryStore((s) => s.resetConfig);
  const selectedAgentId = useMemoryStore((s) => s.selectedAgentId);
  const [edits, setEdits] = useState<Record<string, string>>({});
  const [saving, setSaving] = useState(false);
  const [modelGroups, setModelGroups] = useState<ModelGroup[]>([]);

  useEffect(() => { fetchConfig(); }, [fetchConfig, selectedAgentId]);
  useEffect(() => { setEdits({}); }, [config]);
  useEffect(() => {
    fetchModelGroups().then(setModelGroups).catch(() => {});
  }, []);

  const hasEdits = Object.keys(edits).length > 0;

  const handleSave = async () => {
    setSaving(true);
    try {
      await saveConfig(edits);
      setEdits({});
    } finally {
      setSaving(false);
    }
  };

  const handleReset = async () => {
    if (!confirm("确定恢复所有配置为默认值？")) return;
    setSaving(true);
    try {
      await resetCfg();
      setEdits({});
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <div className="text-center py-12 text-slate-400">加载配置中...</div>;
  }

  if (configError || !config) {
    return (
      <div className="text-center py-12">
        <p className="text-red-500 mb-4">{configError ?? "加载配置失败"}</p>
        <button
          onClick={() => fetchConfig()}
          className="px-4 py-2 bg-indigo-600 text-white rounded-lg hover:bg-indigo-700 transition-colors text-sm"
        >
          重试
        </button>
      </div>
    );
  }

  return (
    <div className="max-w-4xl space-y-8">
      <ConfigSection
        title="工作记忆"
        prefix="working"
        items={config.working}
        edits={edits}
        setEdits={setEdits}
      />
      <ConfigSection
        title="记忆巩固"
        prefix="consolidation"
        items={config.consolidation}
        edits={edits}
        setEdits={setEdits}
        modelGroups={modelGroups}
      />
      <ConfigSection
        title="长期记忆"
        prefix="long_term"
        items={config.longTerm}
        edits={edits}
        setEdits={setEdits}
      />

      <div className="flex gap-3 pt-4 border-t border-slate-200 dark:border-slate-700">
        <button
          onClick={handleSave}
          disabled={!hasEdits || saving}
          className="px-4 py-2 text-sm font-medium rounded-lg bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          {saving ? "保存中..." : hasEdits ? "保存修改" : "无修改"}
        </button>
        <button
          onClick={handleReset}
          disabled={saving}
          className="px-4 py-2 text-sm font-medium rounded-lg border border-slate-300 dark:border-slate-600 text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800 disabled:opacity-50 transition-colors flex items-center gap-1.5"
        >
          <RotateCcw size={14} />
          恢复默认
        </button>
      </div>
    </div>
  );
}

const CONFIG_TOOLTIPS: Record<string, string> = {
  "working.consolidation_threshold":
    "当工作记忆的 token 使用率超过此阈值时，自动触发记忆巩固（压缩）。值范围 0.5~0.95，越低则巩固越频繁，上下文越精简；越高则保留更多原始对话，但可能溢出。",
  "working.token_budget":
    "工作记忆的 token 容量上限。等于模型的最大上下文窗口减去预留给输出的 token 数。超出此预算时将触发巩固或截断，防止请求超限。",
  "consolidation.model":
    "执行记忆巩固（摘要/事实提取）时使用的 LLM 模型。建议选择速度快、成本低的模型，因为巩固是后台异步操作，不影响主对话延迟。",
  "consolidation.timeout_ms":
    "单次巩固 LLM 调用的超时时间（毫秒）。超时后会取消巩固并保留原始内容。设置过短可能导致巩固频繁失败；过长可能阻塞内存释放。",
  "consolidation.max_summary_tokens":
    "巩固摘要的最大 token 数。巩固后的摘要不会超过此长度。值越小压缩越激进（丢失细节更多），越大则摘要越详细但节省的空间越少。",
  "consolidation.max_retries":
    "巩固 LLM 调用失败时的最大重试次数。包括超时、网络错误等场景。重试之间会短暂延迟。超过重试次数后回退到 fallback_model 或保持原样。",
  "consolidation.fallback_model":
    "当主巩固模型不可用或重试耗尽时，使用的备用模型。建议选择稳定性高的模型作为兜底，确保巩固流程不会完全中断。",
  "consolidation.overflow_tolerance":
    "允许工作记忆临时超出 token_budget 的弹性比例。例如 1.1 表示允许超出 10%。在巩固完成前提供缓冲区，避免硬截断丢失正在处理的对话。",
  "long_term.enabled":
    "是否启用长期记忆功能。开启后，系统会将巩固后的知识存入长期记忆库，并在新对话开始时自动检索相关记忆注入上下文，让 Agent 具有跨会话的持久记忆。",
  "long_term.compaction_threshold":
    "当单个用户+Agent 的长期记忆条数超过此阈值时，触发自动压缩（合并相似记忆、清理冗余）。防止记忆库无限膨胀。",
  "long_term.max_memories_per_user":
    "单个用户在每个 Agent 下可保留的最大长期记忆条数。超出后将按重要性和访问频率淘汰旧记忆（遗忘机制）。",
  "long_term.max_injection_tokens":
    "每次对话开始时，从长期记忆中检索并注入上下文的最大 token 数。控制注入量避免占用过多上下文窗口。建议设为 token_budget 的 10%~20%。",
  "long_term.forgetting_cron":
    "遗忘调度的 Cron 表达式。定时执行记忆衰减评估、淘汰低价值记忆、归档冷数据。默认每天凌晨2点执行。格式: 秒 分 时 日 月 星期。",
  "long_term.decay_rate":
    "记忆衰减速率。每次遗忘调度时，未被访问的记忆得分会按此比例衰减。值越大遗忘越快（0.1 = 每次衰减 10%）。值为 0 表示永不衰减。",
  "long_term.decay_lambda":
    "记忆衰减系数 λ。控制遗忘曲线的陡峭程度：得分衰减公式为 score × (1 - λ)。值越大遗忘越快（0.1 表示每次衰减约 10%）。值为 0 表示永不衰减。建议范围 0.05~0.2。",
  "long_term.archive_after_days":
    "冷记忆归档天数。当记忆超过此天数未被访问且得分低于阈值时，将从主表迁移到归档表（冷存储）。归档后不再参与检索，但可在「遗忘日志」中查看。",
};

function Tooltip({ text }: { text: string }) {
  const [visible, setVisible] = useState(false);
  return (
    <span className="relative inline-flex ml-1" onMouseEnter={() => setVisible(true)} onMouseLeave={() => setVisible(false)}>
      <HelpCircle size={14} className="text-slate-400 hover:text-indigo-500 cursor-help transition-colors" />
      {visible && (
        <span className="absolute z-50 left-full ml-2 top-1/2 -translate-y-1/2 w-72 px-3 py-2 text-xs leading-relaxed text-slate-100 bg-slate-800 dark:bg-slate-700 rounded-lg shadow-lg pointer-events-none">
          {text}
          <span className="absolute right-full top-1/2 -translate-y-1/2 w-0 h-0 border-y-[6px] border-y-transparent border-r-[6px] border-r-slate-800 dark:border-r-slate-700" />
        </span>
      )}
    </span>
  );
}

function ConfigSection({
  title,
  prefix,
  items,
  edits,
  setEdits,
  modelGroups,
}: {
  title: string;
  prefix: string;
  items: Record<string, MemoryConfigItem>;
  edits: Record<string, string>;
  setEdits: React.Dispatch<React.SetStateAction<Record<string, string>>>;
  modelGroups?: ModelGroup[];
}) {
  const isModelKey = (key: string) => key === "model" || key === "fallback_model";
  const hasModels = modelGroups && modelGroups.length > 0;

  return (
    <div>
      <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-3">{title}</h3>
      <div className="space-y-3">
        {Object.entries(items).map(([key, item]) => {
          const fullKey = `${prefix}.${key}`;
          const currentValue = edits[fullKey] ?? item.value;
          const isModified = fullKey in edits;
          const borderClass = isModified
            ? "border-indigo-400 dark:border-indigo-500 ring-1 ring-indigo-200 dark:ring-indigo-800"
            : "border-slate-200 dark:border-slate-700";

          return (
            <div key={fullKey} className="flex items-center gap-4">
              <div className="w-48 flex-shrink-0">
                <p className="text-sm text-slate-700 dark:text-slate-200 flex items-center">
                  {key}
                  {CONFIG_TOOLTIPS[fullKey] && <Tooltip text={CONFIG_TOOLTIPS[fullKey]} />}
                </p>
                <p className="text-xs text-slate-400">{item.description}</p>
              </div>
              <div className="flex-1">
                {item.type === "boolean" ? (
                  <button
                    onClick={() => {
                      const newVal = currentValue === "true" ? "false" : "true";
                      setEdits((prev) => ({ ...prev, [fullKey]: newVal }));
                    }}
                    className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
                      currentValue === "true" ? "bg-indigo-600" : "bg-slate-300 dark:bg-slate-600"
                    }`}
                  >
                    <span
                      className={`inline-block h-4 w-4 rounded-full bg-white transition-transform ${
                        currentValue === "true" ? "translate-x-6" : "translate-x-1"
                      }`}
                    />
                  </button>
                ) : isModelKey(key) && hasModels ? (
                  <select
                    value={currentValue}
                    onChange={(e) => setEdits((prev) => ({ ...prev, [fullKey]: e.target.value }))}
                    className={`w-full px-3 py-1.5 text-sm rounded-lg border ${borderClass} bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200`}
                  >
                    {!modelGroups.some((g) => g.models.some((m) => m.modelId === currentValue)) && (
                      <option value={currentValue}>{currentValue}</option>
                    )}
                    {modelGroups.map((group) => (
                      <optgroup key={group.providerId} label={group.providerName}>
                        {group.models.map((m) => (
                          <option key={m.id} value={m.modelId}>
                            {m.displayName || m.modelId}
                          </option>
                        ))}
                      </optgroup>
                    ))}
                  </select>
                ) : (
                  <input
                    type={item.type === "number" ? "number" : "text"}
                    value={currentValue}
                    onChange={(e) => setEdits((prev) => ({ ...prev, [fullKey]: e.target.value }))}
                    className={`w-full px-3 py-1.5 text-sm rounded-lg border ${borderClass} bg-white dark:bg-slate-800 text-slate-700 dark:text-slate-200`}
                  />
                )}
              </div>
              <span className="text-xs text-slate-400 w-20 text-right flex-shrink-0">默认: {item.default}</span>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function ForgettingLogTab() {
  const selectedAgentId = useMemoryStore((s) => s.selectedAgentId);
  const [rows, setRows] = useState<ArchivedMemoryItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    setLoading(true);
    setError(null);
    fetchArchivedMemories("default", selectedAgentId)
      .then(setRows)
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    void load();
  }, [selectedAgentId]);

  return (
    <div className="max-w-5xl space-y-4">
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm text-slate-600 dark:text-slate-300">
          以下为冷归档记忆（夜间任务写入归档表）。遗忘/压实在服务端执行，此处展示归档结果。
        </p>
        <button
          type="button"
          onClick={() => void load()}
          disabled={loading}
          className="px-3 py-1.5 text-xs rounded-lg border border-slate-200 dark:border-slate-600 hover:bg-slate-50 dark:hover:bg-slate-800 text-slate-600 dark:text-slate-300 disabled:opacity-50 flex items-center gap-1.5"
        >
          <RefreshCw size={14} className={loading ? "animate-spin" : ""} />
          刷新
        </button>
      </div>
      <p className="text-xs text-slate-400">调度：每天凌晨 3:00（按 userId + agentId 分区维护）。</p>

      {error && (
        <div className="rounded-lg border border-red-200 dark:border-red-900/50 bg-red-50 dark:bg-red-900/20 px-3 py-2 text-sm text-red-700 dark:text-red-300">
          {error}
        </div>
      )}

      {loading ? (
        <div className="text-center py-12 text-slate-400 text-sm">加载中…</div>
      ) : rows.length === 0 ? (
        <div className="text-center py-12 text-slate-400">
          <Trash2 size={36} className="mx-auto mb-2 opacity-30" />
          <p className="text-sm">暂无归档记录。</p>
        </div>
      ) : (
        <div className="overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700">
          <table className="w-full text-sm text-left">
            <thead className="bg-slate-50 dark:bg-slate-800/80 text-slate-600 dark:text-slate-300">
              <tr>
                <th className="px-3 py-2 font-medium">类型</th>
                <th className="px-3 py-2 font-medium">内容</th>
                <th className="px-3 py-2 font-medium w-24">重要度</th>
                <th className="px-3 py-2 font-medium w-28">访问</th>
                <th className="px-3 py-2 font-medium w-36">归档时间</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-200 dark:divide-slate-700">
              {rows.map((r) => (
                <tr key={r.id} className="bg-white dark:bg-slate-900/40">
                  <td className="px-3 py-2 text-slate-500 whitespace-nowrap">{r.memoryType}</td>
                  <td className="px-3 py-2 text-slate-800 dark:text-slate-100 max-w-md truncate" title={r.content}>
                    {r.content}
                  </td>
                  <td className="px-3 py-2 text-slate-600 dark:text-slate-300">
                    {typeof r.importance === "number" ? r.importance.toFixed(2) : "—"}
                  </td>
                  <td className="px-3 py-2 text-slate-500">{r.accessCount}</td>
                  <td className="px-3 py-2 text-slate-500 whitespace-nowrap text-xs">
                    {r.archivedAt ? new Date(r.archivedAt).toLocaleString() : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
