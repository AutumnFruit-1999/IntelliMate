import { parse as yamlParse } from "yaml";
import type { SkillDefinition, SkillDefinitionCreate } from "./api";

export interface ParsedSkill {
  name?: string;
  displayName?: string;
  description?: string;
  content: string;
  tags?: string;
  metadata?: Record<string, unknown>;
  extraFields?: Record<string, unknown>;
  warnings?: string[];
}

const KNOWN_FIELDS = new Set([
  "name", "displayName", "display_name", "description", "tags", "metadata",
]);

export function normalizeSkillName(raw: string): { name: string; changed: boolean } {
  let normalized = raw.trim().replace(/\s+/g, "-").replace(/[^a-zA-Z0-9_-]/g, "");
  if (!normalized) return { name: "", changed: true };
  if (!/^[a-zA-Z]/.test(normalized)) normalized = "s-" + normalized;
  if (normalized.length > 128) normalized = normalized.substring(0, 128);
  return { name: normalized, changed: normalized !== raw.trim() };
}

export function parseSkillMd(raw: string): ParsedSkill {
  const warnings: string[] = [];
  const match = raw.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);

  if (!match) {
    return inferFromContent(raw, warnings);
  }

  const yamlStr = match[1];
  const content = match[2].trim();

  let meta: Record<string, unknown> = {};
  try {
    const parsed = yamlParse(yamlStr);
    if (parsed && typeof parsed === "object") meta = parsed;
  } catch {
    warnings.push("YAML frontmatter 解析失败，尝试智能推断");
    return inferFromContent(raw, warnings);
  }

  const extraFields: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(meta)) {
    if (!KNOWN_FIELDS.has(key)) {
      extraFields[key] = value;
    }
  }
  if (Object.keys(extraFields).length > 0) {
    warnings.push(`额外字段已保留到 metadata: ${Object.keys(extraFields).join(", ")}`);
  }

  let name = typeof meta.name === "string" ? meta.name : undefined;
  if (name) {
    const { name: normalized, changed } = normalizeSkillName(name);
    if (changed) {
      warnings.push(`名称已规范化: "${name}" → "${normalized}"`);
      name = normalized;
    }
  }

  const displayName = typeof meta.displayName === "string" ? meta.displayName
    : typeof meta.display_name === "string" ? meta.display_name : undefined;

  let description: string | undefined;
  if (meta.description != null) {
    description = typeof meta.description === "string"
      ? meta.description.trim()
      : String(meta.description).trim();
  }

  const tags = typeof meta.tags === "string" ? meta.tags : undefined;

  let parsedMetadata: Record<string, unknown> | undefined;
  if (meta.metadata) {
    if (typeof meta.metadata === "string") {
      try { parsedMetadata = JSON.parse(meta.metadata); } catch { /* ignore */ }
    } else if (typeof meta.metadata === "object") {
      parsedMetadata = meta.metadata as Record<string, unknown>;
    }
  }

  return { name, displayName, description, content, tags, metadata: parsedMetadata, extraFields, warnings };
}

function inferFromContent(raw: string, warnings: string[]): ParsedSkill {
  const lines = raw.split("\n");
  let name: string | undefined;
  let description: string | undefined;

  let pastTitle = false;
  for (const line of lines) {
    const trimmed = line.trim();
    if (!name && trimmed.startsWith("# ") && !trimmed.startsWith("## ")) {
      const titleText = trimmed.slice(2).trim();
      const { name: normalized } = normalizeSkillName(titleText);
      if (normalized) {
        name = normalized;
        warnings.push(`name 由标题推断: "${titleText}" → "${normalized}"`);
        pastTitle = true;
        continue;
      }
    }
    if (pastTitle && !description && trimmed && !trimmed.startsWith("#")) {
      description = trimmed.length > 500 ? trimmed.slice(0, 500) : trimmed;
      warnings.push("description 由首段推断");
      break;
    }
  }

  return { name, description, content: raw.trim(), warnings };
}

export function parsedToCreate(parsed: ParsedSkill): Partial<SkillDefinitionCreate> {
  let metadata = parsed.metadata;
  if (parsed.extraFields && Object.keys(parsed.extraFields).length > 0) {
    metadata = { ...(metadata ?? {}), _extraFields: parsed.extraFields };
  }
  return {
    name: parsed.name,
    displayName: parsed.displayName,
    description: parsed.description ?? "",
    content: parsed.content || undefined,
    tags: parsed.tags,
    metadata,
  };
}

export function exportSkillMd(skill: SkillDefinition): string {
  let md = "---\n";
  md += `name: ${skill.name}\n`;
  md += `description: ${skill.description}\n`;
  if (skill.displayName) md += `displayName: ${skill.displayName}\n`;
  if (skill.tags) md += `tags: ${skill.tags}\n`;
  if (skill.metadata) md += `metadata: ${skill.metadata}\n`;
  md += "---\n\n";
  md += skill.content ?? "";
  return md;
}

export function downloadSkillMd(skill: SkillDefinition) {
  const content = exportSkillMd(skill);
  const blob = new Blob([content], { type: "text/markdown" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "SKILL.md";
  a.click();
  URL.revokeObjectURL(url);
}

export function downloadBlob(data: Blob, filename: string) {
  const url = URL.createObjectURL(data);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}
