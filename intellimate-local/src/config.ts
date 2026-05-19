import { readFileSync } from 'fs';
import { parse } from 'yaml';

export interface SecurityConfig {
  allowedPaths?: string[];
  blockedCommands?: string[];
}

export interface McpServerConfig {
  name: string;
  command: string;
  args?: string[];
}

export interface AppConfig {
  server: string;
  token: string;
  name: string;
  security?: SecurityConfig;
  mcpServers?: McpServerConfig[];
}

export function loadConfig(options: {
  server?: string;
  token?: string;
  name?: string;
  config?: string;
}): AppConfig {
  let fileConfig: Partial<AppConfig> = {};

  if (options.config) {
    const raw = readFileSync(options.config, 'utf-8');
    fileConfig = parse(raw) as Partial<AppConfig>;
  }

  const config: AppConfig = {
    server: options.server || fileConfig.server || '',
    token: options.token || fileConfig.token || '',
    name: options.name || fileConfig.name || `node-${process.pid}`,
    security: fileConfig.security,
    mcpServers: fileConfig.mcpServers,
  };

  if (!config.server) throw new Error('--server is required');
  if (!config.token) throw new Error('--token is required');

  return config;
}
