import { useState, useCallback, useEffect, useMemo, useRef } from "react";
import { ArrowLeft, Sparkles, Plus, Pencil, Trash2, Loader2, FileText, Terminal, BookOpen, Download, Upload, FolderOpen, GitBranch } from "lucide-react";
import {
  fetchSkillDefinitions,
  createSkillDefinition,
  updateSkillDefinition,
  deleteSkillDefinition,
  exportSkillZip,
  fetchSkillStats,
  importSkillFromGit,
  type SkillDefinition,
  type SkillDefinitionCreate,
  type GitImportParams,
} from "../lib/api";
import { parseSkillMd, parsedToCreate, downloadSkillMd } from "../lib/skillImporter";
import SkillEditor from "./SkillEditor";
import SkillGroupManager from "./SkillGroupManager";

interface SkillManagerPageProps {
  onBack: () => void;
}

type View = "list" | "create" | "edit" | "import" | "groups";

export default function SkillManagerPage({ onBack }: SkillManagerPageProps) {
  const [skills, setSkills] = useState<SkillDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<View>("list");
  const [editingSkill, setEditingSkill] = useState<SkillDefinition | null>(null);
  const [deleting, setDeleting] = useState<number | null>(null);
  const [importText, setImportText] = useState("");
  const [importError, setImportError] = useState<string | null>(null);
  const [importMode, setImportMode] = useState<"paste" | "upload" | "git">("paste");
  const [gitUrl, setGitUrl] = useState("");
  const [gitBranch, setGitBranch] = useState("");
  const [gitSubPath, setGitSubPath] = useState("");
  const [gitImporting, setGitImporting] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [selectedTag, setSelectedTag] = useState<string | null>(null);
  const [exportingId, setExportingId] = useState<number | null>(null);
  const [statsMap, setStatsMap] = useState<Record<string, number>>({});

  const loadSkills = useCallback(() => {
    setLoading(true);
    setError(null);
    fetchSkillDefinitions()
      .then(setSkills)
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
    fetchSkillStats()
      .then((stats) => {
        const map: Record<string, number> = {};
        stats.forEach((s) => { map[s.skillName] = s.totalActivations; });
        setStatsMap(map);
      })
      .catch(() => { /* stats are optional */ });
  }, []);

  useEffect(() => {
    loadSkills();
    setView("list");
    setEditingSkill(null);
  }, [loadSkills]);

  const handleCreate = useCallback(
    async (data: SkillDefinitionCreate) => {
      await createSkillDefinition(data);
      setView("list");
      loadSkills();
    },
    [loadSkills],
  );

  const handleUpdate = useCallback(
    async (data: SkillDefinitionCreate) => {
      if (!editingSkill) return;
      await updateSkillDefinition(editingSkill.id, data);
      setView("list");
      setEditingSkill(null);
      loadSkills();
    },
    [editingSkill, loadSkills],
  );

  const handleDelete = useCallback(
    async (id: number) => {
      if (!window.confirm("确定删除此 Skill？")) return;
      setDeleting(id);
      try {
        await deleteSkillDefinition(id);
        loadSkills();
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setDeleting(null);
      }
    },
    [loadSkills],
  );

  const handleToggleEnabled = useCallback(
    async (skill: SkillDefinition) => {
      try {
        await updateSkillDefinition(skill.id, {
          enabled: skill.enabled === 1 ? 0 : 1,
        });
        loadSkills();
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    },
    [loadSkills],
  );

  const allTags = useMemo(() => {
    const tagSet = new Set<string>();
    skills.forEach((s) => {
      if (s.tags) s.tags.split(",").forEach((t) => tagSet.add(t.trim()));
    });
    return [...tagSet].sort();
  }, [skills]);

  const filteredSkills = useMemo(
    () =>
      selectedTag
        ? skills.filter((s) =>
            s.tags?.split(",").map((t) => t.trim()).includes(selectedTag),
          )
        : skills,
    [skills, selectedTag],
  );

  const handleImportParse = useCallback(() => {
    setImportError(null);
    if (!importText.trim()) {
      setImportError("请粘贴 SKILL.md 内容");
      return;
    }
    const parsed = parseSkillMd(importText);
    if (parsed.warnings && parsed.warnings.length > 0) {
      setImportError(`提示: ${parsed.warnings.join("; ")}`);
    }
    const prefill = parsedToCreate(parsed);
    setEditingSkill({
      id: 0,
      name: prefill.name ?? "",
      displayName: prefill.displayName ?? null,
      description: prefill.description ?? "",
      content: prefill.content ?? null,
      tags: prefill.tags ?? null,
      metadata: prefill.metadata ? JSON.stringify(prefill.metadata) : null,
      hasScripts: 0,
      hasReferences: 0,
      enabled: 1,
      gitUrl: null,
      gitSubPath: null,
      createdAt: "",
      updatedAt: "",
    } as SkillDefinition);
    setView("create");
    setImportText("");
  }, [importText]);

  const handleFileUpload = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    e.target.value = "";
    const reader = new FileReader();
    reader.onload = () => {
      const text = reader.result as string;
      setImportText(text);
      setImportMode("paste");
    };
    reader.readAsText(file);
  }, []);

  const handleGitImport = useCallback(async () => {
    setImportError(null);
    if (!gitUrl.trim()) {
      setImportError("请输入 Git URL");
      return;
    }
    setGitImporting(true);
    try {
      const params: GitImportParams = { gitUrl: gitUrl.trim() };
      if (gitBranch.trim()) params.branch = gitBranch.trim();
      if (gitSubPath.trim()) params.subPath = gitSubPath.trim();
      await importSkillFromGit(params);
      setGitUrl("");
      setGitBranch("");
      setGitSubPath("");
      setView("list");
      loadSkills();
    } catch (e) {
      setImportError(e instanceof Error ? e.message : String(e));
    } finally {
      setGitImporting(false);
    }
  }, [gitUrl, gitBranch, gitSubPath, loadSkills]);

  const handleExportMd = useCallback((skill: SkillDefinition) => {
    downloadSkillMd(skill);
  }, []);

  const handleExportZip = useCallback(async (skill: SkillDefinition) => {
    setExportingId(skill.id);
    try {
      await exportSkillZip(skill.id, skill.name);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setExportingId(null);
    }
  }, []);

  return (
    <div className="flex flex-col flex-1 min-w-0 min-h-0">
      {/* Header */}
      <div className="px-6 py-4 border-b border-slate-200 dark:border-slate-700 flex items-center gap-3">
        <button
          onClick={onBack}
          className="p-1.5 rounded hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
        >
          <ArrowLeft size={18} className="text-slate-500" />
        </button>
        <Sparkles size={20} className="text-blue-500" />
        <h1 className="text-lg font-semibold">Skills 管理</h1>

        <div className="ml-auto flex items-center gap-2">
          <button
            type="button"
            onClick={() => setView("groups")}
            className={`flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium rounded-lg transition-colors ${
              view === "groups"
                ? "text-blue-700 bg-blue-50 dark:text-blue-400 dark:bg-blue-900/30"
                : "text-slate-700 dark:text-slate-200 bg-slate-100 dark:bg-slate-800 hover:bg-slate-200 dark:hover:bg-slate-700"
            }`}
          >
            <FolderOpen size={14} />
            分组管理
          </button>
          <button
            type="button"
            onClick={() => { setImportText(""); setImportError(null); setView("import"); }}
            className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-slate-700 dark:text-slate-200 bg-slate-100 dark:bg-slate-800 rounded-lg hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
          >
            <Upload size={14} />
            导入 SKILL.md
          </button>
          <button
            type="button"
            onClick={() => { setEditingSkill(null); setView("create"); }}
            className="flex items-center gap-1.5 px-3 py-1.5 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors"
          >
            <Plus size={14} />
            创建 Skill
          </button>
        </div>
      </div>

      {/* Body */}
      <div className="flex-1 min-h-0 overflow-y-auto px-6 py-4">
        {view === "groups" && <SkillGroupManager />}
        {view === "list" && (
          <>
            {/* Tag Filter */}
            {allTags.length > 0 && (
              <div className="flex flex-wrap gap-1.5 mb-4">
                <button
                  type="button"
                  onClick={() => setSelectedTag(null)}
                  className={`px-2.5 py-1 text-xs rounded-full border transition-colors ${
                    selectedTag === null
                      ? "border-blue-500 bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400 dark:border-blue-600"
                      : "border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-400 hover:border-slate-300 dark:hover:border-slate-600"
                  }`}
                >
                  全部
                </button>
                {allTags.map((tag) => (
                  <button
                    key={tag}
                    type="button"
                    onClick={() => setSelectedTag(selectedTag === tag ? null : tag)}
                    className={`px-2.5 py-1 text-xs rounded-full border transition-colors ${
                      selectedTag === tag
                        ? "border-blue-500 bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400 dark:border-blue-600"
                        : "border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-400 hover:border-slate-300 dark:hover:border-slate-600"
                    }`}
                  >
                    {tag}
                  </button>
                ))}
              </div>
            )}

            {error && (
              <div className="mb-4 px-3 py-2 text-sm text-red-600 bg-red-50 dark:bg-red-900/20 dark:text-red-400 rounded-lg">
                {error}
              </div>
            )}

            {loading ? (
              <div className="flex items-center justify-center py-12">
                <Loader2 size={24} className="animate-spin text-slate-400" />
              </div>
            ) : filteredSkills.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 text-slate-400 dark:text-slate-500 space-y-2">
                <FileText size={32} className="opacity-50" />
                <p className="text-sm">{selectedTag ? "该标签下没有 Skill" : "还没有创建任何 Skill"}</p>
                <p className="text-xs">{selectedTag ? "尝试其他标签或清除筛选" : "点击上方「创建 Skill」开始"}</p>
              </div>
            ) : (
              <div className="space-y-3">
                {filteredSkills.map((skill) => (
                  <div
                    key={skill.id}
                    className="rounded-lg border border-slate-200 dark:border-slate-700 px-4 py-3"
                  >
                    <div className="flex items-start justify-between">
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                          <span className="text-sm font-semibold text-slate-800 dark:text-slate-100">
                            {skill.displayName || skill.name}
                          </span>
                          <span className="text-xs font-mono text-slate-400">
                            {skill.name}
                          </span>
                          <span
                            className={`px-1.5 py-0.5 text-[10px] font-medium rounded ${
                              skill.enabled === 1
                                ? "bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400"
                                : "bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-500"
                            }`}
                          >
                            {skill.enabled === 1 ? "已启用" : "已禁用"}
                          </span>
                          {(statsMap[skill.name] ?? 0) > 0 && (
                            <span className="px-1.5 py-0.5 text-[10px] font-medium rounded bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400">
                              {statsMap[skill.name]} 次激活
                            </span>
                          )}
                        </div>
                        <p className="text-xs text-slate-500 dark:text-slate-400 mt-1 line-clamp-2">
                          {skill.description}
                        </p>
                        <div className="flex items-center gap-3 mt-1.5">
                          {skill.tags && (
                            <div className="flex gap-1">
                              {skill.tags.split(",").map((t) => (
                                <span
                                  key={t.trim()}
                                  className="px-1.5 py-0.5 text-[10px] rounded bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400"
                                >
                                  {t.trim()}
                                </span>
                              ))}
                            </div>
                          )}
                          {skill.content && (
                            <span className="flex items-center gap-0.5 text-[10px] text-slate-400">
                              <FileText size={10} /> SKILL.md
                            </span>
                          )}
                          {skill.hasScripts === 1 && (
                            <span className="flex items-center gap-0.5 text-[10px] text-emerald-600 dark:text-emerald-400">
                              <Terminal size={10} /> scripts
                            </span>
                          )}
                          {skill.hasReferences === 1 && (
                            <span className="flex items-center gap-0.5 text-[10px] text-sky-600 dark:text-sky-400">
                              <BookOpen size={10} /> references
                            </span>
                          )}
                        </div>
                      </div>
                      <div className="flex items-center gap-1 ml-3 shrink-0">
                        <button
                          type="button"
                          onClick={() => handleToggleEnabled(skill)}
                          className="px-2 py-1 text-xs rounded-md border border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800 transition-colors"
                        >
                          {skill.enabled === 1 ? "禁用" : "启用"}
                        </button>
                        {/* Export dropdown */}
                        <div className="relative group">
                          <button
                            type="button"
                            disabled={exportingId === skill.id}
                            className="p-1.5 rounded-md hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                            title="导出"
                          >
                            {exportingId === skill.id ? (
                              <Loader2 size={14} className="animate-spin text-slate-400" />
                            ) : (
                              <Download size={14} className="text-slate-400" />
                            )}
                          </button>
                          <div className="absolute right-0 top-full mt-1 z-10 hidden group-hover:block bg-white dark:bg-slate-800 border border-slate-200 dark:border-slate-700 rounded-lg shadow-lg py-1 min-w-[120px]">
                            <button
                              type="button"
                              onClick={() => handleExportMd(skill)}
                              className="w-full px-3 py-1.5 text-xs text-left text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700"
                            >
                              导出 .md
                            </button>
                            <button
                              type="button"
                              onClick={() => handleExportZip(skill)}
                              className="w-full px-3 py-1.5 text-xs text-left text-slate-700 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-700"
                            >
                              导出 .zip
                            </button>
                          </div>
                        </div>
                        <button
                          type="button"
                          onClick={() => {
                            setEditingSkill(skill);
                            setView("edit");
                          }}
                          className="p-1.5 rounded-md hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                          title="编辑"
                        >
                          <Pencil size={14} className="text-slate-400" />
                        </button>
                        <button
                          type="button"
                          onClick={() => handleDelete(skill.id)}
                          disabled={deleting === skill.id}
                          className="p-1.5 rounded-md hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors disabled:opacity-30 disabled:cursor-not-allowed"
                          title="删除"
                        >
                          {deleting === skill.id ? (
                            <Loader2 size={14} className="animate-spin text-red-400" />
                          ) : (
                            <Trash2 size={14} className="text-red-400" />
                          )}
                        </button>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </>
        )}

        {view === "import" && (
          <div className="space-y-4">
            <h3 className="text-base font-semibold text-slate-800 dark:text-slate-100">
              导入 Skill
            </h3>

            {/* 3 Tab Bar */}
            <div className="flex border-b border-slate-200 dark:border-slate-700">
              {([
                { key: "paste" as const, label: "粘贴文本", icon: <FileText size={14} /> },
                { key: "upload" as const, label: "上传文件", icon: <Upload size={14} /> },
                { key: "git" as const, label: "Git URL", icon: <GitBranch size={14} /> },
              ]).map((tab) => (
                <button
                  key={tab.key}
                  type="button"
                  onClick={() => { setImportMode(tab.key); setImportError(null); }}
                  className={`flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
                    importMode === tab.key
                      ? "border-blue-500 text-blue-600 dark:text-blue-400"
                      : "border-transparent text-slate-500 dark:text-slate-400 hover:text-slate-700 dark:hover:text-slate-300"
                  }`}
                >
                  {tab.icon}
                  {tab.label}
                </button>
              ))}
            </div>

            {importError && (
              <div className={`px-3 py-2 text-sm rounded-lg ${
                importError.startsWith("提示:")
                  ? "text-amber-700 bg-amber-50 dark:bg-amber-900/20 dark:text-amber-400"
                  : "text-red-600 bg-red-50 dark:bg-red-900/20 dark:text-red-400"
              }`}>
                {importError}
              </div>
            )}

            {/* Paste Mode */}
            {importMode === "paste" && (
              <>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  粘贴标准 SKILL.md 内容（含 YAML frontmatter），系统将自动解析并预填充表单。
                </p>
                <textarea
                  value={importText}
                  onChange={(e) => setImportText(e.target.value)}
                  placeholder={"---\nname: my-skill\ndescription: Does something useful...\n---\n\n# My Skill\n\n## Step 1\n..."}
                  rows={16}
                  className="w-full px-3 py-2 text-sm font-mono rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 resize-y"
                />
                <div className="flex items-center justify-end gap-2">
                  <button type="button" onClick={() => setView("list")}
                    className="px-4 py-2 text-sm text-slate-600 dark:text-slate-300 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors">
                    取消
                  </button>
                  <button type="button" onClick={handleImportParse}
                    className="flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 transition-colors">
                    解析并预填充
                  </button>
                </div>
              </>
            )}

            {/* Upload Mode */}
            {importMode === "upload" && (
              <>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  选择本地 SKILL.md 文件，系统将读取内容并自动切换到粘贴模式进行解析。
                </p>
                <input ref={fileInputRef} type="file" accept=".md,.txt,.markdown" onChange={handleFileUpload} className="hidden" />
                <div
                  onClick={() => fileInputRef.current?.click()}
                  className="flex flex-col items-center justify-center py-12 border-2 border-dashed border-slate-300 dark:border-slate-600 rounded-lg cursor-pointer hover:border-blue-400 dark:hover:border-blue-500 hover:bg-blue-50/50 dark:hover:bg-blue-900/10 transition-colors"
                >
                  <Upload size={32} className="text-slate-400 mb-3" />
                  <p className="text-sm text-slate-600 dark:text-slate-300 font-medium">点击选择文件</p>
                  <p className="text-xs text-slate-400 mt-1">支持 .md, .txt, .markdown</p>
                </div>
                <div className="flex items-center justify-end">
                  <button type="button" onClick={() => setView("list")}
                    className="px-4 py-2 text-sm text-slate-600 dark:text-slate-300 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors">
                    取消
                  </button>
                </div>
              </>
            )}

            {/* Git Mode */}
            {importMode === "git" && (
              <>
                <p className="text-xs text-slate-500 dark:text-slate-400">
                  输入包含 SKILL.md 的 Git 仓库 URL（仅支持 HTTPS），系统将自动克隆并导入。
                </p>
                <div className="space-y-3">
                  <div>
                    <label className="block text-xs font-medium text-slate-600 dark:text-slate-300 mb-1">
                      Git URL <span className="text-red-500">*</span>
                    </label>
                    <input type="text" value={gitUrl} onChange={(e) => setGitUrl(e.target.value)}
                      placeholder="https://github.com/user/skill-repo.git"
                      className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500" />
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="block text-xs font-medium text-slate-600 dark:text-slate-300 mb-1">
                        分支（可选）
                      </label>
                      <input type="text" value={gitBranch} onChange={(e) => setGitBranch(e.target.value)}
                        placeholder="main"
                        className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500" />
                    </div>
                    <div>
                      <label className="block text-xs font-medium text-slate-600 dark:text-slate-300 mb-1">
                        子路径（可选，monorepo 用）
                      </label>
                      <input type="text" value={gitSubPath} onChange={(e) => setGitSubPath(e.target.value)}
                        placeholder="packages/my-skill"
                        className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500" />
                    </div>
                  </div>
                </div>
                <div className="flex items-center justify-end gap-2">
                  <button type="button" onClick={() => setView("list")}
                    className="px-4 py-2 text-sm text-slate-600 dark:text-slate-300 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors">
                    取消
                  </button>
                  <button type="button" onClick={handleGitImport} disabled={gitImporting}
                    className="flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors">
                    {gitImporting ? <Loader2 size={14} className="animate-spin" /> : <GitBranch size={14} />}
                    {gitImporting ? "克隆中..." : "克隆并导入"}
                  </button>
                </div>
              </>
            )}
          </div>
        )}

        {view === "create" && (
          <SkillEditor
            skill={editingSkill && editingSkill.id === 0 ? editingSkill : undefined}
            onSave={handleCreate}
            onCancel={() => { setEditingSkill(null); setView("list"); }}
          />
        )}

        {view === "edit" && editingSkill && (
          <SkillEditor
            skill={editingSkill}
            onSave={handleUpdate}
            onCancel={() => {
              setView("list");
              setEditingSkill(null);
            }}
          />
        )}
      </div>
    </div>
  );
}
