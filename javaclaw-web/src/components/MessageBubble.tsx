import type { ChatMessage } from "../stores/chatStore";
import StreamingText from "./StreamingText";
import { Bot, User } from "lucide-react";

interface MessageBubbleProps {
  message: ChatMessage;
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
      <div
        className={`max-w-[75%] rounded-2xl px-4 py-2.5 ${
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
    </div>
  );
}
