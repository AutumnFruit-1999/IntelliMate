import { readFile, writeFile, editFile, listFiles } from './tools/file-ops.js';
import { exec } from './tools/exec.js';
import { AppConfig } from './config.js';
import { resolve } from 'path';

export interface ToolResult {
  output: string;
  exitCode?: number;
}

type StreamCallback = (chunk: string) => void;

export class ToolExecutor {
  private config: AppConfig;

  constructor(config: AppConfig) {
    this.config = config;
  }

  getToolNames(): string[] {
    return ['readFile', 'writeFile', 'editFile', 'exec', 'listFiles'];
  }

  async execute(
    toolName: string,
    args: Record<string, unknown>,
    onStream?: StreamCallback
  ): Promise<ToolResult> {
    this.validateSecurity(toolName, args);

    switch (toolName) {
      case 'readFile':
        return { output: readFile(args as Parameters<typeof readFile>[0]) };
      case 'writeFile':
        return { output: writeFile(args as Parameters<typeof writeFile>[0]) };
      case 'editFile':
        return { output: editFile(args as Parameters<typeof editFile>[0]) };
      case 'listFiles':
        return { output: listFiles(args as Parameters<typeof listFiles>[0]) };
      case 'exec': {
        const result = await exec(args as Parameters<typeof exec>[0], onStream);
        return { output: result.output, exitCode: result.exitCode };
      }
      default:
        throw new Error(`Unknown tool: ${toolName}`);
    }
  }

  private validateSecurity(toolName: string, args: Record<string, unknown>): void {
    const security = this.config.security;
    if (!security) return;

    if (security.allowedPaths?.length && toolName !== 'exec') {
      const path = args.path as string;
      if (path) {
        const resolved = resolve(path);
        const allowed = security.allowedPaths.some((p) => resolved.startsWith(resolve(p)));
        if (!allowed) {
          throw new Error(`Access denied: ${path} is not in allowed paths`);
        }
      }
    }

    if (security.blockedCommands?.length && toolName === 'exec') {
      const command = args.command as string;
      if (command) {
        for (const pattern of security.blockedCommands) {
          const regex = new RegExp(pattern.replace('*', '.*'));
          if (regex.test(command)) {
            throw new Error(`Command blocked by security policy: ${command}`);
          }
        }
      }
    }
  }
}
