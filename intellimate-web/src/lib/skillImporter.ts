import type { SkillDefinition, SkillDefinitionCreate } from "./api";

export interface ParsedSkill {
  name?: string;
  displayName?: string;
  description?: string;
  content: string;
  tags?: string;
  metadata?: Record<string, unknown>;
}

export function parseSkillMd(raw: string): ParsedSkill {
  const match = raw.match(/^---\n([\s\S]*?)\n---\n([\s\S]*)$/);
  if (!match) {
    return { content: raw.trim() };
  }

  const yamlStr = match[1];
  const content = match[2].trim();
  const meta: Record<string, string> = {};

  for (const line of yamlStr.split("\n")) {
    const colonIdx = line.indexOf(":");
    if (colonIdx > 0) {
      const key = line.slice(0, colonIdx).trim();
      const value = line.slice(colonIdx + 1).trim();
      if (value) meta[key] = value;
    }
  }

  let parsedMetadata: Record<string, unknown> | undefined;
  if (meta.metadata) {
    try {
      parsedMetadata = JSON.parse(meta.metadata);
    } catch {
      /* ignore parse failure */
    }
  }

  return {
    name: meta.name,
    displayName: meta.displayName,
    description: meta.description,
    content,
    tags: meta.tags,
    metadata: parsedMetadata,
  };
}

export function parsedToCreate(parsed: ParsedSkill): Partial<SkillDefinitionCreate> {
  return {
    name: parsed.name,
    displayName: parsed.displayName,
    description: parsed.description ?? "",
    content: parsed.content || undefined,
    tags: parsed.tags,
    metadata: parsed.metadata,
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
