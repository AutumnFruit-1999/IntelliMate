import {
  type GatewayFrame,
  type EventFrame,
  type ResponseFrame,
  type RequestFrame,
  createPong,
  isEventFrame,
  isResponseFrame,
} from "./protocol";

export type ConnectionState =
  | "connecting"
  | "connected"
  | "disconnected"
  | "reconnecting";

export interface ReconnectMeta {
  attempt: number;
  nextRetryMs: number;
}

export interface WsClientOptions {
  url: string;
  token?: string;
  onEvent?: (event: EventFrame) => void;
  onResponse?: (response: ResponseFrame) => void;
  onStateChange?: (state: ConnectionState, meta?: ReconnectMeta) => void;
}

export class WsClient {
  private ws: WebSocket | null = null;
  private options: Required<WsClientOptions>;
  private reconnectAttempt = 0;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private _state: ConnectionState = "disconnected";
  private intentionalDisconnect = false;

  constructor(opts: WsClientOptions) {
    this.options = {
      onEvent: () => {},
      onResponse: () => {},
      onStateChange: (_state, _meta) => {},
      token: "",
      ...opts,
    };
  }

  get state(): ConnectionState {
    return this._state;
  }

  connect(): void {
    this.intentionalDisconnect = false;
    this.cleanup();
    this.setState("connecting");

    const url = this.options.token
      ? `${this.options.url}?token=${encodeURIComponent(this.options.token)}`
      : this.options.url;

    this.ws = new WebSocket(url);

    this.ws.onopen = () => {
      this.reconnectAttempt = 0;
      this.setState("connected");
    };

    this.ws.onmessage = (evt) => {
      try {
        const frame: GatewayFrame = JSON.parse(evt.data);
        this.handleFrame(frame);
      } catch {
        console.error("Failed to parse frame:", evt.data);
      }
    };

    this.ws.onclose = () => {
      this.setState("disconnected");
      this.scheduleReconnect();
    };

    this.ws.onerror = (err) => {
      console.error("WebSocket error:", err);
    };
  }

  disconnect(): void {
    this.intentionalDisconnect = true;
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer);
      this.reconnectTimer = null;
    }
    this.cleanup();
    this.setState("disconnected");
  }

  send(frame: RequestFrame | EventFrame): void {
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify(frame));
    }
  }

  private handleFrame(frame: GatewayFrame): void {
    if (isEventFrame(frame)) {
      if (frame.event === "ping") {
        this.send(createPong());
        return;
      }
      this.options.onEvent(frame);
    } else if (isResponseFrame(frame)) {
      this.options.onResponse(frame);
    }
  }

  private scheduleReconnect(): void {
    if (this.intentionalDisconnect) return;
    this.reconnectAttempt++;
    const delay = Math.min(1000 * Math.pow(2, this.reconnectAttempt - 1), 30_000);
    this.setState("reconnecting", { attempt: this.reconnectAttempt, nextRetryMs: delay });
    this.reconnectTimer = setTimeout(() => {
      this.connect();
    }, delay);
  }

  private cleanup(): void {
    if (this.ws) {
      this.ws.onopen = null;
      this.ws.onmessage = null;
      this.ws.onclose = null;
      this.ws.onerror = null;
      if (
        this.ws.readyState === WebSocket.OPEN ||
        this.ws.readyState === WebSocket.CONNECTING
      ) {
        this.ws.close();
      }
      this.ws = null;
    }
  }

  private setState(s: ConnectionState, meta?: ReconnectMeta): void {
    this._state = s;
    this.options.onStateChange(s, meta);
  }
}
