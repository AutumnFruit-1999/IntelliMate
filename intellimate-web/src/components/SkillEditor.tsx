import { useState, useCallback, useEffect, useRef } from "react";
import { Loader2, Upload, Trash2, FileText, Terminal, BookOpen, History, RotateCcw, ChevronRight, ChevronDown, GitBranch, RefreshCw } from "lucide-react";
import type { SkillDefinition, SkillDefinitionCreate, SkillFiles, SkillVersion, FileNode } from "../lib/api";
import { fetchSkillFiles, uploadSkillFile, deleteSkillFile, fetchSkillVersions, fetchSkillVersion, rollbackSkillVersion, fetchSkillTree, readSkillFile, syncSkillFromGit } from "../lib/api";

interface SkillEditorProps {
  skill?: SkillDefinition;
  onSave: (data: SkillDefinitionCreate) => Promise<void>;
  onCancel: () => void;
}

const NAME_REGEX = /^[a-zA-Z][a-zA-Z0-9_-]{0,127}$/;

type FileType = "scripts" | "references" | "assets";

export default function SkillEditor({ skill, onSave, onCancel }: SkillEditorProps) {
  const [name, setName] = useState(skill?.name ?? "");
  const [displayName, setDisplayName] = useState(skill?.displayName ?? "");
  const [description, setDescription] = useState(skill?.description ?? "");
  const [content, setContent] = useState(skill?.content ?? "");
  const [tags, setTags] = useState(skill?.tags ?? "");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [files, setFiles] = useState<SkillFiles | null>(null);
  const [filesLoading, setFilesLoading] = useState(false);
  const [uploadingType, setUploadingType] = useState<FileType | null>(null);
  const [deletingFile, setDeletingFile] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [pendingUploadType, setPendingUploadType] = useState<FileType>("scripts");

  const [versions, setVersions] = useState<SkillVersion[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [viewingVersion, setViewingVersion] = useState<SkillVersion | null>(null);
  const [rollingBack, setRollingBack] = useState(false);
  const [showVersions, setShowVersions] = useState(false);

  const [fileTree, setFileTree] = useState<FileNode | null>(null);
  const [fileTreeLoading, setFileTreeLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const [previewPath, setPreviewPath] = useState<string | null>(null);
  const [previewContent, setPreviewContent] = useState<string | null>(null);
  const [expandedDirs, setExpandedDirs] = useState<Set<string>>(new Set([""]));

  const isEdit = !!skill && skill.id !== 0;
  const isGitSkill = !!skill?.gitUrl;

  const loadFileTree = useCallback(() => {
    if (!skill || skill.id === 0) return;
    setFileTreeLoading(true);
    fetchSkillTree(skill.id)
      .then(setFileTree)
      .catch(() => setFileTree(null))
      .finally(() => setFileTreeLoading(false));
  }, [skill]);

  useEffect(() => {
    if (isEdit && isGitSkill) loadFileTree();
  }, [isEdit, isGitSkill, loadFileTree]);

  const handleSync = useCallback(async () => {
    if (!skill) return;
    setSyncing(true);
    try {
      await syncSkillFromGit(skill.id);
      loadFileTree();
      window.location.reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSyncing(false);
    }
  }, [skill, loadFileTree]);

  const loadFiles = useCallback(() => {
    if (!skill) return;
    setFilesLoading(true);
    fetchSkillFiles(skill.id)
      .then(setFiles)
      .catch(() => setFiles(null))
      .finally(() => setFilesLoading(false));
  }, [skill]);

  useEffect(() => {
    if (isEdit) loadFiles();
  }, [isEdit, loadFiles]);

  const loadVersions = useCallback(() => {
    if (!skill || skill.id === 0) return;
    setVersionsLoading(true);
    fetchSkillVersions(skill.id)
      .then(setVersions)
      .catch(() => setVersions([]))
      .finally(() => setVersionsLoading(false));
  }, [skill]);

  useEffect(() => {
    if (isEdit && showVersions) loadVersions();
  }, [isEdit, showVersions, loadVersions]);

  const handleViewVersion = useCallback(async (v: SkillVersion) => {
    if (!skill) return;
    try {
      const full = await fetchSkillVersion(skill.id, v.version);
      setViewingVersion(full);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [skill]);

  const handleRollback = useCallback(async (version: number) => {
    if (!skill || !window.confirm(`确定回滚到版本 ${version}？当前内容将被保存为新版本。`)) return;
    setRollingBack(true);
    try {
      await rollbackSkillVersion(skill.id, version);
      setViewingVersion(null);
      loadVersions();
      window.location.reload();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRollingBack(false);
    }
  }, [skill, loadVersions]);

  const handleSubmit = useCallback(async () => {
    setError(null);

    if (!name || !NAME_REGEX.test(name)) {
      setError("名称必须以字母开头，只能包含字母、数字、下划线和连字符");
      return;
    }
    if (!description.trim()) {
      setError("触发描述不能为空");
      return;
    }

    setSaving(true);
    try {
      await onSave({
        name,
        displayName: displayName || undefined,
        description,
        content: content || undefined,
        tags: tags || undefined,
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setSaving(false);
    }
  }, [name, displayName, description, content, tags, onSave]);

  const handleUploadClick = useCallback((type: FileType) => {
    setPendingUploadType(type);
    fileInputRef.current?.click();
  }, []);

  const handleFileSelected = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !skill) return;
    e.target.value = "";

    setUploadingType(pendingUploadType);
    try {
      await uploadSkillFile(skill.id, pendingUploadType, file);
      loadFiles();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setUploadingType(null);
    }
  }, [skill, pendingUploadType, loadFiles]);

  const handleDeleteFile = useCallback(async (type: string, filename: string) => {
    if (!skill) return;
    const key = `${type}/${filename}`;
    setDeletingFile(key);
    try {
      await deleteSkillFile(skill.id, type, filename);
      loadFiles();
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
    } finally {
      setDeletingFile(null);
    }
  }, [skill, loadFiles]);

  const formatSize = (bytes: number): string => {
    if (bytes === 0) return "";
    if (bytes < 1024) return bytes + "B";
    if (bytes < 1048576) return (bytes / 1024).toFixed(1) + "KB";
    return (bytes / 1048576).toFixed(1) + "MB";
  };

  const handlePreviewFile = useCallback(async (node: FileNode) => {
    if (!skill || node.isDirectory) return;
    setPreviewPath(node.path);
    try {
      const content = await readSkillFile(skill.id, node.path);
      setPreviewContent(content);
    } catch {
      setPreviewContent(null);
    }
  }, [skill]);

  const renderFileNode = (node: FileNode, depth: number = 0): React.ReactNode => {
    const isExpanded = expandedDirs.has(node.path);
    const toggleExpand = () => {
      setExpandedDirs(prev => {
        const next = new Set(prev);
        if (next.has(node.path)) next.delete(node.path);
        else next.add(node.path);
        return next;
      });
    };

    if (node.isDirectory) {
      return (
        <div key={node.path || "__root__"}>
          {node.path !== "" && (
            <button type="button" onClick={toggleExpand}
              className="flex items-center gap-1 w-full px-2 py-1 text-xs text-slate-600 dark:text-slate-300 hover:bg-slate-50 dark:hover:bg-slate-800/50 rounded"
              style={{ paddingLeft: `${depth * 16 + 8}px` }}>
              {isExpanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
              <span className="font-medium">{node.name}/</span>
            </button>
          )}
          {(node.path === "" || isExpanded) && node.children.map(child => renderFileNode(child, node.path === "" ? depth : depth + 1))}
        </div>
      );
    }

    const isSkillMd = node.name === "SKILL.md";
    return (
      <button key={node.path} type="button" onClick={() => handlePreviewFile(node)}
        className={`flex items-center gap-1 w-full px-2 py-1 text-xs rounded hover:bg-slate-50 dark:hover:bg-slate-800/50 ${
          isSkillMd ? "text-blue-600 dark:text-blue-400 font-medium" : "text-slate-500 dark:text-slate-400"
        } ${previewPath === node.path ? "bg-slate-100 dark:bg-slate-800" : ""}`}
        style={{ paddingLeft: `${depth * 16 + 8}px` }}>
        <FileText size={10} />
        {node.name}
        <span className="ml-auto text-[10px] text-slate-400">{formatSize(node.size)}</span>
      </button>
    );
  };

  const renderFileSection = (type: FileType, label: string, icon: React.ReactNode, fileList: string[]) => (
    <div className="rounded-lg border border-slate-200 dark:border-slate-700 p-3">
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-1.5 text-sm font-medium text-slate-700 dark:text-slate-300">
          {icon}
          {label}
          <span className="text-xs text-slate-400 font-normal">({fileList.length})</span>
        </div>
        <button
          type="button"
          onClick={() => handleUploadClick(type)}
          disabled={uploadingType !== null}
          className="flex items-center gap-1 px-2 py-1 text-xs text-blue-600 dark:text-blue-400 rounded hover:bg-blue-50 dark:hover:bg-blue-900/20 transition-colors disabled:opacity-50"
        >
          {uploadingType === type ? (
            <Loader2 size={12} className="animate-spin" />
          ) : (
            <Upload size={12} />
          )}
          上传
        </button>
      </div>
      {fileList.length === 0 ? (
        <p className="text-xs text-slate-400 dark:text-slate-500">暂无文件</p>
      ) : (
        <div className="space-y-1">
          {fileList.map((f) => {
            const key = `${type}/${f}`;
            return (
              <div
                key={key}
                className="flex items-center justify-between px-2 py-1.5 rounded bg-slate-50 dark:bg-slate-800/50"
              >
                <span className="text-xs font-mono text-slate-600 dark:text-slate-300 truncate">
                  {f}
                </span>
                <button
                  type="button"
                  onClick={() => handleDeleteFile(type, f)}
                  disabled={deletingFile === key}
                  className="p-1 rounded hover:bg-red-50 dark:hover:bg-red-900/20 transition-colors"
                >
                  {deletingFile === key ? (
                    <Loader2 size={12} className="animate-spin text-red-400" />
                  ) : (
                    <Trash2 size={12} className="text-red-400" />
                  )}
                </button>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );

  return (
    <div className="space-y-4">
      <h3 className="text-base font-semibold text-slate-800 dark:text-slate-100">
        {isEdit ? "编辑 Skill" : "创建 Skill"}
      </h3>

      {error && (
        <div className="px-3 py-2 text-sm text-red-600 bg-red-50 dark:bg-red-900/20 dark:text-red-400 rounded-lg">
          {error}
        </div>
      )}

      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">
          名称（唯一标识，小写+连字符）
        </label>
        <input
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="code-reviewer"
          disabled={isEdit}
          className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
        />
      </div>

      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">
          显示名称
        </label>
        <input
          type="text"
          value={displayName}
          onChange={(e) => setDisplayName(e.target.value)}
          placeholder="代码审查"
          className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
      </div>

      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">
          触发描述
          <span className="ml-1 text-xs text-slate-400 font-normal">
            Agent 用来判断何时激活此 Skill，必须包含「做什么」+「什么时候触发」
          </span>
        </label>
        <textarea
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder={'Conducts structured code reviews. Use when user asks to "review this code", "check my PR".'}
          rows={3}
          className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 resize-y"
        />
      </div>

      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">
          标签（逗号分隔）
        </label>
        <input
          type="text"
          value={tags}
          onChange={(e) => setTags(e.target.value)}
          placeholder="开发, 质量"
          className="w-full px-3 py-2 text-sm rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
      </div>

      <div>
        <label className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1">
          SKILL.md 正文
          <span className="ml-1 text-xs text-slate-400 font-normal">
            Markdown 格式的工作流指令
          </span>
        </label>
        <textarea
          value={content}
          onChange={(e) => setContent(e.target.value)}
          placeholder={"# Code Reviewer\n\n## Step 1: Understand context\n- What is this code supposed to do?\n\n## Step 2: Run the review\n..."}
          rows={12}
          className="w-full px-3 py-2 text-sm font-mono rounded-lg border border-slate-200 dark:border-slate-700 bg-white dark:bg-slate-800 text-slate-800 dark:text-slate-100 placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 resize-y"
        />
      </div>

      {/* File Management (edit mode only) */}
      {isEdit && isGitSkill && (
        <div>
          <div className="flex items-center justify-between mb-2">
            <h4 className="text-sm font-semibold text-slate-700 dark:text-slate-200 flex items-center gap-1.5">
              <GitBranch size={14} className="text-purple-500" />
              Git 来源
            </h4>
            <button type="button" onClick={handleSync} disabled={syncing}
              className="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-purple-600 dark:text-purple-400 border border-purple-200 dark:border-purple-700 rounded-lg hover:bg-purple-50 dark:hover:bg-purple-900/20 disabled:opacity-50 transition-colors">
              {syncing ? <Loader2 size={12} className="animate-spin" /> : <RefreshCw size={12} />}
              {syncing ? "同步中..." : "同步更新"}
            </button>
          </div>
          <div className="text-xs text-slate-500 dark:text-slate-400 mb-3 space-y-0.5">
            <p><span className="text-slate-600 dark:text-slate-300 font-medium">URL:</span> {skill?.gitUrl}</p>
            {skill?.gitSubPath && (
              <p><span className="text-slate-600 dark:text-slate-300 font-medium">子路径:</span> {skill.gitSubPath}</p>
            )}
          </div>

          <h4 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">文件树</h4>
          {fileTreeLoading ? (
            <div className="flex items-center justify-center py-4">
              <Loader2 size={18} className="animate-spin text-slate-400" />
            </div>
          ) : fileTree ? (
            <div className="flex gap-3">
              <div className="w-1/2 rounded-lg border border-slate-200 dark:border-slate-700 p-2 max-h-64 overflow-y-auto">
                {renderFileNode(fileTree)}
              </div>
              <div className="w-1/2 rounded-lg border border-slate-200 dark:border-slate-700 p-3">
                {previewPath ? (
                  <>
                    <div className="flex items-center justify-between mb-2">
                      <span className="text-xs font-mono text-slate-600 dark:text-slate-300 truncate">{previewPath}</span>
                      <button type="button" onClick={() => { setPreviewPath(null); setPreviewContent(null); }}
                        className="text-[10px] text-slate-400 hover:text-slate-600 dark:hover:text-slate-300">
                        关闭
                      </button>
                    </div>
                    <pre className="text-xs font-mono text-slate-600 dark:text-slate-300 whitespace-pre-wrap max-h-48 overflow-y-auto bg-slate-50 dark:bg-slate-800/50 rounded p-2">
                      {previewContent ?? "无法读取文件"}
                    </pre>
                  </>
                ) : (
                  <p className="text-xs text-slate-400 dark:text-slate-500 text-center py-8">点击左侧文件查看内容</p>
                )}
              </div>
            </div>
          ) : (
            <p className="text-xs text-slate-400">无法加载文件树</p>
          )}
        </div>
      )}

      {isEdit && !isGitSkill && (
        <div>
          <h4 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">
            文件管理
          </h4>
          <input
            ref={fileInputRef}
            type="file"
            className="hidden"
            onChange={handleFileSelected}
          />
          {filesLoading ? (
            <div className="flex items-center justify-center py-4">
              <Loader2 size={18} className="animate-spin text-slate-400" />
            </div>
          ) : files ? (
            <div className="space-y-3">
              {renderFileSection("scripts", "Scripts", <Terminal size={14} className="text-emerald-500" />, files.scripts)}
              {renderFileSection("references", "References", <BookOpen size={14} className="text-sky-500" />, files.references)}
              {renderFileSection("assets", "Assets", <FileText size={14} className="text-amber-500" />, files.assets)}
            </div>
          ) : (
            <p className="text-xs text-slate-400">无法加载文件列表</p>
          )}
        </div>
      )}

      {/* Version History (edit mode only) */}
      {isEdit && (
        <div>
          <button
            type="button"
            onClick={() => setShowVersions(!showVersions)}
            className="flex items-center gap-1.5 text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2 hover:text-blue-600 dark:hover:text-blue-400 transition-colors"
          >
            <History size={14} />
            版本历史
            <span className="text-xs font-normal text-slate-400">
              ({showVersions ? "收起" : "展开"})
            </span>
          </button>

          {showVersions && (
            <div className="rounded-lg border border-slate-200 dark:border-slate-700 p-3 space-y-3">
              {versionsLoading ? (
                <div className="flex items-center justify-center py-4">
                  <Loader2 size={18} className="animate-spin text-slate-400" />
                </div>
              ) : versions.length === 0 ? (
                <p className="text-xs text-slate-400 dark:text-slate-500">暂无版本历史（首次编辑后将自动保存版本）</p>
              ) : (
                <>
                  <div className="space-y-2 max-h-48 overflow-y-auto">
                    {versions.map((v) => (
                      <div
                        key={v.id}
                        className={`flex items-center justify-between px-3 py-2 rounded-md border transition-colors ${
                          viewingVersion?.id === v.id
                            ? "border-blue-400 bg-blue-50 dark:bg-blue-900/20 dark:border-blue-600"
                            : "border-slate-100 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800/30"
                        }`}
                      >
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2">
                            <span className="text-xs font-medium text-slate-700 dark:text-slate-300">
                              v{v.version}
                            </span>
                            <span className="text-[10px] text-slate-400">
                              {new Date(v.createdAt).toLocaleString("zh-CN")}
                            </span>
                          </div>
                          {v.changeNote && (
                            <p className="text-[10px] text-slate-500 dark:text-slate-400 mt-0.5 truncate">
                              {v.changeNote}
                            </p>
                          )}
                        </div>
                        <div className="flex items-center gap-1 ml-2 shrink-0">
                          <button
                            type="button"
                            onClick={() => handleViewVersion(v)}
                            className="px-2 py-1 text-[10px] rounded border border-slate-200 dark:border-slate-700 text-slate-500 dark:text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                          >
                            查看
                          </button>
                          <button
                            type="button"
                            onClick={() => handleRollback(v.version)}
                            disabled={rollingBack}
                            className="flex items-center gap-0.5 px-2 py-1 text-[10px] rounded border border-amber-200 dark:border-amber-700 text-amber-600 dark:text-amber-400 hover:bg-amber-50 dark:hover:bg-amber-900/20 transition-colors disabled:opacity-50"
                          >
                            <RotateCcw size={10} />
                            回滚
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>

                  {viewingVersion && (
                    <div className="mt-3">
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-xs font-medium text-slate-600 dark:text-slate-300">
                          v{viewingVersion.version} 的内容
                        </span>
                        <button
                          type="button"
                          onClick={() => setViewingVersion(null)}
                          className="text-[10px] text-slate-400 hover:text-slate-600 dark:hover:text-slate-300"
                        >
                          关闭预览
                        </button>
                      </div>
                      {viewingVersion.description && (
                        <p className="text-[10px] text-slate-500 dark:text-slate-400 mb-1">
                          触发描述: {viewingVersion.description}
                        </p>
                      )}
                      <textarea
                        readOnly
                        value={viewingVersion.content ?? ""}
                        rows={8}
                        className="w-full px-3 py-2 text-xs font-mono rounded-lg border border-slate-200 dark:border-slate-700 bg-slate-50 dark:bg-slate-800/50 text-slate-600 dark:text-slate-300 resize-y"
                      />
                    </div>
                  )}
                </>
              )}
            </div>
          )}
        </div>
      )}

      <div className="flex items-center justify-end gap-2 pt-2">
        <button
          type="button"
          onClick={onCancel}
          disabled={saving}
          className="px-4 py-2 text-sm text-slate-600 dark:text-slate-300 rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
        >
          取消
        </button>
        <button
          type="button"
          onClick={handleSubmit}
          disabled={saving}
          className="flex items-center gap-1.5 px-4 py-2 text-sm font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700 disabled:opacity-50 transition-colors"
        >
          {saving && <Loader2 size={14} className="animate-spin" />}
          {isEdit ? "更新" : "创建"}
        </button>
      </div>
    </div>
  );
}
