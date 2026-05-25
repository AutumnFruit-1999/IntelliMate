import Markdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Copy, Check } from "lucide-react";
import { useState, useCallback, memo, lazy, Suspense, useDeferredValue } from "react";
import { sanitizePartialMarkdown } from "../lib/markdownSanitizer";

const SyntaxHighlighter = lazy(() =>
  import("react-syntax-highlighter/dist/esm/prism-light").then((mod) => ({ default: mod.default }))
);
const oneDarkPromise = import("react-syntax-highlighter/dist/esm/styles/prism/one-dark").then(
  (mod) => mod.default
);

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
  const [style, setStyle] = useState<Record<string, React.CSSProperties> | null>(null);

  if (style === null) {
    oneDarkPromise.then(setStyle);
  }

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
      <Suspense
        fallback={
          <pre className="bg-slate-900 text-slate-300 p-4 text-sm overflow-auto">{children}</pre>
        }
      >
        {style ? (
          <SyntaxHighlighter
            language={language ?? "text"}
            style={style}
            customStyle={{ margin: 0, borderRadius: 0 }}
          >
            {children}
          </SyntaxHighlighter>
        ) : (
          <pre className="bg-slate-900 text-slate-300 p-4 text-sm overflow-auto">{children}</pre>
        )}
      </Suspense>
    </div>
  );
}

const RenderedMarkdown = memo(function RenderedMarkdown({ content }: { content: string }) {
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
    </div>
  );
});

export default function StreamingText({ content, streaming }: StreamingTextProps) {
  const deferredContent = useDeferredValue(content);

  if (!content && streaming) {
    return <div className="h-5" />;
  }

  if (streaming) {
    const sanitized = sanitizePartialMarkdown(deferredContent);
    return (
      <div className="relative">
        <RenderedMarkdown content={sanitized} />
        <span className="cursor-blink text-blue-500 ml-0.5">█</span>
      </div>
    );
  }

  return <RenderedMarkdown content={content} />;
}
