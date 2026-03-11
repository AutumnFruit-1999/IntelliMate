export interface EventFrame {
  type: "event";
  event: string;
  payload: Record<string, unknown>;
  seq: number | null;
}

export interface RequestFrame {
  type: "request";
  id: string;
  method: string;
  params: Record<string, unknown>;
}

export interface ResponseFrame {
  type: "response";
  id: string;
  ok: boolean;
  payload: Record<string, unknown> | null;
  error: unknown | null;
}

export type GatewayFrame = EventFrame | RequestFrame | ResponseFrame;

export function createRequest(
  method: string,
  params: Record<string, unknown>,
): RequestFrame {
  return {
    type: "request",
    id: crypto.randomUUID(),
    method,
    params,
  };
}

export function createPong(): EventFrame {
  return {
    type: "event",
    event: "pong",
    payload: {},
    seq: null,
  };
}

export function isEventFrame(frame: GatewayFrame): frame is EventFrame {
  return frame.type === "event";
}

export function isResponseFrame(frame: GatewayFrame): frame is ResponseFrame {
  return frame.type === "response";
}
