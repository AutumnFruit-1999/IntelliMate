import { useEffect, useState, useMemo, useCallback } from "react";
import { Loader2, FileText, Terminal, BookOpen, FolderOpen } from "lucide-react";
import { fetchSkillDefinitions, fetchSkillGroups, type SkillDefinition, type SkillGroup } from "../lib/api";

interface SkillsTabProps {
  skillsEnabled: string | null;
  onChange: (value: string | null) => void;
  skillGroupsEnabled: string | null;
  onGroupsChange: (value: string | null) => void;
}

export default function SkillsTab({ skillsEnabled, onChange, skillGroupsEnabled, onGroupsChange }: SkillsTabProps) {
  const [skills, setSkills] = useState<SkillDefinition[]>([]);
  const [groups, setGroups] = useState<SkillGroup[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selectedTag, setSelectedTag] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    Promise.all([
      fetchSkillDefinitions(),
      fetchSkillGroups(),
    ])
      .then(([skillData, groupData]) => {
        setSkills(skillData.filter((s) => s.enabled === 1));
        setGroups(groupData);
      })
      .catch((e) => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false));
  }, []);

  const allTags = useMemo(() => {
    const tagSet = new Set<string>();
    skills.forEach((s) => {
      if (s.tags) s.tags.split(",").forEach((t) => tagSet.add(t.trim()));
    });
    return [...tagSet].sort();
  }, [skills]);

  const displaySkills = useMemo(
    () =>
      selectedTag
        ? skills.filter((s) =>
            s.tags?.split(",").map((t) => t.trim()).includes(selectedTag),
          )
        : skills,
    [skills, selectedTag],
  );

  const selectedNames = useMemo(() => {
    if (!skillsEnabled || skillsEnabled.trim() === "") return new Set<string>();
    if (skillsEnabled.toLowerCase() === "full") return new Set(skills.map((s) => s.name));
    try {
      const parsed = JSON.parse(skillsEnabled);
      if (Array.isArray(parsed)) return new Set<string>(parsed);
    } catch { /* ignore */ }
    return new Set<string>();
  }, [skillsEnabled, skills]);

  const mode = useMemo<"none" | "full" | "custom">(() => {
    if (!skillsEnabled || skillsEnabled.trim() === "") return "none";
    if (skillsEnabled.toLowerCase() === "full") return "full";
    return "custom";
  }, [skillsEnabled]);

  const handlePreset = useCallback(
    (preset: "none" | "full") => {
      onChange(preset === "none" ? null : "full");
    },
    [onChange],
  );

  const handleToggle = useCallback(
    (name: string) => {
      const next = new Set(selectedNames);
      if (next.has(name)) next.delete(name);
      else next.add(name);

      if (next.size === 0) {
        onChange(null);
      } else if (next.size === skills.length && skills.every((s) => next.has(s.name))) {
        onChange("full");
      } else {
        onChange(JSON.stringify([...next]));
      }
    },
    [selectedNames, skills, onChange],
  );

  const groupMode = useMemo<"none" | "full" | "custom">(() => {
    if (!skillGroupsEnabled || skillGroupsEnabled.trim() === "") return "none";
    if (skillGroupsEnabled.toLowerCase() === "full") return "full";
    return "custom";
  }, [skillGroupsEnabled]);

  const selectedGroupNames = useMemo(() => {
    if (!skillGroupsEnabled || skillGroupsEnabled.trim() === "") return new Set<string>();
    if (skillGroupsEnabled.toLowerCase() === "full") return new Set(groups.map((g) => g.name));
    try {
      const parsed = JSON.parse(skillGroupsEnabled);
      if (Array.isArray(parsed)) return new Set<string>(parsed);
    } catch { /* ignore */ }
    return new Set<string>();
  }, [skillGroupsEnabled, groups]);

  const handleGroupPreset = useCallback(
    (preset: "none" | "full") => {
      onGroupsChange(preset === "none" ? null : "full");
    },
    [onGroupsChange],
  );

  const handleGroupToggle = useCallback(
    (name: string) => {
      const next = new Set(selectedGroupNames);
      if (next.has(name)) next.delete(name);
      else next.add(name);

      if (next.size === 0) {
        onGroupsChange(null);
      } else if (next.size === groups.length && groups.every((g) => next.has(g.name))) {
        onGroupsChange("full");
      } else {
        onGroupsChange(JSON.stringify([...next]));
      }
    },
    [selectedGroupNames, groups, onGroupsChange],
  );

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

  if (skills.length === 0) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center text-slate-400 dark:text-slate-500 py-12 space-y-2">
        <FileText size={32} className="opacity-50" />
        <p className="text-sm">暂无可用 Skills</p>
        <p className="text-xs">请先在「Skills 管理」中创建 Skill</p>
      </div>
    );
  }

  return (
    <div className="space-y-5">
      {/* Skill Groups Section */}
      {groups.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2 flex items-center gap-1.5">
            <FolderOpen size={14} />
            技能分组权限
          </h3>
          <div className="flex flex-wrap gap-2 mb-3">
            <button
              type="button"
              onClick={() => handleGroupPreset("full")}
              className={`px-3 py-1.5 text-xs rounded-lg border transition-colors ${
                groupMode === "full"
                  ? "border-blue-500 bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400 dark:border-blue-600"
                  : "border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 hover:border-slate-300 dark:hover:border-slate-600"
              }`}
            >
              全部分组
            </button>
            <button
              type="button"
              onClick={() => handleGroupPreset("none")}
              className={`px-3 py-1.5 text-xs rounded-lg border transition-colors ${
                groupMode === "none"
                  ? "border-blue-500 bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400 dark:border-blue-600"
                  : "border-slate-200 dark:border-slate-700 text-slate-600 dark:text-slate-400 hover:border-slate-300 dark:hover:border-slate-600"
              }`}
            >
              不使用分组
            </button>
            {groupMode === "custom" && (
              <span className="px-3 py-1.5 text-xs rounded-lg border border-amber-400 bg-amber-50 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400 dark:border-amber-600">
                自定义（{selectedGroupNames.size}/{groups.length}）
              </span>
            )}
          </div>
          <div className="space-y-1.5">
            {groups.map((group) => (
              <label
                key={group.id}
                className="flex items-center gap-2.5 px-3 py-2 rounded-lg border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800/30 cursor-pointer transition-colors"
              >
                <input
                  type="checkbox"
                  checked={selectedGroupNames.has(group.name)}
                  onChange={() => handleGroupToggle(group.name)}
                  className="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium text-slate-700 dark:text-slate-200">
                      {group.displayName || group.name}
                    </span>
                    <span className="text-xs font-mono text-slate-400">{group.name}</span>
                    <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-slate-100 dark:bg-slate-800 text-slate-500">
                      {group.skillCount} 技能
                    </span>
                  </div>
                  {group.description && (
                    <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">{group.description}</p>
                  )}
                </div>
              </label>
            ))}
          </div>
        </div>
      )}

      {/* Quick Presets */}
      <div>
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">
          单技能选择（补充）
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
            全部 Skills
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
            不使用 Skills
          </button>
          {mode === "custom" && (
            <span className="px-3 py-1.5 text-xs rounded-lg border border-amber-400 bg-amber-50 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400 dark:border-amber-600">
              自定义选择（{selectedNames.size}/{skills.length}）
            </span>
          )}
        </div>
      </div>

      {/* Tag Filter */}
      {allTags.length > 0 && (
        <div>
          <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">
            按标签筛选
          </h3>
          <div className="flex flex-wrap gap-1.5">
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
        </div>
      )}

      {/* Skills List */}
      <div>
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-2">
          可用 Skills{selectedTag ? ` (${selectedTag})` : ""}
        </h3>
        <div className="space-y-2">
          {displaySkills.map((skill) => (
            <label
              key={skill.id}
              className="flex items-start gap-3 px-4 py-3 rounded-lg border border-slate-200 dark:border-slate-700 hover:bg-slate-50 dark:hover:bg-slate-800/30 cursor-pointer transition-colors"
            >
              <input
                type="checkbox"
                checked={selectedNames.has(skill.name)}
                onChange={() => handleToggle(skill.name)}
                className="mt-0.5 h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
              />
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-slate-700 dark:text-slate-200">
                    {skill.displayName || skill.name}
                  </span>
                  <span className="text-xs font-mono text-slate-400">
                    {skill.name}
                  </span>
                </div>
                <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5 line-clamp-2">
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
            </label>
          ))}
        </div>
      </div>

      {/* Summary */}
      <div className="space-y-2">
        <h3 className="text-sm font-semibold text-slate-700 dark:text-slate-200 mb-1">
          当前配置值
        </h3>
        <div className="rounded-lg bg-slate-50 dark:bg-slate-800/50 px-3 py-2 text-xs font-mono text-slate-500 dark:text-slate-400 break-all">
          <span className="text-slate-400">skillGroupsEnabled: </span>
          {skillGroupsEnabled ?? "(null — 不使用分组)"}
        </div>
        <div className="rounded-lg bg-slate-50 dark:bg-slate-800/50 px-3 py-2 text-xs font-mono text-slate-500 dark:text-slate-400 break-all">
          <span className="text-slate-400">skillsEnabled: </span>
          {skillsEnabled ?? "(null — 不使用 Skills)"}
        </div>
      </div>
    </div>
  );
}
