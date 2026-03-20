import { useMemo } from "react";
import type { ChatMessage, ToolCallInfo } from "../stores/chatStore";
import StreamingText from "./StreamingText";
import ToolCallGroup from "./ToolCallGroup";
import { Bot, User } from "lucide-react";

interface MessageBubbleProps {
  message: ChatMessage;
}

function TurnIndicator({ turn, maxTurns }: { turn: number; maxTurns: number }) {
  return (
    <div className="mb-1 flex items-center gap-1 text-xs text-gray-400">
      <span className="inline-block h-1.5 w-1.5 animate-pulse rounded-full bg-blue-400" />
      Turn {turn}/{maxTurns}
    </div>
  );
}

export default function MessageBubble({ message }: MessageBubbleProps) {
  const isUser = message.role === "user";
  const isSystem = message.role === "system";

  if (isSystem) {
    return (
      <div className="flex justify-center my-2">
        <div className="bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 text-xs px-3 py-1.5 rounded-full">
          {message.content}
        </div>
      </div>
    );
  }

  const hasToolCalls = !isUser && message.toolCalls && message.toolCalls.length > 0;
  const showTurnIndicator =
    !isUser && message.streaming && message.currentTurn != null && message.currentTurn > 1;

  const toolCallGroups = useMemo(() => {
    if (!hasToolCalls) return [];
    return groupByTurn(message.toolCalls!);
  }, [hasToolCalls, message.toolCalls]);

  return (
    <div className={`flex gap-3 my-4 ${isUser ? "flex-row-reverse" : ""}`}>
      <div
        className={`flex-shrink-0 w-8 h-8 rounded-full flex items-center justify-center ${
          isUser
            ? "bg-blue-500 text-white"
            : "bg-slate-200 dark:bg-slate-700 text-slate-600 dark:text-slate-300"
        }`}
      >
        {isUser ? <User size={16} /> : <Bot size={16} />}
      </div>
      <div className="max-w-[75%]">
        {showTurnIndicator && (
          <TurnIndicator
            turn={message.currentTurn!}
            maxTurns={message.maxTurns ?? 0}
          />
        )}

        {toolCallGroups.length > 0 && (
          <div className="mb-2">
            {toolCallGroups.map((group) => (
              <ToolCallGroup
                key={group.key}
                calls={group.calls}
                turn={group.turn}
              />
            ))}
          </div>
        )}

        <div
          className={`rounded-2xl px-4 py-2.5 ${
            isUser
              ? "bg-blue-500 text-white"
              : "bg-slate-100 dark:bg-slate-800 text-slate-800 dark:text-slate-100"
          }`}
        >
          {isUser ? (
            <p className="text-sm whitespace-pre-wrap">{message.content}</p>
          ) : (
            <StreamingText
              content={message.content}
              streaming={message.streaming}
            />
          )}
        </div>

        {!isUser && message.totalTurns != null && message.totalTurns > 1 && !message.streaming && (
          <div className="mt-1 text-xs text-gray-400">
            共 {message.totalTurns} 轮推理
          </div>
        )}
      </div>
    </div>
  );
}

interface ToolCallGroupData {
  key: string;
  turn?: number;
  calls: ToolCallInfo[];
}

function groupByTurn(toolCalls: ToolCallInfo[]): ToolCallGroupData[] {
  const groups: ToolCallGroupData[] = [];
  let currentTurn: number | undefined;
  let currentCalls: ToolCallInfo[] = [];

  for (const tc of toolCalls) {
    if (tc.turn !== currentTurn && currentCalls.length > 0) {
      groups.push({
        key: `turn-${currentTurn ?? "none"}-${groups.length}`,
        turn: currentTurn,
        calls: currentCalls,
      });
      currentCalls = [];
    }
    currentTurn = tc.turn;
    currentCalls.push(tc);
  }

  if (currentCalls.length > 0) {
    groups.push({
      key: `turn-${currentTurn ?? "none"}-${groups.length}`,
      turn: currentTurn,
      calls: currentCalls,
    });
  }

  return groups;
}
