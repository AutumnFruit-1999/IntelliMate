import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Prism as SyntaxHighlighter } from "react-syntax-highlighter";
import { oneDark } from "react-syntax-highlighter/dist/esm/styles/prism";
import { Copy, Check } from "lucide-react";
import { useState, useCallback } from "react";

interface StreamingTextProps {
  content: string;
  streaming: boolean;
}

function CodeBlock({
  language,
  children,
}: {
  language: string | undefined;
  children: string;
}) {
  const [copied, setCopied] = useState(false);

  const handleCopy = useCallback(() => {
    navigator.clipboard.writeText(children);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }, [children]);

  return (
    <div className="relative group my-2 rounded-lg overflow-hidden">
      <div className="flex items-center justify-between bg-slate-800 px-4 py-1.5 text-xs text-slate-400">
        <span>{language ?? "text"}</span>
        <button
          onClick={handleCopy}
          className="flex items-center gap-1 hover:text-white transition-colors"
        >
          {copied ? <Check size={14} /> : <Copy size={14} />}
          {copied ? "已复制" : "复制"}
        </button>
      </div>
      <SyntaxHighlighter
        language={language ?? "text"}
        style={oneDark}
        customStyle={{ margin: 0, borderRadius: 0 }}
      >
        {children}
      </SyntaxHighlighter>
    </div>
  );
}

export default function StreamingText({ content, streaming }: StreamingTextProps) {
  if (!content && streaming) {
    return (
      <span className="text-slate-400 dark:text-slate-500 italic">
        思考中...
      </span>
    );
  }

  return (
    <div className="prose prose-sm dark:prose-invert max-w-none break-words">
      <Markdown
        remarkPlugins={[remarkGfm]}
        components={{
          code({ className, children, ...props }) {
            const match = /language-(\w+)/.exec(className || "");
            const codeString = String(children).replace(/\n$/, "");

            if (match) {
              return <CodeBlock language={match[1]}>{codeString}</CodeBlock>;
            }

            return (
              <code
                className="bg-slate-200 dark:bg-slate-700 px-1.5 py-0.5 rounded text-sm"
                {...props}
              >
                {children}
              </code>
            );
          },
        }}
      >
        {content}
      </Markdown>
      {streaming && <span className="cursor-blink text-blue-500">█</span>}
    </div>
  );
}
