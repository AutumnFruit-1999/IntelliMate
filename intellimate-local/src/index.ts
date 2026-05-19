#!/usr/bin/env node
import { Command } from 'commander';
import { loadConfig } from './config.js';
import { BridgeClient } from './bridge-client.js';
import { ToolExecutor } from './tool-executor.js';

const program = new Command();

program
  .name('intellimate-local')
  .description('Local execution bridge for IntelliMate agents')
  .option('-s, --server <url>', 'Gateway WebSocket URL')
  .option('-t, --token <token>', 'Authentication token')
  .option('-n, --name <name>', 'Node name')
  .option('-c, --config <path>', 'Config file path')
  .parse();

const opts = program.opts();

try {
  const config = loadConfig(opts);
  const executor = new ToolExecutor(config);
  const client = new BridgeClient(config, executor);
  client.connect();

  console.log(`[intellimate-local] Node "${config.name}" connecting to ${config.server}`);

  process.on('SIGINT', () => {
    console.log('\n[intellimate-local] Shutting down...');
    client.disconnect();
    process.exit(0);
  });
} catch (e: unknown) {
  const message = e instanceof Error ? e.message : String(e);
  console.error(`[intellimate-local] Error: ${message}`);
  process.exit(1);
}
