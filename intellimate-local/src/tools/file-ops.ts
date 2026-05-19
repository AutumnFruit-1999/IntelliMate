import { readFileSync, writeFileSync, existsSync, readdirSync, statSync, mkdirSync } from 'fs';
import { join, resolve, dirname } from 'path';

export function readFile(args: { path: string; startLine?: number; lineCount?: number }): string {
  const filePath = resolve(args.path);
  if (!existsSync(filePath)) return `Error: File not found: ${args.path}`;

  const content = readFileSync(filePath, 'utf-8');
  const lines = content.split('\n');

  const start = args.startLine && args.startLine > 0 ? args.startLine - 1 : 0;
  const end =
    args.lineCount && args.lineCount > 0
      ? Math.min(start + args.lineCount, lines.length)
      : lines.length;

  const result = lines
    .slice(start, end)
    .map((line, i) => `${start + i + 1}|${line}`)
    .join('\n');

  if (start > 0 || end < lines.length) {
    return result + `\n--- [Showing lines ${start + 1}-${end} of ${lines.length} total lines.] ---`;
  }
  return result;
}

export function writeFile(args: { path: string; content: string }): string {
  const filePath = resolve(args.path);
  const dir = dirname(filePath);
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true });
  writeFileSync(filePath, args.content, 'utf-8');
  return `File written: ${filePath} (${args.content.length} chars)`;
}

export function editFile(args: { path: string; oldText: string; newText: string }): string {
  const filePath = resolve(args.path);
  if (!existsSync(filePath)) return `Error: File not found: ${args.path}`;

  const content = readFileSync(filePath, 'utf-8');
  if (!content.includes(args.oldText)) {
    return 'Error: oldText not found in file. Make sure it matches exactly.';
  }

  const occurrences = content.split(args.oldText).length - 1;
  if (occurrences > 1) {
    return `Error: oldText found ${occurrences} times. Provide more context to uniquely identify the replacement.`;
  }

  const newContent = content.replace(args.oldText, args.newText);
  writeFileSync(filePath, newContent, 'utf-8');
  return `File edited: ${filePath}`;
}

export function listFiles(args: { path: string; pattern?: string; recursive?: boolean }): string {
  const dirPath = resolve(args.path);
  if (!existsSync(dirPath)) return `Error: Directory not found: ${args.path}`;

  const entries: string[] = [];
  function walk(dir: string, depth: number) {
    for (const entry of readdirSync(dir)) {
      if (entry.startsWith('.')) continue;
      const fullPath = join(dir, entry);
      const stat = statSync(fullPath);
      const relative = fullPath.replace(dirPath + '/', '');

      if (args.pattern && !relative.includes(args.pattern)) continue;

      entries.push(stat.isDirectory() ? `${relative}/` : relative);

      if (stat.isDirectory() && args.recursive && depth < 5) {
        walk(fullPath, depth + 1);
      }
    }
  }

  walk(dirPath, 0);
  return entries.join('\n') || '(empty directory)';
}
