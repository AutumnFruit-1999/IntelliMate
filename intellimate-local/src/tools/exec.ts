import { spawn } from 'child_process';
import { resolve } from 'path';
import { existsSync } from 'fs';

export interface ExecResult {
  output: string;
  exitCode: number;
}

export async function exec(
  args: { command: string; workingDirectory?: string; timeoutSeconds?: number },
  onChunk?: (chunk: string) => void
): Promise<ExecResult> {
  const timeout =
    args.timeoutSeconds && args.timeoutSeconds > 0 ? args.timeoutSeconds * 1000 : 30000;

  const cwd = args.workingDirectory ? resolve(args.workingDirectory) : process.cwd();
  if (!existsSync(cwd)) {
    throw new Error(`Working directory not found: ${cwd}`);
  }

  return new Promise((resolvePromise, reject) => {
    const child = spawn('sh', ['-c', args.command], {
      cwd,
      env: process.env,
      stdio: ['pipe', 'pipe', 'pipe'],
    });

    let output = '';
    const maxOutput = 1024 * 1024;
    let truncated = false;

    const collectOutput = (data: Buffer) => {
      const text = data.toString();
      if (output.length < maxOutput) {
        output += text;
        if (output.length > maxOutput) {
          truncated = true;
          output = output.substring(0, maxOutput);
        }
      }
      if (onChunk) onChunk(text);
    };

    child.stdout.on('data', collectOutput);
    child.stderr.on('data', collectOutput);

    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      reject(new Error(`Command timed out after ${timeout / 1000} seconds`));
    }, timeout);

    child.on('close', (code) => {
      clearTimeout(timer);
      if (truncated) output += '\n--- [Output truncated at 1MB] ---';
      resolvePromise({
        output: `Exit code: ${code ?? -1}\n${output}`,
        exitCode: code ?? -1,
      });
    });

    child.on('error', (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}
