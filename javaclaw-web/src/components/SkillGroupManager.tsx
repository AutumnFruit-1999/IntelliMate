import { useState, useCallback, useEffect } from "react";
import {
  Plus, Pencil, Trash2, Loader2, GripVertical, Check, X, FolderOpen,
} from "lucide-react";
import {
  fetchSkillGroups,
  createSkillGroup,
  updateSkillGroup,
  deleteSkillGroup,
  reorderSkillGroups,
  fetchSkillGroupMembers,
  setSkillGroupMembers,
  fetchSkillDefinitions,
  type SkillGroup,
  type SkillDefinition,
} from "../lib/api";

export default function SkillGroupManager() {
  const [groups, setGroups] = useState<SkillGroup[]>([]);
  const [skills, setSkills] = useState<SkillDefinition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [editingId, setEditingId] = useState<number | null>(null);
  const [editForm, setEditForm] = useState({ name: "", displayName: "", description: "" });

  const [creating, setCreating] = useState(false);
  const [createForm, setCreateForm] = useState({ name: "", displayName: "", description: "" });

  const [managingId, setManagingId] = useState<number | null>(null);
  const [memberSkillIds, setMemberSkillIds] = useState<number[]>([]);
  const [savingMembers, setSavingMembers] = useState(false);

  const [dragIdx, setDragIdx] = useState<number | null>(null);
  const [dragOverIdx, setDragOverIdx] = useState<number | null>(null);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [g, s] = await Promise.all([fetchSkillGroups(), fetchSkillDefinitions()]);
      setGroups(g);
      setSkills(s);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { loadData(); }, [loadData]);

  const handleCreate = useCallback(async () => {
    if (!createForm.name.trim()) return;
    try {
      await createSkillGroup({
        name: createForm.name.trim(),
        displayName: createForm.displayName.trim() || undefined,
        description: createForm.description.trim() || undefined,
      });
      setCreating(false);
      setCreateForm({ name: "", displayName: "", description: "" });
      loadData();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [createForm, loadData]);

  const handleUpdate = useCallback(async () => {
    if (editingId == null) return;
    try {
      await updateSkillGroup(editingId, {
        name: editForm.name.trim(),
        displayName: editForm.displayName.trim(),
        description: editForm.description.trim(),
      });
      setEditingId(null);
      loadData();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [editingId, editForm, loadData]);

  const handleDelete = useCallback(async (id: number) => {
    if (!window.confirm("确定删除此分组？关联关系也会被清除。")) return;
    try {
      await deleteSkillGroup(id);
      loadData();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [loadData]);

  const handleDrop = useCallback(async (dropIdx: number) => {
    if (dragIdx === null || dragIdx === dropIdx) return;
    const newGroups = [...groups];
    const [moved] = newGroups.splice(dragIdx, 1);
    newGroups.splice(dropIdx, 0, moved);
    setGroups(newGroups);
    setDragIdx(null);
    setDragOverIdx(null);
    try {
      await reorderSkillGroups(newGroups.map((g) => g.id));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      loadData();
    }
  }, [dragIdx, groups, loadData]);

  const openMemberManager = useCallback(async (groupId: number) => {
    setManagingId(groupId);
    try {
      const ids = await fetchSkillGroupMembers(groupId);
      setMemberSkillIds(ids);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  const handleSaveMembers = useCallback(async () => {
    if (managingId == null) return;
    setSavingMembers(true);
    try {
      await setSkillGroupMembers(managingId, memberSkillIds);
      setManagingId(null);
      loadData();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSavingMembers(false);
    }
  }, [managingId, memberSkillIds, loadData]);

  const toggleMember = useCallback((skillId: number) => {
    setMemberSkillIds((prev) =>
      prev.includes(skillId) ? prev.filter((id) => id !== skillId) : [...prev, skillId],
    );
  }, []);

  if (loading) {
    return (
      <div className="flex items-center justify-center py-12">
        <Loader2 size={24} className="animate-spin text-slate-400" />
      </div>
    );
  }

  if (managingId !== null) {
    const group = groups.find((g) => g.id === managingId);
    return (
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">
            管理「{group?.displayName || group?.name}」的技能关联
          </h3>
          <div className="flex gap-2">
            <button
              onClick={() => setManagingId(null)}
              className="px-3 py-1.5 text-xs bg-slate-100 dark:bg-slate-800 rounded-lg hover:bg-slate-200 dark:hover:bg-slate-700"
            >
              取消
            </button>
            <button
              onClick={handleSaveMembers}
              disabled={savingMembers}
              className="px-3 py-1.5 text-xs bg-blue-600 text-white rounded-lg hover:bg-blue-700 disabled:opacity-50"
            >
              {savingMembers ? "保存中..." : "保存"}
            </button>
          </div>
        </div>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-2 max-h-[60vh] overflow-y-auto">
          {skills.filter((s) => s.enabled === 1).map((skill) => (
            <label
              key={skill.id}
              className={`flex items-center gap-2 px-3 py-2 rounded-lg border cursor-pointer transition-colors ${
                memberSkillIds.includes(skill.id)
                  ? "border-blue-500 bg-blue-50 dark:bg-blue-900/20"
                  : "border-slate-200 dark:border-slate-700 hover:border-slate-300 dark:hover:border-slate-600"
              }`}
            >
              <input
                type="checkbox"
                checked={memberSkillIds.includes(skill.id)}
                onChange={() => toggleMember(skill.id)}
                className="rounded border-slate-300"
              />
              <div className="flex-1 min-w-0">
                <div className="text-xs font-medium text-slate-700 dark:text-slate-200 truncate">
                  {skill.displayName || skill.name}
                </div>
                <div className="text-[10px] text-slate-400 truncate">{skill.name}</div>
              </div>
            </label>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {error && (
        <div className="px-3 py-2 text-sm text-red-600 bg-red-50 dark:bg-red-900/20 dark:text-red-400 rounded-lg">
          {error}
        </div>
      )}

      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200">
          技能分组 ({groups.length})
        </h3>
        <button
          onClick={() => setCreating(true)}
          className="flex items-center gap-1 px-3 py-1.5 text-xs font-medium text-white bg-blue-600 rounded-lg hover:bg-blue-700"
        >
          <Plus size={12} />
          新建分组
        </button>
      </div>

      {creating && (
        <div className="rounded-lg border border-dashed border-blue-300 dark:border-blue-700 p-3 space-y-2">
          <input
            className="w-full border border-slate-200 dark:border-slate-700 rounded-md px-2.5 py-1.5 text-sm bg-white dark:bg-slate-800 outline-none focus:border-blue-400"
            placeholder="分组标识（英文，如 coding）"
            value={createForm.name}
            onChange={(e) => setCreateForm({ ...createForm, name: e.target.value })}
            autoFocus
          />
          <input
            className="w-full border border-slate-200 dark:border-slate-700 rounded-md px-2.5 py-1.5 text-sm bg-white dark:bg-slate-800 outline-none focus:border-blue-400"
            placeholder="显示名称（如 代码开发）"
            value={createForm.displayName}
            onChange={(e) => setCreateForm({ ...createForm, displayName: e.target.value })}
          />
          <textarea
            className="w-full border border-slate-200 dark:border-slate-700 rounded-md px-2.5 py-1.5 text-sm bg-white dark:bg-slate-800 outline-none focus:border-blue-400 resize-none"
            rows={2}
            placeholder="分组描述（告诉模型这个分组包含什么类型的技能）"
            value={createForm.description}
            onChange={(e) => setCreateForm({ ...createForm, description: e.target.value })}
          />
          <div className="flex gap-2">
            <button
              onClick={handleCreate}
              disabled={!createForm.name.trim()}
              className="px-3 py-1 text-xs bg-blue-600 text-white rounded-md hover:bg-blue-700 disabled:opacity-50"
            >
              创建
            </button>
            <button
              onClick={() => { setCreating(false); setCreateForm({ name: "", displayName: "", description: "" }); }}
              className="px-3 py-1 text-xs bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 rounded-md hover:bg-slate-200 dark:hover:bg-slate-700"
            >
              取消
            </button>
          </div>
        </div>
      )}

      {groups.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-8 text-slate-400 dark:text-slate-500 space-y-2">
          <FolderOpen size={28} className="opacity-50" />
          <p className="text-sm">还没有创建任何分组</p>
        </div>
      ) : (
        <div className="space-y-2">
          {groups.map((group, idx) => (
            <div
              key={group.id}
              draggable
              onDragStart={() => setDragIdx(idx)}
              onDragOver={(e) => { e.preventDefault(); setDragOverIdx(idx); }}
              onDrop={(e) => { e.preventDefault(); handleDrop(idx); }}
              onDragEnd={() => { setDragIdx(null); setDragOverIdx(null); }}
              className={`rounded-lg border px-3 py-2.5 transition-all ${
                dragOverIdx === idx ? "ring-2 ring-blue-400" : ""
              } ${group.enabled ? "border-slate-200 dark:border-slate-700" : "border-slate-100 dark:border-slate-800 opacity-50"}`}
            >
              {editingId === group.id ? (
                <div className="space-y-2">
                  <input
                    className="w-full border border-slate-200 dark:border-slate-700 rounded-md px-2 py-1 text-sm bg-white dark:bg-slate-800 outline-none focus:border-blue-400"
                    value={editForm.name}
                    onChange={(e) => setEditForm({ ...editForm, name: e.target.value })}
                  />
                  <input
                    className="w-full border border-slate-200 dark:border-slate-700 rounded-md px-2 py-1 text-sm bg-white dark:bg-slate-800 outline-none focus:border-blue-400"
                    value={editForm.displayName}
                    onChange={(e) => setEditForm({ ...editForm, displayName: e.target.value })}
                    placeholder="显示名称"
                  />
                  <textarea
                    className="w-full border border-slate-200 dark:border-slate-700 rounded-md px-2 py-1 text-sm bg-white dark:bg-slate-800 outline-none focus:border-blue-400 resize-none"
                    rows={2}
                    value={editForm.description}
                    onChange={(e) => setEditForm({ ...editForm, description: e.target.value })}
                    placeholder="描述"
                  />
                  <div className="flex gap-2">
                    <button onClick={handleUpdate} className="p-1 rounded hover:bg-emerald-100 dark:hover:bg-emerald-900/30">
                      <Check size={14} className="text-emerald-600" />
                    </button>
                    <button onClick={() => setEditingId(null)} className="p-1 rounded hover:bg-slate-100 dark:hover:bg-slate-800">
                      <X size={14} className="text-slate-400" />
                    </button>
                  </div>
                </div>
              ) : (
                <div className="flex items-center gap-2">
                  <GripVertical size={14} className="text-slate-300 cursor-grab flex-shrink-0" />
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <span className="text-sm font-medium text-slate-700 dark:text-slate-200">
                        {group.displayName || group.name}
                      </span>
                      <span className="text-[10px] text-slate-400 font-mono">{group.name}</span>
                      <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-slate-100 dark:bg-slate-800 text-slate-500">
                        {group.skillCount} 技能
                      </span>
                    </div>
                    {group.description && (
                      <p className="text-xs text-slate-400 mt-0.5 truncate">{group.description}</p>
                    )}
                  </div>
                  <div className="flex items-center gap-1 flex-shrink-0">
                    <button
                      onClick={() => openMemberManager(group.id)}
                      className="p-1.5 rounded hover:bg-slate-100 dark:hover:bg-slate-800"
                      title="管理关联技能"
                    >
                      <FolderOpen size={13} className="text-slate-400" />
                    </button>
                    <button
                      onClick={() => {
                        setEditingId(group.id);
                        setEditForm({ name: group.name, displayName: group.displayName, description: group.description });
                      }}
                      className="p-1.5 rounded hover:bg-slate-100 dark:hover:bg-slate-800"
                      title="编辑"
                    >
                      <Pencil size={13} className="text-slate-400" />
                    </button>
                    <button
                      onClick={() => handleDelete(group.id)}
                      className="p-1.5 rounded hover:bg-red-50 dark:hover:bg-red-900/20"
                      title="删除"
                    >
                      <Trash2 size={13} className="text-red-400" />
                    </button>
                  </div>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
