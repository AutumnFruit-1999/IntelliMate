import WebSocket from 'ws';
import { AppConfig } from './config.js';
import { ToolExecutor } from './tool-executor.js';

interface ToolCallMessage {
  type: 'tool_call';
  id: string;
  tool: string;
  args: Record<string, unknown>;
}

interface PingMessage {
  type: 'ping';
}

interface RegisteredMessage {
  type: 'registered';
  nodeId: string;
}

interface ErrorMessage {
  type: 'error';
  message: string;
}

type IncomingMessage = ToolCallMessage | PingMessage | RegisteredMessage | ErrorMessage;

export class BridgeClient {
  private ws: WebSocket | null = null;
  private config: AppConfig;
  private executor: ToolExecutor;
  private reconnectDelay = 1000;
  private maxReconnectDelay = 30000;
  private shouldReconnect = true;

  constructor(config: AppConfig, executor: ToolExecutor) {
    this.config = config;
    this.executor = executor;
  }

  connect(): void {
    const url = `${this.config.server}?token=${encodeURIComponent(this.config.token)}`;
    this.ws = new WebSocket(url);

    this.ws.on('open', () => {
      console.log('[bridge] Connected to gateway');
      this.reconnectDelay = 1000;
      this.sendRegister();
    });

    this.ws.on('message', (data: WebSocket.Data) => {
      void this.handleMessage(data.toString());
    });

    this.ws.on('close', () => {
      console.log('[bridge] Disconnected');
      if (this.shouldReconnect) {
        console.log(`[bridge] Reconnecting in ${this.reconnectDelay}ms...`);
        setTimeout(() => this.connect(), this.reconnectDelay);
        this.reconnectDelay = Math.min(this.reconnectDelay * 2, this.maxReconnectDelay);
      }
    });

    this.ws.on('error', (err: Error) => {
      console.error(`[bridge] WebSocket error: ${err.message}`);
    });
  }

  disconnect(): void {
    this.shouldReconnect = false;
    this.ws?.close();
  }

  private sendRegister(): void {
    const msg = {
      type: 'register',
      name: this.config.name,
      tools: this.executor.getToolNames(),
      mcpTools: [],
    };
    this.send(msg);
  }

  private async handleMessage(raw: string): Promise<void> {
    let msg: IncomingMessage;
    try {
      msg = JSON.parse(raw) as IncomingMessage;
    } catch {
      console.error('[bridge] Failed to parse message:', raw);
      return;
    }

    switch (msg.type) {
      case 'registered':
        console.log(`[bridge] Registered as node: ${msg.nodeId}`);
        break;

      case 'tool_call':
        await this.handleToolCall(msg);
        break;

      case 'ping':
        this.send({ type: 'pong' });
        break;

      case 'error':
        console.error(`[bridge] Server error: ${msg.message}`);
        break;

      default:
        console.warn(`[bridge] Unknown message type: ${(msg as { type?: string }).type}`);
    }
  }

  private async handleToolCall(msg: ToolCallMessage): Promise<void> {
    console.log(`[bridge] Tool call: ${msg.tool} (id=${msg.id})`);
    try {
      const result = await this.executor.execute(
        msg.tool,
        msg.args,
        (chunk: string) => {
          this.send({ type: 'tool_stream', id: msg.id, chunk });
        }
      );
      this.send({
        type: 'tool_result',
        id: msg.id,
        success: true,
        result: result.output,
        exitCode: result.exitCode ?? null,
      });
    } catch (err: unknown) {
      const message = err instanceof Error ? err.message : String(err);
      this.send({
        type: 'tool_result',
        id: msg.id,
        success: false,
        error: message,
      });
    }
  }

  private send(msg: object): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(msg));
    }
  }
}
