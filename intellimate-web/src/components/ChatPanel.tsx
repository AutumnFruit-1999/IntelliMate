import MessageList from "./MessageList";
import ComposeArea from "./ComposeArea";
import { useChatStore } from "../stores/chatStore";
import type { RequestFrame } from "../lib/protocol";

interface ChatPanelProps {
  onSend: (text: string, forcePlan?: boolean, regenerate?: boolean) => void;
  onCancel?: () => void;
  onSendPlanAction?: (request: RequestFrame) => void;
}

export default function ChatPanel({ onSend, onCancel }: ChatPanelProps) {
  const connectionState = useChatStore((s) => s.connectionState);
  const isWaiting = useChatStore((s) => s.isWaiting);
  const disabled = connectionState !== "connected";

  return (
    <div className="flex flex-col flex-1 min-w-0 min-h-0">
      <MessageList onSend={onSend} />
      <ComposeArea onSend={onSend} onCancel={onCancel} disabled={disabled} isWaiting={isWaiting} />
    </div>
  );
}
